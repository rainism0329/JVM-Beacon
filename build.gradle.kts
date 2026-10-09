plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.jvmbeacon"
version = "1.0.0-rc.3"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        // Compile against the first Java 21 platform, not a newer SDK's APIs.
        if (localIde != null) local(localIde) else intellijIdeaCommunity("2024.2")
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
    // Opt-in isolated acceptance sandbox; never reuse a user's running development IDE.
    providers.gradleProperty("beaconSandboxPath").orNull?.let { sandboxContainer.set(file(it)) }
    buildSearchableOptions = false
    // No GUI Designer .form files or runtime null instrumentation are required.
    instrumentCode = false
    pluginConfiguration {
        name = "JVM Beacon"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            val localIde = providers.gradleProperty("localIdePath").orNull
            if (localIde != null) local(localIde) else current()
            val additionalIde = providers.gradleProperty("additionalVerificationIdePath").orNull
            if (additionalIde != null) local(additionalIde)
            providers.gradleProperty("verificationIdePaths").orNull
                ?.split('|')?.filter { it.isNotBlank() }?.forEach { local(it) }
        }
    }
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.processResources {
    from("docs/marketplace/EULA.md") {
        into("META-INF")
        rename { "LICENSE.txt" }
    }
}
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
