plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
    // Shadow — fat-jar для деплоя (как web-app/mcp-server).
    id("com.gradleup.shadow") version "8.3.9"
}

group = "com.cliagent"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Версии выровнены с корневым проектом и web-app (единый gate).
val ktorVersion = "3.4.3"
val kotlinxSerializationVersion = "1.11.0"
val kotlinxCoroutinesVersion = "1.11.0"

dependencies {
    // Корневой cli-agent — переиспользуем ContextAwareAgent, LlmClientFactory, JsonChatStore,
    // SystemPrompts, RagRetriever, AppConfig, ChatMessage (как web-app).
    implementation(project(":"))

    // Ktor HTTP server — движок + плагины (зеркало web-app/build.gradle.kts).
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-compression:$ktorVersion")

    // kotlinx.serialization — JSON для /api/* эндпоинтов.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // kotlinx.coroutines — suspend-роуты + chatStreamed.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    // SLF4J-бэкенд для логирования Ktor.
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
    mainClass.set("com.cliagent.support.SupportAppKt")
}

// Fat-jar (по образцу web-app/mcp-server): один артефакт `java -jar support-app-*-all.jar`.
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    mergeServiceFiles()
    archiveClassifier.set("all")
}
