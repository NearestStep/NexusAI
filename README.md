# NexusAI

Асинхронный инфраструктурный плагин для Paper: мост между игровым движком и моделями ИИ через PlaceholderAPI.

Другие плагины (меню, чат, голограммы) могут запрашивать текст у ИИ плейсхолдером `%ainexus_generate_<промпт>%` без блокировки главного потока и без просадки TPS.

## Требования

- Paper **26.2** (Java **25**)
- [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) 2.11.6+ (soft-depend)
- API-ключ OpenAI-совместимого провайдера

## Установка

1. Соберите shadow JAR: `./gradlew shadowJar`
2. Скопируйте `build/libs/NexusAI-0.1.0-SNAPSHOT.jar` в папку `plugins/`
3. Установите PlaceholderAPI
4. Задайте ключ (предпочтительно через окружение):

```bash
# Windows (PowerShell)
$env:NEXUSAI_API_KEY = "sk-..."

# Linux / macOS
export NEXUSAI_API_KEY=sk-...
```

Либо укажите `api.key` в `plugins/NexusAI/config.yml` (не коммитьте секреты).

Без ключа плагин загружается, пишет warning в лог и **не** отправляет HTTP-запросы — плейсхолдер всегда возвращает `fallback`.

## Конфигурация

`plugins/NexusAI/config.yml`:

| Секция | Параметры |
|--------|-----------|
| `api` | `provider`, `model`, `base-url`, `key`, `connect-timeout`, `read-timeout` |
| `cache` | `ttl` (сек), `max-size` |
| `limits` | `requests-per-minute`, `requests-per-day`, `max-prompt-length` (по умолчанию **128**) |
| `fallback` | строка, возвращаемая при cache miss / лимитах / отсутствии ключа |

Приоритет API-ключа: **`NEXUSAI_API_KEY`** → `api.key` в YAML.

Если `api.openai.com` недоступен из региона сервера (часто `403 Forbidden`), укажите в `api.base-url` любой OpenAI-compatible endpoint, до которого есть доступ, и соответствующий ключ/модель.

## Плейсхолдер

```
%ainexus_generate_<промпт>%
```

Примеры:

- `%ainexus_generate_Say hello%`
- `%ainexus_generate_One short tip for miners%`

Поведение:

1. Если ответ есть в кэше — сразу текст из кэша
2. Иначе мгновенно возвращается `fallback`, а запрос уходит в фоне (`CompletableFuture` + свой `ExecutorService`)
3. Повторный резолв того же промпта после ответа ИИ отдаёт кэш
4. Промпт длиннее `limits.max-prompt-length` (128) → сразу `fallback`, без HTTP
5. In-flight дедупликация через `ConcurrentHashMap.computeIfAbsent` — параллельные одинаковые запросы не дублируют HTTP

## Сборка

```bash
./gradlew test
./gradlew shadowJar
```

Зависимости Jackson и Caffeine упакованы в JAR и relocated в `io.github.neareststep.nexusai.libs.*`.

Стек тестов: JUnit 5 (без Mockito — совместимость с Java 25).

## Архитектура (кратко)

- `PluginConfig` — config.yml + env
- `AiCache` — Caffeine (TTL + max-size)
- `RateLimiter` — лимиты на игрока и на сервер
- `AiProvider` / `OpenAiProvider` — HTTP к `/chat/completions`
- `AiHttpClient` — кэш + in-flight + проверка ключа
- `AiPlaceholderExpansion` — регистрация `%ainexus_...%`

## License

MIT License — Copyright (c) 2026 mo00Wy. Полный текст: [LICENSE](LICENSE).
