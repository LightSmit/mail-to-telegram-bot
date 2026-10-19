plugins {
    kotlin("jvm") version "2.3.0"
    application
}

group = "io.github.lightsmit"
version = "0.1.0-alpha.1"
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-client-core:3.5.1")
    implementation("io.ktor:ktor-client-okhttp:3.5.1") // Убедись, что версия совпадает с твоим Ktor

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("ch.qos.logback:logback-classic:1.5.34")
    implementation("org.eclipse.angus:jakarta.mail:2.0.4")
    implementation("org.xerial:sqlite-jdbc:3.53.1.0")
    implementation("org.jsoup:jsoup:1.22.2")

    implementation("com.zaxxer:HikariCP:5.1.0")

    testImplementation(kotlin("test"))
}

val ktorVersion = "2.3.9"

dependencies {
    // Ядро и движок сервера
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")

    // Для работы с JSON
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.github.lightsmit.MainKt")

    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
    )
}

tasks.test {
    useJUnitPlatform()
}
