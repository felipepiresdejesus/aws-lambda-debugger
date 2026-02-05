import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "com.felipejesus.aws-lambda-local-debugger"
version = providers.environmentVariable("VERSION").getOrElse("1.0.0-SNAPSHOT")

repositories {
    mavenCentral()
    
    intellijPlatform {
        // Prioritize local platform artifacts to avoid SSL issues
        localPlatformArtifacts()
        // Only use default repositories if local artifacts are not available
        // This helps avoid SSL certificate issues when local Rider is available
        defaultRepositories()
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    
    // Test dependencies
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.mockito:mockito-core:5.5.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    
    intellijPlatform {
        local("/Users/fpxvdd/Applications/Rider.app")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "253.*"
        }
    }
    
    publishing {
        token.set(providers.environmentVariable("JETBRAINS_TOKEN"))
    }
    
    pluginVerification {
        ides {
            if (!System.getenv("CI").isNullOrEmpty()) {
                // Skip IDE download in CI environments
                ide("RD-2024.3.1")
            } else {
                recommended()
            }
        }
    }
    
    buildSearchableOptions.set(false)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
}
