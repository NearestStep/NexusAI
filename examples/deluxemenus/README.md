# DeluxeMenus

Shows `%ainexus_cached_survival_tips%` in an item lore line. Install the prompt first: [placeholders](../placeholders/README.md). PlaceholderAPI must be installed.

## Paste

1. Copy `aitip.yml` to `plugins/DeluxeMenus/gui_menus/aitip.yml`.
2. Register it in `plugins/DeluxeMenus/config.yml`:

```yaml
gui_menus:
  aitip:
    file: aitip.yml
```

3. Reload DeluxeMenus. The menu opens with `/aitip`.

The lore placeholder is resolved for the player who opens the menu. The first open can show `...` until NexusAI has cached the tip. This fragment does not replace `plugins/NexusAI/config.yml`.
