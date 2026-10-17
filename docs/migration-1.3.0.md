# Migration to 1.3.0

Replace the previous jar with `NexusAI-1.3.0.jar` (or a `1.3.0-SNAPSHOT` build) on Paper or Purpur 1.20.6 through 26.2, or Folia 1.21.8, 1.21.11, 26.1.2, or 26.2, Java 21+ (Java 25 for Paper/Folia 26.x). Stop the server, swap the jar, start it. The first start appends the new keys. You do not edit `config-version`.

## What is written for you

On startup and on `/nai reload`, when `plugins/NexusAI/config.yml` is valid YAML, missing keys are copied from the jar default. The log line is:

`Added missing config keys: …`

The same start writes **one** backup: `config.yml.bak`, or `config.yml.bak.<timestamp>` when that backup already exists. The path is logged. A second start that finds every new key already present appends nothing and does not write another backup.

`config-version` stays **2**. No existing value changes meaning. A value you already set is not replaced.

Keys appended when they are absent:

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

Comments you already wrote stay.

With the defaults, `generateJson` behaviour matches 1.2.0: one correction retry, then `INVALID_JSON` with the fallback text, and the future still completes on a NexusAI thread. Set `on-invalid: fallback` to skip the correction retry. Set `enabled: false` to skip local schema and allowlist checks (JSON is still extracted when possible). Set `deliver-on-region: true` (or `ResponseValidation.deliverOnRegion(true)` on the request) only when you want the future to complete on the player's region thread.

`API_VERSION` stays **3**. New types are additive: `ResponseValidation`, `InvalidJsonAction`, `JsonSchema.withActionAllowlist`, and `GenerationRequest.Builder.responseValidation`.

## What does not change

- `prompts.yml`, `pool.yml`, and `usage.yml` stay on their current versions.
- The appended `latency.*` and `world-context.*` features default to off, and `cache.max-memory-bytes: 0` keeps the old `cache.max-size` limit, so behaviour matches 1.2.0 until you enable them.

## How to roll back

1. Stop the server.
2. Remove the 1.3.0 jar and put the previous jar back in `plugins/`.
3. Leave `config.yml` in place. Older builds ignore the 1.3.0 keys (`cache.enabled`, `cache.max-memory-bytes`, `latency.*`, `world-context.*`, `plugin-api.response-validation.*`). The older merger inserts only keys that are in its own defaults and missing from the file. It does not delete extra keys. To drop the appended keys from the file, restore the `config.yml.bak` written by the 1.3.0 start (the log line `Backed up config.yml to …` names it).

Do not change `config-version` to force another migration. It stays 2 on both sides of this upgrade once the 1.3.0 start has run.
