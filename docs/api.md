# NexusAI plugin API

`NexusAIApi.API_VERSION` is 3. New methods are additive. Compile against the plugin jar and set `softdepend: [NexusAI]`. There is no separate API artifact.

A manual Groq check on 2026-10-09 is recorded in Structured JSON. OpenRouter, OpenAI, and Ollama are not recorded.

## Text generation

`NexusAIApi.generate(plugin, request)` may be called from any thread. The future completes on a NexusAI thread, never on a region thread. It completes exceptionally only when NexusAI is not enabled. A finished call is a `GenerationResult`: text (or the fallback when `success()` is false), source, provider, model, token counts, finish reason, latency, and a `NexusErrorKind` when the model did not answer. Do not join the future on the main thread or a region thread. To send a chat line or change the world, hop first.

When the request names a player, that player's state is read on the thread that owns the player. If the caller already owns the player, the read is immediate. Otherwise NexusAI schedules it. On Paper the owning thread is the main thread. On Folia it is the player's region thread. A request with no player does not read a player.

`CacheMode.CACHED` shares one in-flight provider call with a placeholder of the same cache key. A named prompt shares its backoff key with that placeholder. `talk` and context providers are unchanged.

```java
NexusAIApi.generate(plugin, GenerationRequest.prompt("quests:intro")
                .player(player)
                .var("quest", questName)
                .label("quest-intro")
                .build())
        .thenAccept(result -> player.getScheduler().run(plugin, task -> {
            player.sendMessage(result.text());
            if (!result.success()) {
                plugin.getLogger().fine("AI failed: " + result.error().map(GenerationError::kind).orElse(null));
            }
        }, null));
```

`result.text()` is the fallback when `success()` is false. Check a value again before placing it in a command, a name, or a path.

## World context

`NexusAIApi.worldContext(plugin, player)` collects player name, world, block coordinates, inventory materials, nearby block counts, and biome into one compact prompt block in a single call. Call from any thread. World reads run on the player's region thread (Paper main thread, Folia region owner) with the caps in `world-context.*`. When the caller already owns that region, the read runs inside the caller's current tick. Costs and limits are in [Limitations and best practices](limitations.md#world-context-cost). Sorting and formatting run off that thread on `nexusai-context-*`. Within `world-context.cooldown-millis` (default 250) a second call for the same player returns the last block without a fresh region read, even when it passes different `WorldContextLimits` (the saved block is kept per player, not per set of limits). The future completes with `""` when `world-context.enabled` is false, NexusAI is not enabled, or the player is gone. It does not complete exceptionally for those cases. Do not join the future on the main thread or a region thread.

Optional overrides: `NexusAIApi.worldContext(plugin, player, WorldContextLimits.builder().radius(1).maxBlocks(27).build())`. Empty fields keep the config values. Cooldown is config-only.

Token size: the body under the `World:` header is capped by `world-context.max-chars` (default 400 code points). NexusAI estimates tokens as `ceil(codePoints / 4)`, the same formula as missing usage. At the default cap the body is at most 100 tokens. A typical plains scene with a short inventory is about 30–45 tokens for the whole block including the header. `NexusAIApi.worldContextEstimatedTokens(block)` returns that estimate for a finished block.

`world-context.enabled` defaults to `false`. Missing keys are appended on startup; `config-version` stays 2. `API_VERSION` stays 3.

```java
NexusAIApi.worldContext(plugin, player)
        .thenAccept(block -> {
            if (block.isEmpty()) {
                return;
            }
            NexusAIApi.generate(plugin, GenerationRequest.template(
                            "Suggest one craft from what the player can reach.\n\n" + block)
                    .player(player)
                    .label("craft-hint")
                    .build());
        });
```

Example block shape (materials are Minecraft path names, amounts use `×`):

```text
World:
p=Steve w=world @10,64,-3 bio=plains
inv=dirt×64,oak_log×12,iron_pickaxe
near=r2 grass_block×40,dirt×30,stone×20
```

## Prompt registration

`NexusAIApi.registerPrompt(plugin, localId, definition)` stores a prompt under `namespace:localId`. The namespace is the plugin name in lower case, and any character outside `[a-z0-9_-]` becomes `_`. `localId` matches `[a-z0-9_-]{1,64}`. The same plugin may register that id again to replace it. A second plugin with the same namespace gets `IllegalStateException`. The prompt is not written to a file. `/nai reload` keeps it. Disabling the owner plugin removes it.

A prompt registered from code cannot set `actions`, `dialogue`, or `context`. An admin override with the same id in `prompts.yml` replaces the prompt and can add those fields. The schema stored on the code prompt stays. `PromptDefinition.builder` sets the template, format, token cap, ttl, and fallback. `knowledgeSelect` and `knowledgeKeywords` set the same keys as `knowledge-select` and `knowledge-keywords`.

```java
@Override
public void onEnable() {
    if (getServer().getPluginManager().isPluginEnabled("NexusAI") && NexusAIApi.API_VERSION >= 3) {
        NexusAIApi.registerPrompt(this, "intro", PromptDefinition.builder(
                        "Write a two-sentence intro for the quest {quest}.")
                .format("chat")
                .maxTokens(120)
                .ttl(Duration.ofMinutes(30))
                .fallback("A new quest awaits.")
                .build());
    }
}
```

`NexusAIApi.quota(plugin)` reads today's tokens and requests for that plugin from memory. It is safe on any thread. The caps are empty when quotas are off or that consumer has no cap.

## Structured JSON

`NexusAIApi.generateJson(plugin, request, schema)` completes with a `JsonGenerationResult`. Local schema checks and correction retries run off the main thread. The future completes on a NexusAI thread by default (same as 1.2.0). When `plugin-api.response-validation.deliver-on-region` is true (opt-in) or the request sets `ResponseValidation.deliverOnRegion(true)`, and the request has a player entity, the future completes on that player's region thread instead. With response validation enabled (default), `success()` is true only after the reply was extracted and checked against the schema (types, ranges, `enum` / action allowlists). `json()` is the compact text. `asMap()` uses `String`, `Long`, `Double`, `Boolean`, `List`, `Map`, and null. An integer that fits in a long is a `Long`. `mode()` is the mode that was actually requested after a downgrade: `JSON_SCHEMA`, `JSON_OBJECT`, or `PROMPT_ONLY`. `repaired()` is true when at least one correction retry was sent. `validationErrors()` has at most 10 lines and is empty when `success()` is true.

`plugin-api.response-validation` controls the checks: `enabled` (default true), `on-invalid` (`retry` or `fallback`, default `retry`), `max-retries` (0..3, default 1), and `deliver-on-region` (default false, opt-in). `GenerationRequest.responseValidation(...)` overrides those values for one call and may set `allowedActions` for a JSON property (default name `action`). `JsonSchema.withActionAllowlist(schema, property, actions)` embeds the allowlist in the schema as a string `enum` so the provider can see it. When `enabled` is false, NexusAI still extracts a JSON object and sanitizes strings, but does not run the schema or allowlist checks and does not send correction retries.

`generateJson(plugin, request)` reads `JsonSchema` from the registered prompt (`request.promptId()`). A prompt with no schema completes as `INVALID_REQUEST` and does not call the model. An unknown prompt id completes as `UNKNOWN_PROMPT`. An override of that prompt in `prompts.yml` can replace the text. It cannot replace the schema. There is no schema key in `prompts.yml`.

`request.format()` is ignored. A line is written at FINE: `Format is ignored for JSON.` `CacheMode` matches `generate`. Only a successful checked object is stored. The cache key ends with `json:` and the schema hash, so a text answer and a different schema do not share it.

`providers.<id>.structured-output` is optional and is not added to an existing file. Unset means `auto`. `auto` sends `response_format.type` `json_schema`, with `strict` true only when every object schema has `additionalProperties: false` and lists every property in `required`. HTTP 400 or 422 whose message mentions `response_format`, `json_schema`, or `json_object` sends the same call as `json_object`, then as a system instruction and no `response_format`. That pair of provider and model stays on the lower mode until `/nai reload`. The step is logged once at INFO, for example `openrouter/some-model: json_schema not supported, using json_object`. It is not the correction retry and it does not cool the queue row. `json-schema`, `json-object`, and `prompt` stay on that mode.

When the request and the prompt omit `max_tokens`, the JSON call uses 1024. That is not `api.max-tokens`. `0` still omits the field.

When `on-invalid` is `retry`, a reply that is not valid JSON, or that does not match the schema, is sent back up to `max-retries` times (default 1). Each retry uses the same queue row, model, and mode. `attempts` counts every HTTP call. `NexusPreGenerateEvent` is not fired again. All calls are counted in the token ledger. The quota reservation is taken again for each repair. `limits.*` is not spent a second time. If `finish_reason` is `length`, the retry uses `max_tokens` doubled, at most 4096, and does not send the cut reply back. When `on-invalid` is `fallback`, or retries are exhausted, `success()` is false, `meta().error().kind()` is `INVALID_JSON`, `validationErrors()` lists the paths, and `meta().text()` is the fallback. Prompt backoff is not started. HTTP 429 or 5xx on the first attempt uses the normal queue walk and does not spend a correction retry.

String values are stripped of section signs and markup, and configured secrets are masked. Check the values again before placing one in a command, a name, or a path. Do not join the future on the main thread or a region thread. Latency, world context, and validation patterns (including a compiling `generateJson` check and cancelling `NexusActionEvent`) are in [Limitations and best practices](limitations.md).

```java
JsonSchema schema = JsonSchema.parse("""
        {"type":"object","additionalProperties":false,
         "required":["title","goal","reward"],
         "properties":{
           "title":{"type":"string","maxLength":40},
           "goal":{"type":"string","maxLength":200},
           "reward":{"type":"integer","minimum":1,"maximum":1000}}}
        """);

NexusAIApi.generateJson(plugin, GenerationRequest.template("Invent a short fetch quest for a village blacksmith.")
                .cacheMode(CacheMode.FRESH)
                .build(), schema)
        .thenAccept(result -> {
            if (!result.success()) {
                return;
            }
            Map<String, Object> quest = result.asMap().orElseThrow();
            long reward = (Long) quest.get("reward");
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> createQuest(quest, reward));
        });
```

Live checks against Groq, OpenRouter, Ollama, and OpenAI are not part of the default build. They run only when `NEXUSAI_LIVE_JSON=true`. The quest schema above is the one those checks use. A provider that rejects `json_schema` is covered by the `auto` downgrade, which the mock exercises with HTTP 400. The Groq rows below are a manual run on 2026-10-09. OpenRouter, OpenAI, and Ollama are not recorded.

| Provider | Model | structured-output | Result (1.2.0, 2026-10-09) |
|---|---|---|---|
| Groq | openai/gpt-oss-20b | auto | `json_schema` with `strict: true` accepted; valid JSON on the first attempt. Usage reported (incl. reasoning tokens), no cost field. |
| Groq | openai/gpt-oss-20b | json-object / prompt | Works. `json-object` needed one repair retry in our run. |
| Groq | qwen/qwen3.8-27b | auto | `json_schema` with `strict: true` accepted; valid JSON. |
| Groq | allam-2-7b | auto | 400 "does not support response format `json_schema`" → downgraded to `json_object` (logged once, kept until `/nai reload`). Output often fails the schema; use a stronger model for JSON. |
| Groq | allam-2-7b | json-schema | 400, no downgrade in manual mode (expected). |
| OpenRouter | — | — | Not recorded. |
| OpenAI | — | — | Not recorded. |
| Ollama | — | — | Not recorded. |

Events are created only when that event's handler list has listeners. With none registered, a request is unchanged, except that a character-action task which starts after the 5 second wait does not run the command.

## Knowledge query

`GenerationRequest.Builder.knowledgeQuery` is the text used to pick paragraphs when the effective mode is `keywords`. When it is omitted, the prompt text is used, before a context block is appended to the user message. `knowledge-keywords` on the named prompt are added after the same tokenization, and a stop word in that list is kept. `Builder.knowledge` still replaces the file list. A template with no named prompt uses `knowledge.select` from `config.yml` and has no extra keywords. `full` ignores the query and sends the same block as before. `PromptDefinition.Builder.knowledgeSelect` and `knowledgeKeywords` set the same prompt keys for a prompt registered from code. `KnowledgeSelect` may gain values later. A `switch` should keep a `default` branch.

## Event order

A request that calls the model fires events in this order:

1. `NexusPreGenerateEvent` — after limits and quotas, before the first HTTP attempt. Cancellable.
2. `NexusProviderErrorEvent` — zero or more, one per failed HTTP attempt (HTTP 4xx or 5xx, a timeout, or a connection error). `willRetry` is true when another key, another queue row, or `fallback-model` will be tried.
3. Exactly one of `NexusPostGenerateEvent` (the model returned text and the filters have run) or `NexusGenerateFailEvent`.

Post is fired before the text is stored in the cache and before the future completes. The text, the prompt, and the action command cannot be changed. Pre does not carry the prompt.

A cancelled Pre fires `NexusGenerateFailEvent` with `NexusErrorKind.CANCELLED` and does not send HTTP. The quota reservation is released. The cancel reason is masked, query strings are removed, and it is clipped to 300 Unicode code points. It becomes `GenerationError.message()`.

A cache hit, joining a request that is already in flight, and an answer taken from a pool do not fire these events. Moderation does not fire Pre, Post, or Fail. `NexusProviderErrorEvent` does fire for a failed moderation HTTP call. `NexusModerationFlagEvent` fires after the line is appended to the moderation log and before staff are notified. It is not cancellable.

Placeholder, `/nai talk` (including a greeting), a pool refill, prewarm, `/nai test`, and a dialogue summary do not fire Pre or Fail when a limit, a quota, or a pause refuses the call. Prewarm fills the pool and stays quiet. Fail without Pre is allowed only for an API `generate`. An API call fires Fail for every failure, including a failure that never started HTTP. One `/nai talk` line fires one Pre. The model call after character actions is the same request. Post carries the final line. `NexusActionEvent` is between Pre and Post.

`attempts` counts HTTP attempts, including a later queue row. It does not start a second Pre. A JSON correction retry is the same request: one Pre, then Post or Fail, and `attempts` includes that second HTTP call.

## Threads

`NexusPreGenerateEvent`, `NexusPostGenerateEvent`, `NexusGenerateFailEvent`, `NexusProviderErrorEvent`, and `NexusModerationFlagEvent` are asynchronous. They run on a `nexusai-http-*` thread or on an `HttpClient` thread. They do not run on a region thread. A handler that needs the world schedules that work itself. A handler that blocks holds the worker and delays other requests on it.

`NexusActionEvent` is not asynchronous. On Folia, `as: console` fires it on the global region thread. That thread does not own the player, so `Bukkit.isOwnedByCurrentRegion(player)` is false. `as: player` fires it on the player's region thread, where `Bukkit.isOwnedByCurrentRegion(player)` is true. On Paper both run on the main thread, which owns the player, so the handler may read the region, the world, and the player's permissions directly. A listener can record `Bukkit.isPrimaryThread()`. When the player is online it can also record `Bukkit.isOwnedByCurrentRegion(player)`. Paper smoke checks `primary=true` for a console action. Folia smoke checks region ownership for a player action. Cancelling the event skips the command. The model is told `refused: blocked by server`. The action cooldown and the daily counter are not spent. The action log records that fact. It does not name a plugin, because Bukkit does not report which listener cancelled the event.

While the server is stopping, shutdown does not fire Fail. While NexusAI is disabling and the server is still running, an unfinished API request fires Fail with `SHUTDOWN` when the handler list can still be used.

A listener slower than 50 ms logs one warning per event class per five minutes. The warning names the listener plugins.

Every string NexusAI writes onto an event is masked. URL query strings are removed. Provider error messages are at most 300 Unicode code points. A moderation reason is at most 240.

`RequestOrigin` and `NexusErrorKind` may gain values later. A `switch` should keep a `default` branch.

## Listener

```java
public final class AiAudit implements Listener {
    @EventHandler(ignoreCancelled = true)
    public void onPre(NexusPreGenerateEvent event) {
        if (event.origin() == RequestOrigin.PLACEHOLDER && maintenance) {
            event.setCancelled(true);
            event.setCancelReason("maintenance");
        }
    }

    @EventHandler
    public void onPost(NexusPostGenerateEvent event) {
        stats.add(event.consumer(), event.usage().totalTokens());
    }

    @EventHandler(ignoreCancelled = true)
    public void onAction(NexusActionEvent event) {
        if (isInArena(event.player())) {
            event.setCancelled(true);
        }
    }
}
```

Register it with the plugin manager in `onEnable`. Keep each handler short. Read world state from a region scheduler, not from an asynchronous handler. Cancelling an action so the command never runs is also covered in [Limitations and best practices](limitations.md#validate-before-you-act).
