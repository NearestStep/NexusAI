# NexusAI plugin API

`NexusAIApi.API_VERSION` is 3. New methods are additive. This page describes `generateJson` and the Bukkit events added for other plugins. `NexusAIApi.generate`, `registerPrompt`, and `quota` are documented on those classes.

## Structured JSON

`NexusAIApi.generateJson(plugin, request, schema)` completes with a `JsonGenerationResult` on a NexusAI thread. `success()` is true only after the reply was extracted and checked against the schema. `json()` is the compact text. `asMap()` uses `String`, `Long`, `Double`, `Boolean`, `List`, `Map`, and null. An integer that fits in a long is a `Long`. `mode()` is the mode that was actually requested after a downgrade: `JSON_SCHEMA`, `JSON_OBJECT`, or `PROMPT_ONLY`. `repaired()` is true when the one correction retry was sent. `validationErrors()` has at most 10 lines and is empty when `success()` is true.

`generateJson(plugin, request)` reads `JsonSchema` from the registered prompt (`request.promptId()`). A prompt with no schema completes as `INVALID_REQUEST` and does not call the model. An unknown prompt id completes as `UNKNOWN_PROMPT`. An override of that prompt in `prompts.yml` can replace the text. It cannot replace the schema. There is no schema key in `prompts.yml`.

`request.format()` is ignored. A line is written at FINE: `Format is ignored for JSON.` `CacheMode` matches `generate`. Only a successful checked object is stored. The cache key ends with `json:` and the schema hash, so a text answer and a different schema do not share it.

`providers.<id>.structured-output` is optional and is not added to an existing file. Unset means `auto`. `auto` sends `response_format.type` `json_schema`, with `strict` true only when every object schema has `additionalProperties: false` and lists every property in `required`. HTTP 400 or 422 whose message mentions `response_format`, `json_schema`, or `json_object` sends the same call as `json_object`, then as a system instruction and no `response_format`. That pair of provider and model stays on the lower mode until `/nai reload`. The step is logged once at INFO, for example `openrouter/some-model: json_schema not supported, using json_object`. It is not the correction retry and it does not cool the queue row. `json-schema`, `json-object`, and `prompt` stay on that mode.

When the request and the prompt omit `max_tokens`, the JSON call uses 1024. That is not `api.max-tokens`. `0` still omits the field.

A reply that is not valid JSON, or that does not match the schema, is sent back once. The retry uses the same queue row, model, and mode. `attempts` counts both HTTP calls. `NexusPreGenerateEvent` is not fired again. Both calls are counted in the token ledger. The quota reservation is taken again. `limits.*` is not spent a second time. If `finish_reason` is `length`, the retry uses `max_tokens` doubled, at most 4096, and does not send the cut reply back. If the retry still fails, `success()` is false, `meta().error().kind()` is `INVALID_JSON`, `validationErrors()` lists the paths, and `meta().text()` is the fallback. Prompt backoff is not started. HTTP 429 or 5xx on the first attempt uses the normal queue walk and does not spend this retry.

String values are stripped of section signs and markup, and configured secrets are masked. Check the values again before placing one in a command, a name, or a path. Do not join the future on the main thread or a region thread.

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

Live checks against Groq, OpenRouter, Ollama, and OpenAI are not part of the default build. They run only when `NEXUSAI_LIVE_JSON=true`. The quest schema above is the one those checks use. A provider that rejects `json_schema` is covered by the `auto` downgrade, which the mock exercises with HTTP 400.

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

`NexusActionEvent` is not asynchronous. `as: console` fires it on the global region thread. `as: player` fires it on the player's region thread. On Paper both are the main thread, so the handler may read the region, the world, and the player's permissions directly. A listener can record `Bukkit.isPrimaryThread()`. When the player is online it can also record `Bukkit.isOwnedByCurrentRegion(player)`. Paper smoke checks `primary=true` for a console action. Folia must check region ownership for a player action. That check is required in the later Folia smoke (PR9b). Cancelling the event skips the command. The model is told `refused: blocked by server`. The action cooldown and the daily counter are not spent. The action log records that fact. It does not name a plugin, because Bukkit does not report which listener cancelled the event.

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

Register it with the plugin manager in `onEnable`. Keep each handler short. Read world state from a region scheduler, not from an asynchronous handler.
