package com.cliagent.config

import com.cliagent.llm.LlmProvider
import com.cliagent.mcp.McpServerConfig
import kotlinx.serialization.json.Json
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/**
 * Загрузка/сохранение [AppConfig] (день 20). Единый JSON-файл [AppPaths.configFile]
 * (`~/.config/cli-agent/config.json`) — масштабируемая точка конфигурации.
 *
 * **Приоритет источников** (высший → низший):
 * 1. **env vars** — override одиночных полей (секреты/CI): `CLI_AGENT_API_KEY`,
 *    `CLI_AGENT_MODEL`, `CLI_AGENT_BASE_URL`, `CLI_AGENT_MAX_TOOL_ROUNDS`.
 *    Массив серверов (`mcp`) через env НЕ задаётся — только через config.json (пути A/B).
 * 2. **`config.json`** — primary source: `mcp`-массив, `maxToolRounds`, base settings.
 * 3. **`local.properties`** — legacy fallback: `api.key`, `model`, `base.url`,
 *    `mcp.command`/`mcp.url`/`mcp.token`. Legacy single-server → single-element `mcp:[default]`
 *    (только если `mcp` в config.json пуст) → **0 регрессий** Day 17–19.
 *
 * `apiKey` — required для LLM-работы; если отсутствует везде → `error()` (как прежде).
 *
 * @param configFile переопределение пути config.json (default [AppPaths.configFile]). DI-шов для
 *   тестов: позволяет подсунуть temp-файл (@TempDir) без патчинга статичных [AppPaths].
 * @param localPropertiesFile переопределение пути local.properties (default `./local.properties`).
 */
class ConfigRepository(
    private val configFile: Path = AppPaths.configFile,
    private val localPropertiesFile: Path = Path.of("local.properties"),
) {

    private val json = Json {
        ignoreUnknownKeys = true     // forward compat: новые поля не ломают старый код
        encodeDefaults = true        // все поля всегда в JSON — явнее
        explicitNulls = false        // null-поля не пишутся — компактнее
        prettyPrint = true           // человекочитаемый config.json
        coerceInputValues = true     // неизвестные enum → default
    }

    /**
     * Собирает [AppConfig] из трёх источников по приоритету. Бросает [IllegalStateException]
     * если `apiKey` не найден нигде (LLM бесполезен без ключа).
     */
    fun load(): AppConfig {
        val localProps = loadLocalProperties()
        val fileConfig = loadConfigFile()

        // 1. provider: env > config.json (default blank → autoDetect в LlmClientFactory).
        //    Резолвим рано, чтобы решить, нужен ли apiKey (Ollama — без auth).
        val provider = System.getenv("CLI_AGENT_PROVIDER")
            ?: fileConfig.provider.ifBlank { null }
            ?: localProps.getProperty("provider")
            ?: ""

        // 2. apiKey: env > config.json > local.properties. REQUIRED для cloud-провайдеров,
        //    OPTIONAL для Ollama (no-auth). День 25: локальная LLM не требует ключа.
        val resolvedProvider = LlmProvider.fromString(provider)
            ?: LlmProvider.autoDetect(
                System.getenv("CLI_AGENT_BASE_URL")
                    ?: fileConfig.baseUrl.ifBlank { null }
                    ?: localProps.getProperty("base.url")
                    ?: "https://api.z.ai/api/coding/paas/v4"
            )
        val apiKey = System.getenv("CLI_AGENT_API_KEY")
            ?: fileConfig.apiKey.takeIf { it.isNotBlank() }
            ?: localProps.getProperty("api.key")
            ?: if (resolvedProvider.requiresApiKey()) {
                error(
                    "API key not found. Set CLI_AGENT_API_KEY environment variable, " +
                        "or add apiKey to ${AppPaths.configFile}, or api.key=<your-key> to local.properties. " +
                        "For local Ollama (no auth): set provider=ollama (CLI_AGENT_PROVIDER=ollama)."
                )
            } else {
                ""   // Ollama и прочие no-auth провайдеры — пустой apiKey OK.
            }

        // 3. model/baseUrl/maxToolRounds: env override > file > local.properties
        val model = System.getenv("CLI_AGENT_MODEL") ?: fileConfig.model.ifBlank { null } ?: localProps.getProperty("model") ?: "glm-5.1"
        val baseUrl = System.getenv("CLI_AGENT_BASE_URL") ?: fileConfig.baseUrl.ifBlank { null } ?: localProps.getProperty("base.url")
            ?: "https://api.z.ai/api/coding/paas/v4"
        val maxToolRounds = System.getenv("CLI_AGENT_MAX_TOOL_ROUNDS")?.toIntOrNull() ?: fileConfig.maxToolRounds

        // 4. mcp-серверы: ТОЛЬКО из config.json. Legacy fallback, если файл пуст.
        val mcpServers = fileConfig.mcp.ifEmpty { legacyMcpServers(localProps) }

        // 5. День 21 (RAG): config.json как base; env override одиночных полей (как для model/baseUrl).
        //    corpusRoots/defaultStrategy через env НЕ задаётся — только через config.json (как mcp).
        val rag = fileConfig.rag.let { base ->
            base.copy(
                embeddingProvider = System.getenv("CLI_AGENT_RAG_PROVIDER") ?: base.embeddingProvider,
                embeddingModel = System.getenv("CLI_AGENT_RAG_EMBEDDING_MODEL") ?: base.embeddingModel,
                embeddingBaseUrl = System.getenv("CLI_AGENT_RAG_EMBEDDING_URL") ?: base.embeddingBaseUrl,
                chunkSizeTokens = System.getenv("CLI_AGENT_RAG_CHUNK_SIZE")?.toIntOrNull() ?: base.chunkSizeTokens,
                chunkOverlapTokens = System.getenv("CLI_AGENT_RAG_CHUNK_OVERLAP")?.toIntOrNull() ?: base.chunkOverlapTokens,
                // День 24: порог анти-галлюцинации («не знаю» при слабом контексте). 0.0 = выключено.
                dontKnowThreshold = System.getenv("CLI_AGENT_RAG_DONT_KNOW_THRESHOLD")?.toFloatOrNull() ?: base.dontKnowThreshold,
                // День 25: conversation-aware retrieval (env "true"/"1"/"yes" → on).
                conversationalQuery = System.getenv("CLI_AGENT_RAG_CONVERSATIONAL_QUERY")
                    ?.let { it.equals("true", true) || it == "1" || it.equals("yes", true) }
                    ?: base.conversationalQuery,
            )
        }

        return AppConfig(
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            provider = provider,
            maxToolRounds = maxToolRounds,
            mcp = mcpServers,
            rag = rag,
            // День 30 (streaming SSE): env CLI_AGENT_STREAM > config.json > default "auto".
            // Паттерн симметричен CLI_AGENT_PROVIDER (env override одиночного строкового поля).
            stream = System.getenv("CLI_AGENT_STREAM")
                ?.takeIf { it.isNotBlank() }
                ?: fileConfig.stream,
        )
    }

    /**
     * Сохраняет [config] в [AppPaths.configFile] (atomicWrite: temp+rename). Секреты
     * (`apiKey`, `token`) пишутся в файл — для пользовательских машин; права 600 рекомендованы.
     * Создаёт [AppPaths.configDir] при отсутствии.
     */
    fun save(config: AppConfig) {
        Files.createDirectories(configFile.parent)
        atomicWrite(configFile, json.encodeToString(AppConfig.serializer(), config))
    }

    /**
     * Добавляет сервер в `mcp`-секцию config.json (путь B: REPL `/mcp add`). Если сервер с таким
     * `name` уже есть — заменяет его. Остальные секции (apiKey/model/...) сохраняются. Если файл
     * отсутствует/нечитаем — начинает с пустого [AppConfig]. Возвращает обновлённый конфиг.
     */
    fun addMcpServer(server: McpServerConfig): AppConfig {
        val current = loadConfigFile()
        val updated = current.copy(mcp = (current.mcp.filter { it.name != server.name } + server))
        save(updated)
        return updated
    }

    /**
     * Удаляет сервер по имени из `mcp`-секции (REPL `/mcp remove`). Возвращает `true` если сервер
     * существовал и удалён. Остальные секции сохраняются.
     */
    fun removeMcpServer(name: String): Boolean {
        val current = loadConfigFile()
        if (current.mcp.none { it.name == name }) return false
        save(current.copy(mcp = current.mcp.filter { it.name != name }))
        return true
    }

    /**
     * Включает/отключает сервер по имени (REPL `/mcp enable|disable <name>`). Сервер остаётся в
     * config.json, но `enabled=false` → skip'ается при сборке [toolExecutor] (как при `remove`,
     * но без потери записи — быстрое отключение/включение без пере-добавления). Возвращает `true`
     * если сервер найден и флаг изменён; `false` если сервера нет ИЛИ флаг уже в нужном состоянии.
     */
    fun setMcpServerEnabled(name: String, enabled: Boolean): Boolean {
        val current = loadConfigFile()
        var changed = false
        val updatedMcp = current.mcp.map { server ->
            if (server.name == name && server.enabled != enabled) {
                changed = true
                server.copy(enabled = enabled)
            } else server
        }
        if (changed) save(current.copy(mcp = updatedMcp))
        return changed
    }


    /**
     * Устанавливает поле LLM-конфигурации (REPL `/config set <field> <value>`, день 25). Поддерживаемые
     * fields: `provider`, `model`, `baseUrl`, `apiKey`. Остальные секции (mcp, rag, maxToolRounds)
     * сохраняются. Возвращает обновлённый [AppConfig]. Неизвестный field → IllegalArgumentException.
     *
     * ВАЖНО: меняет config.json, но НЕ активную сессию REPL — для применения нужен рестарт `chat`
     * (или перезагрузка config в будущем). Это сознательное упрощение: live-switch провайдера
     * потребовал бы пересоздания [com.cliagent.llm.LlmClient] и всех зависимых агентов.
     */
    fun setLlmField(field: String, value: String): AppConfig {
        val current = loadConfigFile()
        val updated = when (field.lowercase()) {
            "provider" -> current.copy(provider = value)
            "model" -> current.copy(model = value)
            "baseurl", "base_url", "base-url" -> current.copy(baseUrl = value)
            "apikey", "api_key", "api-key" -> current.copy(apiKey = value)
            else -> throw IllegalArgumentException(
                "Unknown field '$field'. Supported: provider, model, baseUrl, apiKey."
            )
        }
        save(updated)
        return updated
    }

    /**
     * Генерирует стартовый config.json из текущих env/properties (REPL `/config init`, день 20).
     * Помогает миграции с legacy. НЕ перезаписывает существующий файл (возвращает false) — явное
     * удаление/переименование лежит на пользователе.
     */
    fun initFromLegacy(): Boolean {
        if (Files.exists(configFile)) return false
        val localProps = loadLocalProperties()
        val config = AppConfig(
            apiKey = (System.getenv("CLI_AGENT_API_KEY") ?: localProps.getProperty("api.key")).orEmpty(),
            model = System.getenv("CLI_AGENT_MODEL") ?: localProps.getProperty("model") ?: "glm-5.1",
            baseUrl = System.getenv("CLI_AGENT_BASE_URL") ?: localProps.getProperty("base.url")
                ?: "https://api.z.ai/api/coding/paas/v4",
            provider = System.getenv("CLI_AGENT_PROVIDER") ?: localProps.getProperty("provider") ?: "",
            maxToolRounds = System.getenv("CLI_AGENT_MAX_TOOL_ROUNDS")?.toIntOrNull() ?: 8,
            mcp = legacyMcpServers(localProps),
        )
        save(config)
        return true
    }

    /** Текущий файл конфига как [AppConfig] (пустой, если файл отсутствует/нечитаем — graceful). */
    internal fun loadConfigFile(): AppConfig {
        if (!Files.exists(configFile)) return AppConfig()
        return runCatching {
            json.decodeFromString(AppConfig.serializer(), Files.readString(configFile, Charsets.UTF_8))
        }.getOrElse {
            // битый JSON — не роняем приложение; env/legacy дадут базовые поля.
            System.err.println("⚠️ config.json parse error: ${it.message}; ignoring file.")
            AppConfig()
        }
    }

    /**
     * Legacy single-server → single-element список (0 регрессий Day 17–19). Приоритет
     * `mcp.url`+`mcp.token` (remote) над `mcp.command` (stdio), как прежде.
     */
    private fun legacyMcpServers(localProps: Properties): List<McpServerConfig> {
        val url = (System.getenv("CLI_AGENT_MCP_URL") ?: localProps.getProperty("mcp.url"))?.takeIf { it.isNotBlank() }
        if (url != null) {
            val token = System.getenv("CLI_AGENT_MCP_TOKEN") ?: localProps.getProperty("mcp.token")
            return listOf(McpServerConfig(name = "default", url = url, token = token))
        }
        val command = (System.getenv("CLI_AGENT_MCP_COMMAND") ?: localProps.getProperty("mcp.command"))
            ?.takeIf { it.isNotBlank() }
            ?.trim()
        if (command != null) {
            val parts = command.split("\\s+".toRegex())
            if (parts.isNotEmpty()) {
                return listOf(
                    McpServerConfig(name = "default", command = parts.first(), args = parts.drop(1))
                )
            }
        }
        return emptyList()
    }

    private fun loadLocalProperties(): Properties {
        val props = Properties()
        val file = localPropertiesFile.toFile()
        if (file.exists()) {
            FileInputStream(file).use { props.load(it) }
        }
        return props
    }

    private fun atomicWrite(target: Path, content: String) {
        // UTF-8 явно (не системная кодировка) — фикс для эмодзи/кириллицы (как JsonChatStore, Day 19).
        val tmp = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
        Files.writeString(tmp, content, Charsets.UTF_8)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }
}
