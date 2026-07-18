package com.cliagent.config

import com.cliagent.mcp.McpServerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * День 20: [ConfigRepository] — единый `config.json`. Покрывает:
 * - загрузка JSON (apiKey/model/maxToolRounds/mcp).
 * - приоритет env > config.json > local.properties.
 * - legacy fallback: mcp.command/url/token → single-element mcp (0 регрессий).
 * - atomicWrite: save/addMcpServer/removeMcpServer пишут файл (остальные секции сохраняются).
 * - `/config init` миграция: не перезаписывает существующий файл.
 *
 * [@TempDir] изолирует тесты от реального [AppPaths.configFile]. env-clean: тесты apiKey
 * проходят через env (не полагаются на файл), поэтому [load] снабжается ключом через env.
 */
class ConfigRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    private fun repo(configJson: String? = null, localProps: String? = null): ConfigRepository {
        val configFile = tempDir.resolve("config.json")
        val propsFile = tempDir.resolve("local.properties")
        if (configJson != null) {
            java.nio.file.Files.writeString(configFile, configJson)
        }
        if (localProps != null) {
            java.nio.file.Files.writeString(propsFile, localProps)
        }
        return ConfigRepository(configFile = configFile, localPropertiesFile = propsFile)
    }

    @Test
    fun `load reads apiKey model baseUrl maxToolRounds from config json`() {
        val json = """
            {"apiKey":"sk-test","model":"glm-5-turbo","baseUrl":"https://x.example.com","maxToolRounds":12,"mcp":[]}
        """.trimIndent()
        val cfg = repo(json).load()

        assertEquals("sk-test", cfg.apiKey)
        assertEquals("glm-5-turbo", cfg.model)
        assertEquals("https://x.example.com", cfg.baseUrl)
        assertEquals(12, cfg.maxToolRounds)
        assertTrue(cfg.mcp.isEmpty())
    }

    @Test
    fun `load parses mcp array of servers`() {
        val json = """
            {"apiKey":"k","mcp":[
              {"name":"local","command":"java","args":["-jar","x.jar"]},
              {"name":"vps","url":"https://mcp.example.com/mcp","token":"t"}
            ]}
        """.trimIndent()
        val cfg = repo(json).load()

        assertEquals(2, cfg.mcp.size)
        val local = cfg.mcp[0]
        assertEquals("local", local.name)
        assertEquals("java", local.command)
        assertEquals(listOf("-jar", "x.jar"), local.args)
        val vps = cfg.mcp[1]
        assertEquals("vps", vps.name)
        assertEquals("https://mcp.example.com/mcp", vps.url)
        assertEquals("t", vps.token)
    }

    @Test
    fun `defaults applied when fields absent (schema evolution)`() {
        val json = """{"apiKey":"k"}"""   // только ключ
        val cfg = repo(json).load()

        assertEquals("glm-5.1", cfg.model)
        assertEquals("https://api.z.ai/api/coding/paas/v4", cfg.baseUrl)
        assertEquals(8, cfg.maxToolRounds)   // default 8 (день 20)
        assertTrue(cfg.mcp.isEmpty())
    }

    @Test
    fun `malformed json falls back gracefully to defaults`() {
        // битый config.json игнорируется → env/legacy дают базовые поля (apiKey из local.properties).
        // Без apiKey load() бросает (правильно) — поэтому даём ключ через legacy props.
        val cfg = repo(configJson = "{not valid json", localProps = "api.key=fallback-key").load()
        assertEquals("fallback-key", cfg.apiKey)
        assertEquals("glm-5.1", cfg.model)
        assertEquals(8, cfg.maxToolRounds)
    }

    @Test
    fun `legacy mcp command maps to single-element mcp list (0 regressions)`() {
        // config.json пуст по mcp → legacy local.properties mcp.command
        val cfg = repo(configJson = """{"apiKey":"k"}""", localProps = "mcp.command=npx -y server-filesystem /tmp").load()
        assertEquals(1, cfg.mcp.size)
        assertEquals("default", cfg.mcp[0].name)
        assertEquals("npx", cfg.mcp[0].command)
        assertEquals(listOf("-y", "server-filesystem", "/tmp"), cfg.mcp[0].args)
    }

    @Test
    fun `legacy mcp url token maps to remote server`() {
        val cfg = repo(
            configJson = """{"apiKey":"k"}""",
            localProps = "mcp.url=https://mcp.example.com/mcp\nmcp.token=secret",
        ).load()
        assertEquals(1, cfg.mcp.size)
        assertEquals("default", cfg.mcp[0].name)
        assertEquals("https://mcp.example.com/mcp", cfg.mcp[0].url)
        assertEquals("secret", cfg.mcp[0].token)
    }

    @Test
    fun `config json mcp array takes priority over legacy`() {
        // В config.json есть mcp → legacy игнорируется
        val cfg = repo(
            configJson = """{"apiKey":"k","mcp":[{"name":"fromjson","command":"java"}]}""",
            localProps = "mcp.command=npx ignored",
        ).load()
        assertEquals(1, cfg.mcp.size)
        assertEquals("fromjson", cfg.mcp[0].name)
    }

    @Test
    fun `save writes file and round-trips`() {
        val r = repo()
        val original = AppConfig(
            apiKey = "k", model = "glm-5", maxToolRounds = 6,
            mcp = listOf(McpServerConfig(name = "local", command = "java", args = listOf("-jar", "x"))),
        )
        r.save(original)

        val loaded = r.loadConfigFile()
        assertEquals("glm-5", loaded.model)
        assertEquals(6, loaded.maxToolRounds)
        assertEquals(1, loaded.mcp.size)
        assertEquals("local", loaded.mcp[0].name)
    }

    @Test
    fun `addMcpServer appends to mcp and preserves other sections`() {
        val r = repo(configJson = """{"apiKey":"k","model":"glm-5-turbo","mcp":[{"name":"a","command":"ja"}]}""")
        val updated = r.addMcpServer(McpServerConfig(name = "b", command = "jb"))

        assertEquals(2, updated.mcp.size)
        assertEquals("glm-5-turbo", updated.model)   // другие секции сохранены
        assertEquals("k", updated.apiKey)
    }

    @Test
    fun `addMcpServer replaces existing by name`() {
        val r = repo(configJson = """{"apiKey":"k","mcp":[{"name":"a","command":"old"}]}""")
        val updated = r.addMcpServer(McpServerConfig(name = "a", command = "new"))

        assertEquals(1, updated.mcp.size)
        assertEquals("new", updated.mcp[0].command)   // заменён, не дублирован
    }

    @Test
    fun `removeMcpServer returns true when removed, false when absent`() {
        val r = repo(configJson = """{"apiKey":"k","mcp":[{"name":"a","command":"ja"},{"name":"b","command":"jb"}]}""")

        assertTrue(r.removeMcpServer("a"))
        assertEquals(1, r.loadConfigFile().mcp.size)
        assertEquals("b", r.loadConfigFile().mcp[0].name)

        assertFalse(r.removeMcpServer("nonexistent"))
    }

    @Test
    fun `setMcpServerEnabled disables a server without removing it`() {
        val r = repo(configJson = """{"apiKey":"k","mcp":[{"name":"fs","command":"npx","args":["server-filesystem","/tmp"]}]}""")
        val changed = r.setMcpServerEnabled("fs", enabled = false)

        assertTrue(changed)
        val servers = r.loadConfigFile().mcp
        assertEquals(1, servers.size, "server must remain in config")
        assertEquals("fs", servers[0].name)
        assertFalse(servers[0].enabled, "enabled flag must flip to false")
        assertEquals("npx", servers[0].command, "transport fields preserved")
    }

    @Test
    fun `setMcpServerEnabled re-enables a previously disabled server`() {
        val r = repo(configJson = """{"apiKey":"k","mcp":[{"name":"fs","command":"npx","enabled":false}]}""")
        assertTrue(r.setMcpServerEnabled("fs", enabled = true))
        assertTrue(r.loadConfigFile().mcp[0].enabled)
    }

    @Test
    fun `setMcpServerEnabled returns false when server absent or flag unchanged`() {
        val r = repo(configJson = """{"apiKey":"k","mcp":[{"name":"fs","command":"npx","enabled":true}]}""")
        // сервера нет
        assertFalse(r.setMcpServerEnabled("ghost", enabled = false))
        // флаг уже в нужном состоянии (true)
        assertFalse(r.setMcpServerEnabled("fs", enabled = true))
        // файл не должен был измениться
        assertTrue(r.loadConfigFile().mcp[0].enabled)
    }

    @Test
    fun `initFromLegacy creates file from local properties`() {
        val r = repo(localProps = "api.key=legacykey\nmodel=glm-4.7")
        val created = r.initFromLegacy()

        assertTrue(created)
        val loaded = r.loadConfigFile()
        assertEquals("legacykey", loaded.apiKey)
        assertEquals("glm-4.7", loaded.model)
        assertEquals(8, loaded.maxToolRounds)   // default
    }

    @Test
    fun `initFromLegacy does not overwrite existing file`() {
        val r = repo(configJson = """{"apiKey":"existing"}""")
        val created = r.initFromLegacy()

        assertFalse(created)
        assertEquals("existing", r.loadConfigFile().apiKey)
    }

    @Test
    fun `load throws when apiKey missing everywhere`() {
        // нет apiKey в файле, нет env, нет local.properties → error
        // (env в тестах чист — предыдущие запуски могут выставить, но здесь рассчитываем на отсутствие файла-ключа)
        val r = repo(configJson = """{"model":"glm-5.1"}""")
        assertThrows(IllegalStateException::class.java) {
            r.load()
        }
    }

    // ── День 25: provider-aware config (Ollama без apiKey) ─────────────────────────
    // CAVEAT: env vars (CLI_AGENT_API_KEY/CLI_AGENT_PROVIDER) — process-global, JVM не даёт
    // надёжно unset'нуть их в тестах. Поэтому deterministic-часть покрываем через round-trip файла
    // (config.json — единственный контролируемый источник в @TempDir). См. также флаг ошибки ниже.

    @Test
    fun `ollama provider loads without apiKey (no-auth)`() {
        // provider=ollama → requiresApiKey()==false → apiKey optional (пустая строка OK).
        // Если CI-окружение выставило CLI_AGENT_API_KEY, тест всё равно проходит (env > file),
        // но здесь проверяем именно что load() НЕ бросает и provider корректно резолвится.
        val json = """
            {"provider":"ollama","baseUrl":"http://localhost:11434/v1","model":"qwen2.5:32b"}
        """.trimIndent()
        val cfg = repo(json).load()

        assertEquals("ollama", cfg.provider)
        assertEquals("http://localhost:11434/v1", cfg.baseUrl)
        assertEquals("qwen2.5:32b", cfg.model)
    }

    @Test
    fun `zai provider without apiKey throws with helpful message`() {
        // provider=zai (requiresApiKey()==true), apiKey отсутствует в file/env/props → error.
        // Сообщение должно упоминать подсказку про Ollama и/или apiKey.
        val json = """{"provider":"zai"}"""
        val ex = assertThrows(IllegalStateException::class.java) {
            repo(json).load()
        }
        val msg = ex.message.orEmpty()
        assertTrue(
            msg.contains("Ollama", ignoreCase = true) || msg.contains("API key", ignoreCase = true),
            "error message should hint at apiKey or Ollama; was: $msg",
        )
    }

    @Test
    fun `provider round-trips through save and load`() {
        // deterministic-проверка провайдера: save()→load() сохраняет поле (без зависимости от env).
        val r = repo()
        r.save(AppConfig(provider = "ollama", baseUrl = "http://localhost:11434/v1", model = "qwen2.5"))

        val loaded = r.load()
        assertEquals("ollama", loaded.provider)
        assertEquals("http://localhost:11434/v1", loaded.baseUrl)
        assertEquals("qwen2.5", loaded.model)
    }

    @Test
    fun `config provider value is loaded into AppConfig`() {
        // env-override (CLI_AGENT_PROVIDER) не тестируем (process-global); вместо этого — что
        // значение из config.json попадает в AppConfig.provider.
        val cfg = repo(configJson = """{"provider":"ollama","model":"qwen2.5","baseUrl":"http://localhost:11434/v1"}""").load()
        assertEquals("ollama", cfg.provider)
    }

    @Test
    fun `initFromLegacy picks up provider from env or local properties`() {
        // legacy-миграция: provider из local.properties попадает в созданный config.json.
        val r = repo(localProps = "provider=ollama\napi.key=k\nmodel=qwen2.5")
        assertTrue(r.initFromLegacy())
        val loaded = r.loadConfigFile()
        assertEquals("ollama", loaded.provider)
    }

    @Test
    fun `toTransport on McpServerConfig - url wins over command`() {
        val s = McpServerConfig(name = "x", command = "java", url = "https://h/mcp")
        assertEquals("https://h/mcp", (s.toTransport() as com.cliagent.mcp.McpTransportConfig.Http).url)
    }

    // ── День 30 (streaming SSE): config.stream field + schema evolution ───────────

    @Test
    fun `stream defaults to auto when absent (schema evolution)`() {
        // Старый config.json без поля stream → default "auto" (streaming для Ollama, off для cloud).
        val cfg = repo(configJson = """{"apiKey":"k"}""").load()
        assertEquals("auto", cfg.stream)
    }

    @Test
    fun `stream value round-trips through save and load`() {
        val r = repo()
        r.save(AppConfig(apiKey = "k", stream = "true"))
        assertEquals("true", r.loadConfigFile().stream)

        r.save(AppConfig(apiKey = "k", stream = "false"))
        assertEquals("false", r.loadConfigFile().stream)
    }

    @Test
    fun `stream value is loaded into AppConfig from config json`() {
        // deterministic-проверка: значение из config.json попадает в AppConfig.stream (env override
        // CLI_AGENT_STREAM не тестируем здесь — env process-global, как для provider).
        val cfg = repo(configJson = """{"apiKey":"k","stream":"true"}""").load()
        assertEquals("true", cfg.stream)
    }

    @Test
    fun `stream raw value round-trips and loads (день 31 raw-mode)`() {
        // День 31: config.stream="raw" — streaming без markdown-дублирования. Проверяем schema
        // evolution: значение принимается из config.json и round-trip'ит через save/load (как
        // "true"/"false"). Поведенческий разбор (streamEnabled/streamRawOnly) — в ChatCommand.
        val r = repo()
        r.save(AppConfig(apiKey = "k", stream = "raw"))
        assertEquals("raw", r.loadConfigFile().stream)
        val cfg = repo(configJson = """{"apiKey":"k","stream":"raw"}""").load()
        assertEquals("raw", cfg.stream)
    }

    // ── День 31: sampling/ollama blocks + schema evolution ──────────────────────

    @Test
    fun `sampling and ollama default to empty blocks when absent (schema evolution)`() {
        // Старый config.json без sampling/ollama → default SamplingTunables()/OllamaTunables()
        // (все поля null) — прежнее поведение дней 1–30, без ошибок парсинга (AGENTS.md).
        val cfg = repo(configJson = """{"apiKey":"k"}""").load()
        assertNotNull(cfg.sampling, "sampling block must default, not null")
        assertNotNull(cfg.ollama, "ollama block must default, not null")
        assertNull(cfg.sampling.temperature, "sampling.temperature default null")
        assertNull(cfg.sampling.topK, "sampling.topK default null")
        assertNull(cfg.ollama.keepAlive, "ollama.keepAlive default null")
    }

    @Test
    fun `sampling values from config json load into AppConfig`() {
        val json = """
            {"apiKey":"k","sampling":{"temperature":0.5,"top_p":0.9,"top_k":40,"max_tokens":512,"seed":42}}
        """.trimIndent()
        val cfg = repo(configJson = json).load()
        assertEquals(0.5, cfg.sampling.temperature)
        assertEquals(0.9, cfg.sampling.topP)
        assertEquals(40, cfg.sampling.topK)
        assertEquals(512, cfg.sampling.maxTokens)
        assertEquals(42L, cfg.sampling.seed)
    }

    @Test
    fun `ollama values from config json load into AppConfig`() {
        val json = """
            {"apiKey":"k","ollama":{"keep_alive":"10m","think":true}}
        """.trimIndent()
        val cfg = repo(configJson = json).load()
        assertEquals("10m", cfg.ollama.keepAlive)
        assertEquals(true, cfg.ollama.think)
    }

    @Test
    fun `sampling round-trips through save and load`() {
        val r = repo()
        r.save(AppConfig(apiKey = "k", sampling = SamplingTunables(temperature = 0.3, topK = 50)))
        val loaded = r.loadConfigFile()
        assertEquals(0.3, loaded.sampling.temperature)
        assertEquals(50, loaded.sampling.topK)
    }
}
