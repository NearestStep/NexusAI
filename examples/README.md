# Examples

Copy a folder's `config.yml` and `prompts.yml` onto `plugins/NexusAI/`, or merge the keys into the files you already have. These files are fragments, not a full `config.yml`. The first start fills every missing default key (on the order of forty keys, including `http.*`, `context.*`, and `dialogue.summary.*`) and writes one `config.yml.bak`. A later start that finds nothing missing does not write another backup. The only edit the examples need is the API key (environment variable or the line in the key file).

Host-plugin files (`tab-scoreboard.yml`, `aitip.yml`, `tips.yml`) are fragments for that plugin. They are not NexusAI configs.

| Folder | What it shows |
|--------|----------------|
| [placeholders](placeholders/README.md) | A shared tip (`cached_`) and a join line (`generate_` + PlaceholderAPI) |
| [tab](tab/README.md) | That tip on a TAB scoreboard |
| [scoreboard](scoreboard/README.md) | That tip on an AnimatedScoreboard |
| [deluxemenus](deluxemenus/README.md) | That tip in a DeluxeMenus lore line |
| [npc-dialogue](npc-dialogue/README.md) | `/nai talk` character, greeting, one action |
| [multi-provider](multi-provider/README.md) | Groq, Gemini, and DeepSeek with `round-robin` |
| [api-key](api-key/README.md) | `${ENV:VAR}` and `api-key-file` |
| [context-prompt](context-prompt/README.md) | A prompt with `context:`, linked to `ExampleBalanceProvider` |

Every `config.yml` and `prompts.yml` in this tree is loaded by `ExamplesConfigTest` with `PluginConfig` and `PromptCatalog`. An unknown key fails that test.
