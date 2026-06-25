plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
    // Shadow — fat-jar для деплоя на VPS (один переносимый `java -jar` артефакт).
    // 8.3.9 совместим с Gradle 9 (см. gradleup.com/shadow/changes).
    id("com.gradleup.shadow") version "8.3.9"
}

group = "com.cliagent"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Версии выровнены с корневым проектом (день 16 gate): одна и та же core-версия MCP SDK.
val ktorVersion = "3.4.3"
val kotlinxSerializationVersion = "1.11.0"
val kotlinxCoroutinesVersion = "1.11.0"
val kotlinIoVersion = "0.9.0"

dependencies {
    // MCP — Model Context Protocol server (официальный Kotlin SDK, день 17)
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.13.0")

    // kotlin-logging — нужен прямой доступ к KotlinLoggingConfiguration (подавление стартового
    // println в stdout, ломающего JSON-RPC). Транзитивно через kotlin-sdk-server, но implementation
    // не экспонирует транзитив на compile-classpath — объявляем явно той же версией.
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.03")

    // kotlinx.coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    // Ktor HTTP client — ходит в GitHub REST API из tool-handler'а
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // Ktor HTTP server — движок для embeddedServer в http-режиме (remote Streamable HTTP, день 18).
    // Остальные ktor-server артефакты (core/sse/content-negotiation/websockets) kotlin-sdk-server
    // 0.13.0 уже тянет транзитивно; движок нужно объявить явно — extension его не подтягивает.
    implementation("io.ktor:ktor-server-cio:$ktorVersion")

    // kotlinx.serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // kotlinx-io — Source/Sink для stdio-транспорта сервера (тот же мост, что у клиента)
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:$kotlinIoVersion")

    // SLF4J-бэкенд для MCP SDK (io.github.oshai:kotlin-logging); runtime-only — не утекает в API
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.cliagent.mcp.server.GitHubMcpServerKt")
}

// Fat-jar (день 18): один переносимый артефакт `java -jar mcp-server-*-all.jar` для деплоя на VPS.
// mergeServiceFiles — схлопывает META-INF/services (Ktor/SLF4J/MCP обнаруживают реализации через
// ServiceLoader; без мержа в fat-jar часть плагинов не поднимется).
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    mergeServiceFiles()
    archiveClassifier.set("all")
}
