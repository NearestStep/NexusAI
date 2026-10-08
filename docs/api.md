# NexusAI plugin API

`NexusAIApi.API_VERSION` is 3. New methods are additive. This page describes the Bukkit events added for other plugins. `NexusAIApi.generate`, `registerPrompt`, and `quota` are documented on those classes.

Events are created only when that event's handler list has listeners. With none registered, a request is unchanged, except that a character-action task which starts after the 5 second wait does not run the command.

## Event order

A request that calls the model fires events in this order:

1. `NexusPreGenerateEvent` — after limits and quotas, before the first HTTP attempt. Cancellable.
2. `NexusProviderErrorEvent` — zero or more, one per failed HTTP attempt (HTTP 4xx or 5xx, a timeout, or a connection error). `willRetry` is true when another key, another queue row, or `fallback-model` will be tried.
3. Exactly one of `NexusPostGenerateEvent` (the model returned text and the filters have run) or `NexusGenerateFailEvent`.

Post is fired before the text is stored in the cache and before the future completes. The text, the prompt, and the action command cannot be changed. Pre does not carry the prompt.

A cancelled Pre fires `NexusGenerateFailEvent` with `NexusErrorKind.CANCELLED` and does not send HTTP. The quota reservation is released. The cancel reason is masked, query strings are removed, and it is clipped to 300 Unicode code points. It becomes `GenerationError.message()`.

A cache hit, joining a request that is already in flight, and an answer taken from a pool do not fire these events. Moderation does not fire Pre, Post, or Fail. `NexusProviderErrorEvent` does fire for a failed moderation HTTP call. `NexusModerationFlagEvent` fires after the line is appended to the moderation log and before staff are notified. It is not cancellable.

Placeholder, `/nai talk` (including a greeting), and pool refills do not fire Pre or Fail when a limit, a quota, or a pause refuses the call. An API `generate` fires Fail for every failure, including a failure that never started HTTP. One `/nai talk` line fires one Pre. The model call after character actions is the same request. Post carries the final line. `NexusActionEvent` is between Pre and Post.

`attempts` counts HTTP attempts, including a later queue row. It does not start a second Pre.

## Threads

`NexusPreGenerateEvent`, `NexusPostGenerateEvent`, `NexusGenerateFailEvent`, `NexusProviderErrorEvent`, and `NexusModerationFlagEvent` are asynchronous. They run on a `nexusai-http-*` thread or on an `HttpClient` thread. They do not run on a region thread. A handler that needs the world schedules that work itself. A handler that blocks holds the worker and delays other requests on it.

`NexusActionEvent` is not asynchronous. `as: console` fires it on the global region thread. `as: player` fires it on the player's region thread. On Paper both are the main thread, so the handler may read the region, the world, and the player's permissions directly. Cancelling it skips the command. The model is told `refused: blocked by server`. The action cooldown and the daily counter are not spent. The action log records that fact. It does not name a plugin, because Bukkit does not report which listener cancelled the event.

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
