plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
    // Shadow — fat-jar для деплоя на VPS (один переносимый `java -jar` артефакт).
    id("com.gradleup.shadow") version "8.3.9"
}

group = "com.cliagent"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Версии выровнены с корневым проектом и mcp-server (единый gate).
val ktorVersion = "3.4.3"
val kotlinxSerializationVersion = "1.11.0"
val kotlinxCoroutinesVersion = "1.11.0"

dependencies {
    // Корневой cli-agent — переиспользуем ContextAwareAgent, LlmClientFactory, JsonChatStore,
    // SystemPrompts, SlidingWindowStrategy, AppConfig, ChatMessage. mcp-server дублирует код,
    // но web-app intentionally зависит от project(":") (см. .artifacts/day-30-plan.md).
    implementation(project(":"))

    // Ktor HTTP server — движок + плагины для адаптивного веб-приложения «Мотиватор».
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    // ktor-serialization-kotlinx-json — даёт функцию json() для ContentNegotiation (server side).
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-compression:$ktorVersion")
    implementation("io.ktor:ktor-server-conditional-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-partial-content:$ktorVersion")

    // kotlinx.serialization — JSON для /api/* эндпоинтов (сессии, чат-запросы).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // kotlinx.coroutines — suspend-роуты + chatStreamed.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    // SLF4J-бэкенд для логирования Ktor (call-logging плагин пишет через SLF4J).
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")

    // Testing — выровнено с корневым проектом.
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.cliagent.web.MotivatorAppKt")
}

// Fat-jar (по образцу mcp-server): один артефакт `java -jar web-app-*-all.jar` для деплоя на VPS.
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    mergeServiceFiles()
    archiveClassifier.set("all")
}
