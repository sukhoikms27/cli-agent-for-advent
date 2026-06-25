plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "com.cliagent"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.4.3"
val kotlinxSerializationVersion = "1.11.0"
val kotlinxCoroutinesVersion = "1.11.0"
val kotlinIoVersion = "0.9.0"

dependencies {
    // Ktor HTTP client
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // kotlinx.serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // kotlinx.coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    // kotlinx-io — Source/Sink для MCP stdio-транспорта (день 16)
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:$kotlinIoVersion")

    // SLF4J-бэкенд для MCP SDK (io.github.oshai:kotlin-logging); runtime-only
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")

    // clikt — CLI framework
    implementation("com.github.ajalt.clikt:clikt:4.4.0")

    // mordant — terminal output (цвета, таблицы, спиннеры); объявляем явно (транзитивно через clikt 2.5.0)
    implementation("com.github.ajalt.mordant:mordant:2.5.0")

    // JLine3 — REPL input (история, completion, редактирование строк, сигналы)
    implementation("org.jline:jline:3.29.0")

    // MCP — Model Context Protocol client (официальный Kotlin SDK, день 16)
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.13.0")

    // Testing (день 11)
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Проброс опциональных E2E-флагов/путей в test JVM (день 18): по умолчанию Gradle изолирует
    // test-JVM и не передаёт -D. E2E-тесты (McpClientHttpE2ETest, интеграционный stdio-тест) явно
    // gated — без флага они skipped, обычный `./gradlew test` их не запускает.
    listOf("cli-agent.e2e.http", "cli-agent.mcp.jar", "CLI_AGENT_MCP_INTEGRATION", "CLI_AGENT_MCP_BIN")
        .forEach { key ->
            System.getProperty(key)?.let { systemProperty(key, it) }
            System.getenv(key)?.let { environment(key, it) }
        }
}

application {
    mainClass.set("com.cliagent.MainKt")
}

tasks.withType<JavaExec> {
    standardInput = System.`in`
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "com.cliagent.MainKt"
    }
}
