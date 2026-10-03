# Groq, Gemini, and DeepSeek (round-robin)

`model-queue-strategy: round-robin` starts each new request at the next available row, then walks the circle the same way `failover` does after a failure. The default `failover` always starts at the first available row. Rows on cooldown, at `daily-request-limit`, or at or below `model-queue-remaining-threshold` are skipped in both modes.

## Files

Copy `config.yml` and `prompts.yml` to `plugins/NexusAI/`. Set these on the server process:

- `GROQ_API_KEY`
- `GEMINI_API_KEY`
- `DEEPSEEK_API_KEY`

An unset name is logged and becomes an empty key. That row cannot send. The others still can.

After `/nai reload`, `/nai status` shows `Model queue strategy: round-robin (next: <provider>/<model>)`. `usage.yml` still counts each row on its own.

Model ids in this file are the ones the README and the bundled config already use for Groq and Gemini (`llama-3.3-70b-versatile`, `gemini-2.0-flash`) and DeepSeek's chat id `deepseek-chat`. Change a row if your account does not serve that id.

`%ainexus_cached_survival_tips%` is a shared prompt with no `context:`, so every provider returns into the same cache key.
