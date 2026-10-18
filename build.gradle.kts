import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform") version "2.4.20"
    `maven-publish`
}

group = "com.normtronix"
version = System.getenv("VERSION") ?: "1.0.3"

repositories {
    mavenCentral()
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS framework, packaged as CompressGps.xcframework for Swift Package Manager
    val xcf = XCFramework("CompressGps")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "CompressGps"
            xcf.add(this)
        }
    }

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation(project.dependencies.platform("org.junit:junit-bom:5.10.0"))
            implementation("org.junit.jupiter:junit-jupiter")
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

// Configure JAR manifest
tasks.named<Jar>("jvmJar") {
    manifest {
        attributes(
            "Implementation-Title" to "compress-gps",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Normtronix",
        )
    }
}

// Configure publishing
publishing {
    publications {
        // The multiplatform plugin creates the publications (compress-gps plus
        // compress-gps-jvm etc.); this just fills in their POMs.
        withType<MavenPublication>().configureEach {
            pom {
                name.set("compress-gps")
                description.set("High-performance GPS telemetry compression library for racing applications")
                url.set("https://github.com/sprintf/compress-gps")
                
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                
                developers {
                    developer {
                        id.set("sprintf")
                        name.set("Paul Normington")
                        email.set("paul@normingtons.org")
                    }
                }
                
                scm {
                    connection.set("scm:git:git://github.com/sprintf/compress-gps.git")
                    developerConnection.set("scm:git:ssh://github.com/sprintf/compress-gps.git")
                    url.set("https://github.com/sprintf/compress-gps/tree/main")
                }
            }
        }
    }
    
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/sprintf/compress-gps")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}