// Lively NPCs: NPCs that look at you, wave, talk and move, drawn with packets, with a web
// animation editor. Paper 26.3; needs PacketEvents on the server. EntityLib is shaded in.
plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.livelynpcs"
version = "0.2.0"
base.archivesName.set("LivelyNPCs")

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.codemc.io/repository/maven-snapshots/")
    maven("https://maven.pvphub.me/tofaa")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    compileOnly("com.github.retrooper:packetevents-spigot:${property("packetEventsVersion")}")
    implementation("io.github.tofaa2:spigot:${property("entityLibVersion")}")

    testImplementation("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    testImplementation("com.github.retrooper:packetevents-spigot:${property("packetEventsVersion")}")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
    compileTestJava {
        options.encoding = "UTF-8"
    }
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }
    test {
        useJUnitPlatform()
    }
    shadowJar {
        archiveClassifier.set("")
        relocate("me.tofaa.entitylib", "dev.livelynpcs.libs.entitylib")
    }
    jar {
        archiveClassifier.set("plain")
    }
    build {
        dependsOn(shadowJar)
    }
}
