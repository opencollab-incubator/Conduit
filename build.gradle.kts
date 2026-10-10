plugins {
    java
    `maven-publish`
    alias(libs.plugins.shadow)
}

allprojects {
    group = "dev.opencollab"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    pluginManager.withPlugin("maven-publish") {
        configure<PublishingExtension> {
            repositories {
                maven("https://repo.opencollab.dev/maven-snapshots") {
                    name = "openCollabSnapshots"
                    credentials(PasswordCredentials::class)
                }
            }

            publications.withType<MavenPublication>().configureEach {
                pom {
                    url = "https://github.com/opencollab-incubator/Conduit"
                    scm {
                        url = "https://github.com/opencollab-incubator/Conduit"
                        connection = "scm:git:https://github.com/opencollab-incubator/Conduit.git"
                        developerConnection = "scm:git:ssh://git@github.com/opencollab-incubator/Conduit.git"
                    }
                }
            }
        }

        tasks.withType<PublishToMavenRepository>().configureEach {
            val snapshot = version.toString().endsWith("-SNAPSHOT")
            doFirst {
                check(snapshot) { "Only snapshots are published for now, but the version is $version." }
            }
        }
    }
}

dependencies {
    compileOnly(libs.annotations)
    implementation(libs.asm.commons)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
    withJavadocJar()
}

tasks {
    build {
        dependsOn(shadowJar)
    }
    test {
        useJUnitPlatform()
    }
    javadoc {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
    }
    jar {
        archiveClassifier = "unshaded"
        manifest.attributes("Implementation-Version" to project.version)
    }
    shadowJar {
        archiveClassifier = ""
        // Fabric Loader ships its own ASM and rejects a second copy on the classpath.
        // No public API exposes ASM types, so relocating is invisible to consumers.
        relocate("org.objectweb.asm", "org.geysermc.conduit.shaded.org.objectweb.asm")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            pom {
                name = "Conduit"
                description = "Extracts the data a jar registers with an API, straight from its bytecode."
            }
            artifact(tasks.shadowJar)
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))
        }
    }
}
