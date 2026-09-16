# NexusAI

Асинхронный инфраструктурный плагин для Paper: мост между игровым движком и моделями ИИ через PlaceholderAPI.

Другие плагины (меню, чат, голограммы) могут запрашивать текст у ИИ плейсхолдерами без блокировки главного потока и без просадки TPS.

## Требования

- Paper **26.2** (Java **25**)
- [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) 2.11.6+ (soft-depend)
- API-ключ OpenAI-совместимого провайдера

## Установка

1. Соберите shadow JAR: `./gradlew shadowJar`
2. Скопируйте `build/libs/NexusAI-0.2.0-SNAPSHOT.jar` в папку `plugins/`
3. Установите PlaceholderAPI
4. Задайте ключ (предпочтительно через окружение):

```bash
# Windows (PowerShell)
$env:NEXUSAI_API_KEY = "sk-..."

# Linux / macOS
export NEXUSAI_API_KEY=sk-...
```

Либо укажите `api.key` в `plugins/NexusAI/config.yml` (не коммитьте секреты).

Без ключа плагин загружается, пишет warning в лог и **не** отправляет HTTP-запросы — плейсхолдеры возвращают `fallback`.

## Конфигурация

`plugins/NexusAI/config.yml`:

| Секция | Параметры |
|--------|-----------|
| `api` | `provider`, `model`, `base-url` (пустой = дефолт провайдера), `key`, `connect-timeout`, `read-timeout` |
| `cache` | `ttl` (сек), `max-size` |
| `limits` | `requests-per-minute`, `requests-per-day`, `max-prompt-length` (по умолчанию **128**) |
| `pool` | `enabled`, `max-total-prompts`, `entries[]` (`prompt`, `size`, `min-threshold`) |
| `prewarm` | `enabled`, `refresh-before-ttl` (сек), `prompts[]` (поддерживает `{player}`) |
| `fallback` | строка при miss / лимитах / отсутствии ключа |

Приоритет API-ключа: **`NEXUSAI_API_KEY`** → `api.key` в YAML.

### Провайдеры

`api.provider` выбирает дефолтный `base-url`, если поле `api.base-url` пустое:

| provider | base-url по умолчанию |
|----------|------------------------|
| `openai` | `https://api.openai.com/v1` |
| `groq` | `https://api.groq.com/openai/v1` |
| `cerebras` | `https://api.cerebras.ai/v1` |
| `gemini` | `https://generativelanguage.googleapis.com/v1beta/openai` |
| `deepseek` | `https://api.deepseek.com` |

Явный `api.base-url` всегда побеждает. При старте в лог пишется: `Using provider: …, base-url: …, model: …`.

## Плейсхолдеры

### Уникальные ответы (пул)

```
%ainexus_generate_<промпт>%
```

Берёт и **удаляет** один ответ из пула для этого промпта. Если пул пуст — сразу `fallback`, параллельно `PoolService` может пополнить очередь (если промпт есть в `pool.entries`).

Пример `pool.entries`:

```yaml
pool:
  enabled: true
  max-total-prompts: 10
  entries:
    - prompt: "One short tip for miners"
      size: 3
      min-threshold: 1
```

### Общий TTL-кэш (голограммы)

```
%ainexus_cached_<промпт>%
```

Поведение:

1. Если ответ есть в TTL-кэше — сразу общий текст
2. Иначе мгновенно `fallback`, запрос уходит в фоне
3. Повторный резолв того же промпта после ответа отдаёт кэш до истечения TTL
4. Промпт длиннее `limits.max-prompt-length` → сразу `fallback`, без HTTP
5. In-flight дедупликация — параллельные одинаковые запросы не дублируют HTTP

### Prewarm

Секция `prewarm` прогревает TTL-кэш при старте и периодически обновляет промпты, когда запись уже не `isFresh` (возраст ≥ 80% TTL). Шаблоны с `{player}` на старте пропускаются; для них вызывайте `PrewarmService.warmForPlayer(playerName)`.

## Сборка

```bash
./gradlew test
./gradlew shadowJar
```

Зависимости Jackson и Caffeine упакованы в JAR и relocated в `io.github.neareststep.nexusai.libs.*`.

Стек тестов: JUnit 5 (без Mockito — совместимость с Java 25).

## Архитектура (кратко)

- `PluginConfig` — config.yml + env + дефолты провайдеров
- `AiCache` — Caffeine (TTL + max-size + `isFresh`)
- `AiPool` / `PoolService` — очереди уникальных ответов для `generate_`
- `PrewarmService` — прогрев и refresh TTL-кэша для `cached_`
- `RateLimiter` — лимиты на игрока и на сервер
- `AiProvider` / `OpenAiProvider` — HTTP к `/chat/completions`
- `AiHttpClient` — кэш + in-flight + `generateFreshAsync` для пула
- `AiPlaceholderExpansion` — `%ainexus_generate_*%` / `%ainexus_cached_*%`

## License

MIT License — Copyright (c) 2026 mo00Wy. Полный текст: [LICENSE](LICENSE).
