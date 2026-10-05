plugins {
    java
}

group = "de.cryptocraft"
version = "1.1.0"

val spigotVersion = providers.gradleProperty("spigotVersion").orElse("26.3-R0.1-SNAPSHOT").get()
val mockitoAgent by configurations.creating

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:" + spigotVersion)
    compileOnly("net.md-5:bungeecord-chat:1.21-R0.4")
    compileOnly("com.google.code.gson:gson:2.13.2")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.14") { isTransitive = false }
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.14") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.9") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.9") { isTransitive = false }
    testImplementation("org.spigotmc:spigot-api:" + spigotVersion)
    testImplementation("com.google.code.gson:gson:2.13.2")
    testImplementation(platform("org.junit:junit-bom:5.14.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.20.0")
    mockitoAgent("org.mockito:mockito-core:5.20.0") { isTransitive = false }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

val pluginVersion = version.toString()
tasks.processResources {
    val resourceProperties = mapOf("version" to pluginVersion)
    filteringCharset = "UTF-8"
    inputs.properties(resourceProperties)
    filesMatching("plugin.yml") { expand(resourceProperties) }
}

abstract class MockitoAgentArguments : CommandLineArgumentProvider {
    @get:Classpath abstract val agentFiles: ConfigurableFileCollection
    override fun asArguments() = listOf("-javaagent:${agentFiles.singleFile.absolutePath}")
}
tasks.test {
    useJUnitPlatform()
    jvmArgumentProviders.add(objects.newInstance<MockitoAgentArguments>().apply { agentFiles.from(mockitoAgent) })
}

tasks.wrapper {
    gradleVersion = "9.3.1"
    distributionType = Wrapper.DistributionType.BIN
}
