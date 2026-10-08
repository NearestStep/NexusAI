# FAQ

Short answers for the first week. Key priority, the cache, and the player-input guard are in the [README](../README.md). Files you can copy are in [examples/](../examples/README.md).

## The plugin loaded, but nothing is sent

Remote providers do not send HTTP without a key. The startup warning is one of:

- `API key is not set (env NEXUSAI_API_KEY or api.key). Plugin will load, but AI requests will not be sent.`
- `API key is not set for the model queue or fallback model. Placeholders, the pool, prewarm, and /nai test will not be sent.`

Placeholders return the fallback string. `/nai status` shows `API key: no`, or `provider: no` on a row that has no key.

Set the key in one of these ways, then `/nai reload`:

| Way | Example |
|-----|---------|
| Environment placeholder | `api-key: "${ENV:GROQ_API_KEY}"` or `api-key: "${GROQ_API_KEY}"` |
| Key file | `api-key-file: secrets/groq.key` |
| One literal on the active provider | `api.key`, replaced by `NEXUSAI_API_KEY` when that variable is set |

An unset `${ENV:NAME}` is logged by name and becomes empty. It is not sent as the key. A non-empty `api-key-file` wins over `api-key`. The file is UTF-8, one raw key per line, `#` comments skipped, read at startup and on `/nai reload`. A missing file, a file over 64KB, or a line that contains a space or `:`, logs one warning with the path and without the line. See [examples/api-key](../examples/api-key/README.md) and the README section "Providers, keys, and the model queue".

A local endpoint (the `ollama` preset, localhost, or port `11434`) is called with no `Authorization` header.

## HTTP 401

The key was rejected. `/nai status` last error contains `HTTP 401 unauthorized. The API key was rejected`. HTTP 403 is reported as an invalid or unauthorized key as well.

NexusAI pauses requests to that provider for `limits.auth-pause-seconds` (default **300**). During the pause, placeholders and the pool serve a stored answer or the fallback. `/nai test` still sends, and it does not start or lengthen the pause. A list of keys skips the rejected key and tries the next one.

Check the key, the provider URL, and that the variable is visible to the server process. The status line shows `****` and the last four characters, plus `(env)` or `(file)`. The full key is not printed.

## HTTP 429, limits, and pauses

HTTP 429 is a provider rate limit. The queue row goes on cooldown. The pause is `limits.provider-pause-seconds` (default **60**) unless a numeric `Retry-After` is longer. When every row is unavailable, the error includes `Retry after yyyy-MM-dd HH:mm:ss`.

These caps are local. They do not pause the provider. A player who hits a personal cap sees the fallback. The pool and prewarm keep using the server caps.

| Key | Default | Who spends it |
|-----|---------|----------------|
| `limits.requests-per-minute` | 30 | Every request, including console, pool, and prewarm |
| `limits.requests-per-day` | 1000 | Same |
| `limits.player-requests-per-minute` | 10 | A request made for a player |
| `limits.player-requests-per-day` | 200 | A request made for a player |
| `model-queue` `daily-request-limit` | unset | That row, stored in `usage.yml`, reset at server-local midnight |

`/nai talk` also has its own caps (`dialogue.message-cooldown-millis`, `dialogue.conversations-per-player-per-day`, `dialogue.max-replies-per-session`). A dialogue line still spends the server and player request caps.

A full HTTP queue fails immediately with `HTTP queue is full`. The placeholder already returned the fallback. One warning is written at most once per 30 seconds. That interval is fixed. The four `nexusai-http-*` threads stay at four. Extra calls are not queued without a bound.

Groq's free tier also has an output-token quota per minute. `api.max-tokens: 256` keeps one short reply inside a cap of about 1000. It does not remove the quota. A longer `ttl` and a shorter prompt avoid sending the same call on every refresh.

## The placeholder stays on the fallback

`%ainexus_cached_<id>%` returns the cache when there is one. On a miss it starts a background request when requests are allowed, and it returns a stored pooled answer without removing it, or the prompt fallback (then `fallback` from `config.yml`).

The first look is often the fallback. Resolve the placeholder again after a few seconds. Then check `/nai status`:

- `Last error: none` and a non-empty cache means the next look should show the sentence.
- `API key: no` means the key never left the server. See the first section.
- A 401 or 429 means the pause above.
- `generate_ requested but not pooled` means `%ainexus_generate_<id>%` was used for an id that is not in `pool.entries`. `generate_` only returns pooled answers. Use `cached_` for a shared line, or add a pool entry.

A prompt longer than `limits.max-prompt-length` (default **128**) is rejected only when the text is a literal placeholder. Text stored in `prompts.yml` is not cut by that limit unless the prompt sets `max-prompt-length`.

`/nai test <id>` sends that named prompt and prints the answer or the error. It does not apply `limits.max-prompt-length` to literal text.

## The model reply was empty

Two different messages:

- `The model reply was empty after removing colour codes.` Legacy `&` or `§` codes were the whole reply. That prompt waits 5 minutes, then 15, then 30, capped at 60. `/nai reload` clears the wait.
- `The model reply was empty after removing markup.` Hex colours, MiniMessage tags, or a JSON click/hover component were the whole reply. That prompt waits **30 seconds**. The wait does not climb. A later success on the same key does not end it. `/nai reload` does. While it holds, the pool sends nothing for that prompt, then at most one refill per window.

`/nai test` reports the empty reply and does not start either wait. The text is not cached.

## `knowledge/` is not UTF-8

Files in `plugins/NexusAI/knowledge/` must be UTF-8 (`.md` or `.txt`). A Windows-1251 file saved from Notepad logs one warning:

`Knowledge file '<name>' is not valid UTF-8, re-save the file as UTF-8. The file was skipped.`

The stack trace is only at FINE. Re-save the file as UTF-8 and run `/nai reload`. `example.md` is created once and is never overwritten.

## Keyword knowledge sends the whole file

`knowledge.select` defaults to `full`. That mode still sends each listed file, including HTML comments, up to `knowledge.max-file-chars` (4000) and `knowledge.max-chars` (6000). Set `knowledge.select: keywords`, or `knowledge-select: keywords` on the prompt, to send only matching paragraphs. A file in that mode may be up to `knowledge.keywords.max-file-chars` (200000). Split the file on blank lines, and put a `#` heading above a section so those words count for the paragraphs under it. `<!-- keywords: ban, mute -->` on a paragraph adds words and is not sent. A word in `knowledge-keywords` is added even when it is a stop word. `/nai talk` does not attach knowledge while the mode is `full`. There is no vector database and no embeddings.

## Placeholders and `{tokens}`

| Token | Where it is filled |
|-------|--------------------|
| `{player}`, `{world}`, `{biome}`, `{time}`, `{weather}` | By NexusAI, without PlaceholderAPI. `{time}` is `morning`, `day`, `evening`, or `night` |
| `%placeholder%` inside a prompt `vars:` value | PlaceholderAPI, for the player who is looking |
| `{token}` left in a pooled answer | On delivery of `%ainexus_generate_...%`, from that pool entry's `vars` |

NexusAI does not run PlaceholderAPI over the model reply. A reply that contains the literal `%player_name%` stays that way. Ask the model to leave `{player_name}`, and set the pool entry:

```yaml
vars:
  player_name: "%player_name%"
```

The placeholder argument must match `pool.entries[].prompt` (the id or the literal text). A worked pair is [examples/placeholders](../examples/placeholders/README.md).

`%ainexus_cached_%` shares one answer among players when the prompt has no player-specific vars. `%ainexus_generate_%` removes one pooled answer. A prompt id that is missing from `prompts.yml` is sent as literal text.

## How to enable round-robin

`model-queue-strategy` defaults to `failover`: every new request starts at the first available row. Set:

```yaml
model-queue-strategy: round-robin
```

Each new request starts at the next available row, then walks the circle. A row on cooldown, at `daily-request-limit`, or at or below `model-queue-remaining-threshold` is skipped in both modes. An unknown value is treated as `failover` and logged once: `Unknown model-queue-strategy '<value>'. Using failover.`

`/nai reload`, then `/nai status`. The line is `Model queue strategy: round-robin (next: groq/llama-3.3-70b-versatile)`, or `none` when no row can be used. A missing key is appended as `failover`. `config-version` stays 2. A full queue is in [examples/multi-provider](../examples/multi-provider/README.md).

`usage.yml` accounting does not change. Keys inside one provider were already round-robin. This switch is the rows of `model-queue`, not the keys of one provider.

## Context from your own plugin

A prompt with no `context:` key is unchanged. To attach a short line (balance, rank, quest):

1. `softdepend: [NexusAI]` in your `plugin.yml`. Compile against the NexusAI jar (`compileOnly`). There is no separate API jar.
2. Implement `io.github.neareststep.nexusai.api.NexusContextProvider`. `id()` matches `[a-z0-9_]{1,32}`. `provide(ContextRequest)` is called on `nexusai-context-N`, never on the main thread. Return a future immediately. `ContextRequest` has no `Player`. Read Bukkit state on the main thread into your own map, and return that map from `provide()`.
3. Register in `onEnable`:

```java
NexusAIApi.registerContextProvider(this, provider);
```

That is Bukkit's services manager. `ServicePriority` does not set the order. Order is `priority()` (lower first), then `id()`. Disabling your plugin removes the provider. `/nai reload` does not.

4. Opt in on the prompt:

```yaml
shop_tip:
  prompt: "Give the player one short shopping tip."
  context: [economy]    # or context: all
```

`%ainexus_cached_shop_tip%`, `/nai talk`, `NexusAIApi.talk`, and `/nai test shop_tip` run by a player include the block. `%ainexus_generate_%`, the pool, prewarm, a console `/nai test`, and a literal placeholder do not. A prompt listed in `pool.entries` or `prewarm.prompts` logs that context is ignored there.

Until a plugin with that id is registered, startup logs `Prompt 'shop_tip' lists unknown context provider 'economy'.` The prompt still loads. The value is sanitized (markup is always removed), wrapped as player input, and becomes part of the `cached_` cache key. Round the value (`~12k`) so every coin does not open a new cache row. The class that does this for a balance is `src/test/java/io/github/neareststep/nexusai/context/ExampleBalanceProvider.java`. The prompt file is [examples/context-prompt](../examples/context-prompt/README.md).

`context.enabled: false` skips every provider.

## Dialogue summaries

`dialogue.summary.enabled` defaults to **false**. Leave it false and `/nai talk` drops lines outside `memory-turns` / `memory-max-chars`, as in 1.0.2.

To fold those lines into one short summary:

```yaml
dialogue:
  summary:
    enabled: true
    threshold-turns: 2      # 1..16 player lines that must drop first
    max-chars: 400          # 100..2000, stored summary
    max-tokens: 200         # this call only; 0 or negative omits max_tokens
    provider: ""            # both empty = model queue; both set = pin that call
    model: ""
```

Each summary is its own model request. Once the window is full, that is about one extra request for every `threshold-turns` player lines. There is no per-character switch. The summary prompt is not configurable.

A refused summary (rate limit, provider pause, daily cap, `HTTP queue is full`, error, timeout, or empty or rejected text) drops the waiting lines and keeps the previous summary. Those waiting lines are not written to disk. A restart before the call trims them.

`/nai status` shows `Dialogue summaries: off` or `Dialogue summaries: on (N ok, M failed today)`. The counters reset at local midnight and on `/nai reload`.

With `persist-memory: true`, the file stores `summary`, `summary-updated`, and `format: 2`. The first rewrite of a file that is not format 2 copies it to `dialogue-memory.yml.bak` once.

## The `lang/` folder is empty

On enable, NexusAI copies every bundled locale into `plugins/NexusAI/lang/` when that file is missing. An empty folder is filled. A file that is already there is not overwritten. Bundled codes: `en`, `ru`, `uk`, `de`, `es`, `fr`, `it`, `pl`, `pt_BR`, `nl`, `cs`, `tr`, `zh_CN`, `ja`, `ko`.

A key you delete is filled from the bundled file for that locale, then from English. Set `locale:` in `config.yml` and run `/nai reload`. Quote `yes` and `no` so YAML does not turn them into booleans.

A `locale:` that is not bundled and has no file logs the path, that English is the fallback, and the bundled codes. A file that is not valid YAML logs the path, the parser reason, and the fallback. The stack trace is only at FINE.

## `sanitize.allow-markup`

The default is **false**. MiniMessage click and hover tags, hex colours, and JSON click/hover components are removed before a reply reaches a placeholder, a dialogue line, the pool, or the cache. Legacy `§` and `&` codes are always removed.

`sanitize.allow-markup: true` lets those tags through to plugins that parse NexusAI placeholders. That plugin can turn the text into a click, including `run_command`. Leave the option false unless you accept that. `/nai talk` and `/nai test` still insert the reply as plain text.

## Java, Paper, and Folia

One jar runs on Paper and Purpur **1.20.6 through 26.2**, Java **21** or newer. It is built with JDK 25 and `--release 21`. `api-version` is `1.20.6`. Folia is not supported (`folia-supported: false`). Paper and Purpur 26.3 are not supported. Spigot and CraftBukkit are not supported.

## Will an upgrade overwrite my config?

Valid `config.yml` keeps the values and comments you wrote. Missing default keys are appended. The log says `Added missing config keys: …`. Before that write, NexusAI copies the file to `<file>.bak`, or `<file>.bak.<timestamp>` when that backup already exists. One startup writes one backup of `config.yml`. `config-version` for a current file is 2.

From 1.0.x to 1.1.0 the appended keys are `model-queue-strategy`, `context.*`, `dialogue.summary.*`, `http.max-in-flight`, and `http.queue-size`. Both HTTP keys are appended as `64`. A `0` or a negative value on either key is treated as 64. Nothing you already set is rewritten. Details and rollback: [Migration to 1.1.0](migration-1.1.0.md).

If `config.yml` is not valid YAML, startup and `/nai reload` leave the file as it is. Reload reports the failure and keeps the configuration already in memory.

`prompts.yml` is created only when the file is missing. `/nai reload` does not rewrite a valid prompts file.

When upgrading from 0.6.0 or 0.7.0, a `config.yml` older than version 2 whose `api.max-tokens` is exactly `0` is changed to `256` once. That `0` was the default shipped in those versions. After the file is version 2, a `0` you set is kept.

## Why is there no top-level pool capacity?

Capacity is per prompt: `pool.entries[].size` (and `min-threshold` for refill). `pool.max-total-prompts` (default 10) caps how many entries are loaded. There is no global `pool.size`.

## What is prewarm?

Prewarm fills the shared TTL cache used by `%ainexus_cached_*%` so a hologram or a scoreboard can show a ready answer instead of the first-hit fallback. It is not the unique-answer pool (`generate_` / `pool`). Prompts that depend on the player are skipped until a player reads them.

## Why did generateJson fail?

`success()` is false when the reply was not one JSON object that matched the schema, including after the one correction retry. `meta().error().kind()` is `INVALID_JSON`, `validationErrors()` names the paths (at most 10), and `meta().text()` is the fallback. That does not pause the provider. A schema keyword outside the supported subset, or a root that is not an object, throws `IllegalArgumentException` from `JsonSchema.parse` before any HTTP call. The message has the path, not the schema text.

`QUOTA_EXCEEDED`, `LOCAL_LIMIT`, and `PROVIDER_ERROR` are the same kinds as `generate`. HTTP 429 or 5xx on the first attempt walks the model queue and does not use the correction retry. The same errors on the retry complete with that provider error. `request.format()` does not change the JSON call. A cached text answer is not reused as JSON.

## A scoreboard, a menu, and an NPC

- Shared tip: [examples/placeholders](../examples/placeholders/README.md), then [examples/tab](../examples/tab/README.md), [examples/scoreboard](../examples/scoreboard/README.md), or [examples/deluxemenus](../examples/deluxemenus/README.md).
- NPC dialogue: [examples/npc-dialogue](../examples/npc-dialogue/README.md).
- Several providers: [examples/multi-provider](../examples/multi-provider/README.md).
