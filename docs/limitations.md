# Limitations and best practices

Where NexusAI fits, and how to use model replies safely. The same points are summarised in the [README](../README.md#limitations-and-best-practices). Compiling examples live in `src/test/java/io/github/neareststep/nexusai/api/LimitationsBestPracticesExample.java`.

## Response latency

An API answer takes from hundreds of milliseconds to several seconds. That includes the HTTP round trip, the provider queue, context providers, and any JSON repair retry. NexusAI is not suitable for real-time reactions such as PvP hit detection, knockback, or frame-tight combat. Put hard logic in your plugin for those paths. Use NexusAI for dialogue, tips, quest text, menus, and other work that can wait for a future to complete on a NexusAI thread, then hop onto the player's scheduler (or the global region scheduler) before touching the world.

Do not join `generate` / `generateJson` / `talk` on the main thread or a region thread. See [Plugin API](api.md#text-generation) and [Threads](api.md#threads).

## The model does not see the world

The model only receives the prompt text you send. It does not read blocks, coordinates, inventory, or nearby entities by itself. The calling plugin collects that state on the correct thread (Paper main thread, or the region owner's thread on Folia) and passes it into the prompt:

- **Request variables** — `GenerationRequest.Builder.var` / `vars` for one call.
- **Context providers** — `NexusContextProvider` plus a prompt `context:` list, for shared lines (balance, rank, quest). See [Context from your own plugin](faq.md#context-from-your-own-plugin) and the README section "Context providers".
- **Named prompt text** — admin prompts in `prompts.yml`, or `NexusAIApi.registerPrompt`.

Built-in tokens such as `{player}`, `{world}`, `{biome}`, `{time}`, and `{weather}` are filled by NexusAI when a player is attached. Everything else is your responsibility.

NexusAI 1.3.0 adds an optional helper for this: `NexusAIApi.worldContext(plugin, player)` (and an overload with `WorldContextLimits`) returns one compact `World:` block with player, world, coordinates, inventory materials, nearby block counts, and biome. It is off by default; enable it with `world-context.enabled: true` and tune `world-context.radius`, `max-blocks`, `max-block-types`, `max-inventory-items`, `max-chars`, and `cooldown-millis`. Only a short snapshot is taken on the player's region thread; sorting and formatting run on `nexusai-context-*`. The future completes with `""` when the feature is off, NexusAI is disabled, or the player is gone, so do not depend on it being non-empty, and never join it on the main thread or a region thread. `NexusAIApi.worldContextEstimatedTokens(block)` returns the token estimate. See [World context](api.md#world-context).

## World context cost

`NexusAIApi.worldContext` is off by default (`world-context.enabled: false`). When it is on, the snapshot (player, inventory, nearby blocks, biome) is taken on the player's region thread: the main thread on Paper, the region owner on Folia. If the caller already owns that region, the snapshot runs inside the caller's current tick. Sorting and formatting run afterwards on `nexusai-context-*`.

The cost of a snapshot grows with the number of blocks it reads. The limits come from `config.yml` and are clamped by the loader. A `WorldContextLimits` override is clamped the same way:

| Key | Default | Range |
|-----|---------|-------|
| `world-context.radius` | 2 | 0..8, a cube around the block at the player's feet |
| `world-context.max-blocks` | 125 | 1..1331, hard cap on blocks examined |
| `world-context.cooldown-millis` | 250 | 0..5000 |

Within `cooldown-millis` a second call for the same player returns the last block without reading the world again, even when it passes different `WorldContextLimits`: the saved block is kept per player, not per set of limits. There is no shared per-tick budget and no merging of calls in flight: calls for the same player that arrive before the first block is ready each take their own snapshot. Keep `max-blocks` near the default if another plugin calls the helper often, and measure before raising it toward 1331.

## Context provider timeouts

Each `NexusContextProvider` runs on `nexusai-context-N` (2 threads, queue of 256), never on the main thread or a region thread. Three limits apply:

| Limit | Default | Where |
|-------|---------|-------|
| Provider request | 100 ms | `NexusContextProvider.timeout()` |
| Ceiling for one provider | 200 ms | `context.max-provider-timeout-millis` (10..1000); `timeout()` is clamped to it |
| Whole collect | 300 ms | `context.total-timeout-millis` (10..2000) |

A provider that is still blocking in `provide()` at its limit is interrupted and only its value is dropped. A provider that returned a future and completes it later on another thread is not interrupted: its value is ignored. The limit counts from the moment the call is queued, so a provider that waited for a free thread has less time, and it can log `timed out after <N> ms` after running for less than `<N>` ms. The other providers that finished are kept, and the request to the model goes ahead. A value that arrives after the limit is discarded and is not cached. When the whole collect reaches `total-timeout-millis`, the block is built from the providers that have finished.

A call that overruns its provider limit logs one INFO line: `Context provider '<id>' timed out after <N> ms and was skipped.` `<N>` is the limit that applied, `min(timeout(), context.max-provider-timeout-millis)`. Per provider there is at most one line per `limits.error-log-cooldown-seconds` (30). Timeouts inside that window are counted, and the next line adds `<K> more timeouts since the last notice.` (`1 more timeout` for one). The line is written on the `nexusai-scheduler` thread, never on the main thread or a region thread. A provider that never started because both threads were busy logs no line. `context.log-timeouts: false` turns the line off; the timeout still counts toward the pause below and in the `/nai status` count.

World context formatting (`NexusAIApi.worldContext`) runs on the same two threads and the same queue. Frequent `worldContext` calls can leave a provider without a free thread; that provider is skipped for the request and is not counted as a failure.

After `/nai reload`, `<K>` is counted again from zero: timeouts that were counted but not yet reported are not reported, and the next timeout logs a line right away, even inside the old cooldown window. Reload also clears the pause and the count in `/nai status`.

After `context.suspend-after-timeouts` (5) timeouts or exceptions in a row, the provider is paused for `context.suspend-seconds` (60) and the log shows `Context provider '<id>' suspended until ...`. An exception is logged as a warning, at most once per `limits.error-log-cooldown-seconds` per provider. `/nai status` lists each provider with its state and a count labelled `timeouts`. The count goes up on every failed call, a timeout or an exception, so a provider that only throws also shows a growing count. A provider that never started because both threads were busy is skipped for that request and does not count against it.

## Known limits with lone surrogates in names

A lone UTF-16 surrogate (half of an emoji) is replaced with U+FFFD in model replies, answers, and the files NexusAI writes. A provider or model name with one can only come from a YAML escape such as `\ud83d` that an admin wrote into `config.yml` or `prompts.yml`. Two limits remain for such a name:

- The name is sent to the provider as written, and `usage.yml` stores it with U+FFFD. After a restart, a fallback row with that name can appear twice in `usage.yml`, and the requests counted for it can be too low.
- `token-usage.yml` is written as UTF-8 bytes, so the same name is stored there with `?` instead of U+FFFD, and the key differs from the one in `usage.yml`.

Use plain names for providers and models to avoid both.

## Validate before you act

Prompt-injection protection and output sanitising reduce risk. They do not make structured answers safe to run as commands. Even when `generateJson` reports `success()` (JSON extracted and checked against your `JsonSchema`), check the values again in your plugin before changing the world: types you expect, ranges, allow-lists for action names, and strings that must not enter a command, a file name, or a path.

Never let the model change the world directly. Prefer fixed action commands offered by name (`actions:` on a character), and cancel anything unsafe with `NexusActionEvent`. For JSON drafts, apply only after your own checks, on the correct scheduler.

Two compiling patterns:

1. **`generateJson`** — require `success()`, read `asMap()`, then check each field before scheduling world work. Full class: `LimitationsBestPracticesExample` (methods `questSchema`, `requestQuestDraft`, `acceptQuest`).
2. **`NexusActionEvent`** — cancel the event so the command does not run; the model is told `refused: blocked by server`. Full class: same file (`ActionGuard` listener).

```java
NexusAIApi.generateJson(plugin, GenerationRequest.template(
                "Invent a short fetch quest. Player stands at {x},{y},{z} on {block}.")
                .player(player)
                .var("x", Integer.toString(blockX))
                .var("y", Integer.toString(blockY))
                .var("z", Integer.toString(blockZ))
                .var("block", blockType)
                .cacheMode(CacheMode.FRESH)
                .build(), schema)
        .thenAccept(result -> {
            Optional<AcceptedQuest> quest = LimitationsBestPracticesExample.acceptQuest(result);
            if (quest.isEmpty()) {
                return;
            }
            AcceptedQuest draft = quest.get();
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> createQuest(draft));
        });
```

```java
@EventHandler(ignoreCancelled = true)
public void onAction(NexusActionEvent event) {
    if ("give_iron".equals(event.actionName()) && isInArena(event.player())) {
        event.setCancelled(true);
    }
}
```

NexusAI 1.3.0 validates `generateJson` replies itself under `plugin-api.response-validation.*`: `enabled` (default `true`), `on-invalid` (`retry` or `fallback`, default `retry`), `max-retries` (0..3, default 1), and `deliver-on-region` (default `false`). With validation on, `success()` is true only after the reply matched the schema (types, ranges, `enum` and action allowlists); otherwise the result is `INVALID_JSON` with the fallback text. `GenerationRequest.responseValidation(...)` overrides the config per call and can set `allowedActions`, and `JsonSchema.withActionAllowlist` shows the allowlist to the provider. When `enabled` is false, the schema and allowlist checks are skipped. Schema matching inside NexusAI is still not a substitute for application checks. See [Structured JSON](api.md#structured-json).

Related reading: [Structured JSON](api.md#structured-json), [Event order](api.md#event-order) (`NexusActionEvent`), [Why did generateJson fail?](faq.md#why-did-generatejson-fail), README [Security](../README.md#security) (injection and markup).

## Prompt injection still matters

The player-input boundary and the output filter reduce prompt-injection risk. They do not eliminate it. Prefer a stronger model in `model-queue`, keep a fallback, and treat every model string as untrusted until your code accepts it. Details and measured rates are in the README section linked above.
