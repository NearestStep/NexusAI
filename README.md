# NexusAI

Asynchronous infrastructure plugin for Paper: a bridge between the game engine and AI models via PlaceholderAPI.

Other plugins (menus, chat, holograms) can request AI text through placeholders without blocking the main thread or hurting TPS.

## Requirements

- Paper **26.2** (Java **25**)
- [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) 2.11.6+ (soft-depend)
- API key for an OpenAI-compatible provider, unless you use a local endpoint such as Ollama

## Installation

1. Build the shadow JAR: `./gradlew shadowJar`
2. Copy `build/libs/NexusAI-0.5.1-SNAPSHOT.jar` into `plugins/`
3. Install PlaceholderAPI
4. Set the API key (prefer environment):

```bash
# Windows (PowerShell)
$env:NEXUSAI_API_KEY = "sk-..."

# Linux / macOS
export NEXUSAI_API_KEY=sk-...
```

Or set `api.key` in `plugins/NexusAI/config.yml` (do not commit secrets).

Without a key the plugin still loads. Remote providers log a warning and do **not** send HTTP requests — placeholders return `fallback`. Local endpoints (the `ollama` preset, `localhost` / `127.0.0.1` / `0.0.0.0` / `::1`, including the bracketed form `[::1]`, a host ending in `.local`, or any base URL on port `11434`) are called without an `Authorization` header.

## Configuration

`plugins/NexusAI/config.yml`:

| Section | Parameters |
|---------|------------|
| `locale` | Command language (`en`, `ru`, `de`, …). Missing keys fall back to English |
| `api` | `provider`, `model`, `base-url` (empty = provider default), `key`, `system-prompt`, `temperature` (negative = omit), `max-tokens` (`0` = omit), `strip-markdown`, `max-answer-chars`, `max-answer-lines`, `reasoning-effort`, `connect-timeout`, `read-timeout` |
| `cache` | `ttl` (seconds), `max-size` |
| `limits` | `requests-per-minute`, `requests-per-day` (server), `player-requests-per-minute`, `player-requests-per-day`, `max-prompt-length` (default **128**), `provider-pause-seconds`, `auth-pause-seconds`, `error-backoff-initial-seconds`, `error-backoff-max-seconds`, `error-log-cooldown-seconds` |
| `pool` | `enabled`, `max-total-prompts`, `persist`, `save-delay-seconds`, `entries[]` (`prompt`, `size`, `min-threshold`, optional `vars`, optional `system-prompt` / `temperature` / `max-tokens`) |
| `prewarm` | `enabled`, `refresh-before-ttl` (seconds), `prompts[]` (supports `{player}`) |
| `fallback` | String on miss / rate limits / missing key |

API key priority: **`NEXUSAI_API_KEY`** → `api.key` in YAML.

Bundled locales: `en` (default), `ru`, `uk`, `de`, `es`, `fr`, `it`, `pl`, `pt_BR`, `nl`, `cs`, `tr`, `zh_CN`, `ja`, `ko`. Optional overrides: `plugins/NexusAI/lang/<locale>.yml`.

### Providers

`api.provider` selects the default `base-url` when `api.base-url` is empty:

| provider | Default base-url |
|----------|------------------|
| `openai` | `https://api.openai.com/v1` |
| `groq` | `https://api.groq.com/openai/v1` |
| `cerebras` | `https://api.cerebras.ai/v1` |
| `gemini` | `https://generativelanguage.googleapis.com/v1beta/openai` |
| `deepseek` | `https://api.deepseek.com` |
| `ollama` | `http://localhost:11434/v1` |
| `openrouter` | `https://openrouter.ai/api/v1` |

An explicit `api.base-url` always wins. On startup the log prints: `Using provider: …, base-url: …, model: …`.

`ollama` does not need an API key. Any other provider pointed at localhost or port `11434` is treated the same way: if `api.key` and `NEXUSAI_API_KEY` are empty, the `Authorization` header is omitted.

### Generation

Optional request fields, all omitted when left at the defaults (`system-prompt` empty, `temperature` negative, `max-tokens` 0):

- `api.system-prompt` — sent as a system message before the user prompt
- `api.temperature` and `api.max-tokens` — copied onto the JSON body
- each `pool.entries[]` item may override those three for pool refills only
- `api.strip-markdown`, `api.max-answer-chars`, `api.max-answer-lines` — applied to every answer (`0` means no limit)
- `api.reasoning-effort` — sent only for reasoning models (`o1` / `o3` / `o4`, `gpt-oss`, `deepseek-r1`, `qwq`, names containing `reasoner`). Their token budget is raised to at least 2048. o-series models receive `max_completion_tokens` instead of `max_tokens`, and temperature is not sent. Set the effort to `off` to skip the field.

Answers whose `content` is an array of parts are joined into one string.

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/nai help` | `nexusai.command` | Show command help |
| `/nai version` | `nexusai.command` | Show plugin version |
| `/nai reload` | `nexusai.reload` | Reload config + locale; rebuild cache/pool/prewarm |
| `/nai status` | `nexusai.status` | Provider, model, key set, pool, cache, PlaceholderAPI, last error, provider pause |
| `/nai test [prompt]` | `nexusai.test` | One live request. Prints the answer and latency. With no prompt, asks the model to reply `pong`. Extra words are part of the prompt. This command does not apply `limits.max-prompt-length` and does not clear, start, or extend a provider pause |

Alias: `/nexusai`. Defaults: OP. `/nai help`, `/nai version`, `/nai reload`, and `/nai status` reject unexpected extra arguments and point at `/nai help`. Locale codes are matched without case: `RU` loads `ru`, and `PT-br` loads `pt_BR`.

## Placeholders

### Unique answers (pool)

```
%ainexus_generate_<prompt>%
```

Takes and **removes** one answer from that prompt's pool. If the pool is empty — immediate `fallback`; `PoolService` may refill when the prompt is listed in `pool.entries`. On refill, finished text with none of this entry's `{token}` markers left is stored only once per prompt, and handing it out does not make it eligible again until `/nai reload` or a restart. Repeated model output of that kind does not fill `size` and is not returned a second time; later reads get `fallback` until a different answer is stored. An answer that still contains a configured token such as `{player_name}` is a template: the same template may occupy every slot up to `size`, because each player receives their own substitution. After the error backoff the pool asks again until it has enough answers or it logs that it stopped. A short pool also asks again after a provider error or pause ends, without waiting for a placeholder read. Refills, prewarm, and cache misses all spend the shared server rate limit. A player request also spends `player-requests-per-minute` and `player-requests-per-day`. After a provider error the prompt backs off; HTTP 401, 402, and 429 pause every request to that provider except `/nai test`. A numeric `Retry-After` on HTTP 429 is used only when it is longer than `provider-pause-seconds`. `/nai test` does not shorten or extend that pause.

With `pool.persist: true` (default), answers are written to `plugins/NexusAI/pool.yml` on shutdown and, while the server is running, after `pool.save-delay-seconds` of quiet. They are loaded again on startup and `/nai reload`, so a restart does not buy a full pool if it was already filled. Loading does not remove duplicate lines, so repeated `{token}` templates survive a restart. Duplicate finished answers saved by 0.5.0-SNAPSHOT stay in the file and are handed out once each. To start with a clean pool, stop the server and delete `plugins/NexusAI/pool.yml`. Answers are regenerated, which spends provider requests.

Example `pool.entries` with personalization vars:

```yaml
pool:
  enabled: true
  max-total-prompts: 10
  entries:
    - prompt: "Short warm welcome for the joining player"
      size: 3
      min-threshold: 1
      vars:
        player_name: "%player_name%"
```

How `vars` work:

1. On refill, NexusAI tells the model to leave brace tokens like `{player_name}` in the answer (no real name invented).
2. Answers are stored with those tokens still in place. The same template can fill `size` (the welcome example with `size: 3` can serve three players). On refill, text that no longer contains a configured token is stored only once. Copies already saved in `pool.yml`, including duplicate finished lines from 0.5.0-SNAPSHOT, are loaded as they are and handed out once each.
3. On `%ainexus_generate_<same prompt>%`, tokens are replaced via PlaceholderAPI for the viewing player → e.g. `Hello, Steve!`.

The placeholder prompt string must match `entries[].prompt` exactly for `vars` to apply.

CustomWelcome-style join message (prompt text must match the entry):

```yaml
join-message: '&e%ainexus_generate_Short warm welcome for the joining player%'
```

### Shared TTL cache (holograms)

```
%ainexus_cached_<prompt>%
```

Behavior:

1. Cached answer → return it immediately
2. Otherwise return `fallback` and fetch in the background
3. Later resolves of the same prompt return the cache until TTL expires
4. Prompt longer than `limits.max-prompt-length` → `fallback`, no HTTP
5. In-flight deduplication — parallel identical requests share one HTTP call
6. `cache.max-size` is Caffeine's maximum. The cache does not promise to drop the oldest key in the same millisecond a new one is written

### Prewarm

`prewarm` warms the TTL cache on startup and periodically refreshes prompts that are no longer `isFresh` (age ≥ 80% of TTL). Templates with `{player}` are skipped on startup; use `PrewarmService.warmForPlayer(playerName)` for those.

## FAQ

### Why is there no top-level “pool capacity” setting?

Capacity is per prompt: `pool.entries[].size` (with `min-threshold` for refill). There is no global `pool.size`.

### Will an upgrade overwrite my config?

On startup and `/nai reload`, NexusAI inserts keys that exist in the default `config.yml` and are missing from `plugins/NexusAI/config.yml`, when that file is valid YAML. Values you already set are left as they are, and comments already in the file stay put. Added keys are listed in the server log (`Added missing config keys: …`). Keys that are new to you still use defaults until you edit them: a negative `temperature` and `max-tokens: 0` mean those fields are not sent, which matches older behavior.

If `config.yml` is not valid YAML, startup and `/nai reload` leave the file byte for byte as it is. Reload reports the failure and keeps the configuration already in memory. It does not append default keys and it does not print `Configuration reloaded`. A broken file on startup does not enable requests, so an `NEXUSAI_API_KEY` in the environment is not sent to the default OpenAI URL. A reload onto a remote provider with no key logs the missing-key warning once; reloading that same state again does not repeat it. A local endpoint such as Ollama does not log that warning.

### What is prewarm?

Prewarm fills the shared TTL cache used by `%ainexus_cached_*%` so holograms (and similar) can show a ready answer instead of the first-hit `fallback`. It is not the unique-answer pool (`generate_` / `pool`).

### Why doesn’t `%player_name%` inside the AI answer get replaced?

NexusAI returns the model text as-is. Asking the model to emit `%player_name%` usually leaves that literal string — PlaceholderAPI is not re-run on the whole AI answer.  
Use pool `vars` instead: the model writes `{player_name}`, and NexusAI substitutes it from `%player_name%` (or another PAPI template) when delivering `%ainexus_generate_*%`. Nested placeholders *inside* the NexusAI placeholder identifier are also unreliable across host plugins.

## Build

```bash
./gradlew test
./gradlew shadowJar
```

Jackson and Caffeine are shaded and relocated under `io.github.neareststep.nexusai.libs.*`.

Test stack: JUnit 5 (no Mockito — Java 25 compatibility).

## Architecture (short)

- `PluginConfig` — config.yml + env + provider defaults + locale
- `ConfigMerger` — adds missing config keys without overwriting user values
- `MessageService` — `lang/*.yml` with English fallback
- `AiCache` — Caffeine (TTL + max-size + `isFresh`)
- `AiPool` / `PoolService` / `PoolStore` — unique answer queues for `generate_`, saved to `pool.yml`
- `VarSubstitutor` — `{token}` delivery substitution from pool `vars`
- `PrewarmService` — TTL warm-up/refresh for `cached_`
- `RateLimiter` / `RequestGate` — per-player and server limits, per-prompt backoff, provider pause
- `AiDiagnostics` — last error and rate-limited WARNING logs
- `AiProvider` / `OpenAiProvider` — HTTP `/chat/completions`
- `AiHttpClient` — cache + in-flight + `generateFreshAsync` for the pool
- `AiPlaceholderExpansion` — `%ainexus_generate_*%` / `%ainexus_cached_*%`
- `NaiCommand` — `/nai` admin commands, including `/nai test`

## License

MIT License — Copyright (c) 2026 mo00Wy. Full text: [LICENSE](LICENSE).
