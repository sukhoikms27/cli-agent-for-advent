plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
    // Shadow — fat-jar для деплоя (как web-app/mcp-server/support-app).
    id("com.gradleup.shadow") version "8.3.9"
}

group = "com.cliagent"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Версии выровнены с корневым проектом и support-app (единый gate).
val ktorVersion = "3.4.3"
val kotlinxSerializationVersion = "1.11.0"
val kotlinxCoroutinesVersion = "1.11.0"

dependencies {
    // Корневой cli-agent — переиспользуем LlmClientFactory, AppConfig, ConfigRepository,
    // SystemPrompts, RagRetriever, RagIndexer, StructuralChunker, JsonRagStore,
    // OllamaEmbeddingClient, AppPaths (как support-app).
    implementation(project(":"))

    // clikt — CLI-фреймворк (как в корневом cli-agent).
    implementation("com.github.ajalt.clikt:clikt:4.4.0")

    // mordant — терминальный вывод (цвета, спиннеры, таблицы).
    implementation("com.github.ajalt.mordant:mordant:2.5.0")

    // kotlinx.serialization — JSON-договор с LLM и GitHub API.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // kotlinx.coroutines — suspend pipeline.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    // SLF4J-бэкенд (перетягивается транзитивно через project(":"); явно для надёжности).
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
    mainClass.set("com.cliagent.review.ReviewBotAppKt")
}

// Fat-jar (по образцу support-app/mcp-server): один артефакт `java -jar review-bot-*-all.jar`.
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    mergeServiceFiles()
    archiveClassifier.set("all")
}
