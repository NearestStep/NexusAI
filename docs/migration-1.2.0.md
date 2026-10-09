# Migration to 1.2.0

Replace the previous jar with `NexusAI-1.2.0-SNAPSHOT.jar` (Paper or Purpur 1.20.6 through 26.2, or Folia 1.21.8, 1.21.11, 26.1.2, or 26.2, Java 21+). Stop the server, swap the jar, start it. The first start appends the new keys. You do not edit `config-version`.

The snapshot jar is not a published release.

## What is written for you

On startup and on `/nai reload`, when `plugins/NexusAI/config.yml` is valid YAML, missing keys are copied from the jar default. The log line is:

`Added missing config keys: …`

The same start writes **one** backup: `config.yml.bak`, or `config.yml.bak.<timestamp>` when that backup already exists. The path is logged. A second start that finds every new key already present appends nothing and does not write another backup.

`config-version` stays **2**. No existing value changes meaning. A value you already set is not replaced.

Keys appended when they are absent:

| Key | Value written |
|-----|----------------|
| `plugin-api.enabled` | `true` |
| `plugin-api.max-template-chars` | `8000` |
| `plugin-api.max-var-chars` | `1000` |
| `quotas.enabled` | `false` |
| `quotas.missing-usage` | `estimate` |
| `quotas.server-tokens-per-day` | `0` |
| `quotas.player-tokens-per-day` | `0` |
| `quotas.groups` | `{}` |
| `quotas.consumers.default.tokens-per-day` | `0` |
| `quotas.consumers.default.requests-per-day` | `0` |
| `quotas.save-interval-seconds` | `10` |
| `knowledge.select` | `full` |
| `knowledge.keywords.max-paragraphs` | `6` |
| `knowledge.keywords.max-paragraph-chars` | `1200` |
| `knowledge.keywords.max-file-chars` | `200000` |
| `knowledge.keywords.min-matches` | `1` |
| `knowledge.keywords.on-no-match` | `none` |
| `knowledge.keywords.stop-words` | `[]` |

Comments you already wrote stay. `knowledge.max-chars` and `knowledge.max-file-chars` that you already set stay.

These keys are **not** appended. They are comments in the bundled file, or comments in `prompts.yml`:

- `model-queue` `daily-token-limit`
- `providers.<id>.structured-output`
- `knowledge-select` and `knowledge-keywords` on a prompt

Add those yourself when you want them.

## What does not change

- `prompts.yml`, `pool.yml`, and `usage.yml` stay on version **1**. 1.2.0 does not add keyword keys to an existing `prompts.yml`. A file that has no `config-version` still receives the older step that inserts `config-version: 1`. An old pool file is still rewritten to version 1. A second pass of a file that is already version 1 changes nothing.
- `token-usage.yml` is not created at startup. The first counted request marks the ledger dirty. `nexusai-scheduler` writes the file when `quotas.save-interval-seconds` has elapsed (default 10) and the ledger is still dirty. Disable flushes a dirty ledger. A crash can lose at most one interval of counts.
- With the appended defaults, quotas reject nothing and knowledge selection stays `full`. Placeholders, permissions, and the command names are unchanged.

A file that is still `config-version: 1` takes the existing step from 1 to 2 in that same startup, including the one-time change of a leftover `api.max-tokens: 0` to 256, and then receives the keys in the table. That startup still writes one backup.

## What you turn on

Quotas apply only after you set `quotas.enabled: true`. A `0` cap is not a cap. Keyword selection applies only after you set `knowledge.select: keywords`, or `knowledge-select: keywords` on a prompt. `knowledge-keywords` on a prompt is optional and is not written into an existing `prompts.yml`.

## How to roll back

1. Stop the server.
2. Remove the 1.2.0 jar and put the 1.1.2 jar back in `plugins/`.
3. Leave `config.yml` in place. 1.1.2 reads `knowledge.max-chars` and `knowledge.max-file-chars`. It does not read `knowledge.select`, `knowledge.keywords`, `plugin-api`, or `quotas`. The 1.1.2 merger inserts only keys that are in its own defaults and missing from the file. It does not delete extra keys. No 1.1.2 server was started for this note, so no warning level is claimed. To drop the appended keys from the file, restore the `config.yml.bak` written by the 1.2.0 start (the log line `Backed up config.yml to …` names it).
4. Leave `prompts.yml` in place. A `knowledge-select` or `knowledge-keywords` key you added is an unknown prompt setting on 1.1.2: it is ignored, one warning is logged, and the prompt still loads.
5. `token-usage.yml` is not read by 1.1.2. Leave it or delete it. It is not part of the 1.1.2 config.
6. `usage.yml`, `pool.yml`, and `dialogue-memory.yml` need no rollback for the keys in the table above.

Do not change `config-version` to force another migration. It stays 2 on both sides of this upgrade once the 1.2.0 start has run.
