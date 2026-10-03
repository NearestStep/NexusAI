# NPC dialogue

A blacksmith for `/nai talk`. The prompt text is the character. The player's chat is wrapped as player input and is not the prompt.

## Files

Copy `config.yml` and `prompts.yml` to `plugins/NexusAI/`. Set `GROQ_API_KEY` on the server process.

Grant `nexusai.talk` to the players who should talk. They do not need `nexusai.command`. Operators already have it.

From a player:

```
/nai talk blacksmith
```

The greeting is the fixed line `Need something forged?` (`dialogue.greeting`), so that open does not call the model. Later chat in the session does. `/nai talk end` closes it. The id on the chat line keeps the spelling of the command that opened the session.

Citizens, as the clicking player (leave the slash off unless that build requires it):

```
/npc command add -p nai talk blacksmith
```

From the console, name the player: `nai talk <player> blacksmith`.

The action `give_iron` runs `give {player} iron_ingot 1` as the console, at most once a day, and only for a player with `nexusai.action.give_iron`. That node is not in `plugin.yml`. Grant it yourself. The model can only choose the action name.

`dialogue.summary.enabled` is `false` in this file, which is the default. Turning it on is described in the [FAQ](../../docs/faq.md#dialogue-summaries).
