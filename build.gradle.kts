plugins {
    kotlin("jvm") version "2.1.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // Small embedded HTTP server (wraps Jetty). One line per route, no framework magic.
    implementation("io.javalin:javalin:6.4.0")
    // YAML config + JSON responses/sidecar files.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
    // Logging backend for SLF4J (which Javalin already uses).
    implementation("ch.qos.logback:logback-classic:1.5.16")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("repeater.MainKt")
}
