# TAB scoreboard

Shows `%ainexus_cached_survival_tips%` on a TAB scoreboard. Install the prompt first: [placeholders](../placeholders/README.md). PlaceholderAPI must be installed. NexusAI registers the `ainexus` expansion on its own.

## Paste

`tab-scoreboard.yml` is not a full TAB config. In `plugins/TAB/config.yml`:

1. Set `scoreboard.enabled` to `true`.
2. Add the `nexusai-tips` entry under `scoreboard.scoreboards`, next to the boards you already have.

The keys match TAB's scoreboard feature (`title`, `lines`). A line that is only an empty placeholder is hidden by TAB, so keep the label line (`&7Tip`) above the placeholder. The first refresh can be `...` until NexusAI has an answer. Reload TAB after the edit (`/tab reload`).

This fragment does not replace `plugins/NexusAI/config.yml`.
