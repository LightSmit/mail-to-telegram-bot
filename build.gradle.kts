plugins {
    kotlin("jvm") version "2.3.0"
    application
}

group = "io.github.lightsmit"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-client-core:3.5.1")
    implementation("io.ktor:ktor-client-cio:3.5.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("ch.qos.logback:logback-classic:1.5.34")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.github.lightsmit.MainKt")
}

tasks.test {
    useJUnitPlatform()
}