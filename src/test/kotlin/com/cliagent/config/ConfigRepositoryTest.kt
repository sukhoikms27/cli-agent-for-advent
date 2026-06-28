package com.cliagent.config

import com.cliagent.mcp.McpServerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun `toTransport on McpServerConfig - url wins over command`() {
        val s = McpServerConfig(name = "x", command = "java", url = "https://h/mcp")
        assertEquals("https://h/mcp", (s.toTransport() as com.cliagent.mcp.McpTransportConfig.Http).url)
    }
}
