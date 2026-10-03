#!/usr/bin/env python3
"""Boot one official stable Paper build and check that NexusAI enables.

The server is started with PlaceholderAPI 2.12.3 and a local mock
OpenAI-compatible endpoint. No real API key is used.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PAPI_URL = (
    "https://github.com/PlaceholderAPI/PlaceholderAPI/releases/download/"
    "2.12.3/PlaceholderAPI-2.12.3.jar"
)
FILL = "https://fill.papermc.io/v3/projects/paper/versions/{version}/builds"
USER_AGENT = "NexusAI-smoke/1.0"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default=os.environ.get("MC_VERSION", ""))
    parser.add_argument("--plugins-dir", default=os.environ.get("PLUGIN_DIR", "dist"))
    parser.add_argument("--timeout", type=int, default=360, help="seconds to wait for Done")
    args = parser.parse_args()
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
        mock_port = free_port()
        start_mock(mock_port)
        write_config(work / "plugins" / "NexusAI" / "config.yml", mock_port)
        server_port = free_port()
        rcon_port = free_port()
        write_server(work, server_port, rcon_port)
        return boot(work, args.version, paper, rcon_port, mock_port, args.timeout)
    except Exception as error:
        print(f"SMOKE FAIL {args.version}: {error}", file=sys.stderr)
        return 1


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
        self.server: ThreadingHTTPServer | None = None

    def add(self) -> int:
        with self._lock:
            self._count += 1
            return self._count

    @property
    def count(self) -> int:
        with self._lock:
            return self._count

    def close(self) -> None:
        if self.server is not None:
            self.server.shutdown()
            self.server = None


def start_mock(
    port: int,
    latency_ms: int = 0,
    requests_path: Path | None = None,
    fail_every: int = 0,
) -> MockHandle:
    """OpenAI-compatible mock. Defaults match the smoke boot: instant HTTP 200, body discarded.

    Load runs pass ``latency_ms``, ``requests_path`` (JSONL of request bodies), and
    ``fail_every`` (HTTP 429 on every Nth request).
    """
    handle = MockHandle()
    quiet = latency_ms > 0 or requests_path is not None or fail_every > 0
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
            status = 429 if fail_every > 0 and number % fail_every == 0 else 200
            if latency_ms > 0:
                time.sleep(latency_ms / 1000.0)
            if status == 429:
                body = b'{"error":{"message":"rate limit","type":"rate_limit"}}'
            else:
                body = json.dumps({
                    "id": "smoke",
                    "object": "chat.completion",
                    "choices": [{
                        "index": 0,
                        "message": {"role": "assistant", "content": "pong"},
                        "finish_reason": "stop",
                    }],
                }).encode("utf-8")
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
            if requests_path is not None:
                text = raw.decode("utf-8", errors="replace")
                line = json.dumps({
                    "n": number,
                    "t": time.time(),
                    "status": status,
                    "path": self.path,
                    "body": text,
                }, ensure_ascii=False)
                with handle._lock:
                    with requests_path.open("a", encoding="utf-8") as handle_out:
                        handle_out.write(line + "\n")

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


def boot(work: Path, version: str, paper: dict, rcon_port: int, mock_port: int, timeout: int) -> int:
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

        # 26.2 closes the RCON socket as soon as stop begins, before the response
        # packet is finished. The command still reached the server.
        try:
            rcon("127.0.0.1", rcon_port, "smoke", "stop")
        except EOFError:
            pass
        code = process.wait(timeout=90)
        if code != 0:
            raise RuntimeError(f"server stop exited {code}")
        print(f"SMOKE OK {version} build {paper['id']}")
        return 0
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=30)


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


if __name__ == "__main__":
    sys.exit(main())
