# Migration from 1.0.x to 1.1.0

Replace `NexusAI-1.0.2.jar` with `NexusAI-1.1.0.jar` (Paper or Purpur 1.20.6 through 26.2, Java 21+). Stop the server, swap the jar, start it. The first start appends the new keys. You do not edit `config-version`.

## What is written for you

On startup and on `/nai reload`, when `plugins/NexusAI/config.yml` is valid YAML, missing keys are copied from the jar default. The log line is:

`Added missing config keys: …`

The same start writes **one** backup: `config.yml.bak`, or `config.yml.bak.<timestamp>` when that backup already exists. The path is logged. A later start that only appends more keys writes one new backup.

`config-version` stays **2**. No existing value changes meaning.

Keys appended when they are absent:

| Key | Value written |
|-----|----------------|
| `model-queue-strategy` | `failover` |
| `context.enabled` | `true` |
| `context.max-provider-timeout-millis` | `200` |
| `context.total-timeout-millis` | `300` |
| `context.max-chars-per-provider` | `200` |
| `context.max-chars` | `600` |
| `context.refresh-seconds` | `30` |
| `context.suspend-after-timeouts` | `5` |
| `context.suspend-seconds` | `60` |
| `dialogue.summary.enabled` | `false` |
| `dialogue.summary.threshold-turns` | `2` |
| `dialogue.summary.max-chars` | `400` |
| `dialogue.summary.max-tokens` | `200` |
| `dialogue.summary.provider` | empty |
| `dialogue.summary.model` | empty |

`dialogue.summary` is inserted inside an existing `dialogue:` section. Comments you already wrote stay. A value you already set is not replaced. If you had set `model-queue-strategy: round-robin` yourself, it stays `round-robin`.

`api-key-file` is **not** inserted under each provider. It is only a comment in the bundled `config.yml`. Add it yourself when you want a key file. See [examples/api-key](../examples/api-key/README.md).

## What does not change

- `prompts.yml` stays on version **1**. The `context:` key is optional. A prompt that omits it keeps the 1.0.x text and the same cache key. There is no prompts migration.
- A literal `api-key`, `${VAR}`, `${ENV:VAR}`, and `NEXUSAI_API_KEY` on one literal active key behave as in 1.0.x. `${ENV:VAR}` is the same placeholder as `${VAR}`.
- `usage.yml` is unchanged.
- `pool.yml` is unchanged by 1.1.0. The one-time markup backup of that file is the 1.0.2 behaviour.
- `dialogue.summary.enabled: false` (the appended default) keeps `/nai talk` and `dialogue-memory.yml` as in 1.0.2: lines outside the memory window are dropped.
- Placeholders, permissions, and the command names are unchanged.

`dialogue-memory.yml` gains keys only when summaries are on and the file is saved. The document is then `format: 2` and may contain `summary` and `summary-updated`. The first time that rewrite replaces a file that is not format 2, NexusAI copies it to `dialogue-memory.yml.bak` (or `dialogue-memory.yml.bak.<timestamp>` when that backup exists) and does not write another backup on later saves. The save writes a temporary file and then moves it into place. Lines waiting to be summarised are not stored. With `persist-memory: false` the summary lives in memory only, like the transcript.

## How to roll back

1. Stop the server.
2. Remove the 1.1.0 jar and put `NexusAI-1.0.2.jar` back in `plugins/`.
3. Leave `config.yml` in place. 1.0.x reads the keys it knows. It ignores `model-queue-strategy`, `context.*`, and `dialogue.summary.*`. `config-version` is already 2, which 1.0.x also uses, so the file is not migrated again. To drop the appended keys from the file, restore the `config.yml.bak` written by the 1.1.0 start (the log line `Backed up config.yml to …` names it).
4. Leave `prompts.yml` in place. A `context:` key is an unknown prompt setting on 1.0.x: it is ignored, one warning is logged, and the prompt still loads.
5. Leave `dialogue-memory.yml` in place. 1.0.x reads `updated` and `lines`. It ignores `summary`, `summary-updated`, and `format`. The transcript remains. The summary text is not shown. If you need the pre-1.1.0 file, restore `dialogue-memory.yml.bak` from the first format-2 save.
6. `usage.yml` and `pool.yml` need no rollback for this upgrade.
7. A key file you added is not part of the jar upgrade. 1.0.x does not read `api-key-file`. Point `api-key` at the key again, or use `NEXUSAI_API_KEY`, before you expect 1.0.x to send requests.

Do not change `config-version` to force another migration. It stays 2 on both sides of this upgrade.
