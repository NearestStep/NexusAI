# Quick start

About five minutes from an empty `plugins/` folder to the first cached answer. The longer reference stays in the [README](../README.md). Worked files are in [examples/](../examples/README.md).

## 1. Install

1. Put `NexusAI-1.1.1.jar` in `plugins/`. A local `./gradlew shadowJar` writes `build/libs/NexusAI-1.1.1.jar`. The `version` in `build.gradle.kts` is `1.1.1`.
2. Install [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) 2.11.6 or newer.
3. Start the server once. NexusAI creates `plugins/NexusAI/` (`config.yml`, `prompts.yml`, `lang/`, `knowledge/example.md`). Stop the server before editing those files.

Paper or Purpur **1.20.6 through 26.2**, Java **21** or newer. Folia is not supported.

## 2. Set a key

Groq is the example below. Any OpenAI-compatible provider works the same way. Prefer an environment variable:

```bash
# Linux / macOS
export GROQ_API_KEY=sk-...

# Windows (PowerShell)
$env:GROQ_API_KEY = "sk-..."
```

The server process must see that variable. A variable set in a shell after the server has started is not visible until the next start.

In `plugins/NexusAI/config.yml` set the Groq provider and one queue row. Leave the other providers as they are.

```yaml
api:
  provider: groq
  model: llama-3.3-70b-versatile
  max-tokens: 256

providers:
  groq:
    type: openai-compatible
    url: "https://api.groq.com/openai/v1"
    api-key: "${ENV:GROQ_API_KEY}"

model-queue:
  - provider: groq
    model: llama-3.3-70b-versatile
```

`${ENV:GROQ_API_KEY}` and `${GROQ_API_KEY}` are the same placeholder. A key file is the other option: [examples/api-key](../examples/api-key/README.md). Do not put the key in `url`.

`api.max-tokens: 256` keeps a short reply under a small Groq output-token cap. Leave it at 256 unless you know you want the field omitted (`0` or a negative value).

## 3. Add one prompt

In `plugins/NexusAI/prompts.yml`:

```yaml
survival_tips:
  prompt: |
    Give one short Minecraft survival tip.
    One sentence, no markdown.
  ttl: 600
  fallback: "..."
```

The same prompt is in [examples/placeholders](../examples/placeholders/README.md).

## 4. Check the plugin

Start the server. From the console, or as an operator:

```
nai test survival_tips
nai status
```

`/nai test` needs `nexusai.command` and `nexusai.test` (both default to op). `/nai status` needs `nexusai.command` and `nexusai.status`.

What you want to see:

- `nai test survival_tips` prints `Answer (<ms> ms):` and a one-sentence tip.
- `nai status` shows `API key:` as `****` plus the last four characters, and `(env)` when the key came from the environment. `Last error:` is `none`. `Model queue strategy:` is `failover (next: groq/llama-3.3-70b-versatile)` unless you changed the strategy.

A missing variable is logged by name: `Environment variable GROQ_API_KEY is not set. Its placeholder was replaced with an empty value and is not used as an API key.` Placeholders then return the fallback and no HTTP request is sent. Fix the variable and run `/nai reload`, or restart if the variable was not in the server process.

## 5. Show it in game

Install the placeholder where a player will look at it. In TAB's scoreboard (see [examples/tab](../examples/tab/README.md)):

```yaml
- "%ainexus_cached_survival_tips%"
```

The first time a player resolves that placeholder, NexusAI may still be waiting on the model. The line shows `...` (the prompt fallback) and starts one background request. The next refresh shows the cached sentence for `ttl` seconds (600 here, otherwise `cache.ttl`).

`/nai reload` picks up edits to `config.yml`, `prompts.yml`, `knowledge/`, and `lang/`.
