package com.cliagent.config

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

        // 1. apiKey: env > config.json > local.properties (required)
        val apiKey = System.getenv("CLI_AGENT_API_KEY")
            ?: fileConfig.apiKey.takeIf { it.isNotBlank() }
            ?: localProps.getProperty("api.key")
            ?: error(
                "API key not found. Set CLI_AGENT_API_KEY environment variable, " +
                    "or add apiKey to ${AppPaths.configFile}, or api.key=<your-key> to local.properties"
            )

        // 2. model/baseUrl/maxToolRounds: env override > file > local.properties
        val model = System.getenv("CLI_AGENT_MODEL") ?: fileConfig.model.ifBlank { null } ?: localProps.getProperty("model") ?: "glm-5.1"
        val baseUrl = System.getenv("CLI_AGENT_BASE_URL") ?: fileConfig.baseUrl.ifBlank { null } ?: localProps.getProperty("base.url")
            ?: "https://api.z.ai/api/coding/paas/v4"
        val maxToolRounds = System.getenv("CLI_AGENT_MAX_TOOL_ROUNDS")?.toIntOrNull() ?: fileConfig.maxToolRounds

        // 3. mcp-серверы: ТОЛЬКО из config.json. Legacy fallback, если файл пуст.
        val mcpServers = fileConfig.mcp.ifEmpty { legacyMcpServers(localProps) }

        return AppConfig(
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            maxToolRounds = maxToolRounds,
            mcp = mcpServers,
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
