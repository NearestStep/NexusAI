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
import hashlib
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
import urllib.request
from urllib.parse import parse_qs, urlsplit
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PAPI_URL = (
    "https://github.com/PlaceholderAPI/PlaceholderAPI/releases/download/"
    "2.12.3/PlaceholderAPI-2.12.3.jar"
)
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
        download(PAPI_URL, work / "plugins" / "PlaceholderAPI-2.12.3.jar")
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
        write_config(work / "plugins" / "NexusAI" / "config.yml", mock_port)
        write_knowledge_fixture(work / "plugins" / "NexusAI")
        server_port = free_port()
        rcon_port = free_port()
        write_server(work, server_port, rcon_port)
        return boot(work, args.version, paper, rcon_port, mock_port, args.timeout, check_api, mock)
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
    payload = json.loads(fetch(FILL.format(version=version)))
    builds = payload["builds"] if isinstance(payload, dict) else payload
    stable = [build for build in builds if build.get("channel") == "STABLE"]
    if not stable:
        raise RuntimeError(f"no STABLE Paper build for {version}")
    chosen = max(stable, key=lambda build: build["id"])
    if chosen.get("channel") != "STABLE":
        raise RuntimeError(f"refusing non-stable build {chosen}")
    download_info = chosen["downloads"]["server:default"]
    print(f"Paper {version} stable build {chosen['id']} ({download_info['name']})")
    download(download_info["url"], destination)
    return {"id": chosen["id"], "name": download_info["name"]}


def download(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256(url.encode("utf-8")).hexdigest()
    cache = Path(tempfile.gettempdir()) / "nexusai-smoke-cache" / digest
    cache.parent.mkdir(parents=True, exist_ok=True)
    if not cache.is_file() or cache.stat().st_size < 1000:
        print(f"download {url}")
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=120) as response, cache.open("wb") as handle:
            shutil.copyfileobj(response, handle)
    shutil.copy2(cache, destination)


def fetch(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8")


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


def write_config(path: Path, mock_port: int) -> None:
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
    deadline = time.time() + timeout
    try:
        while time.time() < deadline:
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
        if "NexusAI enabled." not in text:
            raise RuntimeError("NexusAI did not log that it enabled")
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
    deadline = time.time() + timeout
    body = ""
    while time.time() < deadline:
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
    deadline = time.time() + timeout
    while time.time() < deadline:
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
    """One placeholder logs Pre then Post, both nexusai, and Post is before the visible text."""
    pre_index, pre_id, post_index, text_index = _paired_phase(
        text, "PLACEHOLDER", "nexusai", "PLACEHOLDER_TEXT", "text=pong"
    )
    if pre_index is None or post_index is None or text_index is None:
        raise RuntimeError(
            "events probe did not log a PLACEHOLDER pre and post for consumer nexusai before PLACEHOLDER_TEXT"
        )
    if pre_index >= post_index or post_index >= text_index:
        raise RuntimeError(
            "expected PLACEHOLDER pre, then post, then PLACEHOLDER_TEXT; "
            f"indexes were {pre_index}, {post_index}, {text_index} requestId={pre_id}"
        )


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


def wait_event_checks(process, chunks: list[str], timeout: int) -> None:
    """Poll until the placeholder text and the action result are both in the log."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited during event smoke with code {process.returncode}")
        text = "".join(chunks)
        if "PLACEHOLDER_TEXT text=pong" in text and "ACTION_DONE" in text and "phase=action" in text:
            print("event smoke saw placeholder text and a character action")
            return
        if "PLACEHOLDER_TEXT timeout" in text or "PLACEHOLDER_TEXT error=" in text:
            raise RuntimeError("placeholder expansion did not return the model text")
        time.sleep(0.5)
    raise RuntimeError("timed out waiting for PLACEHOLDER_TEXT and ACTION_DONE")


def wait_api_result(process, chunks: list[str], timeout: int) -> None:
    """Poll until one generate() result is a model reply and every call has finished."""
    deadline = time.time() + timeout
    while time.time() < deadline:
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
    deadline = time.time() + 20
    last_error: Exception | None = None
    while time.time() < deadline:
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
        download(PAPI_URL, work / "plugins" / "PlaceholderAPI-2.12.3.jar")
        shutil.copy2(plugin, work / "plugins" / plugin.name)
        if mode == "full":
            install_folia_probe(args.driver_dir, work / "plugins")
        elif args.driver_dir.strip():
            print("folia startup checks leave the load driver and events probe uninstalled")
        mock_port = free_port()
        mock = start_mock(mock_port)
        write_config(work / "plugins" / "NexusAI" / "config.yml", mock_port)
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
        payload = json.loads(fetch(url))
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
        download(download_info["url"], destination)
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
    deadline = time.time() + timeout
    try:
        while time.time() < deadline:
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
    if "NexusAI enabled." in text:
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
    if "NexusAI enabled." not in text:
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


def verify_folia_usage(process, chunks, rcon_port: int, timeout: int) -> None:
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited during /nai usage with code {process.returncode}")
        last = strip_colors(rcon("127.0.0.1", rcon_port, "smoke", "nai usage"))
        if "Token usage today:" in last or "Token usage today:" in strip_colors("".join(chunks)):
            print("--- /nai usage ---")
            print(last)
            return
        time.sleep(0.5)
    print("--- /nai usage ---")
    print(last)
    raise RuntimeError("/nai usage did not print today's token usage")


def verify_folia_default_test(process, chunks, rcon_port: int, mock: MockHandle, timeout: int) -> None:
    before = len(mock.bodies())
    answers_before = mock_answer_count("".join(chunks))
    print("--- /nai test ---", flush=True)
    console_command(process, "nai test")
    deadline = time.time() + timeout
    while time.time() < deadline:
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
    deadline = time.time() + timeout
    while time.time() < deadline:
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
    deadline = time.time() + timeout
    while time.time() < deadline:
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


if __name__ == "__main__":
    sys.exit(main())
