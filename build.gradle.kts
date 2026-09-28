plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.jvmbeacon"
version = "0.11.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde) else intellijIdeaUltimate("2025.1.3")
        bundledPlugin("com.intellij.java")
        pluginVerifier("1.408")
    }
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
    // The IntelliJ test bootstrap references JUnit 4 rules even for Jupiter tests.
    testRuntimeOnly("junit:junit:4.13.2")
}

intellijPlatform {
    buildSearchableOptions = false
    // No GUI Designer .form files or runtime null instrumentation are required.
    instrumentCode = false
    pluginConfiguration {
        name = "JVM Beacon"
        version = project.version.toString()
        ideaVersion { sinceBuild = "251.26927"; untilBuild = "251.*" }
    }
    pluginVerification {
        ides {
            val localIde = providers.gradleProperty("localIdePath").orNull
            if (localIde != null) local(localIde) else current()
            val additionalIde = providers.gradleProperty("additionalVerificationIdePath").orNull
            if (additionalIde != null) local(additionalIde)
        }
    }
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.test {
    useJUnitPlatform()
    systemProperty("beacon.fixture.classes", sourceSets.test.get().output.classesDirs.asPath)
    systemProperty("beacon.core.classes", sourceSets.main.get().output.classesDirs.asPath)
    maxParallelForks = 1
    testLogging { events("passed", "skipped", "failed") }
}

tasks.verifyPlugin {
    // Do not scan unrelated plugins cached by other projects in the user's verifier home.
    systemProperty("plugin.verifier.home.dir", layout.buildDirectory.dir("plugin-verifier-home").get().asFile.absolutePath)
    maxHeapSize = "1536m"
}
