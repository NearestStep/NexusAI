# Migration to 1.3.1

Replace the previous jar with `NexusAI-1.3.1.jar` on Paper or Purpur 1.20.6 through 26.2, or Folia 1.21.8, 1.21.11, 26.1.2, or 26.2, Java 21+ (Java 25 for every 26.x server: Paper, Purpur, and Folia). Stop the server, swap the jar, start it. You do not edit `config-version`.

## What is written for you

On startup and on `/nai reload`, when `plugins/NexusAI/config.yml` is valid YAML, missing keys are copied from the jar default. The log line is `Added missing config keys: …`. The same start writes **one** backup: `config.yml.bak`, or `config.yml.bak.<timestamp>` when that backup already exists. `config-version` stays **2**. A value you already set is not replaced, and comments you wrote stay.

From 1.3.0 three keys are appended:

| Key | Value written |
|-----|----------------|
| `startup-banner` | `true` |
| `context.log-timeouts` | `true` |
| `dialogue.log-stop-budget` | `true` |

From 1.2.x straight to 1.3.1, 22 keys are appended in that one start: the 19 keys from [Migration to 1.3.0](migration-1.3.0.md), `startup-banner`, `context.log-timeouts`, and `dialogue.log-stop-budget`.

All 22 keys appended on an upgrade from 1.2.x:

| Key | Value written |
|-----|----------------|
| `cache.enabled` | `true` |
| `cache.max-memory-bytes` | `0` |
| `latency.streaming.enabled` | `false` |
| `latency.streaming.chars-per-chunk` | `48` |
| `latency.streaming.chunk-delay-millis` | `40` |
| `latency.thinking-indicator.enabled` | `false` |
| `latency.timeout.enabled` | `false` |
| `latency.timeout.millis` | `15000` |
| `world-context.enabled` | `false` |
| `world-context.radius` | `2` |
| `world-context.max-blocks` | `125` |
| `world-context.max-block-types` | `16` |
| `world-context.max-inventory-items` | `36` |
| `world-context.max-chars` | `400` |
| `world-context.cooldown-millis` | `250` |
| `plugin-api.response-validation.enabled` | `true` |
| `plugin-api.response-validation.on-invalid` | `retry` |
| `plugin-api.response-validation.max-retries` | `1` |
| `plugin-api.response-validation.deliver-on-region` | `false` |
| `startup-banner` | `true` |
| `context.log-timeouts` | `true` |
| `dialogue.log-stop-budget` | `true` |

## startup-banner

- `true` (default, and what is appended) prints the full console banner, the same as 1.3.0.
- `false` prints no banner. NexusAI logs one line when enable work finishes: `[NexusAI] NexusAI enabled. Ready in N ms`.
- Any other value logs one warning on startup and on `/nai reload` and uses `true`. The words from 1.3.0 development builds are read without a warning: `full` as `true`, and `compact` and `off` as `false` (`compact` asked for less than the full banner, so it gets the one-line output).

## context.log-timeouts

- `true` (default, and what is appended): a context provider call that overruns its limit logs one INFO line, `Context provider '<id>' timed out after <N> ms and was skipped.` Per provider there is at most one line per `limits.error-log-cooldown-seconds` (30); the next line adds `<K> more timeouts since the last notice.` (`1 more timeout` for one). The line is written on the `nexusai-scheduler` thread, never on the main thread or a region thread.
- `false`: no line for a single timeout. The timeout still counts toward `context.suspend-after-timeouts`, the `Context provider '<id>' suspended until ...` warning is still logged, and `/nai status` still counts the failed call (the field labelled `timeouts` counts timeouts and exceptions).
- After `/nai reload`, `<K>` is counted again from zero. Details: [Context provider timeouts](limitations.md#context-provider-timeouts).

## dialogue.log-stop-budget

- `true` (default, and what is appended): when the server stops while the first off-to-on load of `dialogue-memory.yml` is still running, stop logs one INFO line that starts with `Stop ran during the first dialogue memory load and used the stop budget:`. A normal stop does not log it.
- `false` turns the line off. The `Did not save ...` warning is not affected. Details: [When is `dialogue-memory.yml` written](faq.md#when-is-dialogue-memoryyml-written-and-what-happens-to-expired-transcripts).

## How to roll back

1. Stop the server.
2. Remove the 1.3.1 jar and put the 1.3.0 jar back in `plugins/`.
3. Leave `config.yml` in place. 1.3.0 ignores `startup-banner`, `context.log-timeouts`, and `dialogue.log-stop-budget` and always prints the full banner. To drop the appended keys from the file, restore the `config.yml.bak` written by the 1.3.1 start (the log line `Backed up config.yml to …` names it).
