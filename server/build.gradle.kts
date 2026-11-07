plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

group = "com.portal"
version = providers.gradleProperty("portal.version").get()

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.portal.server.ServerMainKt")
}

tasks.test {
    useJUnitPlatform()
}
