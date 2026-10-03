# Key from the environment or from a file

Two providers, two sources. Priority for one provider: a non-empty `api-key-file` wins, then `api-key`, then `NEXUSAI_API_KEY` on the active provider when that source is empty. This file does not set both `api-key` and `api-key-file` on the same provider. Doing that logs one warning and ignores `api-key`.

## Groq: key file

`providers.groq.api-key-file` is `secrets/groq.key`, relative to `plugins/NexusAI/`. Copy `secrets/groq.key` to `plugins/NexusAI/secrets/groq.key` and replace the placeholder line with the raw key. One key per non-empty line. Lines starting with `#` are ignored. A line with a space or `:` is rejected and is not logged. Two lines would be a round-robin list of keys for that provider.

```bash
chmod 600 plugins/NexusAI/secrets/groq.key
```

A key file that is readable by the group or by others logs `chmod 600 <path>` once. The file is read at startup and on `/nai reload`. NexusAI does not copy it to a `.bak` and does not overwrite it.

## Gemini: environment

`providers.gemini.api-key` is `"${ENV:GEMINI_API_KEY}"`. `${GEMINI_API_KEY}` is the same variable. An unset name is logged and becomes empty.

## Check

`/nai status` shows a file key as `****abcd (file)` and an environment key as `****abcd (env)`. A literal key in `config.yml` has no suffix. The `url` is not masked.

The queue is `failover` (the default). For several providers sharing traffic, use [multi-provider](../multi-provider/README.md).
