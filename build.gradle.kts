plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
}

group = "com.cliagent"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.3"
val kotlinxSerializationVersion = "1.8.1"
val kotlinxCoroutinesVersion = "1.10.2"

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

    // clikt — CLI framework
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
}

kotlin {
    jvmToolchain(17)
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
