plugins {
    id("java")
    `java-library`
    kotlin("jvm") version "1.9.10"
    `maven-publish`
}

group = "com.normtronix"
version = System.getenv("VERSION") ?: "1.0.3"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Configure JAR manifest
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "compress-gps",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Normtronix",
        )
    }
}

// Configure source JAR
java {
    withSourcesJar()
}

// Configure publishing
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            
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