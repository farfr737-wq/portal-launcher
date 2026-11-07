import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

group = "com.portal"
version = providers.gradleProperty("portal.version").get()

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.portal.MainKt"

        // Минимум ресурсов самого лаунчера
        jvmArgs += listOf("-Xms32m", "-Xmx384m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Dfile.encoding=UTF-8")

        nativeDistributions {
            // Exe-инсталлятор (нужен WiX, на windows-runner GitHub он есть) и app-image с PortalLauncher.exe
            targetFormats(TargetFormat.Exe)
            packageName = "PortalLauncher"
            packageVersion = providers.gradleProperty("portal.version").get()
            description = "Portal Launcher — Minecraft Java Edition Launcher"
            vendor = "Portal"
            // java.sql нужен для SQLite, jdk.unsupported — для Compose/Skiko, jdk.httpserver не нужен
            modules("java.sql", "jdk.unsupported", "java.net.http", "java.naming", "jdk.crypto.ec")
            windows {
                iconFile.set(project.file("packaging/icon.ico"))
                menuGroup = "Portal"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f3c2d0e-8b7a-4a53-9d0e-5b1d7a2c9e11"
            }
        }
    }
}
