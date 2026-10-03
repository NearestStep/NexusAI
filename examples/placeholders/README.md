# Shared tip and a join line

Two prompts:

- `%ainexus_cached_survival_tips%` is one shared sentence. TAB, a scoreboard, and a menu can all use it. The first look may show `...` until the request finishes. Later looks reuse the cache for `ttl` seconds (600).
- `%ainexus_generate_join_welcome%` takes one pooled answer and replaces `{player_name}` with `%player_name%` for the player who is looking. PlaceholderAPI must be installed, including the Player expansion if you use `%player_name%`.

## Files

Copy `config.yml` and `prompts.yml` to `plugins/NexusAI/`. Set `GROQ_API_KEY` in the server process (see [Quick start](../../docs/quickstart.md)). The queue uses `llama-3.3-70b-versatile`. Change the model id if your Groq account does not serve that model.

`pool.entries[].prompt` is the id `join_welcome`, so the placeholder argument must be that id. `prewarm.prompts` lists `survival_tips` only. `join_welcome` stays out of prewarm because the answer is personal. Neither prompt sets `context:`.

Display fragments that reference `survival_tips`: [tab](../tab/README.md), [scoreboard](../scoreboard/README.md), [deluxemenus](../deluxemenus/README.md).
