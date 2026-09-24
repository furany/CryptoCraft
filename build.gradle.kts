plugins {
    java
}

group = "de.cryptocraft"
version = "1.0.0"

val paperVersion = providers.gradleProperty("paperVersion").orElse("26.3.build.+").get()

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:" + paperVersion)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    filteringCharset = "UTF-8"
}
