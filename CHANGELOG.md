# Changelog

## 0.5.1-SNAPSHOT

Replace the published `NexusAI-0.5.0-SNAPSHOT.jar` with this build. Your existing `config.yml` is kept. On startup and `/nai reload`, two new limit keys are inserted if they are missing: `limits.player-requests-per-minute` (default 10) and `limits.player-requests-per-day` (default 200). Server limits stay `requests-per-minute` 30 and `requests-per-day` 1000.

### Commands and status

- `/nai help`, `/nai version`, `/nai reload`, and `/nai status` reject unexpected extra arguments and point at `/nai help`. `/nai test` still treats the rest of the line as the prompt, and that prompt is not cut to `limits.max-prompt-length`.
- `/nai status` shows the locale words for yes and no (`yes` / `no` in English, `да` / `нет` in Russian) instead of the raw message keys. In Russian, an empty last error is «нет ошибки», so it is distinct from «нет».
- Locale codes ignore case. `RU` loads Russian, and `PT-br` loads `pt_BR`. An unknown code still falls back to English with one warning.
- A successful `/nai test` no longer clears a provider pause. The test itself still reaches the provider so you can check the channel. Placeholders, prewarm, and the pool stay paused until `auth-pause-seconds` or `provider-pause-seconds` ends.
- Reloading onto a remote provider with no API key logs the missing-key warning once. Reloading again in that same state does not repeat it. Ollama and other local endpoints still run without a key and without that warning.
- `/nai reload` no longer re-registers the PlaceholderAPI expansion, so the log is not filled with “Successfully registered internal expansion: ainexus” on every reload.

### Config reload

- If `config.yml` is not valid YAML, `/nai reload` reports the failure and leaves both the file and the loaded settings unchanged. It does not append default keys and does not print “Configuration reloaded”.
- Starting the server with a broken `config.yml` does not send requests, including when `NEXUSAI_API_KEY` is set in the environment.
- A valid file still receives only keys that are missing. Values and comments you already wrote stay as they are.

### Answers

- A blank model `content` is a failed request: it is not cached, it is not stored in a pool, `/nai test` prints a failure, and the prompt backs off. If the visible text is only in `reasoning_content`, `reasoning`, or `reasoning_text`, that text is used and formatted like a normal answer. A non-empty `content` still wins.
- With `api.strip-markdown: true`, list markers (`-`, `*`, `+`, and numbered items) and quote markers (`>`) at the start of a line are removed, along with the headings, emphasis, code, and links that were already stripped.
- On HTTP 429, a numeric `Retry-After` is used only when it is longer than `limits.provider-pause-seconds`. It never shortens the configured pause. An HTTP-date value in that header is ignored.
- `cache.max-size` is not a hard cap in the same millisecond. Caffeine trims the cache as it is maintained, so the oldest entry can still be served immediately after a new one is written.

### Pools and limits

- The same answer text is stored only once per pool prompt. Handing it out does not make it eligible again until `/nai reload` or a restart. If the model keeps repeating itself, later `%ainexus_generate_...%` reads return `fallback` until a different answer is stored. The pool keeps asking until it has enough different answers or it logs, once, that it stopped.
- After a provider error or a provider pause, a short pool refills on its own. It does not wait for a player to read the placeholder. Reading an empty pool still returns `fallback` immediately.
- Console commands, pool refills, and prewarm spend only the server rate limit. A request made for a player also spends `player-requests-per-minute` and `player-requests-per-day`. Hitting the player cap does not pause the provider and is not recorded as a provider error.
