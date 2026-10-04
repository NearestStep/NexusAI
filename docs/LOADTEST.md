# Load test

Mode A runs without a game client. A synthetic viewer is `PlaceholderAPI.setPlaceholders(null, ...)`. Mode B adds mineflayer bots for context and `/nai talk`. CI runs mode A only, and only on `workflow_dispatch` or a `v*` tag.

The driver is a separate plugin, `NexusAI-LoadDriver`. It is not inside the shaded release jar. `./gradlew loadDriverJar` writes `build/loadtest/NexusAI-LoadDriver.jar`. The CI artifact glob `build/libs/NexusAI-*.jar` does not upload it. A second artifact, `NexusAI-LoadDriver`, is uploaded for the load job only.

## Run

Unit check, no Paper:

```bash
python3 .github/scripts/paper-load.py --self-check
./gradlew test loadDriverJar
```

Mode A, local Paper. The plugin jar and the driver jar are passed in separately. The default scenario list is `S1,S2,S2-over,S3,S-pool`.

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

`--bots` installs `mineflayer@4.33.0` with npm and connects offline-mode players `NaiBot01` … . The harness ops them over RCON. S4 and S5 are `not-run` (exit 0) when no bot is connected, so a mode A CI job stays green. Paper 26.2 needs Java 25.

Useful flags: `--mock-latency-ms` (default 300), `--baseline-seconds` (60), `--load-seconds` (120), `--pool-seconds` (3), `--skip-jfr`. A FAIL is retried once. Two FAILs fail the process. `not-run` does not.

The mock is the smoke endpoint. A load run asks it to sleep, append bodies to `mock-requests.jsonl`, and speak HTTP/1.1. The smoke default stays an instant HTTP/1.0 `pong`. The canary key is `sk-canary-load-7f3a9c2e1b`. Logs show `****2e1b`.

RCON command, four arguments:

```text
naiload <scenario> <rate> <seconds> <viewers>
```

The report is `plugins/NexusAI-LoadDriver/report.json`. The harness copies it to `load-out/report-<scenario>.json` and writes `load-out/report.json` plus `load-out/jfr-summary.txt`.

CI (`.github/workflows/build.yml`, job `load`) runs `S1,S2,S3,S-pool` on Paper 1.20.6 (Java 21) and 26.2 (Java 25), `timeout-minutes: 30`. Artifacts: `report.json`, `mock-requests.jsonl`, `jfr-summary.txt`. A pull request runs build and smoke, not this job.

## What is measured

Each scenario is a 60s baseline, then 120s of load. The first 10s of load are warmup and are not in the MSPT stats. Drain is 30s, or until the HTTP snapshot is idle. S-pool is a 3s burst with no warmup and a 15s drain. Baseline has no drain and no JFR.

MSPT is `ServerTickEndEvent#getTickDuration()` (paper-api 1.20.6). Placeholder work is scheduled with `runTaskTimer` earlier in the same tick, so the tick event sees it. If that event is missing, the driver logs `Refusing to enable` and does not enable. TPS is `Server#getTPS()[0]`. Main-thread call time is `System.nanoTime()` around `PlaceholderAPI.setPlaceholders` or, for S5, around the talk path the bots drive. JFR is `jcmd JFR.start` with only `jdk.ExecutionSample` at 10ms, started at `SCENARIO_MEASURE` and stopped at the end of the load window. The share is Server-thread samples whose stack contains `io.github.neareststep.nexusai.` and not `io.github.neareststep.nexusai.load.`.

The driver registers three context providers: `fast` (returns `coins ~12k`), `slow` (`Thread.sleep` 2s inside `provide()`), and `boom` (throws). It counts `provide()` calls where `Bukkit.isPrimaryThread()` is true.

Config for the run: pool off, prewarm off, `limits.error-log-cooldown-seconds: 30`. S3 uses the 1.0.x caps (30/min, 1000/day, 10 and 200 per player). Every other scenario raises those four to 100000 and reloads. Prompt ids are `load_hit` (no context), `load_ctx` (`context: all`), and `harbor` (dialogue). `load_hit` and `load_ctx` share prompt text, so startup logs one warning that they share a cache entry. That line is outside the scenario windows.

`nexusai-context-*` threads are started at enable (`ContextService.newWorkerPool` calls `prestartAllCoreThreads`). Before that, a scenario that never called a provider saw zero context threads even though the pool size was 2. The HTTP pool already kept its four threads. The regression is `ContextServiceTest.workerPoolPrestartsTheConfiguredDaemonThreads`.

## Pass rules

Every scenario: no `ERROR]: [NexusAI]`, no NexusAI stack frame outside the load-driver package, `nexusai-http-*` stayed at 4, `nexusai-context-*` stayed at 2, the canary is absent, no `OutOfMemoryError`.

| Scenario | Mode | Load | Pass |
|----------|------|------|------|
| baseline | A | idle 60s | MSPT reference. Not a gate. |
| S1 | A | 1000 cached resolutions/s (100 synthetic viewers × 10/s), cache warmed | mean MSPT − baseline ≤ 1.0 ms; p99 of one main-thread call ≤ 200 µs |
| S2 | A | 10 unique misses/s, mock latency 300 ms, each prompt resolved 3 times in one tick | ΔMSPT ≤ 2.0 ms; max tick ≤ 100 ms; TPS ≥ 19.8; JFR NexusAI share of Server-thread samples ≤ 2%; mock requests ≤ unique prompts; pool idle within 30s |
| S2-over | A | 50 unique misses/s, same latency and dedup | ΔMSPT ≤ 2.0 ms; queue depths stay inside the snapshot caps; no OOM. Queue growth is recorded, not failed, when the depths stay capped. |
| S3 | A | 200 misses/s at the default 30/min cap | ΔMSPT ≤ 2.0 ms; mock requests fit a tumbling 60s window of 30 + 1 (a sliding minute may see 60 when the window resets once); NexusAI warnings ≤ `seconds / 30 + 2` |
| S-pool | A | 40 unique misses per tick for 3s | at least one new rejection; the log contains `HTTP queue is full`; call p99 ≤ 50 ms; depths stay inside the caps; the pool drains |
| S4 | B | 20 bots, `cached_` with `context: all` | ΔMSPT ≤ 2.0 ms; `provide()` on the main thread = 0; the slow provider is suspended; a mock body contains `coins ~12k` inside `§§§ PLAYER INPUT §§§` … `§§§ END §§§`; `load-boom-secret` is not in a body |
| S5 | B | 20 bots, `/nai talk` every 3s, `dialogue.summary.enabled: true` | ΔMSPT ≤ 2.0 ms; no `Dialogue failed`; at least one talk command or bot reply |

S-pool is the overflow check. Fifty misses per second at 300 ms is about 15 calls in flight. The cap is 64 in flight and 64 waiting, so S2-over does not fill the pool. The old picture (an unbounded queue, or four blocking threads near 13 requests/s) does not describe this build.

The JFR 2% line is a gate only when the recording has at least 200 Server-thread samples. `jdk.ExecutionSample` hits a thread only while it is running. On an idle or lightly loaded server the server thread is parked, and a 110s recording can contain 2 to 5 samples. A share computed from that handful is noise: 1 of 5 is 20% and is not a measurement of a 2% budget. Below that sample count, treat the JFR share as not enough data. Do not fail the scenario on it. Judge the run by the tick delta, the max tick, and the p99 of the placeholder or talk call, which the plugin records itself.

`RateLimiter` is a tumbling 60s window opened at construction, not a sliding minute. S3's judge accepts a series when some alignment keeps every 60s bucket at or under 31. Thirty requests, a reset, then thirty more, is a pass. Sixty requests at one instant is a fail. `LOCAL_LIMIT` is not logged, so S3's warning count can be 0 and still pass.

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
