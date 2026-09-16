# NexusAI

Asynchronous infrastructure plugin for Paper: a bridge between the game engine and AI models via PlaceholderAPI.

Other plugins (menus, chat, holograms) can request AI text through placeholders without blocking the main thread or hurting TPS.

## Requirements

- Paper **26.2** (Java **25**)
- [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) 2.11.6+ (soft-depend)
- API key for an OpenAI-compatible provider

## Installation

1. Build the shadow JAR: `./gradlew shadowJar`
2. Copy `build/libs/NexusAI-0.4.0-SNAPSHOT.jar` into `plugins/`
3. Install PlaceholderAPI
4. Set the API key (prefer environment):

```bash
# Windows (PowerShell)
$env:NEXUSAI_API_KEY = "sk-..."

# Linux / macOS
export NEXUSAI_API_KEY=sk-...
```

Or set `api.key` in `plugins/NexusAI/config.yml` (do not commit secrets).

Without a key the plugin still loads, logs a warning, and **does not** send HTTP requests — placeholders return `fallback`.

## Configuration

`plugins/NexusAI/config.yml`:

| Section | Parameters |
|---------|------------|
| `locale` | Command language (`en`, `ru`, `de`, …). Missing keys fall back to English |
| `api` | `provider`, `model`, `base-url` (empty = provider default), `key`, `connect-timeout`, `read-timeout` |
| `cache` | `ttl` (seconds), `max-size` |
| `limits` | `requests-per-minute`, `requests-per-day`, `max-prompt-length` (default **128**) |
| `pool` | `enabled`, `max-total-prompts`, `entries[]` (`prompt`, `size`, `min-threshold`, optional `vars`) |
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

An explicit `api.base-url` always wins. On startup the log prints: `Using provider: …, base-url: …, model: …`.

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/nai help` | `nexusai.command` | Show command help |
| `/nai version` | `nexusai.command` | Show plugin version |
| `/nai reload` | `nexusai.reload` | Reload config + locale; rebuild cache/pool/prewarm |
| `/nai status` | `nexusai.status` | Provider, model, key set, pool, cache, PlaceholderAPI |

Alias: `/nexusai`. Defaults: OP.

## Placeholders

### Unique answers (pool)

```
%ainexus_generate_<prompt>%
```

Takes and **removes** one answer from that prompt's pool. If the pool is empty — immediate `fallback`; `PoolService` may refill when the prompt is listed in `pool.entries`.

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
2. Answers are stored in the shared unique pool with those tokens.
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

### Prewarm

`prewarm` warms the TTL cache on startup and periodically refreshes prompts that are no longer `isFresh` (age ≥ 80% of TTL). Templates with `{player}` are skipped on startup; use `PrewarmService.warmForPlayer(playerName)` for those.

## FAQ

### Why is there no top-level “pool capacity” setting?

Capacity is per prompt: `pool.entries[].size` (with `min-threshold` for refill). There is no global `pool.size`.  
Also, Bukkit `saveDefaultConfig()` does **not** merge new keys into an existing `plugins/NexusAI/config.yml` — after upgrading, add new sections manually or regenerate the file.

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
- `MessageService` — `lang/*.yml` with English fallback
- `AiCache` — Caffeine (TTL + max-size + `isFresh`)
- `AiPool` / `PoolService` — unique answer queues for `generate_`
- `VarSubstitutor` — `{token}` delivery substitution from pool `vars`
- `PrewarmService` — TTL warm-up/refresh for `cached_`
- `RateLimiter` — per-player and server limits
- `AiProvider` / `OpenAiProvider` — HTTP `/chat/completions`
- `AiHttpClient` — cache + in-flight + `generateFreshAsync` for the pool
- `AiPlaceholderExpansion` — `%ainexus_generate_*%` / `%ainexus_cached_*%`
- `NaiCommand` — `/nai` admin commands

## License

MIT License — Copyright (c) 2026 mo00Wy. Full text: [LICENSE](LICENSE).
