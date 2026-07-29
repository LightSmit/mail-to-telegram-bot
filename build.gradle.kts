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