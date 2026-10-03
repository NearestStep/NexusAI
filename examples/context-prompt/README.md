# Prompt with `context:`

`shop_tip` asks for the context provider `economy`. `%ainexus_cached_shop_tip%` and `/nai test shop_tip` (run by a player) append that provider's line. The pool and prewarm do not list this id: a shared answer must not carry one player's balance.

Copy `config.yml` and `prompts.yml` to `plugins/NexusAI/`. Set `GROQ_API_KEY` on the server process.

Until a plugin registers an id of `economy`, startup logs:

`Prompt 'shop_tip' lists unknown context provider 'economy'.`

The prompt still loads. With no provider, the request is the template alone.

## Provider

Implement `NexusContextProvider` and register it from your plugin (`softdepend: [NexusAI]`, compile against the NexusAI jar):

```java
NexusAIApi.registerContextProvider(this, provider);
```

`id()` returns `economy`. `provide()` runs on `nexusai-context-N` and must return a future without blocking. `ContextRequest` has no `Player`. Read the balance on the main thread into a map, and return the map from `provide()`.

The worked class is `src/test/java/io/github/neareststep/nexusai/context/ExampleBalanceProvider.java`. It rounds `12347.18` to `~12k` so the `cached_` key does not change on every coin. The same shape is in the README section "Context providers".

`context: all` would call every registered provider. A prompt with no `context:` key does not call any.
