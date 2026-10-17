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
