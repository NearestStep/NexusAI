# Migration to 1.3.0

Replace the previous jar with `NexusAI-1.3.0.jar` (or a `1.3.0-SNAPSHOT` build) on Paper or Purpur 1.20.6 through 26.2, or Folia 1.21.8, 1.21.11, 26.1.2, or 26.2, Java 21+ (Java 25 for Paper/Folia 26.x). Stop the server, swap the jar, start it. The first start appends the new keys. You do not edit `config-version`.

## What is written for you

On startup and on `/nai reload`, when `plugins/NexusAI/config.yml` is valid YAML, missing keys are copied from the jar default. The log line is:

`Added missing config keys: …`

The same start writes **one** backup: `config.yml.bak`, or `config.yml.bak.<timestamp>` when that backup already exists. The path is logged. A second start that finds every new key already present appends nothing and does not write another backup.

`config-version` stays **2**. No existing value changes meaning. A value you already set is not replaced.

Keys appended when they are absent:

| Key | Value written |
|-----|----------------|
| `plugin-api.response-validation.enabled` | `true` |
| `plugin-api.response-validation.on-invalid` | `retry` |
| `plugin-api.response-validation.max-retries` | `1` |
| `plugin-api.response-validation.deliver-on-region` | `false` |

With the defaults, `generateJson` behaviour matches 1.2.0: one correction retry, then `INVALID_JSON` with the fallback text, and the future still completes on a NexusAI thread. Set `on-invalid: fallback` to skip the correction retry. Set `enabled: false` to skip local schema and allowlist checks (JSON is still extracted when possible). Set `deliver-on-region: true` (or `ResponseValidation.deliverOnRegion(true)` on the request) only when you want the future to complete on the player's region thread.

`API_VERSION` stays **3**. New types are additive: `ResponseValidation`, `InvalidJsonAction`, `JsonSchema.withActionAllowlist`, and `GenerationRequest.Builder.responseValidation`.
