#!/usr/bin/env python3
"""Load harness for NexusAI. Reuses the smoke helpers in paper-smoke.py.

Mode A (no game client) runs S1, S2, S2-over, S3, and the HTTP-pool overflow probe S-pool.
Mode B (--bots) adds S4 and S5 and needs mineflayer. CI runs mode A only.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import math
import os
import platform
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

CANARY = "sk-canary-load-7f3a9c2e1b"
MARKERS = ("§§§ PLAYER INPUT §§§", "§§§ END §§§")
COOLDOWN_SECONDS = 30
PAPI_URL = (
    "https://github.com/PlaceholderAPI/PlaceholderAPI/releases/download/"
    "2.12.3/PlaceholderAPI-2.12.3.jar"
)


def load_smoke():
    path = Path(__file__).with_name("paper-smoke.py")
    spec = importlib.util.spec_from_file_location("paper_smoke", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot import {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--version", default=os.environ.get("MC_VERSION", ""))
    parser.add_argument("--plugins-dir", default=os.environ.get("PLUGIN_DIR", "dist"))
    parser.add_argument("--driver-dir", default=os.environ.get("DRIVER_DIR", "build/loadtest"))
    parser.add_argument("--scenario", default="S1,S2,S2-over,S3,S-pool")
    parser.add_argument("--bots", type=int, default=0)
    parser.add_argument("--mock-latency-ms", type=int, default=300)
    parser.add_argument("--mock-429-every", type=int, default=0)
    parser.add_argument("--baseline-seconds", type=int, default=60)
    parser.add_argument("--load-seconds", type=int, default=120)
    parser.add_argument("--pool-seconds", type=int, default=3)
    parser.add_argument("--out", default="load-out")
    parser.add_argument("--timeout", type=int, default=420, help="seconds to wait for Done")
    parser.add_argument("--skip-jfr", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        return self_check()
    if not args.version:
        print("missing Paper version", file=sys.stderr)
        return 2
    scenarios = [item.strip() for item in args.scenario.split(",") if item.strip()]
    unknown = [item for item in scenarios if item not in SCENARIO_NAMES]
    if unknown:
        print(f"unknown scenario {unknown}", file=sys.stderr)
        return 2
    try:
        return run(args, scenarios)
    except Exception as error:
        print(f"LOAD FAIL: {error}", file=sys.stderr)
        return 1


SCENARIO_NAMES = {"baseline", "S1", "S2", "S2-over", "S3", "S4", "S5", "S-pool"}


def run(args, scenarios: list[str]) -> int:
    smoke = load_smoke()
    plugin = smoke.find_plugin(Path(args.plugins_dir))
    driver = find_driver(Path(args.driver_dir))
    for jar, entry in (
        (plugin, "io/github/neareststep/nexusai/NexusAI.class"),
        (driver, "io/github/neareststep/nexusai/load/LoadDriverPlugin.class"),
    ):
        major = smoke.class_major(jar, entry)
        if major != 65:
            raise RuntimeError(f"expected class file 65, found {major} in {jar.name}")
        print(f"plugin {jar.name} class file {major}")

    out = Path(args.out)
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    work = out / "server"
    work.mkdir()
    print(f"server directory {work}")

    paper = smoke.download_paper(args.version, work / "paper.jar")
    smoke.download(PAPI_URL, work / "plugins" / "PlaceholderAPI-2.12.3.jar")
    shutil.copy2(plugin, work / "plugins" / plugin.name)
    shutil.copy2(driver, work / "plugins" / driver.name)
    write_prompts(work / "plugins" / "NexusAI" / "prompts.yml")

    mock_port = smoke.free_port()
    requests_path = out / "mock-requests.jsonl"
    mock = smoke.start_mock(
        mock_port,
        latency_ms=args.mock_latency_ms,
        requests_path=requests_path,
        fail_every=args.mock_429_every,
    )
    write_load_config(work / "plugins" / "NexusAI" / "config.yml", mock_port, high_limits())
    server_port = smoke.free_port()
    rcon_port = smoke.free_port()
    smoke.write_server(
        work,
        server_port,
        rcon_port,
        max_players=max(30, args.bots + 5),
        rcon_password="loadtest",
        motd="NexusAI load",
        extras=["spawn-protection=0"],
    )
    jfc = work / "load.jfc"
    jfc.write_text(JFC, encoding="utf-8")

    log_chunks: list[str] = []
    process = boot_process(work)
    reader = threading.Thread(target=read_log, args=(process.stdout, log_chunks), daemon=True)
    reader.start()
    bots = None
    results = []
    try:
        wait_for_done(process, log_chunks, args.timeout)
        text = "".join(log_chunks)
        if "NexusAI enabled." not in text or "NexusAI-LoadDriver enabled." not in text:
            raise RuntimeError("NexusAI or the load driver did not enable")
        if CANARY in text:
            raise RuntimeError("canary key appeared in the boot log")

        planned = ["baseline"] + [item for item in scenarios if item != "baseline"]
        needs_bots = any(item in {"S4", "S5"} for item in planned)
        bot_note = ""
        if needs_bots:
            if args.bots <= 0:
                bot_note = "S4/S5 were requested without --bots"
            else:
                bots, bot_note = start_bots(args, server_port, work, out)
                if bots is None:
                    print(f"bots not started: {bot_note}")
                else:
                    wait_for_players(smoke, rcon_port, args.bots, log_chunks, process)

        current_limits = high_limits()
        baseline_mean = None
        for scenario in planned:
            wanted = limits_for(scenario)
            if wanted != current_limits:
                write_load_config(work / "plugins" / "NexusAI" / "config.yml", mock_port, wanted)
                reload_plugin(smoke, rcon_port, log_chunks, process)
                current_limits = wanted
            if scenario == "S5" and bots is not None:
                (work / "talk.flag").write_text("go\n", encoding="utf-8")
            elif (work / "talk.flag").exists():
                (work / "talk.flag").unlink()
            outcome = execute_scenario(
                smoke, args, scenario, process, log_chunks, rcon_port, work, out, jfc,
                requests_path, baseline_mean, bot_note, bots is not None,
            )
            results.append(outcome)
            if scenario == "baseline" and outcome.get("report"):
                baseline_mean = outcome["report"]["mspt"]["mean"]
            print(format_result(outcome))

        write_summary(out, args, paper, results, log_chunks)
        failed = [item for item in results if item["status"] == "fail"]
        return 1 if failed else 0
    finally:
        stop_bots(bots)
        try:
            smoke.rcon("127.0.0.1", rcon_port, "loadtest", "stop")
        except Exception:
            pass
        try:
            process.wait(timeout=90)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=30)
        mock.close()
        (out / "server.log").write_text("".join(log_chunks), encoding="utf-8")


def execute_scenario(
    smoke, args, scenario, process, log_chunks, rcon_port, work, out, jfc,
    requests_path, baseline_mean, bot_note, bots_up,
) -> dict:
    if scenario in {"S4", "S5"} and not bots_up:
        return {
            "scenario": scenario,
            "status": "not-run",
            "reasons": [bot_note or "mode B needs mineflayer bots and a live Paper server"],
            "report": None,
        }
    last = None
    for attempt in (1, 2):
        before = len(log_chunks)
        started = time.time()
        smoke.rcon(
            "127.0.0.1",
            rcon_port,
            "loadtest",
            f"naiload {scenario} {rate_for(scenario)} {seconds_for(args, scenario)} {viewers_for(args, scenario)}",
        )
        jfr_path = out / f"{scenario}-try{attempt}.jfr"
        jfr_note = ""
        if not args.skip_jfr and scenario not in {"baseline"}:
            try:
                jfr_note = record_jfr(
                    process, log_chunks, before, jfc, jfr_path,
                    seconds_for(args, scenario), scenario,
                )
            except RuntimeError as error:
                jfr_note = str(error)
        wait_marker(process, log_chunks, before, f"SCENARIO_END {scenario}", seconds_for(args, scenario) + 90)
        ended = time.time()
        report_path = work / "plugins" / "NexusAI-LoadDriver" / "report.json"
        if not report_path.is_file():
            raise RuntimeError(f"{scenario} did not write report.json")
        saved = out / f"report-{scenario}-try{attempt}.json"
        shutil.copy2(report_path, saved)
        report = json.loads(saved.read_text(encoding="utf-8"))
        window = slice_after(log_chunks, before)
        bodies, stamps = mock_window(requests_path, started, ended)
        status_text = ""
        try:
            status_text = smoke.strip_colors(smoke.rcon("127.0.0.1", rcon_port, "loadtest", "nai status"))
        except Exception as error:
            status_text = f"status failed: {error}"
        (out / f"status-{scenario}-try{attempt}.txt").write_text(status_text, encoding="utf-8")
        jfr_server, jfr_nexus = (0, 0)
        if jfr_path.is_file() and jfr_path.stat().st_size > 0:
            jfr_server, jfr_nexus = read_jfr(jfr_path, out / f"jfr-{scenario}-try{attempt}.txt")
        elif not args.skip_jfr and scenario not in {"baseline"} and not jfr_note:
            jfr_note = "JFR recording was not written"
        bot_messages = read_bot_messages(out / "bots.json")
        judged = judge(scenario, report, {
            "baseline_mean": baseline_mean,
            "mock_count": len(stamps),
            "mock_timestamps": stamps,
            "bodies": bodies,
            "warnings": count_warnings(window),
            "errors": count_errors(window),
            "frames": nexus_frames(window),
            "canary": CANARY in "".join(window) or CANARY in status_text or CANARY in saved.read_text(encoding="utf-8"),
            "oom": "OutOfMemoryError" in "".join(window),
            "dialogue_failed": "Dialogue failed" in "".join(window),
            "jfr_server": jfr_server,
            "jfr_nexus": jfr_nexus,
            "jfr_note": jfr_note,
            "bot_messages": bot_messages,
            "seconds": seconds_for(args, scenario),
            "queue_full": "HTTP queue is full" in "".join(window),
            "jfr_required": not args.skip_jfr,
        })
        last = {
            "scenario": scenario,
            "attempt": attempt,
            "status": judged["status"],
            "reasons": judged["reasons"],
            "report": report,
            "mockCount": len(stamps),
            "warnings": count_warnings(window),
            "jfrServer": jfr_server,
            "jfrNexus": jfr_nexus,
            "jfrPercent": percent(jfr_nexus, jfr_server),
            "botMessages": bot_messages,
        }
        if judged["status"] != "fail" or attempt == 2:
            shutil.copy2(saved, out / f"report-{scenario}.json")
            return last
        print(f"{scenario} attempt {attempt} failed ({'; '.join(judged['reasons'])}); retrying once")
    return last


def record_jfr(process, log_chunks, before, jfc, destination, load_seconds, scenario: str) -> str:
    try:
        wait_marker(process, log_chunks, before, "SCENARIO_MEASURE ", min(45, load_seconds + 40))
    except RuntimeError as error:
        return str(error)
    # jcmd resolves settings and filename from the target JVM's working directory
    # (the Paper server dir). A relative path misses load.jfc and writes nothing,
    # and jcmd still exits 0.
    jfc = Path(jfc).resolve()
    destination = Path(destination).resolve()
    jcmd = tool("jcmd")
    subprocess.run([jcmd, str(process.pid), "JFR.stop", "name=nexusai"], capture_output=True, text=True)
    if destination.exists():
        destination.unlink()
    started = subprocess.run(
        [jcmd, str(process.pid), "JFR.start", "name=nexusai", f"settings={jfc}", f"filename={destination}", "dumponexit=true"],
        capture_output=True, text=True,
    )
    start_text = (started.stdout + started.stderr).strip()
    if started.returncode != 0 or "Could not" in start_text:
        return start_text or f"JFR.start failed with exit {started.returncode}"
    warmup = 0 if scenario in {"S-pool"} else 10
    time.sleep(max(1, load_seconds - warmup))
    stopped = subprocess.run(
        [jcmd, str(process.pid), "JFR.stop", "name=nexusai", f"filename={destination}"],
        capture_output=True, text=True,
    )
    stop_text = (stopped.stdout + stopped.stderr).strip()
    if stopped.returncode != 0 or "Could not" in stop_text:
        return stop_text or f"JFR.stop failed with exit {stopped.returncode}"
    return ""


def read_jfr(path: Path, summary: Path) -> tuple[int, int]:
    jfr = tool("jfr")
    printed = subprocess.run(
        [jfr, "print", "--events", "jdk.ExecutionSample", str(path)],
        capture_output=True, text=True,
    )
    text = printed.stdout if printed.returncode == 0 else printed.stderr
    server, nexus = jfr_share(text)
    summary.write_text(
        f"server_samples={server}\nnexusai_samples={nexus}\npercent={percent(nexus, server)}\n",
        encoding="utf-8",
    )
    return server, nexus


def judge(scenario: str, report: dict, ctx: dict) -> dict:
    if report.get("skipped"):
        return {"status": "not-run", "reasons": [report.get("error") or "skipped"]}
    reasons = common_reasons(report, ctx)
    if report.get("error"):
        reasons.append(report["error"])
    baseline = ctx.get("baseline_mean")
    mean = report.get("mspt", {}).get("mean", 0.0)
    delta = None if baseline is None or scenario == "baseline" else mean - baseline
    if scenario == "S1":
        if delta is None or delta > 1.0:
            reasons.append(f"ΔMSPT {fmt(delta)} > 1.0 ms")
        p99 = report.get("callNanos", {}).get("p99", 10**12)
        if p99 > 200_000:
            reasons.append(f"call p99 {p99} ns > 200 µs")
        if report.get("mspt", {}).get("samples", 0) < 10:
            reasons.append("too few MSPT samples")
    elif scenario == "S2":
        reasons.extend(load_budget(delta, report, 2.0))
        if report.get("mspt", {}).get("max", 10**9) > 100:
            reasons.append(f"max tick {report['mspt']['max']:.1f} ms > 100")
        if report.get("tpsMin", 0) < 19.8:
            reasons.append(f"TPS {report.get('tpsMin')} < 19.8")
        if ctx.get("jfr_required", True):
            note = ctx.get("jfr_note") or ""
            server_samples = ctx.get("jfr_server", 0)
            if note:
                reasons.append(note)
            elif server_samples >= 200 and percent(ctx["jfr_nexus"], server_samples) > 2.0:
                # Below ~200 Server-thread samples the 2% line is noise. See docs/LOADTEST.md.
                reasons.append(f"JFR {percent(ctx['jfr_nexus'], server_samples):.2f}% > 2%")
        unique = report.get("uniquePrompts", 0)
        if ctx.get("mock_count", 0) > unique:
            reasons.append(f"mock requests {ctx['mock_count']} > unique prompts {unique}")
        if not report.get("drain", {}).get("settled", False):
            reasons.append("futures still running 30s after stop")
    elif scenario == "S2-over":
        reasons.extend(load_budget(delta, report, 2.0))
        reasons.extend(queue_bounds(report))
        if ctx.get("oom"):
            reasons.append("OutOfMemoryError")
    elif scenario == "S3":
        reasons.extend(load_budget(delta, report, 2.0))
        if rate_exceeded(ctx.get("mock_timestamps") or [], 30, 1):
            reasons.append("mock requests exceeded 30/min + 1")
        allowed = ctx.get("seconds", 120) // COOLDOWN_SECONDS + 2
        if ctx.get("warnings", 0) > allowed:
            reasons.append(f"warnings {ctx['warnings']} > {allowed}")
    elif scenario == "S4":
        reasons.extend(load_budget(delta, report, 2.0))
        main = report.get("context", {}).get("provideOnMainThread", 1)
        if main != 0:
            reasons.append(f"provide() on main thread {main}")
        if not slow_suspended(report):
            reasons.append("slow provider was not suspended")
        blob = "\n".join(ctx.get("bodies") or [])
        if "§§§ PLAYER INPUT §§§" not in blob or "§§§ END §§§" not in blob:
            reasons.append("mock bodies have no wrapped context block")
        if "coins ~12k" not in blob:
            reasons.append("mock bodies have no fast-provider value")
        if "load-boom-secret" in blob:
            reasons.append("exception text leaked into a prompt")
    elif scenario == "S5":
        reasons.extend(load_budget(delta, report, 2.0))
        if ctx.get("dialogue_failed"):
            reasons.append("Dialogue failed")
        talks = report.get("talkCommands", 0)
        if talks <= 0 and ctx.get("bot_messages", 0) <= 0:
            reasons.append("no talk commands and no bot replies")
    elif scenario == "S-pool":
        reasons.extend(queue_bounds(report))
        delta_rejected = report.get("pool", {}).get("rejectedDelta", 0)
        if delta_rejected <= 0:
            reasons.append("HTTP pool did not reject when flooded")
        if not ctx.get("queue_full"):
            reasons.append("log never said HTTP queue is full")
        p99 = report.get("callNanos", {}).get("p99", 10**12)
        if p99 > 50_000_000:
            reasons.append(f"rejection p99 {p99} ns is not a fast fail")
        if not report.get("drain", {}).get("settled", False):
            reasons.append("pool did not drain after the burst")
    if scenario != "baseline" and report.get("mspt", {}).get("samples", 0) == 0 and scenario != "S-pool":
        if scenario != "S1":
            pass
    return {"status": "fail" if reasons else "pass", "reasons": reasons}


def common_reasons(report: dict, ctx: dict) -> list[str]:
    reasons = []
    threads = report.get("threads") or {}
    if threads.get("httpMin") != 4 or threads.get("httpMax") != 4 or threads.get("httpLast") != 4:
        reasons.append(f"nexusai-http threads {threads.get('httpMin')}..{threads.get('httpMax')} last {threads.get('httpLast')}")
    if threads.get("contextMin") != 2 or threads.get("contextMax") != 2 or threads.get("contextLast") != 2:
        reasons.append(f"nexusai-context threads {threads.get('contextMin')}..{threads.get('contextMax')} last {threads.get('contextLast')}")
    if ctx.get("errors"):
        reasons.append(f"NexusAI ERROR x{ctx['errors']}")
    if ctx.get("frames"):
        reasons.append(f"NexusAI stack frames x{len(ctx['frames'])}")
    if ctx.get("canary"):
        reasons.append("canary key leaked")
    if ctx.get("oom"):
        reasons.append("OutOfMemoryError")
    return reasons


def load_budget(delta, report, limit: float) -> list[str]:
    if delta is None or delta > limit:
        return [f"ΔMSPT {fmt(delta)} > {limit} ms"]
    if report.get("mspt", {}).get("samples", 0) < 10:
        return ["too few MSPT samples"]
    return []


def queue_bounds(report: dict) -> list[str]:
    pool = report.get("pool") or {}
    reasons = []
    if pool.get("maxWorkerQueued", 0) > pool.get("workerQueueCapacity", 64):
        reasons.append("worker queue grew past its cap")
    if pool.get("maxInFlight", 0) > pool.get("maxInFlightCap", 64):
        reasons.append("in-flight HTTP grew past its cap")
    if pool.get("maxHttpWaiting", 0) > pool.get("httpWaitCapacity", 64):
        reasons.append("HTTP wait queue grew past its cap")
    return reasons


def slow_suspended(report: dict) -> bool:
    for row in report.get("context", {}).get("rows", []):
        if row.get("id") == "slow" and row.get("suspended"):
            return True
    return False


def rate_exceeded(timestamps: list[float], per_minute: int, slack: int) -> bool:
    """True when every tumbling 60s alignment exceeds ``per_minute + slack``.

    ``RateLimiter`` resets a counter every 60s from the moment the window was
    opened. It is not a sliding window. A 120s run that crosses one reset
    therefore holds 30 and then 30, and a sliding minute that straddles the
    reset sees 60. That series is within 30/min. A burst that no alignment can
    split into legal windows is not.
    """
    stamps = sorted(float(item) for item in timestamps)
    if not stamps:
        return False
    limit = per_minute + slack
    origin = stamps[0]
    phases = {0.0}
    for moment in stamps:
        phases.add((moment - origin) % 60.0)
    for phase in phases:
        if not tumbling_bucket_over(stamps, origin + phase, limit):
            return False
    return True


def tumbling_bucket_over(stamps: list[float], boundary: float, limit: int) -> bool:
    """Buckets are ``[boundary + k*60, boundary + (k+1)*60)``. A stamp on the
    boundary opens the next window, matching ``now - windowStart >= 60_000``.
    """
    counts: dict[int, int] = {}
    for moment in stamps:
        bucket = math.floor((moment - boundary) / 60.0)
        counts[bucket] = counts.get(bucket, 0) + 1
        if counts[bucket] > limit:
            return True
    return False


def jfr_share(text: str) -> tuple[int, int]:
    server = 0
    nexus = 0
    for chunk in text.split("jdk.ExecutionSample")[1:]:
        thread = ""
        for line in chunk.splitlines():
            if "sampledThread" in line:
                thread = line
                break
        if "Server thread" not in thread:
            continue
        server += 1
        if plugin_frame(chunk):
            nexus += 1
    return server, nexus


def plugin_frame(chunk: str) -> bool:
    for line in chunk.splitlines():
        if "io.github.neareststep.nexusai." in line and "io.github.neareststep.nexusai.load." not in line:
            return True
    return False


def percent(part: int, whole: int):
    if whole <= 0:
        return None
    return 100.0 * part / whole


def fmt(delta) -> str:
    if delta is None:
        return "n/a"
    return f"{delta:.3f}"


def count_warnings(lines: list[str]) -> int:
    return sum(1 for line in lines if "WARN]: [NexusAI]" in line)


def count_errors(lines: list[str]) -> int:
    return sum(1 for line in lines if "ERROR]: [NexusAI]" in line)


def nexus_frames(lines: list[str]) -> list[str]:
    return [
        line for line in lines
        if "at io.github.neareststep.nexusai." in line
        and "io.github.neareststep.nexusai.load." not in line
    ]


def slice_after(lines: list[str], start: int) -> list[str]:
    return lines[start:]


def mock_window(path: Path, started: float, ended: float) -> tuple[list[str], list[float]]:
    bodies = []
    stamps = []
    if not path.is_file():
        return bodies, stamps
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        moment = float(row.get("t", 0))
        if started - 0.5 <= moment <= ended + 0.5:
            stamps.append(moment)
            bodies.append(row.get("body") or "")
    return bodies, stamps


def high_limits() -> dict:
    return {"minute": 100000, "day": 100000, "player_minute": 100000, "player_day": 100000}


def limits_for(scenario: str) -> dict:
    if scenario == "S3":
        return {"minute": 30, "day": 1000, "player_minute": 10, "player_day": 200}
    return high_limits()


def seconds_for(args, scenario: str) -> int:
    if scenario == "baseline":
        return args.baseline_seconds
    if scenario == "S-pool":
        return args.pool_seconds
    return args.load_seconds


def rate_for(scenario: str) -> int:
    return {
        "baseline": 0,
        "S1": 1000,
        "S2": 10,
        "S2-over": 50,
        "S3": 200,
        "S4": 40,
        "S5": 0,
        "S-pool": 0,
    }[scenario]


def viewers_for(args, scenario: str) -> int:
    if scenario == "S1":
        return 100
    if scenario in {"S4", "S5"}:
        return max(1, args.bots)
    return 0


def write_load_config(path: Path, mock_port: int, limits: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    url = f"http://127.0.0.1:{mock_port}/v1"
    path.write_text(
        "\n".join([
            "config-version: 1",
            "locale: en",
            "api:",
            "  provider: openai",
            "  model: load-model",
            f'  base-url: "{url}"',
            f'  key: "{CANARY}"',
            "providers:",
            "  openai:",
            "    type: openai-compatible",
            f'    url: "{url}"',
            f'    api-key: "{CANARY}"',
            "model-queue:",
            "  - provider: openai",
            "    model: load-model",
            "limits:",
            f"  requests-per-minute: {limits['minute']}",
            f"  requests-per-day: {limits['day']}",
            f"  player-requests-per-minute: {limits['player_minute']}",
            f"  player-requests-per-day: {limits['player_day']}",
            "  max-prompt-length: 200",
            "  error-log-cooldown-seconds: 30",
            "cache:",
            "  ttl: 600",
            "  max-size: 20000",
            "pool:",
            "  enabled: false",
            "  entries: []",
            "prewarm:",
            "  enabled: false",
            "  prompts: []",
            "dialogue:",
            "  enabled: true",
            "  memory-turns: 4",
            "  persist-memory: false",
            "  message-cooldown-millis: 0",
            "  conversations-per-player-per-day: 0",
            "  max-replies-per-session: 80",
            "  session-timeout-seconds: 600",
            "  summary:",
            "    enabled: true",
            "    threshold-turns: 2",
            "    max-chars: 400",
            "    max-tokens: 80",
            "context:",
            "  enabled: true",
            'fallback: "..."',
            "",
        ]),
        encoding="utf-8",
    )


def write_prompts(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join([
            "config-version: 1",
            "load_hit:",
            '  prompt: "Reply with the single word pong."',
            "  ttl: 600",
            '  fallback: "..."',
            "load_ctx:",
            '  prompt: "Reply with the single word pong."',
            "  context: all",
            "  ttl: 60",
            '  fallback: "..."',
            "harbor:",
            '  prompt: "You are Harbor, a dock clerk. Answer in one short sentence."',
            "  format: chat",
            "  context: all",
            "  dialogue:",
            '    greeting: "The tide is in."',
            "    max-replies: 80",
            "",
        ]),
        encoding="utf-8",
    )


def find_driver(directory: Path) -> Path:
    if directory.is_file() and directory.name.endswith(".jar"):
        return directory
    jars = sorted(directory.glob("NexusAI-LoadDriver.jar"))
    if len(jars) != 1:
        raise RuntimeError(f"expected NexusAI-LoadDriver.jar in {directory}, found {[p.name for p in jars]}")
    return jars[0]


def boot_process(work: Path) -> subprocess.Popen:
    return subprocess.Popen(
        [
            "java",
            "-Djava.awt.headless=true",
            "-Dterminal.jline=false",
            "-Dterminal.ansi=false",
            "-Xms1G",
            "-Xmx2G",
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


def read_log(pipe, chunks: list[str]) -> None:
    for line in pipe:
        chunks.append(line)
        sys.stdout.write(line)
        sys.stdout.flush()


def wait_for_done(process, chunks: list[str], timeout: int) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited early with code {process.returncode}")
        text = "".join(chunks)
        if "Done (" in text:
            return
        if "Error occurred while enabling NexusAI" in text or "Refusing to enable" in text:
            raise RuntimeError("plugin failed during startup")
        time.sleep(0.5)
    raise RuntimeError("timed out waiting for Done")


def wait_marker(process, chunks: list[str], start: int, token: str, timeout: int) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited while waiting for {token}")
        if any(token in line for line in chunks[start:]):
            return
        time.sleep(0.2)
    raise RuntimeError(f"timed out waiting for {token}")


def reload_plugin(smoke, rcon_port: int, chunks: list[str], process) -> None:
    start = len(chunks)
    smoke.rcon("127.0.0.1", rcon_port, "loadtest", "nai reload")
    wait_marker(process, chunks, start, "NexusAI reloaded", 40)


def start_bots(args, server_port: int, work: Path, out: Path):
    node = shutil.which("node")
    npm = shutil.which("npm")
    if not node or not npm:
        return None, "node or npm is not installed; mode B was not started"
    bot_dir = work / "bots"
    bot_dir.mkdir()
    package = {
        "name": "nexusai-load-bots",
        "private": True,
        "type": "module",
        "dependencies": {"mineflayer": "4.33.0"},
    }
    (bot_dir / "package.json").write_text(json.dumps(package), encoding="utf-8")
    script = Path(__file__).with_name("load-bots.mjs")
    shutil.copy2(script, bot_dir / "load-bots.mjs")
    install = subprocess.run(
        [npm, "install", "--no-fund", "--no-audit"],
        cwd=bot_dir, capture_output=True, text=True, timeout=240,
    )
    if install.returncode != 0:
        return None, "npm install mineflayer failed: " + (install.stderr or install.stdout)[-500:]
    report = out / "bots.json"
    env = os.environ.copy()
    env.update({
        "HOST": "127.0.0.1",
        "PORT": str(server_port),
        "BOTS": str(args.bots),
        "TALK_FLAG": str(work / "talk.flag"),
        "BOT_REPORT": str(report),
        "MC_VERSION": "",
    })
    proc = subprocess.Popen(
        [node, "load-bots.mjs"],
        cwd=bot_dir,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    return proc, ""


def wait_for_players(smoke, rcon_port: int, expected: int, chunks, process) -> None:
    deadline = time.time() + 90
    last_list = ""
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError("server exited while bots were joining")
        try:
            last_list = smoke.strip_colors(smoke.rcon("127.0.0.1", rcon_port, "loadtest", "list"))
        except Exception as error:
            last_list = f"list failed: {error}"
        if bots_online(chunks) >= expected or last_list.count("NaiBot") >= expected:
            for index in range(1, expected + 1):
                smoke.rcon("127.0.0.1", rcon_port, "loadtest", f"op NaiBot{index:02d}")
            return
        time.sleep(1)
    raise RuntimeError(
        f"timed out waiting for {expected} bots (online {bots_online(chunks)}, list {last_list!r})"
    )


def bots_online(chunks) -> int:
    """Join lines are the source of truth. Paper's RCON `list` text does not always carry every name."""
    online: set[str] = set()
    for line in chunks:
        joined = re.search(r"\]: (NaiBot\d+) joined the game", line)
        if joined:
            online.add(joined.group(1))
        left = re.search(r"\]: (NaiBot\d+) left the game", line)
        if left:
            online.discard(left.group(1))
    return len(online)


def stop_bots(proc) -> None:
    if proc is None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()


def read_bot_messages(path: Path) -> int:
    if not path.is_file():
        return 0
    try:
        return int(json.loads(path.read_text(encoding="utf-8")).get("messages", 0))
    except (OSError, ValueError, json.JSONDecodeError):
        return 0


def tool(name: str) -> str:
    java = shutil.which("java")
    if java:
        sibling = Path(java).resolve().parent / name
        if sibling.is_file():
            return str(sibling)
    found = shutil.which(name)
    if not found:
        raise RuntimeError(f"{name} is not on PATH")
    return found


def write_summary(out: Path, args, paper: dict, results: list[dict], log_chunks: list[str]) -> None:
    cpu = ""
    try:
        for line in Path("/proc/cpuinfo").read_text(encoding="utf-8").splitlines():
            if line.lower().startswith("model name"):
                cpu = line.split(":", 1)[1].strip()
                break
    except OSError:
        cpu = platform.processor()
    java = subprocess.run(["java", "-version"], capture_output=True, text=True)
    summary = {
        "version": args.version,
        "paperBuild": paper.get("id"),
        "cpu": cpu,
        "cpus": os.cpu_count(),
        "java": (java.stderr or java.stdout).splitlines()[:1],
        "mockLatencyMs": args.mock_latency_ms,
        "baselineSeconds": args.baseline_seconds,
        "loadSeconds": args.load_seconds,
        "results": [
            {
                "scenario": item["scenario"],
                "status": item["status"],
                "attempt": item.get("attempt"),
                "reasons": item.get("reasons") or [],
                "mockCount": item.get("mockCount"),
                "warnings": item.get("warnings"),
                "jfrPercent": item.get("jfrPercent"),
                "jfrServer": item.get("jfrServer"),
                "jfrNexus": item.get("jfrNexus"),
                "report": item.get("report"),
            }
            for item in results
        ],
    }
    (out / "report.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    parts = ["scenario status jfrPercent reasons"]
    for item in results:
        parts.append(f"{item['scenario']} {item['status']} {item.get('jfrPercent')} {'; '.join(item.get('reasons') or [])}")
    (out / "jfr-summary.txt").write_text("\n".join(parts) + "\n", encoding="utf-8")
    if CANARY in "".join(log_chunks):
        print("CANARY LEAK in server log", file=sys.stderr)


def format_result(item: dict) -> str:
    reasons = "; ".join(item.get("reasons") or [])
    report = item.get("report") or {}
    mspt = (report.get("mspt") or {}).get("mean")
    return f"{item['scenario']} {item['status']} mspt_mean={mspt} reasons={reasons}"


def self_check() -> int:
    smoke = load_smoke()
    for name in ("download_paper", "start_mock", "write_server", "rcon", "find_plugin", "free_port"):
        if not callable(getattr(smoke, name, None)):
            raise SystemExit(f"paper-smoke.py is missing {name}")
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        smoke.write_server(work, 25565, 25575)
        text = (work / "server.properties").read_text(encoding="utf-8")
        if "max-players=5" not in text or "rcon.password=smoke" not in text or "motd=NexusAI smoke" not in text:
            raise SystemExit("write_server defaults changed smoke behavior")
        if "spawn-protection" in text:
            raise SystemExit("write_server defaults gained extra keys")
    port = smoke.free_port()
    handle = smoke.start_mock(port)
    try:
        request = urllib.request.Request(
            f"http://127.0.0.1:{port}/v1/chat/completions",
            data=b"{}",
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=5) as response:
            body = response.read()
        if response.status != 200 or b"pong" not in body:
            raise SystemExit("default mock did not return pong")
        if handle.count != 1:
            raise SystemExit(f"mock count {handle.count}")
    finally:
        handle.close()

    server, nexus = jfr_share(
        'jdk.ExecutionSample {\n  sampledThread = "Server thread" (javaThreadId = 1)\n'
        "  io.github.neareststep.nexusai.ai.HttpPool.snapshot()\n}\n"
        'jdk.ExecutionSample {\n  sampledThread = "Server thread"\n'
        "  io.github.neareststep.nexusai.load.LoadDriverPlugin.heartbeat()\n}\n"
        'jdk.ExecutionSample {\n  sampledThread = "nexusai-http-1"\n'
        "  io.github.neareststep.nexusai.ai.OpenAiProvider.exchangeAsync()\n}\n"
    )
    if (server, nexus) != (2, 1):
        raise SystemExit(f"jfr parser {server}, {nexus}")
    sample_log = [
        "[x]: NaiBot01 joined the game\n",
        "[x]: NaiBot02 joined the game\n",
        "[x]: NaiBot01 left the game\n",
    ]
    if bots_online(sample_log) != 1:
        raise SystemExit(f"bot count {bots_online(sample_log)}")
    if not rate_exceeded([0, 0, 0], 1, 1):
        raise SystemExit("rate window did not trip")
    if rate_exceeded([0, 10, 70], 2, 1):
        raise SystemExit("rate window tripped on a legal series")
    if rate_exceeded([0.0] * 30 + [60.0] * 30, 30, 1):
        raise SystemExit("tumbling 30+30 across one reset should pass")
    if rate_exceeded([0.0] * 30 + [59.0] * 30, 30, 1):
        raise SystemExit("30+30 just inside a sliding minute should pass")
    if not rate_exceeded([0.0] * 30 + [30.0] * 30 + [60.0] * 30, 30, 1):
        raise SystemExit("three full windows 30s apart should fail")
    if not rate_exceeded([1.0] * 60, 30, 1):
        raise SystemExit("a one-second burst of 60 should fail")

    base = {
        "skipped": False,
        "error": "",
        "mspt": {"samples": 100, "mean": 12.0, "max": 20.0},
        "callNanos": {"samples": 100, "p99": 1000},
        "tpsMin": 20.0,
        "threads": {"httpMin": 4, "httpMax": 4, "httpLast": 4, "contextMin": 2, "contextMax": 2, "contextLast": 2},
        "pool": {
            "workerQueueCapacity": 64, "maxInFlightCap": 64, "httpWaitCapacity": 64,
            "maxWorkerQueued": 0, "maxInFlight": 1, "maxHttpWaiting": 0,
            "rejectedDelta": 0,
        },
        "drain": {"settled": True},
        "context": {"provideOnMainThread": 0, "rows": [{"id": "slow", "suspended": True}]},
        "uniquePrompts": 10,
        "talkCommands": 4,
    }
    ctx = {
        "baseline_mean": 11.2,
        "mock_count": 10,
        "mock_timestamps": [0, 1],
        "bodies": ['{"x":"§§§ PLAYER INPUT §§§ coins ~12k §§§ END §§§"}'],
        "warnings": 1,
        "errors": 0,
        "frames": [],
        "canary": False,
        "oom": False,
        "dialogue_failed": False,
        "jfr_server": 1000,
        "jfr_nexus": 10,
        "jfr_note": "",
        "bot_messages": 3,
        "seconds": 120,
        "queue_full": True,
    }
    if judge("S1", base, ctx)["status"] != "pass":
        raise SystemExit(judge("S1", base, ctx))
    slow = dict(base)
    slow["callNanos"] = {"p99": 500_000}
    if judge("S1", slow, ctx)["status"] != "fail":
        raise SystemExit("S1 p99 should fail")
    noisy = dict(ctx)
    noisy["jfr_server"] = 5
    noisy["jfr_nexus"] = 1
    if judge("S2", base, noisy)["status"] != "pass":
        raise SystemExit(f"S2 should ignore a 2% JFR share below 200 samples: {judge('S2', base, noisy)}")
    hot = dict(ctx)
    hot["jfr_server"] = 1000
    hot["jfr_nexus"] = 30
    if judge("S2", base, hot)["status"] != "fail":
        raise SystemExit("S2 should fail a 3% JFR share at 1000 samples")
    missing = dict(ctx)
    missing["jfr_server"] = 0
    missing["jfr_note"] = "JFR recording was not written"
    if judge("S2", base, missing)["status"] != "fail":
        raise SystemExit("S2 should fail when the JFR recording was not written")
    leaked = dict(ctx)
    leaked["canary"] = True
    if judge("baseline", base, leaked)["status"] != "fail":
        raise SystemExit("canary should fail")
    pool = dict(base)
    pool["pool"] = dict(base["pool"])
    pool["pool"]["rejectedDelta"] = 20
    pool["pool"]["maxWorkerQueued"] = 64
    pool["callNanos"] = {"p99": 80_000}
    if judge("S-pool", pool, ctx)["status"] != "pass":
        raise SystemExit(judge("S-pool", pool, ctx))
    quiet = dict(ctx)
    quiet["queue_full"] = False
    if judge("S-pool", pool, quiet)["status"] != "fail":
        raise SystemExit("S-pool without the queue-full line should fail")
    skipped = dict(base)
    skipped["skipped"] = True
    skipped["error"] = "S4 needs online players"
    if judge("S4", skipped, ctx)["status"] != "not-run":
        raise SystemExit("skipped S4 should be not-run")
    print("SELF-CHECK OK")
    return 0


JFC = """<?xml version="1.0" encoding="UTF-8"?>
<configuration version="2.0" label="nexusai-load">
  <event name="jdk.ExecutionSample">
    <setting name="enabled">true</setting>
    <setting name="period">10 ms</setting>
  </event>
</configuration>
"""


if __name__ == "__main__":
    sys.exit(main())
