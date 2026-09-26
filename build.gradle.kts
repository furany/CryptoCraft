plugins {
    java
}

group = "de.cryptocraft"
version = "1.0.0"

val spigotVersion = providers.gradleProperty("spigotVersion").orElse("26.3-R0.1-SNAPSHOT").get()

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:" + spigotVersion)
    compileOnly("net.md-5:bungeecord-chat:1.21-R0.4")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    filteringCharset = "UTF-8"
}
