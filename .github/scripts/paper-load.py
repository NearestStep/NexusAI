#!/usr/bin/env python3
"""Load harness for NexusAI. Reuses the smoke helpers in paper-smoke.py.

Mode A (no game client) runs S1, S1-keywords, S2, S2-over, S2-probe, S3, S6, and the HTTP-pool overflow probe S-pool.
Mode B (--bots) adds S4 and S5 and needs mineflayer. CI runs mode A only, without S2-over.
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
import tempfile
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

CANARY = "sk-canary-load-7f3a9c2e1b"
MARKERS = ("§§§ PLAYER INPUT §§§", "§§§ END §§§")
COOLDOWN_SECONDS = 30
STALL_PROPERTY = "nexusai.load.stallMillis"
RETRY_STATUSES = frozenset({"fail", "invalid"})


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
    parser.add_argument("--scenario", default="S1,S1-keywords,S2,S2-over,S2-probe,S3,S6,S-pool")
    parser.add_argument("--bots", type=int, default=0)
    parser.add_argument("--mock-latency-ms", type=int, default=300)
    parser.add_argument("--mock-429-every", type=int, default=0)
    parser.add_argument("--baseline-seconds", type=int, default=60)
    parser.add_argument("--load-seconds", type=int, default=120)
    parser.add_argument("--pool-seconds", type=int, default=3)
    parser.add_argument("--out", default="load-out")
    parser.add_argument("--timeout", type=int, default=420, help="seconds to wait for Done")
    parser.add_argument("--skip-jfr", action="store_true")
    parser.add_argument(
        "--stall-ms", type=int, default=int(os.environ.get("LOAD_STALL_MS", "5000")),
        help="a tick gap, wall/monotonic clock drift, or window overrun above this marks a scenario invalid "
             "(test host stall) instead of pass or fail; 0 turns the check off",
    )
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


SCENARIO_NAMES = {
    "baseline", "S1", "S1-keywords", "S2", "S2-over", "S2-probe", "S3", "S4", "S5", "S6", "S-pool",
    "S-world",
}


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
    smoke.seed_paperclip(work / "paper.jar", work)
    smoke.install_papi(work / "plugins")
    shutil.copy2(plugin, work / "plugins" / plugin.name)
    shutil.copy2(driver, work / "plugins" / driver.name)
    write_prompts(work / "plugins" / "NexusAI" / "prompts.yml")
    write_knowledge(work / "plugins" / "NexusAI" / "knowledge" / "rules.md")

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
    jfc = write_server_files(smoke, work, server_port, rcon_port, args.bots)

    log_chunks: list[str] = []
    process = boot_process(work, args.stall_ms)
    reader = threading.Thread(target=read_log, args=(process.stdout, log_chunks), daemon=True)
    reader.start()
    bots = None
    results = []
    try:
        wait_for_done(process, log_chunks, args.timeout)
        text = "".join(log_chunks)
        if not nexusai_enabled(text) or "NexusAI-LoadDriver enabled." not in text:
            raise RuntimeError("NexusAI or the load driver did not enable")
        if CANARY in text:
            raise RuntimeError("canary key appeared in the boot log")

        planned = ["baseline"] + [item for item in scenarios if item != "baseline"]
        needs_bots = any(item in {"S4", "S5", "S-world"} for item in planned)
        bot_note = ""
        if needs_bots:
            if args.bots <= 0:
                bot_note = "S4/S5/S-world were requested without --bots"
            else:
                bots, bot_note = start_bots(args, server_port, work, out)
                if bots is None:
                    print(f"bots not started: {bot_note}")
                else:
                    try:
                        wait_for_players(smoke, rcon_port, args.bots, log_chunks, process)
                    except RuntimeError as error:
                        bot_note = str(error)
                        print(f"bots did not join: {bot_note}")
                        stop_bots(bots)
                        bots = None

        run_one = scenario_runner(
            smoke, args, process, log_chunks, rcon_port, work, out, jfc, requests_path, mock_port,
            bot_note, bots is not None,
        )
        return run_plan(
            smoke, rcon_port, log_chunks, process, planned, run_one, results,
            lambda done: write_summary(out, args, paper, done, log_chunks),
        )
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


def write_server_files(smoke, work: Path, server_port: int, rcon_port: int, bots: int) -> Path:
    """server.properties, spigot.yml with the long watchdog timeout, and the JFR settings."""
    smoke.write_server(
        work,
        server_port,
        rcon_port,
        max_players=max(30, bots + 5),
        rcon_password="loadtest",
        motd="NexusAI load",
        extras=["spawn-protection=0"],
    )
    write_spigot(work / "spigot.yml")
    jfc = work / "load.jfc"
    jfc.write_text(JFC, encoding="utf-8")
    return jfc


def scenario_runner(smoke, args, process, log_chunks, rcon_port, work, out, jfc, requests_path, mock_port,
                    bot_note, bots_up, execute=None):
    """The per-scenario step for run_plan: limits and reload, the talk flag, the scenario with the
    recovery step it is given, and the baseline mean for later scenarios."""
    state = {"limits": high_limits(), "baseline_mean": None}
    execute = execute or execute_scenario

    def run_one(scenario: str, recover) -> dict:
        wanted = limits_for(scenario)
        if wanted != state["limits"]:
            write_load_config(work / "plugins" / "NexusAI" / "config.yml", mock_port, wanted)
            reload_plugin(smoke, rcon_port, log_chunks, process)
            state["limits"] = wanted
        if scenario == "S5" and bots_up:
            (work / "talk.flag").write_text("go\n", encoding="utf-8")
        elif (work / "talk.flag").exists():
            (work / "talk.flag").unlink()
        outcome = execute(
            smoke, args, scenario, process, log_chunks, rcon_port, work, out, jfc,
            requests_path, state["baseline_mean"], bot_note, bots_up, recover=recover,
        )
        if scenario == "baseline" and outcome.get("report"):
            state["baseline_mean"] = outcome["report"]["mspt"]["mean"]
        return outcome

    return run_one


def end_budget(args, scenario: str) -> int:
    """How long to wait for SCENARIO_END: the window plus at least the watchdog timeout, so a host
    pause the server survives does not end the run first."""
    return seconds_for(args, scenario) + max(90, SPIGOT_TIMEOUT_SECONDS)


def unfinished_attempt(scenario: str, attempt: int, process, budget: int, evidence: list[str]) -> dict:
    """An attempt without SCENARIO_END: invalid when the host stalled, otherwise a failure."""
    code = process.poll()
    why = f"server exited with {code}" if code is not None else f"no SCENARIO_END within {budget} s"
    if not evidence:
        raise RuntimeError(f"{why} for {scenario}")
    # The host stalled and the scenario never finished: nothing to judge, so it is invalid.
    return {"scenario": scenario, "attempt": attempt, "status": "invalid",
            "reasons": [f"test host stall: {item}" for item in evidence] + [f"{why}; not judged"],
            "report": None}


def process_state(pid: int) -> str:
    """The state letter from /proc/<pid>/stat (T when stopped), or "" where it cannot be read."""
    try:
        text = Path(f"/proc/{pid}/stat").read_text(encoding="ascii", errors="replace")
    except OSError:
        return ""
    after = text.rsplit(")", 1)[-1].split()
    return after[0] if after else ""


def wait_scenario_end(process, chunks: list[str], start: int, scenario: str, timeout_s: float, stall_ms: int,
                      clock=time.monotonic, sleep=time.sleep, state=None) -> tuple[bool, list[str]]:
    """Waits for SCENARIO_END. Returns (ended, stall evidence). Evidence of a test host stall: the
    server process seen stopped, a pause of the harness itself longer than stall_ms, or the
    driver's SCENARIO_INVALID line for this scenario. Without evidence a missing end is a failure."""
    token = f"SCENARIO_END {scenario}"
    invalid_token = f"SCENARIO_INVALID {scenario} "
    state = state or (lambda: process_state(process.pid))
    evidence: list[str] = []
    deadline = clock() + timeout_s
    last = clock()
    while True:
        window = chunks[start:]
        if any(token in line for line in window):
            return True, evidence
        if stall_ms > 0:
            if any(invalid_token in line for line in window) and "driver logged SCENARIO_INVALID" not in evidence:
                evidence.append("driver logged SCENARIO_INVALID")
            now = clock()
            if (now - last) * 1000 > stall_ms:
                evidence.append(f"harness paused {int((now - last) * 1000)} ms")
            last = now
            if state() == "T" and "server process stopped" not in evidence:
                evidence.append("server process stopped")
        if process.poll() is not None or clock() >= deadline:
            return False, evidence
        sleep(0.2)


def execute_scenario(
    smoke, args, scenario, process, log_chunks, rcon_port, work, out, jfc,
    requests_path, baseline_mean, bot_note, bots_up, recover=None, measure=None,
) -> dict:
    """Runs one scenario with one retry. measure replaces the real attempt in the self-check."""
    if scenario in {"S4", "S5", "S-world"} and not bots_up:
        return {
            "scenario": scenario,
            "status": "not-run",
            "reasons": [bot_note or "mode B needs mineflayer bots and a live Paper server"],
            "report": None,
        }

    def attempt_once(attempt: int) -> dict:
        before = len(log_chunks)
        started = time.time()
        started_mono = time.monotonic()
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
        budget = end_budget(args, scenario)
        ended, evidence = wait_scenario_end(process, log_chunks, before, scenario, budget, args.stall_ms)
        if not ended:
            return unfinished_attempt(scenario, attempt, process, budget, evidence)
        ended = time.time()
        ended_mono = time.monotonic()
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
            "mock_latency_ms": args.mock_latency_ms,
            "token_usage": read_token_usage(work),
        })
        stall = host_stall_reasons(report, ended - started, ended_mono - started_mono, args.stall_ms)
        if stall:
            # A stalled host says nothing about the plugin; keep the scenario out of pass and fail.
            judged = {"status": "invalid", "reasons": stall + [f"metrics alone: {judged['status']} (not used)"]}
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
        shutil.copy2(saved, out / f"report-{scenario}.json")
        return last

    return retry_once(scenario, measure or attempt_once, recover)


def retry_once(scenario: str, attempt_once, recover=None) -> dict:
    """Runs attempt 1, and attempt 2 only when attempt 1 failed or ran on a stalled test host.

    After a stall, recover() has to bring the server back first: it returns "" when the server
    is ready, or the reason it is not. A server that does not recover keeps the scenario
    invalid instead of judging a second attempt that still carries the stall.
    """
    last = attempt_once(1)
    if last["status"] in RETRY_STATUSES:
        say(f"{scenario} attempt 1 {last['status']} ({'; '.join(last['reasons'])}); retrying once")
        if last["status"] == "invalid" and recover is not None:
            problem = recover()
            if problem:
                say(f"{scenario} not retried: {problem}")
                return {**last, "reasons": last["reasons"] + [f"not retried: {problem}"]}
        last = attempt_once(2)
    return last


TPS_READY = 19.8
TPS_LINE = re.compile(r"TPS from last 1m, 5m, 15m:\s*\*?([0-9]+(?:\.[0-9]+)?)")


def parse_tps(text: str) -> float | None:
    """The 1-minute TPS from Paper's tps command, or None when the text has none."""
    match = TPS_LINE.search(text or "")
    return float(match.group(1)) if match else None


def wait_for_tps(read_tps, timeout_s: float = 150.0, poll_s: float = 5.0,
                 clock=time.monotonic, sleep=time.sleep, stop=None) -> tuple[bool, float | None]:
    """Polls until the 1-minute TPS is at least TPS_READY. Returns (ready, last value read).
    A read that raises counts as no value. stop() ends the wait early (the server has exited)."""
    deadline = clock() + timeout_s
    last = None
    while True:
        if stop is not None and stop():
            return False, last
        try:
            last = read_tps()
        except Exception:
            last = None
        if last is not None and last >= TPS_READY:
            return True, last
        if clock() >= deadline:
            return False, last
        sleep(poll_s)


def recover_after_stall(smoke, rcon_port: int, log_chunks: list[str], process, unreachable: dict | None = None,
                        wait=None) -> str:
    """Before the retry of an invalid attempt: /nai reload drops the provider pause and backoff
    the stall left behind, then the 1-minute TPS must climb back to TPS_READY, so the retry is
    not judged on the stall. Returns "" when ready, otherwise why not. When the server has exited
    or does not answer RCON, unreachable["reason"] is set so run_plan stops starting scenarios."""
    unreachable = unreachable if unreachable is not None else {}

    def stopped() -> str:
        code = process.poll()
        return "" if code is None else f"server stopped after the stall (exit {code}, Paper watchdog?)"

    if stopped():
        unreachable["reason"] = stopped()
        return stopped()
    try:
        reload_plugin(smoke, rcon_port, log_chunks, process)
    except Exception as error:  # OSError, EOFError ("rcon connection closed"), RuntimeError
        unreachable["reason"] = stopped() or f"server does not answer RCON after the stall: {error}"
        return unreachable["reason"]
    answered = []

    def read() -> float | None:
        text = smoke.rcon("127.0.0.1", rcon_port, "loadtest", "tps")
        answered.append(True)
        return parse_tps(smoke.strip_colors(text))

    ready, tps = (wait or wait_for_tps)(read, stop=lambda: process.poll() is not None)
    if stopped():
        unreachable["reason"] = stopped()
        return stopped()
    if ready:
        say(f"server ready for the retry: /nai reload done, TPS {tps}")
        return ""
    if not answered:
        unreachable["reason"] = "server does not answer RCON after the stall"
        return unreachable["reason"]
    return f"TPS {tps} did not reach {TPS_READY} within 150 s after the stall"


def run_plan(smoke, rcon_port: int, log_chunks: list[str], process, planned, run_one, results: list,
             summarize) -> int:
    """Runs every scenario with the recovery step for stalled attempts, writes the summary, and
    returns the exit code. Once the server has exited (for example the Paper watchdog stopped it
    after a long stall) or stopped answering RCON after a stall, the remaining scenarios are
    reported as not-run instead of failing the run."""
    unreachable: dict = {}

    def recover() -> str:
        return recover_after_stall(smoke, rcon_port, log_chunks, process, unreachable)

    for scenario in planned:
        if process.poll() is not None:
            outcome = {"scenario": scenario, "status": "not-run",
                       "reasons": ["the server stopped earlier in this run"], "report": None}
        elif unreachable:
            outcome = {"scenario": scenario, "status": "not-run",
                       "reasons": [f"not run: {unreachable['reason']}"], "report": None}
        else:
            outcome = run_one(scenario, recover)
        results.append(outcome)
        say(format_result(outcome))
    summarize(results)
    return finish_run(results)


def finish_run(results: list[dict]) -> int:
    """Prints the invalid scenarios and returns the exit code for the run."""
    invalid = [item for item in results if item["status"] == "invalid"]
    if invalid:
        say("INVALID (test host stall): " + ", ".join(item["scenario"] for item in invalid))
    return exit_code(item["status"] for item in results)


def exit_code(statuses) -> int:
    """1 when any scenario failed, otherwise 3 when any ran on a stalled test host, otherwise 0."""
    seen = set(statuses)
    if "fail" in seen:
        return 1
    return 3 if "invalid" in seen else 0


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


def host_stall_reasons(report: dict, wall_seconds: float, mono_seconds: float, stall_ms: int) -> list[str]:
    """Reasons a scenario ran on a stalled test host, from the driver report and this process's clocks.

    The driver reports the largest tick gap not covered by tick work, its own wall/monotonic drift,
    and how far the load window overran. This process adds the drift it saw between its wall and
    monotonic clocks over the same scenario. Empty when stall_ms is 0 or nothing exceeds it.
    """
    if stall_ms <= 0:
        return []
    reasons = []
    stall = (report or {}).get("hostStall") or {}
    if stall.get("invalid"):
        reasons.extend(f"test host stall: {item}" for item in stall.get("reasons") or ["reported by the driver"])
    drift_ms = abs(wall_seconds - mono_seconds) * 1000.0
    if drift_ms > stall_ms:
        reasons.append(f"test host stall: harness wall and monotonic clocks drifted {drift_ms:.0f} ms > {stall_ms} ms")
    return reasons


def judge(scenario: str, report: dict, ctx: dict) -> dict:
    if report.get("skipped"):
        return {"status": "not-run", "reasons": [report.get("error") or "skipped"]}
    reasons = common_reasons(report, ctx)
    if report.get("error"):
        reasons.append(report["error"])
    baseline = ctx.get("baseline_mean")
    mean = report.get("mspt", {}).get("mean", 0.0)
    delta = None if baseline is None or scenario == "baseline" else mean - baseline
    if scenario in {"S1", "S1-keywords"}:
        if delta is None or delta > 1.0:
            reasons.append(f"ΔMSPT {fmt(delta)} > 1.0 ms")
        p99 = report.get("callNanos", {}).get("p99", 10**12)
        if p99 > 200_000:
            reasons.append(f"call p99 {p99} ns > 200 µs")
        if report.get("mspt", {}).get("samples", 0) < 10:
            reasons.append("too few MSPT samples")
    elif scenario in {"S2", "S2-probe"}:
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
    elif scenario == "S6":
        reasons.extend(s6_reasons(report, ctx))
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
    elif scenario == "S-world":
        reasons.extend(load_budget(delta, report, 2.0))
        if report.get("tpsMin", 0) < 19.8:
            reasons.append(f"TPS {report.get('tpsMin')} < 19.8")
        p99 = report.get("callNanos", {}).get("p99", 10**12)
        # Region capture + schedule of format; budget matches the 0.2 ms region target with headroom.
        if p99 > 500_000:
            reasons.append(f"worldContext schedule p99 {p99} ns > 500 µs")
        if report.get("resolutions", 0) < 10:
            reasons.append("too few worldContext calls")
        if not report.get("drain", {}).get("settled", False):
            reasons.append("worldContext futures still open after drain")
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


def s6_reasons(report: dict, ctx: dict) -> list[str]:
    """Quota run. The cap is spent tokens, not the first temporary refusal."""
    reasons = []
    delta = None
    baseline = ctx.get("baseline_mean")
    mean = report.get("mspt", {}).get("mean", 0.0)
    if baseline is not None:
        delta = mean - baseline
    reasons.extend(load_budget(delta, report, 2.0))
    p99 = report.get("callNanos", {}).get("p99", 10**12)
    if p99 > 200_000:
        reasons.append(f"call p99 {p99} ns > 200 µs")
    if int(report.get("quotaDenied") or 0) <= 0:
        reasons.append("quota was never denied")
    if int(report.get("futuresOpen") or 0) != 0:
        reasons.append("generate futures still open")
    if not report.get("drain", {}).get("settled", False):
        reasons.append("futures still running 30s after stop")
    limit = int(report.get("quotaLimit") or 0)
    spent = int(report.get("tokensSpent") or 0)
    reserved = int(report.get("reservedInFlight") or 0)
    if limit <= 0:
        reasons.append("quota limit was not set")
    overshoot = max(0, spent - limit)
    if overshoot > reserved:
        reasons.append(f"token overshoot {overshoot} > in-flight reservation {reserved}")
    seconds = int(ctx.get("seconds") or 120)
    offset = report.get("quotaExhaustedOffsetMillis")
    low = int(seconds * 1000 * 0.25)
    high = int(seconds * 1000 * 0.75)
    if not isinstance(offset, (int, float)) or offset < low or offset > high:
        reasons.append(f"quota exhaustion offset {offset} ms is outside {low}..{high}")
    exhausted = int(report.get("quotaExhaustedEpochMillis") or 0)
    latency = int(ctx.get("mock_latency_ms") or 300) / 1000.0
    if exhausted <= 0:
        reasons.append("quota exhaustion time was not recorded")
    else:
        deadline = exhausted / 1000.0 + latency + 1.0
        late = [stamp for stamp in (ctx.get("mock_timestamps") or []) if float(stamp) > deadline]
        if late:
            reasons.append(f"mock requests after quota exhaustion: {len(late)}")
    try:
        parse_token_usage(ctx.get("token_usage") if isinstance(ctx.get("token_usage"), str) else "")
    except ValueError as error:
        reasons.append(f"token-usage.yml {error}")
    return reasons


def parse_token_usage(text: str) -> dict:
    """Top-level `format` and `day` from token-usage.yml. No YAML parser."""
    if text is None or not str(text).strip():
        raise ValueError("missing")
    if "\t" in text:
        raise ValueError("contains a tab")
    fields = {}
    for line in text.splitlines():
        if not line or line[0] in " \t#-" or ":" not in line:
            continue
        key, value = line.split(":", 1)
        fields[key.strip()] = value.strip().strip('"').strip("'")
    if fields.get("format") != "1":
        raise ValueError("format")
    day = fields.get("day", "")
    if len(day) != 10 or day[4] != "-" or day[7] != "-":
        raise ValueError("day")
    return fields


def read_token_usage(work: Path) -> str:
    path = work / "plugins" / "NexusAI" / "token-usage.yml"
    if not path.is_file():
        return ""
    return path.read_text(encoding="utf-8")


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


# Unique S6 text is 44 characters (11 estimated prompt tokens) plus the reply "pong"
# (1 token). 50 calls/s and one unique call in five is 10 unique calls/s. 600 of those
# is 7200 tokens, about 60s into the 120s window. The shared template's first call adds
# a few tokens on top. A 0 cap is not a cap.
S6_CONSUMER_TOKENS = 7200


def world_context_enabled() -> bool:
    raw = os.environ.get("WORLD_CONTEXT_ENABLED", "false").strip().lower()
    return raw in {"1", "true", "yes", "on"}


def high_limits() -> dict:
    return {
        "minute": 100000,
        "day": 100000,
        "player_minute": 100000,
        "player_day": 100000,
        "quotas_enabled": False,
        "consumer_tokens": 0,
        "world_context_enabled": world_context_enabled(),
    }


def limits_for(scenario: str) -> dict:
    if scenario == "S3":
        return {
            "minute": 30,
            "day": 1000,
            "player_minute": 10,
            "player_day": 200,
            "quotas_enabled": False,
            "consumer_tokens": 0,
            "world_context_enabled": world_context_enabled(),
        }
    if scenario == "S1-keywords":
        limits = high_limits()
        limits["quotas_enabled"] = True
        return limits
    if scenario == "S6":
        limits = high_limits()
        limits["quotas_enabled"] = True
        limits["consumer_tokens"] = S6_CONSUMER_TOKENS
        return limits
    if scenario == "S-world":
        limits = high_limits()
        # S-world only measures the helper when it is on.
        limits["world_context_enabled"] = True
        return limits
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
        "S1-keywords": 1000,
        "S2": 10,
        "S2-over": 50,
        "S2-probe": 10,
        "S3": 200,
        "S4": 40,
        "S5": 0,
        "S6": 50,
        "S-pool": 0,
        # 20 bots × 10 worldContext calls/s each.
        "S-world": 200,
    }[scenario]


def viewers_for(args, scenario: str) -> int:
    if scenario in {"S1", "S1-keywords"}:
        return 100
    if scenario in {"S4", "S5", "S-world"}:
        return max(1, args.bots)
    return 0


def write_load_config(path: Path, mock_port: int, limits: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    url = f"http://127.0.0.1:{mock_port}/v1"
    world_on = bool(limits.get("world_context_enabled", False))
    path.write_text(
        "\n".join([
            "config-version: 1",
            "locale: en",
            "api:",
            "  provider: openai",
            "  model: load-model",
            f'  base-url: "{url}"',
            f'  key: "{CANARY}"',
            '  system-prompt: ""',
            "  max-tokens: 256",
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
            "world-context:",
            f"  enabled: {'true' if world_on else 'false'}",
            "  radius: 2",
            "  max-blocks: 125",
            "  max-block-types: 16",
            "  max-inventory-items: 36",
            "  max-chars: 400",
            "  cooldown-millis: 250",
            "quotas:",
            f"  enabled: {'true' if limits['quotas_enabled'] else 'false'}",
            "  missing-usage: estimate",
            "  consumers:",
            "    default:",
            f"      tokens-per-day: {limits['consumer_tokens']}",
            "      requests-per-day: 0",
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
            "load_kw:",
            '  prompt: "Reply with the single word pong."',
            "  knowledge:",
            "    - rules",
            "  knowledge-select: keywords",
            "  ttl: 600",
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


def write_knowledge(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join([
            "# Rules",
            "",
            "Reply with the single word pong when the load prompt asks for it.",
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


def java_command(stall_ms: int = 5000) -> list[str]:
    """The server command line. The stall threshold reaches LoadDriver as a system property."""
    return [
        "java",
        "-Djava.awt.headless=true",
        f"-D{STALL_PROPERTY}={max(0, stall_ms)}",
        "-Dterminal.jline=false",
        "-Dterminal.ansi=false",
        "-Xms1G",
        "-Xmx2G",
        "-jar",
        "paper.jar",
        "--nogui",
    ]


def boot_process(work: Path, stall_ms: int = 5000) -> subprocess.Popen:
    return subprocess.Popen(
        java_command(stall_ms),
        cwd=work,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )


OUTPUT_LOCK = threading.Lock()


def say(text: str) -> None:
    """One harness line, written in one piece so a server console line cannot land inside it."""
    with OUTPUT_LOCK:
        sys.stdout.write(text + "\n")
        sys.stdout.flush()


def read_log(pipe, chunks: list[str]) -> None:
    for line in pipe:
        chunks.append(line)
        with OUTPUT_LOCK:
            if not line.endswith("\n"):
                line += "\n"
            sys.stdout.write(line)
        sys.stdout.flush()


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


def wait_for_done(process, chunks: list[str], timeout: int) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
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
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited while waiting for {token}")
        if any(token in line for line in chunks[start:]):
            return
        time.sleep(0.2)
    raise RuntimeError(f"timed out waiting for {token}")


SPIGOT_TIMEOUT_SECONDS = 600


def write_spigot(path: Path) -> None:
    """spigot.yml with a long watchdog timeout, so a test host pause does not stop the server.

    Paper merges its defaults into the file on the first boot. The default timeout-time is 60 s,
    and a longer host pause would let the watchdog stop the server in the middle of a run."""
    path.write_text(f"settings:\n  timeout-time: {SPIGOT_TIMEOUT_SECONDS}\n", encoding="utf-8")


def reload_plugin(smoke, rcon_port: int, chunks: list[str], process) -> None:
    start = len(chunks)
    smoke.rcon("127.0.0.1", rcon_port, "loadtest", "nai reload")
    wait_marker(process, chunks, start, "NexusAI reloaded", 40)


def bot_report_paths(work: Path, out: Path) -> tuple[str, str]:
    """Absolute paths. load-bots.mjs runs with cwd under work/bots, so a relative --out would miss."""
    return str((out / "bots.json").resolve()), str((work / "talk.flag").resolve())


def _extend_version_lists(bot_dir: Path, donor: str, target: str) -> None:
    for relative in (
        Path("mineflayer") / "lib" / "version.js",
        Path("minecraft-protocol") / "src" / "version.js",
    ):
        version_js = bot_dir / "node_modules" / relative
        if not version_js.is_file():
            continue
        vtext = version_js.read_text(encoding="utf-8")
        if f"'{target}'" in vtext:
            continue
        needle = f"'{donor}']"
        if needle in vtext:
            version_js.write_text(vtext.replace(needle, f"'{donor}', '{target}']", 1), encoding="utf-8")


def _alias_prismarine_chunk(bot_dir: Path, donor: str, target: str) -> None:
    chunk_js = bot_dir / "node_modules" / "prismarine-chunk" / "src" / "index.js"
    if not chunk_js.is_file():
        return
    ctext = chunk_js.read_text(encoding="utf-8")
    if f"'{target}'" in ctext or f" {target}:" in ctext:
        return
    target_line = f"    '{target}': require('./pc/1.18/chunk')"
    raw = f"    {donor}: require('./pc/1.18/chunk')"
    quoted = f"    '{donor}': require('./pc/1.18/chunk')"
    if raw in ctext:
        chunk_js.write_text(ctext.replace(raw, raw + ",\n" + target_line, 1), encoding="utf-8")
    elif quoted in ctext:
        chunk_js.write_text(ctext.replace(quoted, quoted + ",\n" + target_line, 1), encoding="utf-8")


def shim_minecraft_data(bot_dir: Path, paper_version: str) -> str:
    """Ensure mineflayer can target Paper's version string.

    minecraft-data may list a version (e.g. 26.2) without shipping packet data yet.
    When that happens, clone the nearest older release folder and pin the protocol
    number from Paper so bots can log in. Returns the MC_VERSION to pass to bots.
    """
    modules = bot_dir / "node_modules" / "minecraft-data"
    data_js = modules / "data.js"
    pc_root = modules / "minecraft-data" / "data" / "pc"
    if not data_js.is_file() or not pc_root.is_dir():
        return ""
    target = (paper_version or "").strip()
    if not target:
        return ""

    donor = None
    if target.startswith("26.") and (pc_root / "26.1").is_dir():
        donor = "26.1"
    else:
        candidates = sorted(
            (p.name for p in pc_root.iterdir() if p.is_dir() and p.name[0].isdigit()),
            reverse=True,
        )
        for name in candidates:
            if name < target and (pc_root / name / "version.json").is_file():
                donor = name
                break
    if donor is None:
        return ""

    if f"'{target}':" not in data_js.read_text(encoding="utf-8"):
        protocol = None
        env_proto = os.environ.get("MC_PROTOCOL", "").strip()
        if env_proto.isdigit():
            protocol = int(env_proto)
        elif target == "26.2":
            protocol = 776
        else:
            try:
                donor_ver = json.loads((pc_root / donor / "version.json").read_text(encoding="utf-8"))
                protocol = int(donor_ver.get("version", 0)) + 1
            except (OSError, ValueError, json.JSONDecodeError, TypeError):
                return ""

        dest = pc_root / target
        if dest.exists():
            shutil.rmtree(dest)
        shutil.copytree(pc_root / donor, dest)
        ver_path = dest / "version.json"
        ver = json.loads(ver_path.read_text(encoding="utf-8"))
        ver["version"] = protocol
        ver["minecraftVersion"] = target
        ver["majorVersion"] = target
        ver_path.write_text(json.dumps(ver, indent=2) + "\n", encoding="utf-8")

        text = data_js.read_text(encoding="utf-8")
        marker = f"    '{donor}': {{"
        start = text.find(marker)
        if start < 0:
            return ""
        depth = 0
        end = -1
        for index, ch in enumerate(text[start:], start):
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    end = index + 1
                    break
        if end < 0:
            return ""
        block = text[start:end]
        block = block.replace(f"'{donor}':", f"'{target}':", 1)
        block = block.replace(
            f'require("./minecraft-data/data/pc/{donor}/version.json")',
            f'require("./minecraft-data/data/pc/{target}/version.json")',
            1,
        )
        data_js.write_text(text[:end] + ",\n" + block + text[end:], encoding="utf-8")

    _extend_version_lists(bot_dir, donor, target)
    _alias_prismarine_chunk(bot_dir, donor, target)
    return target


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
        # 4.39.0 tests through Minecraft 26.1; shim_minecraft_data extends that to 26.2.
        "dependencies": {"mineflayer": "4.39.0"},
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
    mc_version = shim_minecraft_data(bot_dir, args.version)
    report, talk_flag = bot_report_paths(work, out)
    env = os.environ.copy()
    env.update({
        "HOST": "127.0.0.1",
        "PORT": str(server_port),
        "BOTS": str(args.bots),
        "TALK_FLAG": talk_flag,
        "BOT_REPORT": report,
        "MC_VERSION": mc_version,
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
    deadline = time.monotonic() + 90
    last_list = ""
    while time.monotonic() < deadline:
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
        if b'"usage"' in body:
            raise SystemExit("default mock gained a usage key")
        if handle.count != 1:
            raise SystemExit(f"mock count {handle.count}")
    finally:
        handle.close()

    modes = smoke.free_port()
    with tempfile.TemporaryDirectory() as tmp:
        journal = Path(tmp) / "mock-requests.jsonl"
        modes_handle = smoke.start_mock(modes, requests_path=journal, usage="off")
        try:
            def post(path: str, headers: dict[str, str]) -> tuple[int, bytes]:
                req = urllib.request.Request(
                    f"http://127.0.0.1:{modes}{path}",
                    data=b'{"model":"smoke"}',
                    headers={"Content-Type": "application/json", **headers},
                    method="POST",
                )
                try:
                    with urllib.request.urlopen(req, timeout=5) as response:
                        return response.status, response.read()
                except urllib.error.HTTPError as error:
                    return error.code, error.read()

            status, body = post("/v1/chat/completions", {"X-Nexus-Mock-Usage": "on"})
            parsed = json.loads(body)
            if status != 200 or parsed["usage"] != {"prompt_tokens": 8, "completion_tokens": 2, "total_tokens": 10}:
                raise SystemExit(f"usage=on {status} {body!r}")
            status, body = post("/v1/chat/completions?usage=partial", {})
            parsed = json.loads(body)
            if status != 200 or parsed["usage"] != {"total_tokens": 12} or "prompt_tokens" in parsed["usage"]:
                raise SystemExit(f"usage=partial {status} {body!r}")
            status, body = post("/v1/chat/completions", {"X-Nexus-Mock-Usage": "cost"})
            parsed = json.loads(body)
            if status != 200 or parsed["usage"].get("cost") != 0.00012:
                raise SystemExit(f"usage=cost {status} {body!r}")
            status, body = post("/v1/chat/completions", {"X-Nexus-Mock-Usage": "off"})
            if status != 200 or b"pong" not in body or b'"usage"' in body:
                raise SystemExit(f"usage=off {status} {body!r}")
            status, body = post("/v1/chat/completions", {"X-Nexus-Mock-Status": "429-once"})
            if status != 429 or b"rate limit" not in body:
                raise SystemExit(f"status=429-once first {status} {body!r}")
            status, body = post("/v1/chat/completions", {"X-Nexus-Mock-Status": "429-once"})
            if status != 200 or b"pong" not in body:
                raise SystemExit(f"status=429-once second {status} {body!r}")
            status, body = post("/v1/chat/completions?status=500", {})
            if status != 500:
                raise SystemExit(f"status=500 {status}")
            status, body = post("/v1/chat/completions", {"X-Nexus-Mock-Status": "401"})
            if status != 401:
                raise SystemExit(f"status=401 {status}")
            lines = [json.loads(line) for line in journal.read_text(encoding="utf-8").splitlines() if line.strip()]
            if len(lines) != 8 or "body" not in lines[0] or lines[0]["usage"] != "on":
                raise SystemExit(f"mock journal {lines!r}")
        finally:
            modes_handle.close()

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
    for relative_out in (Path("load-out-bots"), Path("..") / "load-b"):
        report, flag = bot_report_paths(Path("work"), relative_out)
        if not Path(report).is_absolute() or not Path(flag).is_absolute():
            raise SystemExit(f"bot paths must be absolute: {report} {flag}")
        if Path(report).name != "bots.json" or Path(flag).name != "talk.flag":
            raise SystemExit(f"bot path names {report} {flag}")
        if Path(report) != (relative_out / "bots.json").resolve():
            raise SystemExit(report)
        if Path(flag) != (Path("work") / "talk.flag").resolve():
            raise SystemExit(flag)
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
    if judge("S1-keywords", base, ctx)["status"] != "pass":
        raise SystemExit(judge("S1-keywords", base, ctx))
    if judge("S2-probe", base, noisy)["status"] != "pass":
        raise SystemExit(f"S2-probe should pass the small JFR recording: {judge('S2-probe', base, noisy)}")
    s6 = dict(base)
    s6["quotaDenied"] = 4
    s6["futuresOpen"] = 0
    s6["tokensSpent"] = 7236
    s6["quotaLimit"] = 7200
    s6["reservedInFlight"] = 801
    s6["quotaExhaustedOffsetMillis"] = 60000
    s6["quotaExhaustedEpochMillis"] = 1_000_000
    s6_ctx = dict(ctx)
    s6_ctx["seconds"] = 120
    s6_ctx["mock_latency_ms"] = 300
    s6_ctx["mock_timestamps"] = [999.0]
    s6_ctx["token_usage"] = 'format: 1\nday: "2026-10-08"\nserver:\n  requests: 1\n'
    if judge("S6", s6, s6_ctx)["status"] != "pass":
        raise SystemExit(judge("S6", s6, s6_ctx))
    late = dict(s6_ctx)
    late["mock_timestamps"] = [1002.0]
    if judge("S6", s6, late)["status"] != "fail":
        raise SystemExit("S6 should fail a mock stamp after the latency slack")
    over = dict(s6)
    over["tokensSpent"] = 9000
    over["reservedInFlight"] = 100
    if judge("S6", over, s6_ctx)["status"] != "fail":
        raise SystemExit("S6 should fail when spend passes the in-flight reservation")
    early = dict(s6)
    early["quotaExhaustedOffsetMillis"] = 10000
    if judge("S6", early, s6_ctx)["status"] != "fail":
        raise SystemExit("S6 should fail when the cap is hit outside the middle of the window")
    if host_stall_reasons({"hostStall": {"invalid": False}}, 120.2, 120.0, 5000):
        raise SystemExit("a clean scenario was marked as a host stall")
    flagged = host_stall_reasons({"hostStall": {"invalid": True, "reasons": ["tick gap 48653 ms"]}}, 170.0, 169.9, 5000)
    if len(flagged) != 1 or "tick gap" not in flagged[0]:
        raise SystemExit(f"driver host stall was not reported: {flagged}")
    drift = host_stall_reasons({}, 418.0, 132.0, 5000)
    if len(drift) != 1 or "drifted" not in drift[0]:
        raise SystemExit(f"harness clock drift was not reported: {drift}")
    if host_stall_reasons({"hostStall": {"invalid": True}}, 418.0, 132.0, 0):
        raise SystemExit("--stall-ms 0 should turn the host stall check off")
    stall_self_check()
    print("SELF-CHECK OK")
    return 0


def stall_self_check() -> None:
    """Exit codes, the retry of fail and invalid, and the stall property passed to the server."""
    cases = {
        (): 0,
        ("pass",): 0,
        ("pass", "not-run"): 0,
        ("pass", "invalid"): 3,
        ("invalid", "invalid"): 3,
        ("fail", "invalid"): 1,
        ("invalid", "fail", "pass"): 1,
        ("fail",): 1,
    }
    for statuses, wanted in cases.items():
        got = exit_code(iter(statuses))
        if got != wanted:
            raise SystemExit(f"exit code for {list(statuses)} is {got}, expected {wanted}")
    for plan, wanted_calls, wanted_status in (
        (["pass"], 1, "pass"),
        (["invalid", "pass"], 2, "pass"),
        (["invalid", "invalid"], 2, "invalid"),
        (["fail", "pass"], 2, "pass"),
        (["fail", "invalid"], 2, "invalid"),
        (["not-run"], 1, "not-run"),
    ):
        calls = []

        def attempt(number: int, plan=plan, calls=calls) -> dict:
            calls.append(number)
            return {"status": plan[number - 1], "reasons": ["self-check"]}

        result = retry_once("S1", attempt)
        if calls != list(range(1, wanted_calls + 1)) or result["status"] != wanted_status:
            raise SystemExit(f"retry for {plan}: attempts {calls}, status {result['status']}")
    for stall_ms, wanted in ((1234, "-Dnexusai.load.stallMillis=1234"), (0, "-Dnexusai.load.stallMillis=0"),
                             (-5, "-Dnexusai.load.stallMillis=0")):
        command = java_command(stall_ms)
        if command.count(wanted) != 1 or command.index(wanted) > command.index("-jar"):
            raise SystemExit(f"server command for --stall-ms {stall_ms} lacks {wanted}: {command}")
    # Every plan again, with the recovery step that runs before the retry of an invalid attempt.
    for plan, ready, wanted_calls, wanted_status, wanted_recovers in (
        (["invalid", "pass"], "", [1, 2], "pass", 1),
        (["invalid", "pass"], "TPS 4.0 did not reach 19.8", [1], "invalid", 1),
        (["fail", "pass"], "", [1, 2], "pass", 0),
        (["pass"], "", [1], "pass", 0),
    ):
        events = []

        def attempt(number: int, plan=plan, events=events) -> dict:
            events.append(number)
            return {"status": plan[number - 1], "reasons": ["self-check"]}

        def recover(ready=ready, events=events) -> str:
            events.append("recover")
            return ready

        result = retry_once("S1", attempt, recover)
        calls = [item for item in events if item != "recover"]
        if calls != wanted_calls or result["status"] != wanted_status or events.count("recover") != wanted_recovers:
            raise SystemExit(f"retry with recovery for {plan}: {events}, status {result['status']}")
        if wanted_recovers and len(calls) == 2 and events.index("recover") > events.index(2):
            raise SystemExit(f"recovery ran after the retry: {events}")
        if not ready == "" and not any("not retried" in reason for reason in result["reasons"]):
            raise SystemExit(f"a server that did not recover gave no reason: {result['reasons']}")

    # The case from a real run: a stall leaves the 1-minute TPS low and the provider paused, so a
    # retry right away fails on TPS. With the recovery step the retry starts on a clean server.
    def scenario_after_stall(with_recovery: bool) -> dict:
        server = {"tps_1m": 1.93, "provider_paused": True}

        def attempt(number: int) -> dict:
            if number == 1:
                return {"status": "invalid", "reasons": ["tick gap 109068 ms not covered by tick work > 5000 ms"]}
            dirty = server["tps_1m"] < TPS_READY or server["provider_paused"]
            return {"status": "fail" if dirty else "pass", "reasons": ["TPS 1.93 < 19.8"] if dirty else []}

        readings = iter([1.93, 12.0, 19.9])

        def recover() -> str:
            server["provider_paused"] = False
            ready, tps = wait_for_tps(lambda: next(readings), clock=lambda: 0.0, sleep=lambda seconds: None)
            server["tps_1m"] = tps
            return "" if ready else "not ready"

        return execute_scenario(None, None, "S2-probe", None, [], 0, None, None, None, None, None, "", True,
                                recover=recover if with_recovery else None, measure=attempt)

    if scenario_after_stall(True)["status"] != "pass":
        raise SystemExit("a retry after a stall was judged on the stall")
    if scenario_after_stall(False)["status"] != "fail":
        raise SystemExit("self-check model of the stall is wrong")
    for text, wanted in (
        ("TPS from last 1m, 5m, 15m: *20.0, 19.98, 19.9", 20.0),
        ("TPS from last 1m, 5m, 15m: 1.93, 15.2, 18.0", 1.93),
        ("Unknown command", None),
    ):
        if parse_tps(text) != wanted:
            raise SystemExit(f"parse_tps({text!r}) is {parse_tps(text)}, expected {wanted}")
    now = [0.0]
    values = iter([5.0, None, 19.79, 19.8])
    slept = []
    ready = wait_for_tps(lambda: next(values), timeout_s=60, poll_s=5,
                         clock=lambda: now[0], sleep=lambda seconds: (slept.append(seconds), now.__setitem__(0, now[0] + seconds)))
    if ready != (True, 19.8) or slept != [5, 5, 5]:
        raise SystemExit(f"wait_for_tps gave {ready} after {slept}")
    now[0] = 0.0
    slept.clear()

    def broken() -> float:
        raise OSError("rcon closed")

    ready = wait_for_tps(broken, timeout_s=10, poll_s=5,
                         clock=lambda: now[0], sleep=lambda seconds: (slept.append(seconds), now.__setitem__(0, now[0] + seconds)))
    if ready != (False, None) or slept != [5, 5]:
        raise SystemExit(f"wait_for_tps with a broken read gave {ready} after {slept}")
    if execute_scenario(None, None, "S4", None, [], 0, None, None, None, None, None, "no bots", False,
                        measure=lambda number: {"status": "pass", "reasons": []})["status"] != "not-run":
        raise SystemExit("S4 without bots was run")

    # recover_after_stall sends /nai reload before it reads tps, and a server that died in the
    # stall (the Paper watchdog stops it after timeout-time) keeps the scenario invalid.
    class FakeProcess:
        def __init__(self, code=None):
            self.code = code

        def poll(self):
            return self.code

    class FakeSmoke:
        def __init__(self, chunks, tps="TPS from last 1m, 5m, 15m: 20.0, 20.0, 20.0", refuse=False, process=None):
            self.chunks, self.tps, self.refuse, self.process, self.sent = chunks, tps, refuse, process, []

        def rcon(self, host, port, password, command):
            self.sent.append(command)
            if self.refuse:
                if self.process is not None:
                    self.process.code = 1
                raise ConnectionRefusedError(111, "Connection refused")
            if command == "nai reload":
                self.chunks.append("[INFO]: [NexusAI] NexusAI reloaded.\n")
                return "reloaded"
            if command == "tps":
                return self.tps
            return ""

        @staticmethod
        def strip_colors(text):
            return text

    chunks = []
    fake = FakeSmoke(chunks)
    if recover_after_stall(fake, 0, chunks, FakeProcess()) != "" or fake.sent != ["nai reload", "tps"]:
        raise SystemExit(f"recovery did not reload before reading tps: {fake.sent}")
    dying = FakeProcess()
    refused = FakeSmoke([], refuse=True, process=dying)
    problem = recover_after_stall(refused, 0, [], dying)
    if "server stopped" not in problem:
        raise SystemExit(f"a server stopped by the watchdog was not reported: {problem!r}")
    alive_refused = FakeSmoke([], refuse=True)
    flag = {}
    if "does not answer RCON" not in recover_after_stall(alive_refused, 0, [], FakeProcess(), flag) or not flag:
        raise SystemExit("a refused reload was not reported as an unreachable server")
    if "server stopped" not in recover_after_stall(FakeSmoke([]), 0, [], FakeProcess(1)):
        raise SystemExit("a dead server was not reported before the reload")

    # run_plan: the recovery step reaches every scenario, a long stall that stopped the server
    # ends as invalid (exit 3) with the rest not run, and the exit code comes from finish_run.
    def plan_run(server_dies: bool, second: str = "pass") -> tuple[int, list, list, list]:
        process = FakeProcess()
        log = []
        smoke_fake = FakeSmoke(log, refuse=server_dies, process=process)
        summaries = []

        def run_one(scenario, recover):
            def attempt(number):
                if scenario == "S1" and number == 1:
                    return {"scenario": scenario, "status": "invalid",
                            "reasons": ["tick gap 90000 ms not covered by tick work > 5000 ms"]}
                return {"scenario": scenario, "status": second if scenario == "S1" else "pass", "reasons": []}

            return execute_scenario(None, None, scenario, process, log, 0, None, None, None, None, None, "", True,
                                    recover=recover, measure=attempt)

        results = []
        code = run_plan(smoke_fake, 0, log, process, ["baseline", "S1", "S2"], run_one, results, summaries.append)
        return code, results, summaries, smoke_fake.sent

    code, results, summaries, sent = plan_run(False)
    if code != 0 or [item["status"] for item in results] != ["pass", "pass", "pass"] or sent != ["nai reload", "tps"]:
        raise SystemExit(f"run_plan with a short stall: exit {code}, {[item['status'] for item in results]}, {sent}")
    if len(summaries) != 1 or summaries[0] is not results:
        raise SystemExit("run_plan did not write the summary once")
    code, results, summaries, sent = plan_run(True)
    statuses = [item["status"] for item in results]
    if code != 3 or statuses != ["pass", "invalid", "not-run"]:
        raise SystemExit(f"run_plan with a server stopped in the stall: exit {code}, {statuses}")
    if not any("server stopped" in reason for reason in results[1]["reasons"]):
        raise SystemExit(f"stopped server reason missing: {results[1]['reasons']}")
    code, results, summaries, sent = plan_run(False, second="fail")
    if code != 1:
        raise SystemExit(f"a real failure after recovery gave exit {code}")
    with tempfile.TemporaryDirectory() as tmp:
        spigot = Path(tmp) / "spigot.yml"
        write_spigot(spigot)
        if f"timeout-time: {SPIGOT_TIMEOUT_SECONDS}" not in spigot.read_text(encoding="utf-8") or SPIGOT_TIMEOUT_SECONDS < 600:
            raise SystemExit("spigot.yml does not raise the watchdog timeout")

    stall_wiring_self_check(FakeProcess, FakeSmoke)

    # finish_run gives the exit code of the whole run.
    for statuses, wanted in ((["pass", "invalid"], 3), (["pass", "fail", "invalid"], 1), (["pass"], 0)):
        got = finish_run([{"scenario": f"S{index}", "status": status} for index, status in enumerate(statuses)])
        if got != wanted:
            raise SystemExit(f"finish_run for {statuses} is {got}, expected {wanted}")

    # boot_process starts the server with the stall property.
    started = []

    class FakePopen:
        def __init__(self, command, **kwargs):
            started.append(command)

    real_popen = subprocess.Popen
    subprocess.Popen = FakePopen
    try:
        boot_process(Path("."), 4321)
    finally:
        subprocess.Popen = real_popen
    if len(started) != 1 or started[0].count("-Dnexusai.load.stallMillis=4321") != 1:
        raise SystemExit(f"boot_process did not pass --stall-ms to the server: {started}")

    # A harness line is one write, and a console line without a newline gets one.
    class Recorder:
        def __init__(self):
            self.writes = []

        def write(self, text):
            self.writes.append(text)

        def flush(self):
            pass

    recorder = Recorder()
    real_stdout = sys.stdout
    sys.stdout = recorder
    try:
        say("baseline pass mspt_mean=1.0 reasons=[]")
        read_log(iter(["[21:18:59 INFO]: Thread RCON Client shutting down"]), [])
        say("S1 pass")
    finally:
        sys.stdout = real_stdout
    if recorder.writes != ["baseline pass mspt_mean=1.0 reasons=[]\n",
                           "[21:18:59 INFO]: Thread RCON Client shutting down\n", "S1 pass\n"]:
        raise SystemExit(f"harness lines were split or joined: {recorder.writes}")
    detector = Path(__file__).resolve().parents[2] / "src" / "loadtest" / "java" / "io" / "github" / "neareststep" / "nexusai" / "load" / "HostStallDetector.java"
    if detector.is_file():
        match = re.search(r'PROPERTY = "([^"]+)"', detector.read_text(encoding="utf-8"))
        if match is None or match.group(1) != "nexusai.load.stallMillis":
            raise SystemExit(f"HostStallDetector.PROPERTY does not match the server command: {match and match.group(1)}")


def stall_wiring_self_check(FakeProcess, FakeSmoke) -> None:
    """N12 to N14 and the wiring of main: closed RCON, a server that stays silent, a scenario that
    never ends during a stall, the per-scenario step, and the server files."""
    quick = lambda read, stop: wait_for_tps(read, timeout_s=0, stop=stop)

    class EofSmoke(FakeSmoke):
        def __init__(self, chunks, fail_on, process=None):
            super().__init__(chunks)
            self.fail_on, self.process = fail_on, process

        def rcon(self, host, port, password, command):
            if command == self.fail_on:
                if self.process is not None:
                    self.process.code = 137
                raise EOFError("rcon connection closed")
            return super().rcon(host, port, password, command)

    # EOFError in the reload or in the tps read never escapes, and the server is marked unreachable.
    for fail_on, dies in (("nai reload", False), ("nai reload", True), ("tps", False), ("tps", True)):
        process = FakeProcess()
        chunks = []
        flag = {}
        problem = recover_after_stall(EofSmoke(chunks, fail_on, process if dies else None), 0, chunks, process,
                                      flag, wait=quick)
        if not problem or not flag:
            raise SystemExit(f"EOFError on {fail_on} (server dies: {dies}) gave {problem!r}, flag {flag}")
        if dies and "server stopped" not in problem:
            raise SystemExit(f"a server that died on {fail_on} was not reported as stopped: {problem!r}")

    reads = []
    if wait_for_tps(lambda: reads.append(1) or 20.0, timeout_s=60, sleep=lambda seconds: None,
                    stop=lambda: True) != (False, None) or reads:
        raise SystemExit("wait_for_tps kept reading after the server stopped")

    # A server that is alive but stops answering RCON after a stall: the rest is not-run, exit 3.
    process = FakeProcess()
    log = []
    silent = FakeSmoke(log, refuse=True)
    started = []

    def run_one(scenario, recover):
        started.append(scenario)

        def attempt(number):
            if scenario == "S1" and number == 1:
                return {"scenario": scenario, "status": "invalid", "reasons": ["tick gap 150000 ms"]}
            return {"scenario": scenario, "status": "pass", "reasons": []}

        return execute_scenario(None, None, scenario, process, log, 0, None, None, None, None, None, "", True,
                                recover=recover, measure=attempt)

    results = []
    code = run_plan(silent, 0, log, process, ["baseline", "S1", "S2", "S3"], run_one, results, lambda done: None)
    statuses = [item["status"] for item in results]
    if code != 3 or statuses != ["pass", "invalid", "not-run", "not-run"] or started != ["baseline", "S1"]:
        raise SystemExit(f"RCON lost after a stall: exit {code}, {statuses}, started {started}")

    # The server exits between scenarios without any stall: the rest is not-run.
    dying = FakeProcess()
    ran = []

    def run_and_die(scenario, recover):
        ran.append(scenario)
        dying.code = 1
        return {"scenario": scenario, "status": "pass", "reasons": [], "report": None}

    results = []
    code = run_plan(None, 0, [], dying, ["baseline", "S1"], run_and_die, results, lambda done: None)
    if ran != ["baseline"] or [item["status"] for item in results] != ["pass", "not-run"]:
        raise SystemExit(f"scenario ran after the server exited: {ran}, exit {code}")

    # SCENARIO_END wait: stall evidence makes the attempt invalid, no evidence keeps it a failure.
    if end_budget(argparse.Namespace(baseline_seconds=15, load_seconds=40, pool_seconds=3), "S1") < SPIGOT_TIMEOUT_SECONDS:
        raise SystemExit("the SCENARIO_END wait is shorter than the watchdog timeout")
    now = [0.0]

    def tick(seconds):
        now[0] += seconds

    def wait(chunks, states, code=None, timeout=10.0, jump=0.0):
        process = FakeProcess(code)
        now[0] = 0.0
        sequence = iter(states)
        clock_jumps = [jump]

        def clock():
            if clock_jumps and clock_jumps[0] and now[0] >= 1.0:
                now[0] += clock_jumps.pop()
            return now[0]

        return wait_scenario_end(process, chunks, 0, "S1", timeout, 5000, clock=clock, sleep=tick,
                                 state=lambda: next(sequence, "S"))

    if wait(["[INFO]: SCENARIO_END S1\n"], []) != (True, []):
        raise SystemExit("SCENARIO_END was not seen")
    ended, evidence = wait([], ["S", "T", "T", "S"])
    if ended or evidence != ["server process stopped"]:
        raise SystemExit(f"a stopped server was not taken as stall evidence: {ended}, {evidence}")
    if wait([], []) != (False, []):
        raise SystemExit("a scenario that never ends without a stall gave stall evidence")
    ended, evidence = wait([], [], jump=200.0)
    if ended or not evidence or not evidence[0].startswith("harness paused"):
        raise SystemExit(f"a harness pause was not taken as stall evidence: {evidence}")
    ended, evidence = wait(["[WARN]: SCENARIO_INVALID S1 test host stall: tick gap 150000 ms\n"], [], code=1)
    if ended or evidence != ["driver logged SCENARIO_INVALID"]:
        raise SystemExit(f"SCENARIO_INVALID before the server exited was not evidence: {evidence}")
    stalled = unfinished_attempt("S1", 1, FakeProcess(1), 640, ["server process stopped"])
    if stalled["status"] != "invalid" or not any("server exited with 1" in item for item in stalled["reasons"]):
        raise SystemExit(f"an unfinished attempt in a stall was not invalid: {stalled}")
    try:
        unfinished_attempt("S1", 1, FakeProcess(), 640, [])
    except RuntimeError as error:
        if "no SCENARIO_END within 640 s" not in str(error):
            raise SystemExit(f"wrong failure for an unfinished attempt: {error}")
    else:
        raise SystemExit("an unfinished attempt without a stall was not a failure")

    # scenario_runner: the recovery step, the baseline mean, and a reload when the limits change.
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        (work / "plugins" / "NexusAI").mkdir(parents=True)
        seen = []

        def fake_execute(smoke, args, scenario, process, log_chunks, rcon_port, work, out, jfc,
                         requests_path, baseline_mean, bot_note, bots_up, recover=None):
            seen.append((scenario, baseline_mean, recover))
            return {"scenario": scenario, "status": "pass", "reasons": [],
                    "report": {"mspt": {"mean": 1.5}} if scenario == "baseline" else None}

        chunks = []
        smoke_fake = FakeSmoke(chunks)
        run_one = scenario_runner(smoke_fake, None, FakeProcess(), chunks, 0, work, work, work / "load.jfc",
                                  work / "requests.jsonl", 1, "", False, execute=fake_execute)
        marker = object()
        other = "S3"
        if limits_for(other) == high_limits():
            raise SystemExit("S3 no longer has its own limits; pick another scenario for this check")
        run_one("baseline", marker)
        run_one(other, marker)
        if [item[2] for item in seen] != [marker, marker]:
            raise SystemExit("scenario_runner did not pass the recovery step to the scenario")
        if seen[1][1] != 1.5:
            raise SystemExit(f"the baseline mean did not reach the next scenario: {seen}")
        if smoke_fake.sent != ["nai reload"]:
            raise SystemExit(f"a change of limits did not reload once: {smoke_fake.sent}")

        class ServerSmoke:
            def __init__(self):
                self.calls = []

            def write_server(self, work, server_port, rcon_port, **kwargs):
                self.calls.append((server_port, rcon_port, kwargs.get("rcon_password")))

        server_smoke = ServerSmoke()
        jfc = write_server_files(server_smoke, work, 25565, 25575, 0)
        spigot = work / "spigot.yml"
        if server_smoke.calls != [(25565, 25575, "loadtest")] or not jfc.is_file():
            raise SystemExit(f"server files were not written: {server_smoke.calls}")
        if not spigot.is_file() or f"timeout-time: {SPIGOT_TIMEOUT_SECONDS}" not in spigot.read_text(encoding="utf-8"):
            raise SystemExit("write_server_files did not raise the watchdog timeout")


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
