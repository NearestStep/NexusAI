# Load test

Mode A runs without a game client. A synthetic viewer is `PlaceholderAPI.setPlaceholders(null, ...)`. Mode B adds mineflayer bots for context and `/nai talk`. CI runs mode A only, and only on `workflow_dispatch` or a `v*` tag.

The driver is a separate plugin, `NexusAI-LoadDriver`. It is not inside the shaded release jar. `./gradlew loadDriverJar` writes `build/loadtest/NexusAI-LoadDriver.jar`. The CI artifact glob `build/libs/NexusAI-*.jar` does not upload it. A second artifact, `NexusAI-LoadDriver`, is uploaded for the load job only.

## Run

Unit check, no Paper:

```bash
python3 .github/scripts/paper-load.py --self-check
./gradlew test loadDriverJar
```

Mode A, local Paper. The plugin jar and the driver jar are passed in separately. The default scenario list is `S1,S1-keywords,S2,S2-over,S2-probe,S3,S6,S-pool`.

```bash
python3 .github/scripts/paper-load.py \
  --version 1.20.6 \
  --plugins-dir build/libs \
  --driver-dir build/loadtest \
  --out load-out
```

Mode B, after mode A, on its own server so the bots do not move the mode A MSPT:

```bash
python3 .github/scripts/paper-load.py \
  --version 1.21.4 \
  --plugins-dir build/libs \
  --driver-dir build/loadtest \
  --scenario S4,S5 \
  --bots 20 \
  --out load-out-bots
```

`--bots` installs `mineflayer@4.39.0` with npm and connects offline-mode players `NaiBot01` … . The harness ops them over RCON. On Paper 26.2 the harness shims minecraft-data / prismarine-chunk when packet tables are still one release behind. S4, S5, and S-world are `not-run` (exit 0) when no bot is connected, so a mode A CI job stays green. Paper 26.2 needs Java 25.

Useful flags: `--mock-latency-ms` (default 300), `--baseline-seconds` (60), `--load-seconds` (120), `--pool-seconds` (3), `--skip-jfr`. A FAIL is retried once. Two FAILs fail the process. `not-run` does not.

### Downloads and cache

The Paper (or Folia) jar is checked against the sha256 that the Paper API publishes for that build. The vanilla server jar that paperclip needs (`META-INF/download-context` in the Paper jar, for example `mojang_26.2.jar`) is fetched by the harness with its sha256 and placed in `server/cache/` before the first boot, so paperclip does not download it and cannot fail the boot on a truncated file. Libraries ship inside the paperclip jar. PlaceholderAPI 2.12.3 is checked against a sha256 pinned in `paper-smoke.py` (`PAPI_SHA256`, the digest GitHub publishes for the release asset). The URL-keyed entry an older harness kept for it is deleted. A short read, a hash mismatch, or a transport error (a refused or reset connection, or an HTTP body that breaks off, such as `IncompleteRead` or `BadStatusLine`) deletes the partial file and downloads again, up to 5 attempts with pauses of 2, 4, 8, and 16 s, and then fails with the reason. A file is never used after a failed attempt. Verified files are cached by hash under `$NEXUSAI_SMOKE_CACHE/sha256/` (default: `nexusai-smoke-cache` in the system temp directory). A cached file that no longer matches its hash is removed and fetched again. A download without a published hash is cached by its URL with its sha256 saved beside it (`<entry>.sha256`); a `.jar` must also open as a zip whose entries pass their CRC check. An entry without that file, with a different hash, or that is not a valid jar is removed and fetched again, so an entry left by an older harness is fetched once more. A file placed in the cache directory under its own name (for example `mojang_26.2.jar` or `PlaceholderAPI-2.12.3.jar`) is used when its hash matches. The Paper API build list is fetched with 3 attempts, and each good answer is saved under `$NEXUSAI_SMOKE_CACHE/api/`. When the API cannot be reached, the last saved list is used, so a host whose cache already holds the list and the jars from an earlier run can boot without network access. A first run still needs the network. Every file that is used logs one line with its checked hash, for example `verified sha256 <hash> paper.jar (download, published sha256)`; the part in brackets says whether the file came from a download or the cache and whether the hash was published or saved from the first download. PlaceholderAPI gets its default `config.yml` with `cloud_enabled: false` and `check_updates: false`, so it does not reach the network on startup and a failed expansion cloud download cannot fail the log check. The same code serves `paper-smoke.py` and `paper-load.py`.

### Test host stalls

A scenario that ran on a stalled test host is reported as `invalid`, not as `pass` or `fail`. The driver times every window with `System.nanoTime()`, so a wall clock step cannot cut a window short. During the load window it records three values in `report.json` under `hostStall`:

- `maxTickGapMillis`: the longest time between two heartbeats minus the tick time Paper reported inside it. A slow tick is the server; a gap with no tick work is the host.
- `clockDriftMillis`: how far the wall clock and the monotonic clock moved apart. This catches a wall clock step (an NTP correction, a manual change, or a VM whose wall clock is set forward after a pause while its monotonic clock stood still). When a process or a whole VM is paused and both clocks keep running, as on Linux with `SIGSTOP` or a VM pause in tests, the drift stays near 0 and the pause shows up as `maxTickGapMillis` instead.
- `windowMillis` against `expectedWindowMillis`: how far the window ran past its length.

Any value above the threshold marks the scenario invalid, the driver logs `SCENARIO_INVALID <scenario> test host stall: ...`, and the harness also checks its own wall and monotonic clocks over the scenario. The threshold is `--stall-ms` (default 5000, env `LOAD_STALL_MS`), passed to the driver as `-Dnexusai.load.stallMillis`; `0` turns the check off. An invalid scenario is retried once like a FAIL, but first the harness runs `/nai reload`, which clears the provider pause and backoff the stall left behind, and waits up to 150 s until the 1-minute TPS from `tps` is at least 19.8 again, so the retry is not judged on the stall. When TPS does not come back, the scenario is not retried and stays invalid (`not retried: ...`). When the server has exited or does not answer RCON after the stall (a refused or closed connection), the scenario also stays invalid, the remaining scenarios are reported as `not-run`, and the run ends with exit code 3, not 1. The load server gets `settings.timeout-time: 600` in `spigot.yml` (the default is 60 s), so a test host pause up to 10 minutes does not let the Paper watchdog stop it, and the harness waits for `SCENARIO_END` for the window plus at least those 600 s. If a scenario still does not end (the pause was longer, or the server exited), the attempt is `invalid` only when there is evidence of a test host stall (the server process was seen stopped, the harness itself was paused longer than `--stall-ms`, or the driver logged `SCENARIO_INVALID` for it) and the stall lasted at least 90% of those 600 s. The harness measures the stall as the time it saw the process stopped plus its own pauses, or the driver's tick gap when that is longer. A shorter stall cannot explain a scenario that ran 600 s past its window, so a hang during a short host pause stays a failure. Without such evidence, or after a shorter stall, a scenario that does not end is a failure (exit 1), so a hung plugin is not hidden; it is not retried, and the remaining scenarios are reported as `not-run` because the server is hung or gone. When the retry after an invalid unfinished attempt does not end either, the scenario is a failure. With `--stall-ms 0` there is no stall evidence. The summary and `report.json` of every scenario are written in all these cases, also when the harness itself fails in a scenario (`harness error: ...`, exit 1, the rest `not-run`). Each attempt deletes the driver's `report.json` before it starts, so a report from an earlier scenario is never read. The load harness runs on Paper only: the TPS wait reads Paper's `TPS from last 1m, 5m, 15m` line, and Folia answers `tps` with a region health report that it does not read. LoadDriver is not Folia-supported either, so this retry is not available on Folia. Exit code: 1 when any scenario fails, otherwise 3 when any scenario is invalid, otherwise 0. Results from invalid scenarios should be rerun, not compared. In the result line an invalid scenario ends with `metrics alone: pass (not used)` (or `fail`): what the metrics would say without the stall, which does not count.

Known gap: a pause that falls inside one tick is reported by Paper as tick time, so it is subtracted as tick work and is not seen as a host stall. Such a scenario is judged on its metrics and can fail instead of being marked invalid; it is never marked invalid by mistake. A single long tick is not treated as a host stall on purpose, because a slow tick from the plugin must stay a `fail`.

The mock is the smoke endpoint. A load run asks it to sleep, append bodies to `mock-requests.jsonl`, and speak HTTP/1.1. The smoke default stays an instant HTTP/1.0 `pong`. The canary key is `sk-canary-load-7f3a9c2e1b`. Logs show `****2e1b`.

RCON command, four arguments:

```text
naiload <scenario> <rate> <seconds> <viewers>
```

The report is `plugins/NexusAI-LoadDriver/report.json`. The harness copies it to `load-out/report-<scenario>.json` and writes `load-out/report.json` plus `load-out/jfr-summary.txt`.

CI (`.github/workflows/build.yml`, job `load`) runs `S1,S1-keywords,S2,S2-probe,S3,S6,S-pool` on Paper 1.20.6 (Java 21) and 26.2 (Java 25), `timeout-minutes: 30`. S2-over stays local. Artifacts: `report.json`, `mock-requests.jsonl`, `jfr-summary.txt`. In `NearestStep/NexusAI` a pull request runs build and smoke, not this job. The load job still runs only on `workflow_dispatch` or a `v*` tag. In any other copy of the repository (a fork or the development copy) CI saves Actions minutes: a push runs no job, a pull request runs build and unit tests only and a newer commit cancels the older run, and the Paper/Folia smoke matrix runs on `workflow_dispatch` or on the next push to a pull request labeled `full-ci`.

## What is measured

Each scenario is a 60s baseline, then 120s of load. The first 10s of load are warmup and are not in the MSPT stats. Drain is 30s, or until the HTTP snapshot is idle. S-pool is a 3s burst with no warmup and a 15s drain. Baseline has no drain and no JFR.

MSPT is `ServerTickEndEvent#getTickDuration()` (paper-api 1.20.6). Placeholder work is scheduled with `runTaskTimer` earlier in the same tick, so the tick event sees it. If that event is missing, the driver logs `Refusing to enable` and does not enable. TPS is `Server#getTPS()[0]`. Main-thread call time is `System.nanoTime()` around `PlaceholderAPI.setPlaceholders` or, for S5, around the talk path the bots drive. JFR is `jcmd JFR.start` with only `jdk.ExecutionSample` at 10ms, started at `SCENARIO_MEASURE` and stopped at the end of the load window. The share is Server-thread samples whose stack contains `io.github.neareststep.nexusai.` and not `io.github.neareststep.nexusai.load.`.

The driver registers three context providers: `fast` (returns `coins ~12k`), `slow` (`Thread.sleep` 2s inside `provide()`), and `boom` (throws). It counts `provide()` calls where `Bukkit.isPrimaryThread()` is true.

Config for the run: pool off, prewarm off, `limits.error-log-cooldown-seconds: 30`, `api.max-tokens: 256`. S3 uses the 1.0.x caps (30/min, 1000/day, 10 and 200 per player) and leaves quotas off. Every other scenario raises those four request caps to 100000 and reloads. S1-keywords turns `quotas.enabled` on with `consumers.default.tokens-per-day: 0`, so nothing is rejected. S6 turns quotas on with that consumer cap at 7200 and `missing-usage: estimate`. Prompt ids are `load_hit` (no context), `load_ctx` (`context: all`), `load_kw` (`knowledge: [rules]`, `knowledge-select: keywords`), and `harbor` (dialogue). Other prompts stay on full selection. `load_hit` and `load_ctx` share prompt text, so startup logs one warning that they share a cache entry. That line is outside the scenario windows. `plugins/NexusAI/knowledge/rules.md` is a short file the harness writes for `load_kw`.

Keyword selection stays on the thread that already calls `KnowledgeComposer.prepare`. For a cached placeholder, including S1 and S1-keywords, that is the caller's thread. For `generate` it is a NexusAI thread inside the generation pipeline. It was left on that thread. The recorded 1.2.0 run measured S1 and S1-keywords. Neither missed the MSPT budget.

`nexusai-context-*` threads are started at enable (`ContextService.newWorkerPool` calls `prestartAllCoreThreads`). Before that, a scenario that never called a provider saw zero context threads even though the pool size was 2. The HTTP pool already kept its four threads. The regression is `ContextServiceTest.workerPoolPrestartsTheConfiguredDaemonThreads`.

## Pass rules

Every scenario: no `ERROR]: [NexusAI]`, no NexusAI stack frame outside the load-driver package, `nexusai-http-*` stayed at 4, `nexusai-context-*` stayed at 2, the canary is absent, no `OutOfMemoryError`.

| Scenario | Mode | Load | Pass |
|----------|------|------|------|
| baseline | A | idle 60s | MSPT reference. Not a gate. |
| S1 | A | 1000 cached resolutions/s (100 synthetic viewers × 10/s), cache warmed | mean MSPT − baseline ≤ 1.0 ms; p99 of one main-thread call ≤ 200 µs |
| S1-keywords | A | S1, prompt `load_kw`, `knowledge-select: keywords`, quotas on with a 0 cap | same gates as S1. Keyword selection runs on every cached resolve, including hits |
| S2 | A | 10 unique misses/s, mock latency 300 ms, each prompt resolved 3 times in one tick | ΔMSPT ≤ 2.0 ms; max tick ≤ 100 ms; TPS ≥ 19.8; JFR NexusAI share of Server-thread samples ≤ 2%; mock requests ≤ unique prompts; pool idle within 30s |
| S2-probe | A | S2, with an empty listener on every Nexus generation, provider-error, action, and moderation event | same gates as S2. The listeners are registered only for this scenario and removed when it finishes, so S2 itself is unchanged |
| S2-over | A | 50 unique misses/s, same latency and dedup | ΔMSPT ≤ 2.0 ms; queue depths stay inside the snapshot caps; no OOM. Queue growth is recorded, not failed, when the depths stay capped. |
| S3 | A | 200 misses/s at the default 30/min cap | ΔMSPT ≤ 2.0 ms; mock requests fit a tumbling 60s window of 30 + 1 (a sliding minute may see 60 when the window resets once); NexusAI warnings ≤ `seconds / 30 + 2` |
| S-pool | A | 40 unique misses per tick for 3s | at least one new rejection; the log contains `HTTP queue is full`; call p99 ≤ 50 ms; depths stay inside the caps; the pool drains |
| S6 | A | 50 `generate()` calls/s on the server thread for 120s. One call in five uses a new template (`Reply with the single word pong. case ` plus six digits, 44 characters). The rest repeat `Reply with the single word pong.` Quotas are on. `consumers.default.tokens-per-day` is 7200 | ΔMSPT ≤ 2.0 ms; p99 of the `generate()` call, not of the future, ≤ 200 µs; after today's tokens reach the cap, mock timestamps stop once the mock latency plus one second has passed; token overshoot ≤ the peak in-flight reservation seen while spend was still under the cap; every future is done within 30s of stop; `token-usage.yml` parses (`format: 1` and a day) |
| S4 | B | 20 bots, `cached_` with `context: all` | ΔMSPT ≤ 2.0 ms; `provide()` on the main thread = 0; the slow provider is suspended; a mock body contains `coins ~12k` inside `§§§ PLAYER INPUT §§§` … `§§§ END §§§`; `load-boom-secret` is not in a body |
| S5 | B | 20 bots, `/nai talk` every 3s, `dialogue.summary.enabled: true` | ΔMSPT ≤ 2.0 ms; no `Dialogue failed`; at least one talk command or bot reply |

S-pool is the overflow check. Fifty misses per second at 300 ms is about 15 calls in flight. The cap is 64 in flight and 64 waiting, so S2-over does not fill the pool. The old picture (an unbounded queue, or four blocking threads near 13 requests/s) does not describe this build.

S6 spends the cap near the middle of the 120s window when each unique call records about 12 tokens: 11 from the 44-character template (`ceil(44 / 4)`) and 1 from the mock reply `pong`. The reservation is those prompt tokens plus `api.max-tokens` 256, about 267, because the load config sets no system prompt and the `simple` format instruction is empty. A cache hit does not reserve. An in-flight join of the repeated template does not reserve. The first copy of that repeated template does. Admission can refuse a call while other calls are still reserved and then allow a later call, until tokens spent reach 7200. The HTTP-stop check uses that spent total, not the first `QUOTA_EXCEEDED`. The mock writes its journal time after the latency sleep, so a call sent before the cap can still appear for about that latency afterwards. The judge allows one extra second. Overshoot is `max(0, tokens spent − cap)` and must be no larger than the peak of open HTTP leaders times the reservation, sampled while spend was still under the cap.

The JFR 2% line is a gate only when the recording has at least 200 Server-thread samples. `jdk.ExecutionSample` hits a thread only while it is running. On an idle or lightly loaded server the server thread is parked, and a 110s recording can contain 2 to 5 samples. A share computed from that handful is noise: 1 of 5 is 20% and is not a measurement of a 2% budget. Below that sample count, treat the JFR share as not enough data. Do not fail the scenario on it. Judge the run by the tick delta, the max tick, and the p99 of the placeholder or talk call, which the plugin records itself.

`RateLimiter` is a tumbling 60s window opened at construction, not a sliding minute. S3's judge accepts a series when some alignment keeps every 60s bucket at or under 31. Thirty requests, a reset, then thirty more, is a pass. Sixty requests at one instant is a fail. `LOCAL_LIMIT` is not logged, so S3's warning count can be 0 and still pass.

## Recorded run — 1.2.0

Manual run, 2026-10-09. Commit 4446cbb, Paper 1.21.8 build 60, OpenJDK 25.0.4.1 (Temurin), 8 × Intel Xeon vCPU, Debian 13, kernel 6.12. Mock provider, latency 300 ms; baseline 60 s, load 120 s. S2-over and S-pool were not part of this run and are not recorded. The tables under Recorded run — 1.1.1 and Recorded run — 1.1.0, below, are not 1.2.0 results.

### Mode A

No players. Baseline MSPT 0.68 ms, TPS min 20.0.

| Scenario | ΔMSPT | tick max | call p99 | notes | result |
|----------|-------|----------|----------|-------|--------|
| S1 | −0.19 | 6.8 ms | 37 µs | 119 900 resolutions, 1 request | pass |
| S1-keywords | +0.05 | 128 ms | 101 µs | keyword knowledge on | pass |
| S2 | −0.35 | 26 ms | 273 µs | 1199 requests = 1199 unique prompts | pass |
| S2-over | | | | not recorded | |
| S2-probe | −0.37 | 10 ms | 145 µs | events probe attached | pass |
| S3 | −0.25 | 23 ms | 164 µs | 23 990 resolutions, 90 requests | pass |
| S6 | −0.37 | 12 ms | 77 µs | 7209/7200 tokens, exhausted at 58 % | pass |
| S-pool | | | | not recorded | |

### Mode B

20 mineflayer bots. Baseline MSPT 1.88 ms, TPS min 20.0.

| Scenario | ΔMSPT | tick max | call p99 | notes | result |
|----------|-------|----------|----------|-------|--------|
| S4 | +0.01 | 31 ms | 403 µs | 4798 resolutions, 0 provide on main | pass |
| S5 | −0.17 | 119 ms | — | 820 talk commands, 0 provide on main | pass |

## Recorded run — 1.1.1

2026-10-04, NexusAI 1.1.1, Java 21.0.10, 4 vCPU Intel Xeon, mock latency 300 ms. Mode A and mode B were separate servers, so the bots do not sit inside the mode A MSPT. Both exited 0. On every scenario `nexusai-http-*` stayed at 4 and `nexusai-context-*` stayed at 2. No `ERROR]: [NexusAI]`. The canary is masked as `****2e1b`.

`jcmd` resolves `settings` and `filename` from the Paper process directory and still exits 0 when the file is missing. A relative `load.jfc` therefore produced no recording, and S2 failed with "JFR produced no Server thread samples" before any sample existed. The harness now passes absolute paths and treats that `jcmd` message as a failed recording. The 2% Server-thread share is a fail only when the recording has at least 200 Server samples, which is the rule in the pass table above. Fewer samples are listed here and are not a gate.

### Mode A — Paper 1.20.6 build 151

No game client. Baseline mean **0.1496 ms** (p95 0.405, p99 1.156, max 4.313, 1202 samples). TPS min 20.018.

| Scenario | Result | ΔMSPT | Other numbers |
|----------|--------|-------|----------------|
| baseline | pass, attempt 1 | — | mean 0.1496 ms, max 4.313 ms |
| S1 | pass, attempt 1 | +0.055 ms | 119900 resolutions, 1 mock request (the warm), call p99 8867 ns (8.9 µs), call max 1.10 ms, MSPT max 45.6 ms, JFR 6/12 Server samples (50%, under 200 samples, not a gate) |
| S2 | pass, attempt 2 | −0.073 ms | attempt 1 failed: max tick 190.2 ms (JFR 0/12, so the 2% line was not the cause). Attempt 2: mock 1199 = unique 1199, resolutions 3597 (three copies, one HTTP), max in flight 3, max tick 0.58 ms, TPS min 20.0, call p99 43 µs, drain 250 ms, JFR 0/7 |
| S2-over | pass, attempt 1 | −0.045 ms | mock 5997 = unique 5997 (~50 req/s), resolutions 17991, max in flight 15, wait queue 0, rejected 0, max tick 0.63 ms, drain 300 ms, JFR 1/4 = 25% (not a gate). The queue did not grow. |
| S3 | pass, attempt 1 | −0.032 ms | 23990 resolutions, mock 90, warnings 0 (cap 6), max in flight 30, JFR 0/4 |
| S-pool | pass, attempt 1 | +0.015 ms (not a gate) | rejected delta 1720, in flight peaked at 64, HTTP wait peaked at 64, worker queue 0, threads stayed 4 and 2, call p99 16 µs, mock 640, drain 550 ms, log said `HTTP queue is full`, JFR 0/0 on the 3s window |

S2-over at 50 req/s and 300 ms peaked at 15 in flight, under the cap of 64. S-pool is the case that fills the cap.

Server-thread samples on this host were 0 to 12 over a 110s recording. That is under the 200-sample floor, so S1's 6/12 and S2-over's 1/4 are not a 2% measurement. The MSPT deltas are the evidence that the main thread stayed cheap.

### Mode B — Paper 1.21.4 build 232, 20 mineflayer 4.33.0 bots

Separate boot. Baseline mean **0.8763 ms** with the bots already online (p95 1.323, p99 1.983, max 6.213, 1199 samples). ΔMSPT is against that baseline, not against the mode A number.

| Scenario | Result | ΔMSPT | Other numbers |
|----------|--------|-------|----------------|
| S4 | pass, attempt 1 | +0.233 ms | 20 players, 4798 resolutions, `provide()` on the main thread 0, slow suspended (10 timeouts), fast still ok (47 calls, 0 timeouts), boom suspended, call p99 243 µs, MSPT max 139.5 ms (not an S4 gate), JFR 7/120 = 5.83% (under 200 samples), threads 4 and 2, 4 mock requests in the S4 window |
| S5 | pass, attempt 1 | +0.022 ms | 820 `/nai talk` commands, 1180 mock requests, no `Dialogue failed`, drain settled in 2.95s, fast 784 calls and not suspended, `provide()` on the main thread 0, JFR 1/117 = 0.85% |

Across both scenarios, 806 mock bodies contained `coins ~12k` between `§§§ PLAYER INPUT §§§` and `§§§ END §§§`. None contained `load-boom-secret`. S4's own window sent 4 HTTP calls; the rest of the wrapped bodies are from S5, which also requests `context: all`.

S4 does not gate on JFR. 120 Server samples is still under the 200-sample floor, so 5.83% is not a 2% budget. S5 was 0.85% on 117 samples.

After S5 had already been judged, stopping the server logged `Failed to schedule a command result` (16 lines). The scheduler was going down while a few talk replies were still being delivered. Those lines are outside the scenario window.

## Recorded run — 1.1.0

2026-10-03, Java 21.0.10, 4 vCPU Intel Xeon, mock latency 300 ms. Mode A and mode B were separate servers, so the bots do not sit inside the mode A MSPT. Both exited 0. On every scenario `nexusai-http-*` stayed at 4 and `nexusai-context-*` stayed at 2. No `ERROR]: [NexusAI]`, no NexusAI stack trace, canary masked as `****2e1b`.

### Mode A — Paper 1.20.6 build 151

No game client. Baseline mean **0.0953 ms** (p95 0.263, p99 0.408, max 0.950, 1199 samples). TPS min 20.018.

| Scenario | Result | ΔMSPT | Other numbers |
|----------|--------|-------|----------------|
| baseline | pass, attempt 1 | — | mean 0.0953 ms, max 0.950 ms |
| S1 | pass, attempt 1 | +0.030 ms | 119900 resolutions, 1 mock request (the warm), call p99 6451 ns (6.5 µs), call max 10.6 ms, MSPT max 17.6 ms, JFR 0/2 Server samples |
| S2 | pass, attempt 2 | −0.041 ms | attempt 1 failed: max tick 137.2 ms and JFR 1/5 = 20%. Attempt 2: mock 1199 = unique 1199, resolutions 3597 (three copies, one HTTP), max in flight 3, max tick 0.23 ms, TPS min 19.996, call p99 22 µs, drain 250 ms, JFR 0/5 |
| S2-over | pass, attempt 1 | −0.027 ms | mock 5997 = unique 5997 (~50 req/s), resolutions 17991, max in flight 15, wait queue 0, rejected 0, max tick 0.21 ms, drain 300 ms, JFR 0/3. The queue did not grow. |
| S3 | pass, attempt 1 | −0.009 ms | 23990 resolutions, mock 90 as three bursts of 30 about 60s apart, warnings 0 (cap 6), max in flight 10, JFR 0/3 |
| S-pool | pass, attempt 1 | +0.081 ms (not a gate) | rejected delta 1747, in flight peaked at 64, HTTP wait peaked at 64, worker queue 0, threads stayed 4 and 2, call p99 20 µs, mock 613, drain 550 ms, log said `HTTP queue is full` |

S2-over at 50 req/s and 300 ms sits near 15 in flight, under the cap of 64. That is well above the old ~13 req/s of four threads blocked on HTTP. S-pool is the case that fills the cap: further calls fail immediately with `HTTP queue is full` and do not add threads.

JFR Server-thread samples on this idle host were 2 to 5 over a 110s recording. `jdk.ExecutionSample` only hits a thread while it is running, and the server thread was parked. One NexusAI frame in five samples is 20% and failed S2's first attempt. Zero in five is 0% and passed the retry. Neither figure is a stable measurement of a 2% budget. The MSPT deltas above are the evidence that the main thread stayed cheap.

S3's 90 mock requests are three tumbling windows, not 90 in one minute. A sliding minute that straddles a reset sees 60, which the judge allows. A burst of 60 at one instant still fails.

### Mode B — Paper 1.21.4 build 232, 20 mineflayer 4.33.0 bots

Separate boot. Baseline mean **0.6774 ms** with the bots already online (p95 0.933, p99 1.341, max 23.0, 1199 samples). ΔMSPT is against that baseline, not against the mode A number.

| Scenario | Result | ΔMSPT | Other numbers |
|----------|--------|-------|----------------|
| S4 | pass, attempt 1 | +0.139 ms | 20 players, 4798 resolutions, `provide()` on the main thread 0, slow suspended (10 timeouts), fast still ok (50 calls, 0 timeouts), boom suspended, call p99 179 µs, JFR 4/104 = 3.85%, threads 4 and 2 |
| S5 | pass, attempt 1 | −0.032 ms | 820 `/nai talk` commands, 1180 mock requests, no `Dialogue failed`, drain settled in 3.2s, fast 784 calls and not suspended, `provide()` on the main thread 0, JFR 1/89 = 1.12% |

Across both scenarios, 806 mock bodies contained `coins ~12k` inside `§§§ PLAYER INPUT §§§` … `§§§ END §§§`. None contained `load-boom-secret`. S4's own window sent 4 HTTP calls; the rest of the wrapped bodies are from S5, which also requests `context: all`.

S4 does not gate on JFR. With bots online the server thread was sampled 104 times, and 4 of those stacks were NexusAI (3.85%). That is above the 2% line used for S2. S5 was 1.12% on 89 samples. See the open question below.

After S5 had already been judged, stopping the server logged `Failed to schedule a command result` (16 lines). The scheduler was going down while a few talk replies were still being delivered. Those lines are outside the scenario window.

## Fixes found by the run

- `ContextService.newWorkerPool` prestarts the two `nexusai-context-*` threads. A scenario that never calls a provider still sees the configured pool. Regression: `workerPoolPrestartsTheConfiguredDaemonThreads`.
- A timeout that fires before `provide()` has started is a full worker pool, not a strike. It does not suspend that provider. A call that has started and overruns its timeout is interrupted, so a `Thread.sleep` in one provider does not pin a worker for the rest of the sleep. Regression: `timeoutBeforeStartDoesNotSuspendTheWaitingProvider`. Before this, S4 suspended `fast` as well as `slow` and `boom`, because both workers sat inside the slow provider's 2s sleep after the 200 ms timeout.
- The load mock speaks HTTP/1.1. The smoke mock stays HTTP/1.0. HTTP/1.0 connection close under a few dozen in-flight calls was answered by Java HttpClient as `header parser received no bytes`, which paused the provider. Miss tokens also keep climbing across a retry, so the second attempt is not a cache hit of the first.
- S3's judge matches the tumbling 60s window. Thirty, a reset, then thirty, passes. Sixty at one instant fails.
- Mode B counts `NaiBot` join lines. Paper's RCON `list` text did not carry the names, and the first bot boot timed out after all 20 had joined.

### Paper 26.2 — QA sanity, 15 bots, Java 25

2026-10-03. Not the LoadDriver S1–S3 job. Fifteen bots. Each tick, each bot resolves four placeholders: cached plain, cached per player, cached with context, and one `generate_`. Mock latency 300 ms, `cache.ttl` 300. Phases of 60s load after 30s idle: without NexusAI (cold JVM), with NexusAI 1.1.0, without NexusAI again (warm JVM). `provide()` on the main thread was 0. Mock requests in the NexusAI phase: 215. No errors.

| Scenario | Mode | Load | Result | Δ mean tick | Other numbers |
|----------|------|------|--------|-------------|---------------|
| QA sanity | B | 15 bots, 4 placeholders per bot per tick, 60s | pass, within noise | +0.55 ms vs warm control (1.041 vs 0.488). −0.13 ms vs the cold control (1.170) | TPS 20.0. S2 threshold 2.0 ms. p95 1.802 ms, p99 5.409 ms, max tick 17.4 ms (warm control max 33.2). Placeholder call avg 34.5 µs, p99 243 µs, max 15.6 ms. The spread between the two idle controls is about the same size as the delta |
| S4 | B | spec: 20 bots, `cached_` with `context: all`. This row is the 15-bot QA stand-in above, which includes a cached context placeholder | pass against the 2.0 ms / TPS 20 gates | +0.55 ms | `provide()` on the main thread 0. Not the 20-bot LoadDriver S4, so slow-provider suspend and the `coins ~12k` body check are not claimed here |
| S5 | B | spec: 20 bots, `/nai talk` every 3s, summaries on | not-run on 26.2 | — | This QA pass did not drive `/nai talk` on 26.2. The 20-bot S5 numbers are the Paper 1.21.4 row above |

JFR was not the gate for the 26.2 sample. The 2% rule applies only with about 200–500 Server-thread samples or more. With fewer samples, judge by the tick delta, the max tick, and the p99 call time. Those are the numbers in the table.

The 2% JFR budget is unsettled on the earlier recordings for the same reason. An idle Paper 1.20.6 server produced a handful of Server-thread samples, so one NexusAI frame is 20%. A Paper 1.21.4 server with 20 bots produced 104 samples in S4 and a 3.85% NexusAI share, which is still under the 200-sample floor and would be noise if S4 used that budget. MSPT deltas in both of those runs stayed under 0.14 ms. The thresholds in the pass table are the starting values from the spec.
