plugins {
    `java-gradle-plugin`
    `maven-publish`
}

java {
    withSourcesJar()
}

tasks.compileJava {
    // Runs inside Gradle's own JVM, which can be older than the library's.
    options.release = 17
}

tasks.jar {
    manifest.attributes("Implementation-Version" to project.version)
}

gradlePlugin {
    plugins {
        create("conduit") {
            id = "dev.opencollab.conduit"
            implementationClass = "org.geysermc.conduit.gradle.ConduitPlugin"
            displayName = "Conduit"
            description = "Generates Conduit interfaces from the classes they mirror."
        }
    }
}

publishing {
    publications.withType<MavenPublication>().named { it == "pluginMaven" }.configureEach {
        artifactId = "conduit-gradle-plugin"
        pom {
            name = "Conduit Gradle Plugin"
            description = "Generates Conduit interfaces from the classes they mirror."
        }
    }
}
