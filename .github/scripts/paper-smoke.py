#!/usr/bin/env python3
"""Boot one official Paper or Folia build and check NexusAI.

Paper uses the newest STABLE build. Folia uses the newest STABLE build,
then BETA, then ALPHA, and the channel is printed. PlaceholderAPI 2.12.3
and a local mock OpenAI-compatible endpoint are installed either way.
No real API key is used.

When plugin.yml sets folia-supported to false, Folia smoke only checks
that the server reaches Done, stops cleanly, and that Folia refused to
load NexusAI. Status, /nai test, /nai usage, the named context prompt,
probe events, the absent refusal line, and the strict log filter run
when folia-supported is true or when --folia-checks full is set. A check
that cannot run fails the script.
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import http.client
import io
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import parse_qs, urlsplit
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PAPI_URL = (
    "https://github.com/PlaceholderAPI/PlaceholderAPI/releases/download/"
    "2.12.3/PlaceholderAPI-2.12.3.jar"
)
PAPI_NAME = "PlaceholderAPI-2.12.3.jar"
# sha256 of PlaceholderAPI-2.12.3.jar, the digest GitHub publishes for that release asset.
PAPI_SHA256 = "fde03259f5af6938f3c33eeb4d814000a1adabf1d2304ce14970be81f609a437"
FILL = "https://fill.papermc.io/v3/projects/paper/versions/{version}/builds"
FILL_PROJECT = "https://fill.papermc.io/v3/projects/{project}/versions/{version}/builds"
USER_AGENT = "NexusAI-smoke/1.0"
# Folia/Paper throw these from the wrong thread. Matched as case-insensitive substrings.
THREAD_REGION_ERRORS = (
    "thread failed main thread check",
    "not owned by current region",
    "not owned by the current region",
    "accessing entity state off owning region's thread",
    "off owning region's thread",
    "off the owning region's thread",
    "async off the owning region's thread",
)
REFUSAL_MARKERS = (
    "not marked as supporting Folia",
    "not marked as supporting regionised multithreading",
)
# Flat-world startup prints this exact message. A longer line is not exempt.
EXEMPT_LOG_MESSAGE = "No key layers in MapLike[{}]"
_LOG_PREFIXES = (
    re.compile(r"^\[[0-9]{2}:[0-9]{2}:[0-9]{2}\] \[[^\[\]]+\]: "),
    re.compile(r"^\[[0-9]{2}:[0-9]{2}:[0-9]{2} [A-Za-z]+\]: "),
)
_THROWABLE_LINE = re.compile(
    r"(?<![\w$])(?:[\w$]+\.)*[\w$]*(?:Exception|Error|Throwable)(?=\s*:|$)"
)
# Locale command.test-ok after colour codes are removed. Loose fragments are not enough.
_MOCK_ANSWER = re.compile(r"Answer \(\d+ ms\): pong")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default=os.environ.get("MC_VERSION", ""))
    parser.add_argument("--plugins-dir", default=os.environ.get("PLUGIN_DIR", "dist"))
    parser.add_argument("--driver-dir", default=os.environ.get("DRIVER_DIR", ""))
    parser.add_argument("--timeout", type=int, default=360, help="seconds to wait for Done")
    parser.add_argument("--project", default=os.environ.get("MC_PROJECT", "paper"), help="paper or folia")
    parser.add_argument(
        "--folia-checks",
        default=os.environ.get("FOLIA_CHECKS", "auto"),
        choices=("auto", "startup", "full"),
        help="Folia only. auto runs full checks when plugin.yml sets folia-supported: true",
    )
    parser.add_argument(
        "--startup-banner",
        default=os.environ.get("STARTUP_BANNER", ""),
        choices=("", "true", "false"),
        help="startup-banner value written into the smoke config; empty keeps the plugin default (true)",
    )
    parser.add_argument("--self-check", action="store_true", help="check smoke helpers and exit")
    args = parser.parse_args()
    if args.self_check:
        return smoke_self_check()
    project = args.project.strip().lower()
    if project == "folia":
        return run_folia(args)
    if project != "paper":
        print(f"unknown project {args.project}", file=sys.stderr)
        return 2
    if not args.version:
        print("missing Paper version", file=sys.stderr)
        return 2

    plugin = find_plugin(Path(args.plugins_dir))
    major = class_major(plugin, "io/github/neareststep/nexusai/NexusAI.class")
    if major != 65:
        print(f"expected class file 65, found {major} in {plugin}", file=sys.stderr)
        return 1
    print(f"plugin {plugin.name} class file {major}")

    work = Path(tempfile.mkdtemp(prefix=f"nexusai-paper-{args.version}-"))
    print(f"server directory {work}")
    try:
        paper = download_paper(args.version, work / "paper.jar")
        seed_paperclip(work / "paper.jar", work)
        install_papi(work / "plugins")
        shutil.copy2(plugin, work / "plugins" / plugin.name)
        check_api = False
        if args.driver_dir.strip():
            driver_dir = Path(args.driver_dir)
            driver = find_driver(driver_dir)
            probe = find_probe(driver_dir)
            if probe is None:
                raise RuntimeError(
                    "LoadDriver is present but nexusai-events-probe.jar is missing from " + str(driver_dir)
                )
            shutil.copy2(driver, work / "plugins" / driver.name)
            shutil.copy2(probe, work / "plugins" / probe.name)
            print(f"load driver {driver.name}")
            print(f"events probe {probe.name}")
            check_api = True
        mock_port = free_port()
        mock = start_mock(mock_port)
        write_config(work / "plugins" / "NexusAI" / "config.yml", mock_port, args.startup_banner)
        write_knowledge_fixture(work / "plugins" / "NexusAI")
        server_port = free_port()
        rcon_port = free_port()
        write_server(work, server_port, rcon_port)
        return boot(
            work, args.version, paper, rcon_port, mock_port, args.timeout, check_api, mock,
            startup_banner=args.startup_banner)
    except Exception as error:
        print(f"SMOKE FAIL {args.version}: {error}", file=sys.stderr)
        return 1


def find_probe(directory: Path) -> Path | None:
    if directory.is_file() and directory.name == "nexusai-events-probe.jar":
        return directory
    jars = sorted(path for path in directory.glob("nexusai-events-probe.jar") if path.is_file())
    if not jars:
        return None
    return jars[0]


def find_driver(directory: Path) -> Path:
    if directory.is_file() and directory.name.endswith(".jar"):
        return directory
    jars = sorted(directory.glob("NexusAI-LoadDriver.jar"))
    if len(jars) != 1:
        raise RuntimeError(f"expected NexusAI-LoadDriver.jar in {directory}, found {[p.name for p in jars]}")
    return jars[0]


def find_plugin(directory: Path) -> Path:
    jars = [
        path for path in directory.glob("NexusAI-*.jar")
        if "plain" not in path.name and "sources" not in path.name and "javadoc" not in path.name
        and "LoadDriver" not in path.name
    ]
    if len(jars) != 1:
        raise RuntimeError(f"expected one NexusAI jar in {directory}, found {[p.name for p in jars]}")
    return jars[0]


def class_major(jar: Path, entry: str) -> int:
    with zipfile.ZipFile(jar) as archive:
        data = archive.read(entry)
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise RuntimeError(f"{entry} is not a class file")
    return struct.unpack(">H", data[6:8])[0]


def download_paper(version: str, destination: Path) -> dict:
    payload = fetch_listing(FILL.format(version=version))
    builds = payload["builds"] if isinstance(payload, dict) else payload
    stable = [build for build in builds if build.get("channel") == "STABLE"]
    if not stable:
        raise RuntimeError(f"no STABLE Paper build for {version}")
    chosen = max(stable, key=lambda build: build["id"])
    if chosen.get("channel") != "STABLE":
        raise RuntimeError(f"refusing non-stable build {chosen}")
    download_info = chosen["downloads"]["server:default"]
    print(f"Paper {version} stable build {chosen['id']} ({download_info['name']})")
    download(download_info["url"], destination, sha256=fill_sha256(download_info))
    return {"id": chosen["id"], "name": download_info["name"]}


def fill_sha256(download_info: dict) -> str | None:
    """The sha256 the Paper API publishes for a download, or None when it has none."""
    checksums = download_info.get("checksums") or {}
    value = checksums.get("sha256") if isinstance(checksums, dict) else None
    if isinstance(value, str) and re.fullmatch(r"[0-9a-fA-F]{64}", value):
        return value.lower()
    return None


DOWNLOAD_ATTEMPTS = 5


def retry_pause(attempt: int) -> int:
    """Seconds to wait after a failed attempt: 2, 4, 8, 16, then 30 at most."""
    return min(30, 2 ** max(1, attempt))


def cache_dir() -> Path:
    """Download cache. NEXUSAI_SMOKE_CACHE overrides the default under the temp directory."""
    configured = os.environ.get("NEXUSAI_SMOKE_CACHE", "").strip()
    return Path(configured) if configured else Path(tempfile.gettempdir()) / "nexusai-smoke-cache"


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def download(url: str, destination: Path, sha256: str | None = None, name: str | None = None,
             attempts: int = DOWNLOAD_ATTEMPTS) -> None:
    """Copy url to destination through the cache.

    With sha256 the cache entry is cache/sha256/<hash>, a cached or downloaded file is checked
    against it, and a mismatch deletes the file and downloads again, up to attempts times.
    A file dropped in the cache under its own name (for example cache/mojang_26.2.jar) is used
    when its hash matches. Without sha256 the entry is keyed by the URL, its sha256 is saved
    beside it in <entry>.sha256, and an entry without that file or with a different hash is
    deleted and downloaded again. A .jar without sha256 must also open as a zip archive whose
    entries pass their CRC check. A download shorter than its Content-Length is rejected. A
    transport error (OSError, or an http.client error such as IncompleteRead) is retried, and
    the partial file is always removed.
    """
    destination.parent.mkdir(parents=True, exist_ok=True)
    root = cache_dir()
    check_zip = not sha256 and destination.name.endswith(".jar")
    if sha256:
        sha256 = sha256.lower()
        cache = root / "sha256" / sha256
        placed = root / name if name else None
        if not _cached_ok(cache, sha256) and placed is not None and placed.is_file():
            if file_sha256(placed) == sha256:
                cache.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(placed, cache)
                print(f"cache: using pre-placed {placed.name}")
            else:
                print(f"cache: pre-placed {placed.name} does not match sha256 {sha256}, ignored")
    else:
        cache = root / hashlib.sha256(url.encode("utf-8")).hexdigest()
    cache.parent.mkdir(parents=True, exist_ok=True)
    if sha256 and _cached_ok(cache, sha256):
        shutil.copy2(cache, destination)
        _report_verified(destination, sha256, "cache, published sha256")
        return
    if not sha256 and _cached_by_url_ok(cache, check_zip):
        shutil.copy2(cache, destination)
        _report_verified(destination, _sidecar(cache).read_text(encoding="utf-8").strip(), "cache, saved sha256")
        return
    problem = ""
    for attempt in range(1, max(1, attempts) + 1):
        partial = cache.with_name(cache.name + ".part")
        try:
            print(f"download {url}" + (f" (attempt {attempt})" if attempt > 1 else ""))
            expected_length = _fetch_to(url, partial)
            size = partial.stat().st_size
            digest = file_sha256(partial)
            if expected_length is not None and size != expected_length:
                problem = f"got {size} of {expected_length} bytes"
            elif sha256 and digest != sha256:
                problem = f"sha256 mismatch (expected {sha256})"
            elif not sha256 and size < 1000:
                problem = f"only {size} bytes"
            elif check_zip and not _zip_ok(partial):
                problem = "not a valid jar (zip check failed)"
            else:
                os.replace(partial, cache)
                if not sha256:
                    _sidecar(cache).write_text(digest + "\n", encoding="utf-8")
                shutil.copy2(cache, destination)
                _report_verified(destination, digest, "download, published sha256" if sha256 else "download, no published sha256")
                return
        except (OSError, http.client.HTTPException) as error:
            problem = f"{type(error).__name__}: {error}"
        finally:
            partial.unlink(missing_ok=True)
        print(f"download {url} failed: {problem}", file=sys.stderr)
        if attempt < attempts:
            _sleep(retry_pause(attempt))
    raise RuntimeError(f"download failed after {attempts} attempts: {url}: {problem}")


def _report_verified(destination: Path, digest: str, source: str) -> None:
    """One log line with the sha256 of the file that was checked and used."""
    print(f"verified sha256 {digest} {destination.name} ({source})")


def _cached_ok(cache: Path, sha256: str) -> bool:
    if not cache.is_file():
        return False
    if file_sha256(cache) == sha256:
        return True
    print(f"cache: {cache.name} does not match its sha256, removed")
    cache.unlink(missing_ok=True)
    return False


def _sidecar(cache: Path) -> Path:
    return cache.with_name(cache.name + ".sha256")


def _cached_by_url_ok(cache: Path, check_zip: bool) -> bool:
    """A URL-keyed entry is used only when it matches the sha256 saved beside it (and opens as a jar)."""
    side = _sidecar(cache)
    if not cache.is_file():
        side.unlink(missing_ok=True)
        return False
    try:
        expected = side.read_text(encoding="utf-8").strip() if side.is_file() else ""
    except OSError:
        expected = ""
    if not expected:
        reason = "has no saved sha256"
    elif file_sha256(cache) != expected:
        reason = "does not match its saved sha256"
    elif check_zip and not _zip_ok(cache):
        reason = "is not a valid jar"
    else:
        return True
    print(f"cache: {cache.name} {reason}, removed")
    cache.unlink(missing_ok=True)
    side.unlink(missing_ok=True)
    return False


def _zip_ok(path: Path) -> bool:
    try:
        with zipfile.ZipFile(path) as archive:
            return archive.testzip() is None
    except Exception:
        return False


def _fetch_to(url: str, target: Path) -> int | None:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=120) as response, target.open("wb") as handle:
        length = response.headers.get("Content-Length")
        shutil.copyfileobj(response, handle)
    return int(length) if length and length.isdigit() else None


_sleep = time.sleep


def install_papi(plugins: Path) -> None:
    """PlaceholderAPI 2.12.3, checked against its pinned sha256, with a config that stays offline."""
    jar = plugins / PAPI_NAME
    remove_legacy_url_entry(PAPI_URL)
    download(PAPI_URL, jar, sha256=PAPI_SHA256, name=PAPI_NAME)
    write_papi_config(plugins / "PlaceholderAPI" / "config.yml", jar)


def remove_legacy_url_entry(url: str) -> None:
    """Deletes the URL-keyed cache entry an older harness kept for a file that is now pinned."""
    entry = cache_dir() / hashlib.sha256(url.encode("utf-8")).hexdigest()
    for stale in (entry, _sidecar(entry)):
        if stale.is_file():
            stale.unlink()
            print(f"cache: removed old entry {stale.name} for {url}")


def write_papi_config(path: Path, jar: Path) -> None:
    """PlaceholderAPI's default config with the expansion cloud and the update check turned off.

    Both reach the network on startup. A failed expansion cloud download logs a stack trace
    (Failed to download expansion information) that the smoke log check counts as an error.
    """
    try:
        with zipfile.ZipFile(jar) as archive:
            text = archive.read("config.yml").decode("utf-8")
    except Exception:
        text = ""
    lines = []
    seen = set()
    for line in text.splitlines():
        key = line.split(":", 1)[0]
        if key in ("cloud_enabled", "check_updates"):
            lines.append(f"{key}: false")
            seen.add(key)
        else:
            lines.append(line)
    for key in ("check_updates", "cloud_enabled"):
        if key not in seen:
            lines.append(f"{key}: false")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def read_download_context(jar: Path) -> list[tuple[str, str, str]]:
    """Entries of META-INF/download-context in a paperclip jar: (sha256, url, file name)."""
    try:
        with zipfile.ZipFile(jar) as archive:
            text = archive.read("META-INF/download-context").decode("utf-8")
    except (KeyError, zipfile.BadZipFile, OSError):
        return []
    return parse_download_context(text)


def parse_download_context(text: str) -> list[tuple[str, str, str]]:
    entries = []
    for line in text.splitlines():
        parts = line.strip().split("\t")
        if len(parts) == 3 and re.fullmatch(r"[0-9a-fA-F]{64}", parts[0]) and "/" not in parts[2]:
            entries.append((parts[0].lower(), parts[1], parts[2]))
    return entries


def seed_paperclip(jar: Path, work: Path) -> None:
    """Put the vanilla server jar paperclip needs into work/cache before the first boot.

    Paperclip downloads it on every fresh server directory and fails the boot on a hash
    mismatch. Going through download() gives that file the same cache, hash check, and retries
    as the Paper jar. Libraries ship inside the paperclip jar and need no download.
    """
    for sha256, url, name in read_download_context(jar):
        download(url, work / "cache" / name, sha256=sha256, name=name)
        print(f"paperclip: {name} ready in cache (sha256 checked)")


def fetch(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


_fetch_text = fetch
LISTING_ATTEMPTS = 3


def fetch_listing(url: str, attempts: int = LISTING_ATTEMPTS):
    """A Paper API build list, with retries.

    A good answer is saved under cache/api/. When the API cannot be reached after the retries,
    the last saved answer for that URL is used, so a host with a warm cache can boot offline.
    """
    saved = cache_dir() / "api" / (hashlib.sha256(url.encode("utf-8")).hexdigest() + ".json")
    problem = ""
    for attempt in range(1, max(1, attempts) + 1):
        try:
            text = _fetch_text(url)
            payload = json.loads(text)
            saved.parent.mkdir(parents=True, exist_ok=True)
            temporary = saved.with_name(saved.name + ".part")
            temporary.write_text(text, encoding="utf-8")
            os.replace(temporary, saved)
            return payload
        except (OSError, http.client.HTTPException, ValueError) as error:
            problem = f"{type(error).__name__}: {error}"
            print(f"listing {url} failed: {problem}", file=sys.stderr)
        if attempt < attempts:
            _sleep(retry_pause(attempt))
    if saved.is_file():
        try:
            payload = json.loads(saved.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            payload = None
        if payload is not None:
            print(f"cache: using the saved build list for {url}")
            return payload
    raise RuntimeError(f"could not list builds after {attempts} attempts: {url}: {problem}")


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class MockHandle:
    """Request counter for the load harness. Smoke ignores the return value."""

    def __init__(self) -> None:
        self._count = 0
        self._lock = threading.Lock()
        self._json_invalid_once = False
        self._json_length_once = False
        self._last_body = ""
        self._bodies: list[str] = []
        self._knowledge_body = ""
        self.server: ThreadingHTTPServer | None = None

    def add(self) -> int:
        with self._lock:
            self._count += 1
            return self._count

    @property
    def count(self) -> int:
        with self._lock:
            return self._count

    def note_body(self, raw: bytes) -> None:
        text = raw.decode("utf-8", errors="replace")
        with self._lock:
            self._last_body = text
            self._bodies.append(text)
            if "----- KNOWLEDGE -----" in text:
                self._knowledge_body = text

    def bodies(self) -> list[str]:
        with self._lock:
            return list(self._bodies)

    @property
    def knowledge_body(self) -> str:
        with self._lock:
            return self._knowledge_body or self._last_body

    def close(self) -> None:
        if self.server is not None:
            self.server.shutdown()
            self.server = None


def _mock_choice(headers, path: str, name: str, fallback: str | None) -> str | None:
    """Per-request header or query overrides the value passed to ``start_mock``."""
    header_name = {
        "usage": "X-Nexus-Mock-Usage",
        "status": "X-Nexus-Mock-Status",
        "json": "X-Nexus-Mock-Json",
        "reject": "X-Nexus-Mock-Reject",
    }[name]
    raw = headers.get(header_name)
    if raw:
        return raw.strip().lower()
    query = parse_qs(urlsplit(path).query)
    values = query.get(name)
    if values and values[0].strip():
        return values[0].strip().lower()
    if fallback:
        return fallback.strip().lower()
    return None


def _usage_object(mode: str | None) -> dict | None:
    """``off`` and an unset mode keep the historical body, which has no ``usage`` key."""
    if mode in (None, "", "off"):
        return None
    if mode == "partial":
        return {"total_tokens": 12}
    if mode == "cost":
        return {"prompt_tokens": 8, "completion_tokens": 2, "total_tokens": 10, "cost": 0.00012}
    if mode == "on":
        return {"prompt_tokens": 8, "completion_tokens": 2, "total_tokens": 10}
    return None


QUEST_JSON = '{"title":"Iron nails","goal":"Bring ten iron nails.","reward":12}'
INVALID_QUEST_JSON = '{"title":"Iron nails","goal":"Bring ten iron nails.","reward":"nope"}'
FENCED_QUEST_JSON = "```json\n" + QUEST_JSON + "\n```"
TRUNCATED_QUEST_JSON = '{"title":"Iron'


def _completion_body(usage_mode: str | None, content: str = "pong", finish_reason: str = "stop") -> bytes:
    payload = {
        "id": "smoke",
        "object": "chat.completion",
        "choices": [{
            "index": 0,
            "message": {"role": "assistant", "content": content},
            "finish_reason": finish_reason,
        }],
    }
    usage = _usage_object(usage_mode)
    if usage is not None:
        payload["usage"] = usage
    return json.dumps(payload).encode("utf-8")


def _response_format_type(raw: bytes) -> str | None:
    if not raw:
        return None
    try:
        body = json.loads(raw.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError):
        return None
    if not isinstance(body, dict):
        return None
    fmt = body.get("response_format")
    if not isinstance(fmt, dict):
        return None
    kind = fmt.get("type")
    return kind if isinstance(kind, str) else None


def _json_reply(handle: MockHandle, mode: str | None) -> tuple[str, str] | None:
    """Content and finish_reason for a JSON mock mode. None keeps the text reply."""
    if mode in (None, "", "off"):
        return None
    if mode == "valid":
        return QUEST_JSON, "stop"
    if mode == "invalid":
        return INVALID_QUEST_JSON, "stop"
    if mode == "fenced":
        return FENCED_QUEST_JSON, "stop"
    if mode == "invalid-once":
        with handle._lock:
            if not handle._json_invalid_once:
                handle._json_invalid_once = True
                return INVALID_QUEST_JSON, "stop"
        return QUEST_JSON, "stop"
    if mode == "length-once":
        with handle._lock:
            if not handle._json_length_once:
                handle._json_length_once = True
                return TRUNCATED_QUEST_JSON, "length"
        return QUEST_JSON, "stop"
    return None


def start_mock(
    port: int,
    latency_ms: int = 0,
    requests_path: Path | None = None,
    fail_every: int = 0,
    usage: str | None = None,
    status_mode: str | None = None,
) -> MockHandle:
    """OpenAI-compatible mock. Defaults match the smoke boot: instant HTTP 200, body ``pong``.

    Load runs pass ``latency_ms``, ``requests_path`` (JSONL of request bodies), and
    ``fail_every`` (HTTP 429 on every Nth request).

    ``usage`` is ``on``, ``off``, ``partial``, or ``cost``. ``status_mode`` is
    ``429-once``, ``500``, or ``401``. A request may override either with the header
    ``X-Nexus-Mock-Usage`` / ``X-Nexus-Mock-Status`` or the query ``usage`` / ``status``.
    A status override wins over ``fail_every``. The default response has no ``usage`` key.

    ``X-Nexus-Mock-Json`` or the query ``json`` is ``valid``, ``invalid-once``,
    ``invalid``, ``fenced``, ``length-once``, ``reject-schema``, or ``reject-object``.
    ``X-Nexus-Mock-Reject`` or the query ``reject`` is ``json_schema`` or ``json_object``
    and answers HTTP 400 when the body asks for that ``response_format``. A body that
    already asks for ``json_schema`` or ``json_object``, and does not set ``json``,
    is answered with the quest object.
    """
    handle = MockHandle()
    handle._status_once = False
    quiet = latency_ms > 0 or requests_path is not None or fail_every > 0 or usage is not None or status_mode is not None
    if requests_path is not None:
        requests_path.parent.mkdir(parents=True, exist_ok=True)
        requests_path.write_text("", encoding="utf-8")

    class Handler(BaseHTTPRequestHandler):
        # Smoke stays on the historical HTTP/1.0 close. A load run speaks HTTP/1.1
        # so Java HttpClient can reuse the socket. HTTP/1.0 close under a few
        # dozen in-flight calls surfaces as "header parser received no bytes"
        # and then a provider pause, which is the mock, not the pool.
        protocol_version = "HTTP/1.1" if quiet else "HTTP/1.0"

        def do_POST(self) -> None:
            length = int(self.headers.get("Content-Length", "0") or "0")
            raw = self.rfile.read(length) if length else b""
            number = handle.add()
            handle.note_body(raw)
            usage_mode = _mock_choice(self.headers, self.path, "usage", usage)
            forced = _mock_choice(self.headers, self.path, "status", status_mode)
            json_mode = _mock_choice(self.headers, self.path, "json", None)
            reject_mode = _mock_choice(self.headers, self.path, "reject", None)
            if json_mode == "reject-schema":
                reject_mode = "json_schema"
                json_mode = None
            elif json_mode == "reject-object":
                reject_mode = "json_object"
                json_mode = None
            response_format = _response_format_type(raw)
            status = 200
            if forced == "429-once":
                with handle._lock:
                    if not handle._status_once:
                        handle._status_once = True
                        status = 429
            elif forced == "500":
                status = 500
            elif forced == "401":
                status = 401
            elif fail_every > 0 and number % fail_every == 0:
                status = 429
            if latency_ms > 0:
                time.sleep(latency_ms / 1000.0)
            if status == 429:
                body = b'{"error":{"message":"rate limit","type":"rate_limit"}}'
            elif status == 500:
                body = b'{"error":{"message":"server error","type":"server_error"}}'
            elif status == 401:
                body = b'{"error":{"message":"unauthorized","type":"invalid_api_key"}}'
            elif status == 200 and reject_mode in ("json_schema", "json_object") and response_format == reject_mode:
                status = 400
                body = (
                    '{"error":{"message":"response_format '
                    + reject_mode
                    + ' is not supported","type":"invalid_request_error"}}'
                ).encode("utf-8")
            else:
                chosen = json_mode
                if chosen is None and response_format in ("json_schema", "json_object"):
                    chosen = "valid"
                reply = _json_reply(handle, chosen)
                if reply is None:
                    body = _completion_body(usage_mode)
                else:
                    body = _completion_body(usage_mode, reply[0], reply[1])
            # The journal is visible before the response bytes, so a client that
            # reads it as soon as the status returns cannot miss the last line.
            if requests_path is not None:
                text = raw.decode("utf-8", errors="replace")
                line = json.dumps({
                    "n": number,
                    "t": time.time(),
                    "status": status,
                    "path": self.path,
                    "body": text,
                    "usage": usage_mode or "off",
                    "mockStatus": forced or "",
                }, ensure_ascii=False)
                with handle._lock:
                    with requests_path.open("a", encoding="utf-8") as handle_out:
                        handle_out.write(line + "\n")
            try:
                self.send_response(status)
                if status == 429:
                    self.send_header("Retry-After", "1")
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, TimeoutError):
                return

        def log_message(self, fmt: str, *args) -> None:
            if not quiet:
                print("mock", fmt % args)

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    handle.server = server
    thread = threading.Thread(target=server.serve_forever, name="openai-mock", daemon=True)
    thread.start()
    print(f"mock OpenAI endpoint http://127.0.0.1:{port}/v1")
    return handle


def write_config(path: Path, mock_port: int, startup_banner: str = "") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    url = f"http://127.0.0.1:{mock_port}/v1"
    path.write_text(
        "\n".join([
            "config-version: 1",
            "locale: en",
            "api:",
            "  provider: openai",
            "  model: smoke-model",
            f'  base-url: "{url}"',
            '  key: "sk-smoke-test-not-a-real-key"',
            "providers:",
            "  openai:",
            "    type: openai-compatible",
            f'    url: "{url}"',
            '    api-key: "sk-smoke-test-not-a-real-key"',
            "model-queue:",
            "  - provider: openai",
            "    model: smoke-model",
            "pool:",
            "  enabled: false",
            "  entries: []",
            "prewarm:",
            "  enabled: false",
            "  prompts: []",
            'fallback: "..."',
        ] + ([f"startup-banner: {startup_banner}"] if startup_banner else []) + [
            "",
        ]),
        encoding="utf-8",
    )


def write_server(
    work: Path,
    server_port: int,
    rcon_port: int,
    max_players: int = 5,
    rcon_password: str = "smoke",
    motd: str = "NexusAI smoke",
    extras: list[str] | None = None,
) -> None:
    (work / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    lines = [
        "online-mode=false",
        "enforce-secure-profile=false",
        "server-ip=127.0.0.1",
        f"server-port={server_port}",
        f"motd={motd}",
        f"max-players={max_players}",
        "view-distance=2",
        "simulation-distance=2",
        "spawn-monsters=false",
        "spawn-animals=false",
        "spawn-npcs=false",
        "level-type=flat",
        "enable-rcon=true",
        "broadcast-rcon-to-ops=false",
        "rcon.ip=127.0.0.1",
        f"rcon.port={rcon_port}",
        f"rcon.password={rcon_password}",
        "sync-chunk-writes=false",
    ]
    if extras:
        lines.extend(extras)
    lines.append("")
    (work / "server.properties").write_text("\n".join(lines), encoding="utf-8")


def write_knowledge_fixture(folder: Path) -> None:
    """A keywords prompt and a small rules file. /nai test must select the appeal paragraph."""
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "prompts.yml").write_text(
        "\n".join([
            "config-version: 1",
            "rules_help:",
            '  prompt: "Answer the player question about server rules."',
            "  knowledge:",
            "    - rules",
            "  knowledge-select: keywords",
            "  knowledge-keywords:",
            "    - appeal",
            "",
        ]),
        encoding="utf-8",
    )
    knowledge = folder / "knowledge"
    knowledge.mkdir(parents=True, exist_ok=True)
    (knowledge / "rules.md").write_text(
        "\n".join([
            "# Harbor",
            "",
            "Ships pay the dock fee before they unload.",
            "",
            "# Appeals",
            "",
            "<!-- keywords: grief, steal -->",
            "Write to staff to file an appeal after a ban.",
            "",
        ]),
        encoding="utf-8",
    )


def boot(
    work: Path,
    version: str,
    paper: dict,
    rcon_port: int,
    mock_port: int,
    timeout: int,
    check_api: bool = False,
    mock: MockHandle | None = None,
    startup_banner: str = "",
) -> int:
    log_chunks: list[str] = []

    def reader(pipe) -> None:
        for line in pipe:
            log_chunks.append(line)
            sys.stdout.write(line)
            sys.stdout.flush()

    process = subprocess.Popen(
        [
            "java",
            "-Djava.awt.headless=true",
            "-Dterminal.jline=false",
            "-Dterminal.ansi=false",
            "-Xms512M",
            "-Xmx1024M",
            "-jar",
            "paper.jar",
            "--nogui",
        ],
        cwd=work,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    threading.Thread(target=reader, args=(process.stdout,), daemon=True).start()
    deadline = time.monotonic() + timeout
    try:
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f"server exited early with code {process.returncode}")
            text = "".join(log_chunks)
            if "Done (" in text:
                break
            if "Unsupported API version" in text or "Error occurred while enabling NexusAI" in text:
                raise RuntimeError("plugin failed during startup")
            time.sleep(1)
        else:
            raise RuntimeError("timed out waiting for Done")

        text = "".join(log_chunks)
        if not nexusai_enabled(text):
            raise RuntimeError("NexusAI did not log that it enabled")
        problem = ready_line_problem(text) if startup_banner == "false" else banner_problem(text)
        if problem:
            raise RuntimeError(problem)
        if f"127.0.0.1:{mock_port}/v1" not in text:
            raise RuntimeError("startup log does not show the mock base URL")
        if "ERROR]: [NexusAI]" in text or "ERROR]: [NexusAI] " in text:
            raise RuntimeError("NexusAI logged an error while enabling")

        status = rcon("127.0.0.1", rcon_port, "smoke", "nai status")
        visible = strip_colors(status)
        print("--- /nai status ---")
        print(visible)
        if "Status:" not in visible:
            raise RuntimeError(" /nai status did not print a status header")
        if "PlaceholderAPI:" not in visible or "yes" not in visible.split("PlaceholderAPI:", 1)[-1][:40]:
            raise RuntimeError(" /nai status did not report PlaceholderAPI as yes")
        if f"127.0.0.1:{mock_port}/v1" not in visible:
            raise RuntimeError(" /nai status did not show the mock base URL")
        if "Tokens today:" not in visible:
            raise RuntimeError(" /nai status did not print today's token total")
        if "Quotas:" not in visible:
            raise RuntimeError(" /nai status did not print whether quotas are on")
        if check_api:
            if "NexusAI-LoadDriver enabled." not in text:
                raise RuntimeError("LoadDriver did not enable")
            queued = strip_colors(rcon("127.0.0.1", rcon_port, "smoke", "naiload api template 1"))
            print("--- naiload api ---")
            print(queued)
            wait_api_result(process, log_chunks, min(timeout, 90))
            queued_events = strip_colors(rcon("127.0.0.1", rcon_port, "smoke", "naiload events"))
            print("--- naiload events ---")
            print(queued_events)
            wait_event_checks(process, log_chunks, min(timeout, 90))
            verify_event_order("".join(log_chunks))
            verify_json(process, log_chunks, rcon_port, min(timeout, 90))

        if mock is None:
            raise RuntimeError("knowledge smoke has no mock endpoint")
        verify_knowledge(process, log_chunks, rcon_port, mock, min(timeout, 90))

        # 26.2 closes the RCON socket as soon as stop begins, before the response
        # packet is finished. The command still reached the server.
        try:
            rcon("127.0.0.1", rcon_port, "smoke", "stop")
        except EOFError:
            pass
        code = process.wait(timeout=90)
        if code != 0:
            raise RuntimeError(f"server stop exited {code}")
        if check_api:
            verify_token_usage(work)
        print(f"SMOKE OK {version} build {paper['id']}")
        return 0
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=30)


def verify_knowledge(process, chunks: list[str], rcon_port: int, mock: MockHandle, timeout: int) -> None:
    """ /nai test on a keywords prompt. Prints KNOWLEDGE OK. A miss is a failure."""
    reply = strip_colors(rcon("127.0.0.1", rcon_port, "smoke", "nai test rules_help"))
    print("--- /nai test rules_help ---")
    print(reply)
    if "Knowledge:" not in reply or "rules#" not in reply or "(keywords)" not in reply:
        raise RuntimeError("/nai test did not print the keyword selection")
    deadline = time.monotonic() + timeout
    body = ""
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited during knowledge smoke with code {process.returncode}")
        body = mock.knowledge_body
        if "----- KNOWLEDGE -----" in body and "appeal" in body.lower():
            break
        time.sleep(0.2)
    else:
        raise RuntimeError("keyword knowledge block was not sent to the mock")
    if "<!--" in body:
        raise RuntimeError("a knowledge HTML comment was sent to the model")
    for piece in ("----- KNOWLEDGE -----", "[rules]", "# Appeals"):
        if piece not in body:
            raise RuntimeError(f"knowledge body is missing {piece}")
    if "appeal" not in body.lower():
        raise RuntimeError("the appeal paragraph was not selected")
    if "dock fee" in body.lower():
        raise RuntimeError("the harbor paragraph was sent, so keyword selection did not run")
    print("KNOWLEDGE OK")


def verify_json(process, chunks: list[str], rcon_port: int, timeout: int) -> None:
    """One generateJson call. Prints JSON OK. A missing line is a failure."""
    queued = strip_colors(rcon("127.0.0.1", rcon_port, "smoke", "naiload json"))
    print("--- naiload json ---")
    print(queued)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited during json smoke with code {process.returncode}")
        text = "".join(chunks)
        if "NEXUSAI_JSON success=true mode=JSON_SCHEMA" in text:
            print("JSON OK")
            return
        if "NEXUSAI_JSON success=false" in text:
            raise RuntimeError("generateJson did not return valid JSON")
        time.sleep(0.5)
    raise RuntimeError("timed out waiting for NEXUSAI_JSON success=true mode=JSON_SCHEMA")


def verify_event_order(text: str) -> None:
    """API, placeholder, and action checks. Prints EVENT ORDER OK when they all pass."""
    if "nexusai-events-probe enabled." not in text:
        raise RuntimeError("events probe did not enable")
    verify_api_order(text)
    verify_placeholder_order(text)
    verify_action_thread(text)
    print("EVENT ORDER OK")


def verify_api_order(text: str) -> None:
    """One API generate logs pre, then post with the same request id, before the model result."""
    pre_index = None
    pre_id = None
    post_index = None
    result_index = None
    for index, line in enumerate(text.splitlines()):
        if (
            pre_index is None
            and "NEXUSAI_EVENT" in line
            and "phase=pre" in line
            and "origin=API" in line
        ):
            marker = "requestId="
            start = line.find(marker)
            if start < 0:
                raise RuntimeError("pre event line has no requestId")
            pre_id = line[start + len(marker):].split()[0]
            pre_index = index
        if (
            pre_id
            and post_index is None
            and "NEXUSAI_EVENT" in line
            and "phase=post" in line
            and f"requestId={pre_id}" in line
            and "origin=API" in line
        ):
            post_index = index
        if (
            post_index is not None
            and result_index is None
            and index > post_index
            and "API_RESULT" in line
            and "success=true" in line
            and "source=MODEL" in line
            and "text=pong" in line
        ):
            result_index = index
    if pre_index is None or post_index is None or result_index is None:
        raise RuntimeError("events probe did not log an API pre, a matching post, and API_RESULT")
    if pre_index >= post_index or post_index >= result_index:
        raise RuntimeError(
            f"expected API pre, then post, then API_RESULT; indexes were {pre_index}, {post_index}, {result_index}"
        )


def verify_placeholder_order(text: str) -> None:
    """One placeholder logs Pre and Post for nexusai, and Post ran before the text was read.

    The Post line comes from an HTTP thread and PLACEHOLDER_TEXT from the main thread, so
    their console order is not proof. The load driver appends post=<requestId>, read from a
    property the probe sets after it logs Post, which is.
    """
    pre_index, pre_id, post_index, text_index = _paired_phase(
        text, "PLACEHOLDER", "nexusai", "PLACEHOLDER_TEXT", "text=pong"
    )
    if pre_index is None or text_index is None:
        raise RuntimeError("events probe did not log a PLACEHOLDER pre for consumer nexusai before PLACEHOLDER_TEXT")
    if post_index is None:
        raise RuntimeError(f"events probe did not log a PLACEHOLDER post for requestId={pre_id}")
    if pre_index >= text_index:
        raise RuntimeError(
            f"expected PLACEHOLDER pre before PLACEHOLDER_TEXT; indexes were {pre_index}, {text_index}"
        )
    seen = _placeholder_post_seen(text.splitlines()[text_index])
    if seen != pre_id:
        raise RuntimeError(
            f"PLACEHOLDER_TEXT was read with post={seen}, expected post={pre_id}; Post did not run first"
        )


def _placeholder_post_seen(line: str) -> str | None:
    marker = " post="
    start = line.find(marker)
    if start < 0:
        return None
    return line[start + len(marker):].split()[0]


def wait_placeholder_post(process, chunks: list[str], timeout: float) -> None:
    """Wait for the placeholder Post line; it can reach the console after PLACEHOLDER_TEXT."""
    deadline = time.monotonic() + timeout
    while True:
        text = "".join(chunks)
        _pre, pre_id, post_index, _text = _paired_phase(
            text, "PLACEHOLDER", "nexusai", "PLACEHOLDER_TEXT", "text=pong"
        )
        if pre_id is None or post_index is not None:
            return
        if time.monotonic() >= deadline or process.poll() is not None:
            return
        time.sleep(0.2)


def verify_action_thread(text: str) -> None:
    """A console character action on Paper logs primary=true."""
    for line in text.splitlines():
        if (
            "NEXUSAI_EVENT" in line
            and "phase=action" in line
            and "character=smoke" in line
            and "primary=true" in line
        ):
            return
    raise RuntimeError("character action did not log primary=true")


def _paired_phase(
    text: str,
    origin: str,
    consumer: str,
    appearance_marker: str,
    appearance_token: str,
) -> tuple[int | None, str | None, int | None, int | None]:
    pre_index = None
    pre_id = None
    post_index = None
    appearance_index = None
    for index, line in enumerate(text.splitlines()):
        if (
            pre_index is None
            and "NEXUSAI_EVENT" in line
            and "phase=pre" in line
            and f"origin={origin}" in line
            and f"consumer={consumer}" in line
        ):
            marker = "requestId="
            start = line.find(marker)
            if start < 0:
                raise RuntimeError(f"{origin} pre event line has no requestId")
            pre_id = line[start + len(marker):].split()[0]
            pre_index = index
        if (
            pre_id
            and post_index is None
            and "NEXUSAI_EVENT" in line
            and "phase=post" in line
            and f"requestId={pre_id}" in line
            and f"origin={origin}" in line
            and f"consumer={consumer}" in line
        ):
            post_index = index
        if appearance_index is None and appearance_marker in line and appearance_token in line:
            appearance_index = index
    return pre_index, pre_id, post_index, appearance_index


PLACEHOLDER_POST_WAIT_SECONDS = 10.0


def wait_event_checks(process, chunks: list[str], timeout: int) -> None:
    """Poll until the placeholder text and the action result are both in the log."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited during event smoke with code {process.returncode}")
        text = "".join(chunks)
        if "PLACEHOLDER_TEXT text=pong" in text and "ACTION_DONE" in text and "phase=action" in text:
            print("event smoke saw placeholder text and a character action")
            wait_placeholder_post(process, chunks, PLACEHOLDER_POST_WAIT_SECONDS)
            return
        if "PLACEHOLDER_TEXT timeout" in text or "PLACEHOLDER_TEXT error=" in text:
            raise RuntimeError("placeholder expansion did not return the model text")
        time.sleep(0.5)
    raise RuntimeError("timed out waiting for PLACEHOLDER_TEXT and ACTION_DONE")


def wait_api_result(process, chunks: list[str], timeout: int) -> None:
    """Poll until one generate() result is a model reply and every call has finished."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited during api smoke with code {process.returncode}")
        text = "".join(chunks)
        if "API_DONE" in text and _api_model_line(text):
            print("api smoke saw MODEL pong")
            return
        time.sleep(0.5)
    raise RuntimeError("timed out waiting for API_DONE and a MODEL pong result")


def _api_model_line(text: str) -> bool:
    for line in text.splitlines():
        if "API_RESULT" not in line:
            continue
        if (
            "success=true" in line
            and "source=MODEL" in line
            and "text=pong" in line
            and "provider=openai" in line
            and "model=smoke-model" in line
            and "finish=stop" in line
            and "attempts=1" in line
        ):
            return True
    return False


def verify_token_usage(work: Path) -> None:
    """After a generate() against the mock, disable must have written token-usage.yml."""
    path = work / "plugins" / "NexusAI" / "token-usage.yml"
    if not path.is_file():
        raise RuntimeError("token-usage.yml was not written")
    text = path.read_text(encoding="utf-8")
    if "format: 1" not in text:
        raise RuntimeError("token-usage.yml is missing format 1")
    if "sk-smoke" in text:
        raise RuntimeError("token-usage.yml contains the smoke API key")
    leftover = list(path.parent.glob("token-usage.yml.*.tmp"))
    if leftover:
        raise RuntimeError("token-usage.yml temp file was left behind: " + leftover[0].name)


ANSI_ESCAPE = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")
LEGACY_COLOR = re.compile(r"[\u00a7&](?:x(?:[\u00a7&][0-9a-fA-F]){6}|[0-9a-fk-orA-FK-ORxX])")
BANNER_READY = re.compile(r"(?:^|\s)Ready in \d+ ms")


def plain_log(text: str) -> str:
    """Drop ANSI escapes and legacy section/ampersand colour codes."""
    return LEGACY_COLOR.sub("", ANSI_ESCAPE.sub("", text))


def nexusai_enabled(text: str) -> bool:
    """True when NexusAI finished enabling: banner 'Ready in' line or the plain 'NexusAI enabled.' line."""
    plain = plain_log(text)
    if "NexusAI enabled." in plain:
        return True
    return any(BANNER_READY.search(line) for line in plain.splitlines())


BANNER_LINES = 9


def banner_problem(text: str) -> str:
    """Empty when the startup banner is the full one: an empty spacer, three title lines,
    a rule, four summary lines ending with Ready in, and no [NexusAI] prefix on any of them."""
    lines = plain_log(text).splitlines()
    ready = [i for i, line in enumerate(lines) if BANNER_READY.search(line)]
    if not ready:
        return "startup banner Ready in line is missing"
    end = ready[0]
    if end + 1 < BANNER_LINES:
        return "startup banner is cut off"
    block = [log_message(line).rstrip("\r") for line in lines[end + 1 - BANNER_LINES:end + 1]]
    if block[0].strip():
        return "startup banner does not start with an empty line: " + block[0]
    if any("[NexusAI]" in line for line in block):
        return "startup banner line carries the [NexusAI] prefix"
    if not all(line.strip() for line in block[1:]) or not block[4].startswith("\u2500"):
        return "startup banner is not the full banner"
    return ""


READY_LINE = re.compile(r"\[NexusAI\] NexusAI enabled\. Ready in \d+ ms$")


def ready_line_problem(text: str) -> str:
    """Empty when startup-banner: false left exactly one short ready line and no banner."""
    lines = [log_message(line).rstrip("\r") for line in plain_log(text).splitlines()]
    ready = [line for line in lines if READY_LINE.search(line)]
    if len(ready) != 1:
        return f"expected one short ready line, found {len(ready)}"
    if any(BANNER_READY.search(line) and not READY_LINE.search(line) for line in lines):
        return "startup banner printed although startup-banner is false"
    if any(line.startswith("\u2500" * 10) for line in lines):
        return "startup banner rule printed although startup-banner is false"
    return ""


def strip_colors(text: str) -> str:
    out = []
    skip = False
    for char in text:
        if char == "§":
            skip = True
            continue
        if skip:
            skip = False
            continue
        out.append(char)
    return "".join(out)


def console_command(process, line: str) -> None:
    """Run a command on the server console.

    Folia RCON returns only the text buffered while the command method runs.
    A later {@code SenderTasks} message is not part of that packet and is not
    copied into the server log. The console sender does write it to the log.
    """
    if process.stdin is None or process.stdin.closed:
        raise RuntimeError(f"server console is closed; cannot run {line}")
    process.stdin.write(line + "\n")
    process.stdin.flush()


def rcon(host: str, port: int, password: str, command: str) -> str:
    deadline = time.monotonic() + 20
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            with socket.create_connection((host, port), timeout=5) as sock:
                sock.settimeout(10)
                send_packet(sock, 1, 3, password)
                request_id, _, _ = read_packet(sock)
                if request_id == -1:
                    raise RuntimeError("rcon login failed")
                send_packet(sock, 2, 2, command)
                parts = []
                sock.settimeout(15)
                request_id, _, payload = read_packet(sock)
                parts.append(payload)
                sock.settimeout(1)
                while True:
                    try:
                        _, _, extra = read_packet(sock)
                    except (TimeoutError, socket.timeout):
                        break
                    parts.append(extra)
                return "".join(parts)
        except (ConnectionRefusedError, TimeoutError, socket.timeout, OSError) as error:
            last_error = error
            time.sleep(1)
    raise RuntimeError(f"rcon failed: {last_error}")


def send_packet(sock: socket.socket, request_id: int, kind: int, payload: str) -> None:
    data = payload.encode("utf-8") + b"\x00\x00"
    body = struct.pack("<ii", request_id, kind) + data
    sock.sendall(struct.pack("<i", len(body)) + body)


def read_packet(sock: socket.socket) -> tuple[int, int, str]:
    length = struct.unpack("<i", recvall(sock, 4))[0]
    if length < 8 or length > 10_000_000:
        raise RuntimeError(f"bad rcon packet length {length}")
    data = recvall(sock, length)
    request_id, kind = struct.unpack("<ii", data[:8])
    payload = data[8:-2].decode("utf-8", errors="replace") if len(data) >= 10 else ""
    return request_id, kind, payload


def recvall(sock: socket.socket, size: int) -> bytes:
    chunks = []
    remaining = size
    while remaining:
        chunk = sock.recv(remaining)
        if not chunk:
            raise EOFError("rcon connection closed")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def run_folia(args) -> int:
    """Download one Folia build and boot it. A download or startup failure fails the run."""
    if not args.version:
        print("missing Folia version", file=sys.stderr)
        return 2
    plugin = find_plugin(Path(args.plugins_dir))
    major = class_major(plugin, "io/github/neareststep/nexusai/NexusAI.class")
    if major != 65:
        print(f"expected class file 65, found {major} in {plugin}", file=sys.stderr)
        return 1
    supported = read_folia_supported(plugin)
    mode = folia_check_mode(args.folia_checks, supported)
    print(f"plugin {plugin.name} class file {major}")
    print(f"folia-supported: {'true' if supported else 'false'}")
    print(f"folia checks: {mode}")

    work = Path(tempfile.mkdtemp(prefix=f"nexusai-folia-{args.version}-"))
    print(f"server directory {work}")
    try:
        chosen = download_folia(args.version, work / "folia.jar")
        seed_paperclip(work / "folia.jar", work)
        install_papi(work / "plugins")
        shutil.copy2(plugin, work / "plugins" / plugin.name)
        if mode == "full":
            install_folia_probe(args.driver_dir, work / "plugins")
        elif args.driver_dir.strip():
            print("folia startup checks leave the load driver and events probe uninstalled")
        mock_port = free_port()
        mock = start_mock(mock_port)
        write_config(work / "plugins" / "NexusAI" / "config.yml", mock_port, args.startup_banner)
        write_knowledge_fixture(work / "plugins" / "NexusAI")
        if mode == "full":
            append_context_prompt(work / "plugins" / "NexusAI")
        server_port = free_port()
        rcon_port = free_port()
        write_server(work, server_port, rcon_port)
        return boot_folia(work, args.version, chosen, rcon_port, mock_port, args.timeout, mode, mock)
    except Exception as error:
        print(f"FOLIA SMOKE FAIL {args.version}: {error}", file=sys.stderr)
        return 1


def download_folia(version: str, destination: Path) -> dict:
    url = FILL_PROJECT.format(project="folia", version=version)
    try:
        payload = fetch_listing(url)
    except Exception as error:
        raise RuntimeError(f"could not list Folia builds for {version}: {error}") from error
    builds = payload["builds"] if isinstance(payload, dict) else payload
    try:
        chosen, channel = choose_folia_build(builds)
    except Exception as error:
        raise RuntimeError(f"could not choose a Folia build for {version}: {error}") from error
    try:
        download_info = chosen["downloads"]["server:default"]
    except Exception as error:
        raise RuntimeError(f"Folia build {chosen.get('id')} has no server download: {error}") from error
    print(f"Folia {version} {channel} build {chosen['id']} ({download_info['name']})")
    try:
        download(download_info["url"], destination, sha256=fill_sha256(download_info))
    except Exception as error:
        raise RuntimeError(
            f"could not download Folia {version} {channel} build {chosen['id']}: {error}"
        ) from error
    return {"id": int(chosen["id"]), "name": download_info["name"], "channel": channel}


def choose_folia_build(builds: list) -> tuple[dict, str]:
    """Newest build in STABLE, otherwise BETA, otherwise ALPHA."""
    if not isinstance(builds, list) or not builds:
        raise RuntimeError("no Folia builds were published")
    for channel in ("STABLE", "BETA", "ALPHA"):
        matching = [build for build in builds if str(build.get("channel", "")).upper() == channel]
        if matching:
            chosen = max(matching, key=lambda build: int(build["id"]))
            return chosen, channel
    raise RuntimeError("no STABLE, BETA, or ALPHA Folia build")


def read_folia_supported(jar: Path) -> bool:
    with zipfile.ZipFile(jar) as archive:
        text = archive.read("plugin.yml").decode("utf-8")
    return plugin_yml_folia_supported(text)


def plugin_yml_folia_supported(text: str) -> bool:
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line.lower().startswith("folia-supported:"):
            continue
        value = line.split(":", 1)[1].strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1].strip()
        return value.lower() == "true"
    return False


def folia_check_mode(flag: str, supported: bool) -> str:
    """auto follows plugin.yml. full and startup are explicit overrides."""
    if flag == "full":
        return "full"
    if flag == "startup":
        return "startup"
    if flag != "auto":
        raise RuntimeError(f"unknown folia checks {flag}")
    return "full" if supported else "startup"


def install_folia_probe(driver_dir: str, plugins: Path) -> None:
    """The events probe is Folia-safe. The load driver is not installed here."""
    if not driver_dir.strip():
        raise RuntimeError("full Folia checks need nexusai-events-probe.jar")
    directory = Path(driver_dir)
    if not directory.exists():
        raise RuntimeError(f"full Folia checks need nexusai-events-probe.jar in {directory}")
    probe = find_probe(directory)
    if probe is None:
        raise RuntimeError(f"full Folia checks need nexusai-events-probe.jar in {directory}")
    shutil.copy2(probe, plugins / probe.name)
    print(f"events probe {probe.name}")
    print("load driver is not installed on Folia")


def append_context_prompt(folder: Path) -> None:
    """Named prompt whose context id is the smoke provider. Console does not call provide()."""
    path = folder / "prompts.yml"
    extra = "\n".join([
        "context_ping:",
        '  prompt: "Reply with one word."',
        "  context:",
        "    - smoke",
        "",
    ])
    path.write_text(path.read_text(encoding="utf-8") + extra, encoding="utf-8")


def boot_folia(
    work: Path,
    version: str,
    chosen: dict,
    rcon_port: int,
    mock_port: int,
    timeout: int,
    mode: str,
    mock: MockHandle,
) -> int:
    log_chunks: list[str] = []

    def reader(pipe) -> None:
        for line in pipe:
            log_chunks.append(line)
            sys.stdout.write(line)
            sys.stdout.flush()

    process = subprocess.Popen(
        [
            "java",
            "-Djava.awt.headless=true",
            "-Dterminal.jline=false",
            "-Dterminal.ansi=false",
            "-Xms512M",
            "-Xmx1024M",
            "-jar",
            "folia.jar",
            "--nogui",
        ],
        cwd=work,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    threading.Thread(target=reader, args=(process.stdout,), daemon=True).start()
    deadline = time.monotonic() + timeout
    try:
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f"server exited early with code {process.returncode}")
            text = "".join(log_chunks)
            if "Done (" in text:
                break
            time.sleep(1)
        else:
            raise RuntimeError("timed out waiting for Done")
        # Plugin refusal is logged before Done. A short pause catches a late line.
        time.sleep(2)
        if process.poll() is not None:
            raise RuntimeError(f"server exited after Done with code {process.returncode}")

        if mode == "startup":
            report_folia_startup("".join(log_chunks))
        else:
            run_folia_full_checks(process, log_chunks, rcon_port, mock_port, mock, min(timeout, 90))

        try:
            rcon("127.0.0.1", rcon_port, "smoke", "stop")
        except EOFError:
            pass
        code = process.wait(timeout=90)
        if code != 0:
            raise RuntimeError(f"server stop exited {code}")
        text = "".join(log_chunks)
        thread_hits = thread_region_problems(text)
        if thread_hits:
            raise RuntimeError("thread or region error during shutdown:\n" + "\n".join(thread_hits))
        if mode == "startup":
            report_folia_startup(text, announce=False)
        print(
            f"FOLIA SMOKE OK {version} build {chosen['id']} channel {chosen['channel']} {mode}",
            flush=True,
        )
        return 0
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=30)


def report_folia_startup(text: str, announce: bool = True) -> None:
    """Startup checks: Done is already required. This reports the expected refusal."""
    refusals, problems, exempted = assess_folia_startup(text)
    if announce:
        for line in refusals:
            print("FOLIA REFUSAL EXPECTED: " + line.strip(), flush=True)
    print(f"FOLIA EXEMPT LINES: {exempted}", flush=True)
    if problems:
        raise RuntimeError("folia startup checks failed:\n" + "\n".join(problems))


def assess_folia_startup(text: str) -> tuple[list[str], list[str], int]:
    refusals = expected_refusal_lines(text)
    problems: list[str] = []
    if not refusals:
        problems.append("expected Folia to report that NexusAI is not marked as supporting Folia")
    if nexusai_enabled(text):
        problems.append("NexusAI enabled even though it is not marked as supporting Folia")
    if "Error occurred while enabling NexusAI" in text:
        problems.append("NexusAI threw while enabling")
    problems.extend(thread_region_problems(text))
    unexpected, exempted = unexpected_log_problems(text)
    problems.extend(unexpected)
    if len(problems) > 20:
        problems = problems[:20] + [f"... and {len(problems) - 20} more"]
    return refusals, problems, exempted


def expected_refusal_lines(text: str) -> list[str]:
    return [line for line in text.splitlines() if is_refusal_line(line)]


def is_refusal_line(line: str) -> bool:
    return "NexusAI" in line and any(marker in line for marker in REFUSAL_MARKERS)


def is_load_wrapper(line: str) -> bool:
    return "Could not load plugin" in line and "NexusAI" in line


def is_stack_line(line: str) -> bool:
    stripped = line.strip()
    if not stripped:
        return True
    return (
        stripped.startswith("at ")
        or stripped.startswith("Caused by:")
        or stripped.startswith("Suppressed:")
        or stripped.startswith("...")
    )


def refusal_skip_indexes(lines: list[str]) -> set[int]:
    skip: set[int] = set()
    for index, line in enumerate(lines):
        if not is_refusal_line(line):
            continue
        skip.add(index)
        if index > 0 and is_load_wrapper(lines[index - 1]):
            skip.add(index - 1)
        follower = index + 1
        while follower < len(lines) and is_stack_line(lines[follower]):
            skip.add(follower)
            follower += 1
    return skip


def log_message(line: str) -> str:
    """Text after a ``[time] [thread/LEVEL]:`` or ``[time LEVEL]:`` prefix."""
    for pattern in _LOG_PREFIXES:
        match = pattern.match(line)
        if match:
            return line[match.end():]
    return line


def is_exempt_worldgen_line(line: str) -> bool:
    """True only when the message equals the flat-world line, not a longer one."""
    return log_message(line) == EXEMPT_LOG_MESSAGE


def is_error_level_line(line: str) -> bool:
    return "/ERROR]" in line or " ERROR]:" in line or "SEVERE" in line


def is_throwable_line(line: str) -> bool:
    """A class name ending in Exception, Error, or Throwable, then ':' or end of line."""
    return _THROWABLE_LINE.search(line) is not None


def unexpected_log_problems(text: str) -> tuple[list[str], int]:
    """ERROR, SEVERE, and throwable lines outside the NexusAI refusal.

    The refusal and its stack stay skipped. The flat-world line is exempt only
    when its message is exactly that text, and the exemption does not cover
    the following lines.
    """
    lines = text.splitlines()
    skip = refusal_skip_indexes(lines)
    problems = []
    exempted = 0
    for index, line in enumerate(lines):
        if index in skip:
            continue
        if is_exempt_worldgen_line(line):
            exempted += 1
            continue
        if is_error_level_line(line) or is_throwable_line(line):
            problems.append("unexpected log: " + line.strip())
    return problems, exempted


def thread_region_problems(text: str) -> list[str]:
    problems = []
    for line in text.splitlines():
        folded = line.lower()
        for pattern in THREAD_REGION_ERRORS:
            if pattern in folded:
                problems.append("thread/region: " + line.strip())
                break
    return problems


def run_folia_full_checks(process, chunks, rcon_port: int, mock_port: int, mock: MockHandle, timeout: int) -> None:
    """Status, tests, usage, context prompt, and probe events. Not used while support is off."""
    text = "".join(chunks)
    if f"127.0.0.1:{mock_port}/v1" not in text:
        raise RuntimeError("startup log does not show the mock base URL")
    require_folia_full_log(text, "before commands")

    verify_folia_status(rcon_port, mock_port)
    verify_folia_usage(process, chunks, rcon_port, timeout)
    verify_folia_default_test(process, chunks, rcon_port, mock, timeout)
    verify_knowledge(process, chunks, rcon_port, mock, timeout)
    verify_folia_context_prompt(process, chunks, rcon_port, mock, timeout)
    verify_folia_probe_events(process, chunks, timeout)
    require_folia_full_log("".join(chunks), "after commands")


def require_folia_full_log(text: str, when: str) -> None:
    """Refusal must be absent. ERROR, SEVERE, and throwable lines must be absent too."""
    problems, exempted = assess_folia_full(text)
    print(f"FOLIA EXEMPT LINES: {exempted}", flush=True)
    if expected_refusal_lines(text):
        raise RuntimeError("Folia refused a plugin while NexusAI is enabled")
    print("FOLIA REFUSAL ABSENT", flush=True)
    if problems:
        raise RuntimeError(f"folia full checks failed {when}:\n" + "\n".join(problems))


def assess_folia_full(text: str) -> tuple[list[str], int]:
    """Full-mode log gate. The startup refusal is a failure here, not an exemption."""
    problems: list[str] = []
    refusals = expected_refusal_lines(text)
    if refusals:
        problems.append("Folia refused a plugin: " + refusals[0].strip())
    if not nexusai_enabled(text):
        problems.append("NexusAI did not log that it enabled")
    if "NexusAI-LoadDriver enabled." in text:
        problems.append("load driver must not run on Folia")
    problems.extend(thread_region_problems(text))
    unexpected, exempted = unexpected_log_problems(text)
    problems.extend(unexpected)
    if len(problems) > 20:
        problems = problems[:20] + [f"... and {len(problems) - 20} more"]
    return problems, exempted


def is_mock_answer_line(text: str) -> bool:
    return _MOCK_ANSWER.search(strip_colors(text)) is not None


def mock_answer_count(text: str) -> int:
    return len(_MOCK_ANSWER.findall(strip_colors(text)))


def assert_no_folia_refusal(text: str) -> None:
    for line in text.splitlines():
        if any(marker in line for marker in REFUSAL_MARKERS):
            raise RuntimeError("Folia refused a plugin: " + line.strip())


def verify_folia_status(rcon_port: int, mock_port: int) -> None:
    visible = strip_colors(rcon("127.0.0.1", rcon_port, "smoke", "nai status"))
    print("--- /nai status ---")
    print(visible)
    if "Status:" not in visible:
        raise RuntimeError(" /nai status did not print a status header")
    if "PlaceholderAPI:" not in visible or "yes" not in visible.split("PlaceholderAPI:", 1)[-1][:40]:
        raise RuntimeError(" /nai status did not report PlaceholderAPI as yes")
    if f"127.0.0.1:{mock_port}/v1" not in visible:
        raise RuntimeError(" /nai status did not show the mock base URL")
    if "Tokens today:" not in visible:
        raise RuntimeError(" /nai status did not print today's token total")
    if "Quotas:" not in visible:
        raise RuntimeError(" /nai status did not print whether quotas are on")


def usage_line_seen(rcon_text: str, log_text: str) -> bool:
    """True when /nai usage printed today's header in the RCON reply or the server log."""
    needle = "Token usage today:"
    return needle in strip_colors(rcon_text or "") or needle in strip_colors(log_text or "")


def verify_folia_usage(process, chunks, rcon_port: int, timeout: int) -> None:
    """RCON must see the usage header. The reply is sent before the command returns."""
    deadline = time.monotonic() + timeout
    last = ""
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited during /nai usage with code {process.returncode}")
        last = rcon("127.0.0.1", rcon_port, "smoke", "nai usage")
        if usage_line_seen(last, "".join(chunks)):
            print("--- /nai usage ---")
            print(strip_colors(last))
            return
        time.sleep(0.5)
    print("--- /nai usage ---")
    print(strip_colors(last))
    raise RuntimeError("/nai usage did not print today's token usage")


def verify_folia_default_test(process, chunks, rcon_port: int, mock: MockHandle, timeout: int) -> None:
    before = len(mock.bodies())
    answers_before = mock_answer_count("".join(chunks))
    print("--- /nai test ---", flush=True)
    console_command(process, "nai test")
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited during /nai test with code {process.returncode}")
        text = strip_colors("".join(chunks))
        answered = mock_answer_count(text) > answers_before
        reached = len(mock.bodies()) > before
        if answered and reached:
            print("FOLIA TEST OK", flush=True)
            return
        if "Test failed" in text and not answered:
            raise RuntimeError("/nai test failed")
        time.sleep(0.2)
    raise RuntimeError("/nai test did not log the mock answer line")


def verify_folia_context_prompt(process, chunks, rcon_port: int, mock: MockHandle, timeout: int) -> None:
    """Requires the named prompt and a test plugin log line NEXUSAI_CONTEXT.

    /nai test from the console does not collect context, because that read needs a player.
    The test plugin logs NEXUSAI_CONTEXT when its provider runs for context_ping.
    """
    before = len(mock.bodies())
    answers_before = mock_answer_count("".join(chunks))
    print("--- /nai test context_ping ---", flush=True)
    console_command(process, "nai test context_ping")
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited during context prompt with code {process.returncode}")
        text = "".join(chunks)
        visible = strip_colors(text)
        saw_prompt = any("Reply with one word." in body for body in mock.bodies()[before:])
        answered = mock_answer_count(visible) > answers_before
        if "Test failed" in visible and not answered:
            raise RuntimeError("/nai test context_ping failed")
        if saw_prompt and "NEXUSAI_CONTEXT " in text and answered:
            print("context provider observed", flush=True)
            return
        time.sleep(0.2)
    raise RuntimeError(
        "named prompt context provider was not observed: "
        "expected mock text 'Reply with one word.', the mock answer line, "
        "and a test-plugin log line NEXUSAI_CONTEXT"
    )


def verify_folia_probe_events(process, chunks, timeout: int) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited during probe checks with code {process.returncode}")
        text = "".join(chunks)
        if "NexusAI-LoadDriver enabled." in text:
            raise RuntimeError("load driver must not run on Folia")
        if "nexusai-events-probe enabled." not in text:
            time.sleep(0.2)
            continue
        pre_index, pre_id, post_index, _appearance = _paired_phase(text, "TEST", "nexusai", "NO_SUCH", "absent")
        if pre_index is not None and post_index is not None and pre_index < post_index:
            print(f"PROBE EVENTS OK requestId={pre_id}", flush=True)
            return
        time.sleep(0.2)
    text = "".join(chunks)
    if "nexusai-events-probe enabled." not in text:
        raise RuntimeError("events probe did not enable")
    raise RuntimeError("events probe did not log TEST pre then post")


def smoke_self_check() -> int:
    try:
        _smoke_self_check()
    except Exception as error:
        print(f"SMOKE SELF-CHECK FAIL: {error}", file=sys.stderr)
        return 1
    print("SMOKE SELF-CHECK OK")
    return 0


def _need(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def _smoke_self_check() -> None:
    source = Path(__file__).read_text(encoding="utf-8")
    for token in ("EVENT ORDER OK", "JSON OK", "KNOWLEDGE OK", "SMOKE OK"):
        _need(token in source, f"paper smoke line {token} is missing")
    _need("download_paper" in source, "download_paper was removed")
    _need(callable(download_paper), "download_paper is not callable")
    _need(nexusai_enabled("[01:00:01] [Server thread/INFO]: [NexusAI] NexusAI enabled."), "plain enabled line missed")
    _need(nexusai_enabled("[01:00:01] [Server thread/INFO]: \x1b[0;37mReady in\x1b[m \x1b[1;37m412 ms\x1b[m  |  github.com/NearestStep/NexusAI"), "ANSI banner line missed")
    _need(nexusai_enabled("[01:00:01] [Server thread/INFO]: \u00a77Ready in \u00a7f412 ms \u00a78|"), "section banner line missed")
    _need(nexusai_enabled("[01:00:01] [Server thread/INFO]: &x&0&0&C&5&C&2Ready in &f97 ms"), "hex banner line missed")
    _need(not nexusai_enabled("[01:00:01] [Server thread/INFO]: Done (1.0s)!"), "enabled detected without a marker")
    _need(not nexusai_enabled("[01:00:01] [Server thread/INFO]: Already in 5 ms"), "enabled detected from an unrelated line")
    full = "\n".join("[01:00:01 INFO]: " + line for line in (
        "",
        "\u2588\u2584  \u2588",
        "\u2588 \u2580\u2584\u2588",
        "\u2580   \u2580",
        "\u2500" * 53,
        "Version 1.3.0  |  Paper 26.2  |  Java 25",
        "Providers openai  |  Model queue 1",
        "API v3 + events  |  Quotas off  |  Knowledge full",
        "Ready in 412 ms  |  github.com/NearestStep/NexusAI",
    ))
    _need(banner_problem("[01:00:00 INFO]: [NexusAI] Using provider\n" + full) == "", "full banner rejected")
    _need(banner_problem(full.replace("Version", "[NexusAI] Version")) != "", "prefixed banner accepted")
    _need(banner_problem(full.replace("[01:00:01 INFO]: \n", "[01:00:01 INFO]: x\n", 1)) != "", "banner without spacer accepted")
    _need(banner_problem("\n".join(full.splitlines()[-3:])) != "", "short banner accepted")
    short = "[01:00:00 INFO]: [NexusAI] Using provider\n[01:00:01 INFO]: [NexusAI] NexusAI enabled. Ready in 97 ms"
    _need(nexusai_enabled(short), "short ready line missed")
    _need(ready_line_problem(short) == "", "short ready line rejected")
    _need(ready_line_problem(full) != "", "full banner accepted as the short line")
    _need(ready_line_problem(short + "\n" + full) != "", "banner next to the short line accepted")
    _need(banner_problem(short) != "", "short line accepted as the full banner")

    stable = [
        {"id": 3, "channel": "BETA"},
        {"id": 9, "channel": "BETA"},
        {"id": 4, "channel": "STABLE"},
        {"id": 8, "channel": "ALPHA"},
    ]
    chosen, channel = choose_folia_build(stable)
    _need(channel == "STABLE" and int(chosen["id"]) == 4, f"stable preference {chosen} {channel}")
    chosen, channel = choose_folia_build([{"id": 2, "channel": "ALPHA"}, {"id": 7, "channel": "BETA"}])
    _need(channel == "BETA" and int(chosen["id"]) == 7, f"beta fallback {chosen} {channel}")
    chosen, channel = choose_folia_build([{"id": 1, "channel": "ALPHA"}, {"id": 5, "channel": "alpha"}])
    _need(channel == "ALPHA" and int(chosen["id"]) == 5, f"alpha fallback {chosen} {channel}")
    for builds in ([], [{"id": 1, "channel": "EXPERIMENTAL"}]):
        try:
            choose_folia_build(builds)
        except RuntimeError:
            pass
        else:
            raise RuntimeError(f"build list should have failed: {builds}")

    _need(plugin_yml_folia_supported("folia-supported: true\n"), "true was not read")
    _need(plugin_yml_folia_supported('folia-supported: "true"\n'), "quoted true was not read")
    _need(not plugin_yml_folia_supported("folia-supported: false\n"), "false was read as true")
    _need(not plugin_yml_folia_supported("name: NexusAI\n"), "missing key was read as true")
    _need(
        not plugin_yml_folia_supported("folia-supported: false # folia-supported: true\n"),
        "a comment changed the value",
    )
    _need(not plugin_yml_folia_supported("# folia-supported: true\n"), "a comment was read as the key")
    _need(folia_check_mode("auto", False) == "startup", "auto with support off")
    _need(folia_check_mode("auto", True) == "full", "auto with support on")
    _need(folia_check_mode("full", False) == "full", "full override")
    _need(folia_check_mode("startup", True) == "startup", "startup override")

    sample = "\n".join([
        "[01:00:00] [Server thread/INFO]: Done (4.2s)! For help, type \"help\"",
        "[01:00:01] [Server thread/ERROR]: [ModernPluginLoadingStrategy] Could not load plugin 'NexusAI-1.2.0-SNAPSHOT.jar' in folder 'plugins'",
        "org.bukkit.plugin.InvalidPluginException: Could not load plugin 'NexusAI v1.2.0-SNAPSHOT' as it is not marked as supporting Folia!",
        "\tat io.papermc.paper.plugin.Example.build(Example.java:30)",
        "\tat java.base/java.lang.Thread.run(Thread.java:1)",
        "[01:00:02] [Server thread/INFO]: Preparing spawn area",
        "[01:00:02 ERROR]: No key layers in MapLike[{}]",
    ])
    refusals, problems, exempted = assess_folia_startup(sample)
    _need(len(refusals) == 1, f"refusal lines {refusals}")
    _need(not problems, f"clean refusal was flagged: {problems}")
    _need(exempted == 1, f"expected one exempt worldgen line, got {exempted}")
    exact_thread = "[01:00:02] [Server thread/ERROR]: " + EXEMPT_LOG_MESSAGE
    exact_short = "[01:00:02 ERROR]: " + EXEMPT_LOG_MESSAGE
    for exact in (exact_thread, exact_short):
        found, count = unexpected_log_problems(exact)
        _need(not found and count == 1, f"exact worldgen line was not exempt: {exact!r} {found} {count}")
    longer, count = unexpected_log_problems("[01:00:02 ERROR]: " + EXEMPT_LOG_MESSAGE + " extra")
    _need(longer and count == 0, f"longer worldgen line was exempt: {longer} {count}")
    generic, count = unexpected_log_problems("[01:00:06] [Server thread/ERROR]: Failed to save level data")
    _need(generic and count == 0, f"generic error passed: {generic}")
    missing, count = unexpected_log_problems("java.lang.NoClassDefFoundError: x")
    _need(any("NoClassDefFoundError" in item for item in missing) and count == 0, f"NoClassDefFoundError passed: {missing}")
    version_error, _count = unexpected_log_problems("java.lang.UnsupportedClassVersionError: x")
    _need(version_error, "UnsupportedClassVersionError passed")
    hidden = "\n".join([
        "[01:00:02 ERROR]: " + EXEMPT_LOG_MESSAGE,
        "java.lang.NoClassDefFoundError: x",
    ])
    hidden_problems, hidden_count = unexpected_log_problems(hidden)
    _need(hidden_count == 1 and any("NoClassDefFoundError" in item for item in hidden_problems),
          f"exempt line hid the next line: {hidden_problems} {hidden_count}")
    papi = "\n".join([
        "[01:00:05] [Server thread/ERROR]: Error occurred while enabling PlaceholderAPI",
        "java.lang.NoClassDefFoundError: org.bukkit.plugin.Plugin",
        "\tat me.clip.placeholderapi.PlaceholderAPI.onEnable(PlaceholderAPI.java:1)",
    ])
    papi_problems, papi_count = unexpected_log_problems(papi)
    _need(any("PlaceholderAPI" in item for item in papi_problems), f"enable error passed: {papi_problems}")
    _need(any("NoClassDefFoundError" in item for item in papi_problems), f"enable stack passed: {papi_problems}")
    _need(papi_count == 0, f"enable failure was exempt: {papi_count}")
    broken = sample + "\n[01:00:03] [Server thread/ERROR]: Thread failed main thread check: entity"
    _refusals, problems, _exempted = assess_folia_startup(broken)
    _need(any("thread/region" in item for item in problems), f"thread error missed: {problems}")
    other = sample + "\njava.lang.IllegalStateException: boom"
    _refusals, problems, _exempted = assess_folia_startup(other)
    _need(any("unexpected log" in item and "boom" in item for item in problems), f"exception missed: {problems}")
    nexus = sample + "\n[01:00:04 ERROR]: [NexusAI] Enabling failed"
    _refusals, problems, _exempted = assess_folia_startup(nexus)
    _need(any("[NexusAI]" in item for item in problems), f"plugin error missed: {problems}")
    region = "Entity is not owned by the current region"
    _need(thread_region_problems(region), "region ownership message missed")
    _need(not thread_region_problems("player.getScheduler().run"), "entity scheduler was flagged")
    quiet = "[01:00:00] [Server thread/INFO]: Done (1.0s)!\n[01:00:01] [Server thread/INFO]: NexusAI enabled."
    try:
        assert_no_folia_refusal(quiet)
    except RuntimeError:
        raise RuntimeError("a normal enable line was treated as a refusal") from None
    try:
        assert_no_folia_refusal(sample)
    except RuntimeError:
        pass
    else:
        raise RuntimeError("full checks accepted a refusal line")

    _need(is_mock_answer_line("Answer (12 ms): pong"), "real answer line was rejected")
    _need(
        is_mock_answer_line("[NexusAI] Answer (3 ms): pong"),
        "prefixed answer line was rejected",
    )
    for loose in ("Sending a test request...", "pong", "Answer", "Answer: pong"):
        _need(not is_mock_answer_line(loose), f"loose /nai test line was accepted: {loose}")
    _need(mock_answer_count("Answer (1 ms): pong\nAnswer (2 ms): pong") == 2, "answer count")
    success = "FOLIA SMOKE OK {version} build {chosen['id']} channel {chosen['channel']} {mode}"
    _need(success in source, "full-mode success line is missing")
    _need("FOLIA REFUSAL ABSENT" in source, "refusal-absent check is missing")
    _need("FOLIA TEST OK" in source, "strict test success line is missing")
    _need("def console_command(" in source, "console command helper is missing")
    _need('console_command(process, "nai test")' in source, "full-mode /nai test does not use the console")
    _need('rcon("127.0.0.1", rcon_port, "smoke", "nai usage")' in source, "full-mode /nai usage left RCON")
    moved = "console_command(process, " + '"nai usage")'
    _need(moved not in source, "full-mode /nai usage moved off RCON")
    _need(usage_line_seen("Token usage today: 0", ""), "RCON usage header was ignored")
    _need(usage_line_seen("", "[NexusAI] Token usage today:"), "logged usage header was ignored")
    _need(usage_line_seen("§eToken usage today:", ""), "colored usage header was ignored")
    _need(not usage_line_seen("Token usage", "Sending a test request"), "a partial usage line was accepted")
    _need("PROBE EVENTS OK" in source, "probe success line is missing")

    clean = "\n".join([
        "[01:00:00] [Server thread/INFO]: Done (1.0s)! For help, type \"help\"",
        "[01:00:01] [Server thread/INFO]: NexusAI enabled.",
        "[01:00:02 ERROR]: " + EXEMPT_LOG_MESSAGE,
    ])
    clean_problems, clean_exempt = assess_folia_full(clean)
    _need(not clean_problems and clean_exempt == 1, f"clean full log was flagged: {clean_problems} {clean_exempt}")
    enabled_refusal = sample + "\n[01:00:05] [Server thread/INFO]: NexusAI enabled."
    refused_problems, _refused_exempt = assess_folia_full(enabled_refusal)
    _need(any("refused" in item for item in refused_problems), f"full mode accepted a refusal: {refused_problems}")
    driver_log = clean + "\n[01:00:03] [Server thread/INFO]: NexusAI-LoadDriver enabled."
    driver_problems, _driver_exempt = assess_folia_full(driver_log)
    _need(any("load driver" in item for item in driver_problems), f"load driver was allowed: {driver_problems}")
    broken_full = clean + "\n[01:00:03] [Server thread/ERROR]: Thread failed main thread check: entity"
    broken_problems, _broken_exempt = assess_folia_full(broken_full)
    _need(any("thread/region" in item for item in broken_problems), f"full mode missed a thread error: {broken_problems}")
    startup_refusals, startup_problems, _startup_exempt = assess_folia_startup(sample)
    _need(len(startup_refusals) == 1 and not startup_problems, f"startup still requires the refusal: {startup_problems}")

    with tempfile.TemporaryDirectory() as tmp:
        jar_path = Path(tmp) / "NexusAI.jar"
        with zipfile.ZipFile(jar_path, "w") as archive:
            archive.writestr("plugin.yml", "name: NexusAI\nfolia-supported: false\n")
        _need(not read_folia_supported(jar_path), "jar flag was not false")
    _placeholder_order_self_check()
    _download_self_check()
    _download_cache_self_check()


def _placeholder_order_self_check() -> None:
    pre = "NEXUSAI_EVENT phase=pre requestId=3 origin=PLACEHOLDER consumer=nexusai"
    post = "NEXUSAI_EVENT phase=post requestId=3 origin=PLACEHOLDER consumer=nexusai"
    shown = "PLACEHOLDER_TEXT text=pong post=3"
    verify_placeholder_order("\n".join([pre, post, shown]))
    # Post logged by the HTTP thread after the main thread line still passes.
    verify_placeholder_order("\n".join([pre, shown, post]))
    bad = {
        "no post line": [pre, shown],
        "no pre line": [post, shown],
        "post not seen": [pre, post, "PLACEHOLDER_TEXT text=pong post=none"],
        "old driver": [pre, post, "PLACEHOLDER_TEXT text=pong"],
        "other request": [pre, post, "PLACEHOLDER_TEXT text=pong post=2"],
        "text before pre": [shown, pre, post],
    }
    for name, lines in bad.items():
        try:
            verify_placeholder_order("\n".join(lines))
        except RuntimeError:
            continue
        raise AssertionError(f"placeholder order accepted {name}")

    class Running:
        returncode = None

        def poll(self):
            return None

    chunks = [pre + "\n", shown + "\n"]
    started = time.monotonic()
    wait_placeholder_post(Running(), chunks, 0.5)
    if time.monotonic() - started < 0.4:
        raise AssertionError("post wait returned before its timeout")
    chunks.append(post + "\n")
    started = time.monotonic()
    wait_placeholder_post(Running(), chunks, 5)
    if time.monotonic() - started > 1:
        raise AssertionError("post wait did not return once the line was there")


def _download_self_check() -> None:
    global _fetch_to
    good = "a" * 64
    _need(fill_sha256({"checksums": {"sha256": good.upper()}}) == good, "Fill sha256 not read")
    _need(fill_sha256({"checksums": {"sha256": "abc"}}) is None, "short sha256 accepted")
    _need(fill_sha256({}) is None, "missing checksums not handled")
    context = parse_download_context(good + "\thttps://example.invalid/server.jar\tmojang_1.jar\nbad line\n")
    _need(context == [(good, "https://example.invalid/server.jar", "mojang_1.jar")], f"download-context {context}")
    _need(parse_download_context(good + "\thttps://x\t../evil.jar") == [], "path in download-context accepted")
    saved_env = os.environ.get("NEXUSAI_SMOKE_CACHE")
    real_fetch = _fetch_to
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        os.environ["NEXUSAI_SMOKE_CACHE"] = str(root / "cache")
        try:
            payload = b"paperclip" * 400
            source = root / "source.jar"
            source.write_bytes(payload)
            digest = hashlib.sha256(payload).hexdigest()
            url = source.as_uri()
            printed = io.StringIO()
            with contextlib.redirect_stdout(printed):
                download(url, root / "out" / "a.jar", sha256=digest)
                download(url, root / "out" / "a2.jar", sha256=digest)
            _need((root / "out" / "a.jar").read_bytes() == payload, "verified download content differs")
            _need(f"verified sha256 {digest} a.jar (download, published sha256)" in printed.getvalue(),
                  f"the verified sha256 of a download was not logged: {printed.getvalue()}")
            _need(f"verified sha256 {digest} a2.jar (cache, published sha256)" in printed.getvalue(),
                  f"the verified sha256 of a cached file was not logged: {printed.getvalue()}")
            entry = root / "cache" / "sha256" / digest
            _need(entry.is_file(), "verified download not cached by sha256")
            entry.write_bytes(b"truncated")
            download(url, root / "out" / "b.jar", sha256=digest)
            _need((root / "out" / "b.jar").read_bytes() == payload, "corrupt cache entry was used")
            entry.unlink()
            calls = []

            def flaky(fetch_url: str, target: Path):
                calls.append(fetch_url)
                if len(calls) == 1:
                    target.write_bytes(payload[:100])
                    return None
                return real_fetch(fetch_url, target)

            _fetch_to = flaky
            download(url, root / "out" / "c.jar", sha256=digest, attempts=2)
            _need(len(calls) == 2 and (root / "out" / "c.jar").read_bytes() == payload, "mismatch was not retried")
            calls.clear()

            jar_bytes = _self_check_jar()
            jar_source = root / "plain.jar"
            jar_source.write_bytes(jar_bytes)

            def short(fetch_url: str, target: Path):
                calls.append(fetch_url)
                if len(calls) == 1:
                    target.write_bytes(jar_bytes[:2000])
                    return len(jar_bytes)
                return real_fetch(fetch_url, target)

            _fetch_to = short
            download(jar_source.as_uri(), root / "out" / "f.jar", attempts=2)
            _need(len(calls) == 2 and (root / "out" / "f.jar").read_bytes() == jar_bytes, "short read was not retried")
            # A file that is not a jar has only its length to go by.
            blob = root / "plain.bin"
            blob.write_bytes(payload)
            calls.clear()

            def short_blob(fetch_url: str, target: Path):
                calls.append(fetch_url)
                if len(calls) == 1:
                    target.write_bytes(payload[:-100])
                    return len(payload)
                return real_fetch(fetch_url, target)

            _fetch_to = short_blob
            download(blob.as_uri(), root / "out" / "g.bin", attempts=2)
            _need(len(calls) == 2 and (root / "out" / "g.bin").read_bytes() == payload, "short read of a non-jar was not retried")
            _fetch_to = real_fetch
            entry.unlink()
            try:
                download(url, root / "out" / "d.jar", sha256="b" * 64, attempts=1)
            except RuntimeError:
                pass
            else:
                raise RuntimeError("sha256 mismatch was accepted")
            _need(not (root / "cache" / "sha256" / ("b" * 64)).exists(), "mismatched file was cached")
            placed = root / "cache" / "mojang_1.jar"
            placed.write_bytes(payload)
            download("https://example.invalid/never-fetched.jar", root / "out" / "e.jar", sha256=digest, name="mojang_1.jar")
            _need((root / "out" / "e.jar").read_bytes() == payload, "pre-placed file not used")
        finally:
            _fetch_to = real_fetch
            if saved_env is None:
                os.environ.pop("NEXUSAI_SMOKE_CACHE", None)
            else:
                os.environ["NEXUSAI_SMOKE_CACHE"] = saved_env


def _self_check_jar() -> bytes:
    """A small but real jar (zip) of a few KiB, for the URL-keyed cache checks."""
    import io
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("plugin.yml", "name: Check\nversion: 1\nmain: a.B\n")
        archive.writestr("config.yml", "check_updates: true\ncloud_enabled: true\ncloud_sorting: \"name\"\n")
        archive.writestr("data.bin", os.urandom(4096))
    return buffer.getvalue()


def _download_cache_self_check() -> None:
    """URL-keyed entries, transport errors, the pinned PlaceholderAPI, and the saved build list."""
    global _fetch_to, _fetch_text, _sleep, download
    saved_env = os.environ.get("NEXUSAI_SMOKE_CACHE")
    real_fetch, real_text, real_sleep, real_download = _fetch_to, _fetch_text, _sleep, download
    _sleep = lambda seconds: None
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        os.environ["NEXUSAI_SMOKE_CACHE"] = str(root / "cache")
        try:
            jar_bytes = _self_check_jar()
            source = root / "papi.jar"
            source.write_bytes(jar_bytes)
            url = source.as_uri()
            entry = root / "cache" / hashlib.sha256(url.encode("utf-8")).hexdigest()
            side = entry.with_name(entry.name + ".sha256")

            url_printed = io.StringIO()
            with contextlib.redirect_stdout(url_printed):
                download(url, root / "out" / "1.jar")
            _need(f"verified sha256 {hashlib.sha256(jar_bytes).hexdigest()} 1.jar (download, no published sha256)"
                  in url_printed.getvalue(), f"the sha256 of a URL download was not logged: {url_printed.getvalue()}")
            _need(entry.is_file() and side.read_text(encoding="utf-8").strip() == hashlib.sha256(jar_bytes).hexdigest(),
                  "URL entry saved without its sha256")
            fetches = []

            def counting(fetch_url: str, target: Path):
                fetches.append(fetch_url)
                return real_fetch(fetch_url, target)

            _fetch_to = counting
            url_printed = io.StringIO()
            with contextlib.redirect_stdout(url_printed):
                download(url, root / "out" / "2.jar")
            _need(fetches == [], "a good URL entry was downloaded again")
            _need(f"verified sha256 {hashlib.sha256(jar_bytes).hexdigest()} 2.jar (cache, saved sha256)"
                  in url_printed.getvalue(), f"the sha256 of a URL cache entry was not logged: {url_printed.getvalue()}")
            for label, damage in (
                ("truncated", lambda data: data[: len(data) // 2]),
                ("flipped", lambda data: data[:100] + bytes([data[100] ^ 0xFF]) + data[101:]),
            ):
                entry.write_bytes(damage(jar_bytes))
                fetches.clear()
                download(url, root / "out" / f"{label}.jar")
                _need(len(fetches) == 1, f"{label} URL entry was used without a download")
                _need((root / "out" / f"{label}.jar").read_bytes() == jar_bytes, f"{label} URL entry was used")
            # A valid jar that does not match the saved sha256 (a swapped entry) is not used.
            entry.write_bytes(_self_check_jar())
            fetches.clear()
            download(url, root / "out" / "swapped.jar")
            _need(len(fetches) == 1 and (root / "out" / "swapped.jar").read_bytes() == jar_bytes,
                  "URL entry with a different sha256 was used")
            side.unlink()
            fetches.clear()
            download(url, root / "out" / "nosidecar.jar")
            _need(len(fetches) == 1 and side.is_file(), "URL entry without a saved sha256 was used")
            # A same-size entry with a matching sidecar that is not a valid jar is not used either.
            broken = b"x" * len(jar_bytes)
            entry.write_bytes(broken)
            side.write_text(hashlib.sha256(broken).hexdigest() + "\n", encoding="utf-8")
            fetches.clear()
            download(url, root / "out" / "notzip.jar")
            _need(len(fetches) == 1 and (root / "out" / "notzip.jar").read_bytes() == jar_bytes,
                  "URL entry that is not a jar was used")
            bad = root / "bad.jar"
            bad.write_bytes(b"y" * 5000)
            try:
                download(bad.as_uri(), root / "out" / "bad.jar", attempts=2)
            except RuntimeError as error:
                _need("zip check failed" in str(error), f"wrong failure for a non-jar download: {error}")
            else:
                raise RuntimeError("a download that is not a jar was accepted")

            # Transport errors that are not OSError are retried, and no .part file is left.
            errors = [http.client.IncompleteRead(b"partial"), http.client.BadStatusLine("XYZ garbage")]
            fetches.clear()

            def broken_transport(fetch_url: str, target: Path):
                fetches.append(fetch_url)
                if errors:
                    target.write_bytes(b"half")
                    raise errors.pop(0)
                return real_fetch(fetch_url, target)

            _fetch_to = broken_transport
            fresh = root / "fresh.jar"
            fresh.write_bytes(jar_bytes)
            download(fresh.as_uri(), root / "out" / "fresh.jar", attempts=3)
            _need(len(fetches) == 3 and (root / "out" / "fresh.jar").read_bytes() == jar_bytes,
                  f"IncompleteRead or BadStatusLine was not retried ({len(fetches)} calls)")
            _need(not list((root / "cache").rglob("*.part")), "a .part file was left after a retry")

            def always_broken(fetch_url: str, target: Path):
                target.write_bytes(b"half")
                raise http.client.IncompleteRead(b"half")

            _fetch_to = always_broken
            try:
                download((root / "never.jar").as_uri(), root / "out" / "never.jar", attempts=2)
            except RuntimeError as error:
                _need("IncompleteRead" in str(error), f"wrong failure after transport errors: {error}")
            else:
                raise RuntimeError("a download that always broke off was accepted")
            _need(not list((root / "cache").rglob("*.part")), "a .part file was left after a failed download")
            _fetch_to = real_fetch

            # PlaceholderAPI is pinned to its sha256 and gets an offline config.
            _need(re.fullmatch(r"[0-9a-f]{64}", PAPI_SHA256) is not None, "PlaceholderAPI sha256 is not pinned")
            asked = []

            def fake_download(fetch_url, destination, sha256=None, name=None, attempts=DOWNLOAD_ATTEMPTS):
                asked.append((fetch_url, sha256, name))
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(jar_bytes)

            legacy = root / "cache" / hashlib.sha256(PAPI_URL.encode("utf-8")).hexdigest()
            legacy.write_bytes(b"z" * 500)
            legacy.with_name(legacy.name + ".sha256").write_text("0" * 64 + "\n", encoding="utf-8")
            download = fake_download
            install_papi(root / "server" / "plugins")
            _need(not legacy.exists() and not legacy.with_name(legacy.name + ".sha256").exists(),
                  "old URL-keyed PlaceholderAPI entry was kept")
            download = real_download
            _need(asked == [(PAPI_URL, PAPI_SHA256, PAPI_NAME)], f"PlaceholderAPI download was not pinned: {asked}")
            config = (root / "server" / "plugins" / "PlaceholderAPI" / "config.yml").read_text(encoding="utf-8")
            _need("cloud_enabled: false" in config and "cloud_enabled: true" not in config,
                  "PlaceholderAPI expansion cloud left on")
            _need("check_updates: false" in config and "cloud_sorting: \"name\"" in config,
                  "PlaceholderAPI config lost its other keys")

            # The build list is retried and its last good answer is used when the API is down.
            listing = root / "builds.json"
            listing.write_text('{"builds": [{"id": 7, "channel": "STABLE"}]}', encoding="utf-8")
            calls = []

            def flaky_text(fetch_url: str) -> str:
                calls.append(fetch_url)
                if len(calls) == 1:
                    raise http.client.RemoteDisconnected("closed")
                return real_text(fetch_url)

            _fetch_text = flaky_text
            first = fetch_listing(listing.as_uri(), attempts=2)
            _need(len(calls) == 2 and first["builds"][0]["id"] == 7, "build list was not retried")

            def offline_text(fetch_url: str) -> str:
                raise urllib.error.URLError("[Errno 111] Connection refused")

            _fetch_text = offline_text
            cached = fetch_listing(listing.as_uri(), attempts=2)
            _need(cached == first, "saved build list was not used offline")
            try:
                fetch_listing((root / "unknown.json").as_uri(), attempts=1)
            except RuntimeError as error:
                _need("could not list builds" in str(error), f"wrong failure for an unknown build list: {error}")
            else:
                raise RuntimeError("an unknown build list was returned without the API")

            # Five attempts with growing pauses, and still a clear failure at the end.
            pauses = []
            _sleep = pauses.append
            missing = (root / "gone.jar").as_uri()
            try:
                download(missing, root / "out" / "gone.jar")
            except RuntimeError as error:
                _need("after 5 attempts" in str(error), f"wrong failure after retries: {error}")
            else:
                raise RuntimeError("a missing download was accepted")
            _need(pauses == [2, 4, 8, 16], f"download pauses were {pauses}, expected [2, 4, 8, 16]")
            _need(not (root / "out" / "gone.jar").exists(), "a failed download left a file")
        finally:
            _fetch_to, _fetch_text, _sleep, download = real_fetch, real_text, real_sleep, real_download
            if saved_env is None:
                os.environ.pop("NEXUSAI_SMOKE_CACHE", None)
            else:
                os.environ["NEXUSAI_SMOKE_CACHE"] = saved_env


if __name__ == "__main__":
    sys.exit(main())
