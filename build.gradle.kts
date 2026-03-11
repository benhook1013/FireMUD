import com.github.gradle.node.npm.task.NpxTask
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File
import org.springframework.boot.gradle.tasks.run.BootRun

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.fasterxml.jackson.core:jackson-databind:2.21.1")
        classpath("org.flywaydb:flyway-database-postgresql:12.0.3")
        classpath("org.postgresql:postgresql:42.7.10")
    }
}

plugins {
    java
    id("com.github.node-gradle.node") version "7.1.0"
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.flyway) apply false
    id("com.diffplug.spotless") version "8.3.0"
    id("checkstyle")
    id("com.github.spotbugs") version "6.4.8"
    jacoco
}

node {
    version.set("20.11.0")
    // Don't download Node in CI; use the version provided by the environment
    download.set(System.getenv("CI") == null)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val fullCheck = project.hasProperty("fullCheck") || System.getenv("CI") != null

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "checkstyle")
    apply(plugin = "com.github.spotbugs")
    apply(plugin = "jacoco")
    group = "net.firedevops.firemud"
    version = project.property("version") as String

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    plugins.withId("java") {
        the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(21)
            if (name.contains("Test")) {
                // Ignore the current @MockBean removal warnings until Spring Boot 4.0 lands so tests stay clean.
                options.compilerArgs.add("-Xlint:-removal")
            }
        }
    }

    if (projectDir.parentFile.name == "services" && name != "common-library") {
        apply(plugin = "org.springframework.boot")
        apply(plugin = "org.flywaydb.flyway")

        dependencies {
            implementation(libs.findLibrary("flyway-core").get())
            implementation(libs.findLibrary("flyway-database-postgresql").get())
            implementation(libs.findLibrary("postgresql").get())
            add("developmentOnly", libs.findLibrary("h2").get())
            testRuntimeOnly(libs.findLibrary("h2").get())
        }

        tasks.named<BootRun>("bootRun") {
            val activeProfile = System.getProperty("spring.profiles.active")
                ?: System.getenv("SPRING_PROFILES_ACTIVE")
                ?: "dev"

            systemProperty("spring.profiles.active", activeProfile)
        }

        tasks.withType<Test>().configureEach {
            systemProperty(
                "spring.profiles.active",
                System.getProperty("spring.profiles.active") ?: System.getenv("SPRING_PROFILES_ACTIVE") ?: "test"
            )
        }
    }

    dependencies {
        implementation(libs.findLibrary("protobuf-java").get())
        implementation(libs.findLibrary("grpc-netty-shaded").get())
        implementation(libs.findLibrary("grpc-protobuf").get())
        implementation(libs.findLibrary("grpc-stub").get())
        implementation("javax.annotation:javax.annotation-api:1.3.2")
        testImplementation(libs.findLibrary("spring-boot-starter-test").get())
        testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
    }

    spotless {
        java {
            googleJavaFormat()
            targetExclude("build/generated/**")
        }
    }

    checkstyle {
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        toolVersion = "10.12.1"
    }

    spotbugs {
        toolVersion.set("4.9.3")
    }

    // --- Gate Checkstyle & SpotBugs ---
    tasks.withType<Checkstyle>().configureEach {
        enabled = fullCheck
    }

    tasks.withType<SpotBugsTask>().configureEach {
        enabled = fullCheck
        excludeFilter.set(rootProject.file("config/spotbugs/spotbugs-exclude.xml"))
        setIgnoreFailures(true)
        // Exclude generated sources and protobuf-generated packages from analysis
        val generatedDir = "${File.separator}generated${File.separator}"
        val protoPackages = listOf("net/firedevops/firemud/**/v1/**")
        val originalClassDirs = classDirs.files
        classDirs.setFrom(
            originalClassDirs
                .filterNot { it.path.contains(generatedDir) }
                .map { fileTree(it) { exclude(protoPackages) } }
        )
        sourceDirs.setFrom(
            sourceDirs.files
                .filterNot { it.path.contains(generatedDir) }
                .map { fileTree(it) { exclude(protoPackages) } }
        )
        auxClassPaths.from(originalClassDirs)
    }

    // Ensure SpotBugs runs after compilation
    tasks.named("spotbugsMain") {
        dependsOn("compileJava", "processResources")
    }
    tasks.named("spotbugsTest") {
        dependsOn("compileTestJava", "processTestResources")
    }

    // Gate Spotless checks behind fullCheck (CI or -PfullCheck)
    tasks.matching { it.name.startsWith("spotless") && it.name.endsWith("Check") }
    .configureEach { enabled = fullCheck }

    tasks.test {
        useJUnitPlatform()
        if (fullCheck) finalizedBy("jacocoTestReport")
    }

    tasks.jacocoTestReport {
        enabled = fullCheck
        dependsOn(tasks.test)
        reports {
            xml.required.set(true)
            csv.required.set(false)
            html.required.set(true)
        }
    }
}

tasks.register<NpxTask>("lintMarkdown") {
    dependsOn(tasks.npmInstall)
    command.set("markdownlint-cli2")
    args.set(listOf(
        "--config", "config/markdownlint/.markdownlint-cli2.jsonc",
        "**/*.md"
    ))
}

tasks.register<NpxTask>("lintMarkdownFix") {
    dependsOn(tasks.npmInstall)
    command.set("markdownlint-cli2")
    args.set(listOf(
        "--config", "config/markdownlint/.markdownlint-cli2.jsonc",
        "--fix",
        "**/*.md"
    ))
}

tasks.register<Exec>("linkCheck") {
    commandLine("bash", "./dev-tools/docs/link-check.sh")
    environment("CHECK_EXTERNAL_LINKS", if (fullCheck) "1" else "0")
}

tasks.register<Exec>("validateObservabilityContract") {
    group = "verification"
    description = "Validates the design-level observability contract (metric names/labels) against dashboards and snippets."
    commandLine("python3", "dev-tools/observability/validate-observability-contract.py")
}

tasks.named("check") {
    // Always run Markdown lint and link checks; they are relatively fast.
    dependsOn("lintMarkdown", "linkCheck", "validateObservabilityContract")
    if (fullCheck) {
        dependsOn(
            "checkstyleMain",
            "spotbugsMain",
            "jacocoTestReport"
        )
    }
}

tasks.register("crossServiceTest") {
    group = "verification"
    description = "Runs the cross-service LOOK regression suites."
    dependsOn(":game-session-service:crossServiceTest", ":tcp-proxy-service:crossServiceTest")
}

tasks.register<Exec>("buildBaseImage") {
    commandLine(
        "docker",
        "build",
        "-f",
        "docker/base.Dockerfile",
        "-t",
        "ghcr.io/benhook1013/firemud-base:latest",
        "."
    )
}

tasks.register("buildDockerImages") {
    dependsOn(
        "buildBaseImage",
        ":account-service:bootBuildImage",
        ":automation-scripting-service:bootBuildImage",
        ":entity-management-service:bootBuildImage",
        ":game-design-service:bootBuildImage",
        ":game-logic-service:bootBuildImage",
        ":game-session-service:bootBuildImage",
        ":logging-admin-service:bootBuildImage",
        ":social-groups-service:bootBuildImage",
        ":spring-cloud-gateway:bootBuildImage",
        ":tcp-proxy-service:bootBuildImage",
        ":world-management-service:bootBuildImage"
    )
}

tasks.register<Exec>("generateDevCerts") {
    workingDir("dev-tools")
    commandLine("bash", "generate-dev-certs.sh", "certs")
}

tasks.register<Exec>("devUp") {
    dependsOn("generateDevCerts", "buildDockerImages")
    commandLine(
        "docker",
        "compose",
        "-f",
        "docker/docker-compose.yml",
        "-f",
        "docker/docker-compose.override.yml",
        "up",
        "--build"
    )
}

tasks.register<Exec>("devDown") {
    commandLine(
        "docker",
        "compose",
        "-f",
        "docker/docker-compose.yml",
        "-f",
        "docker/docker-compose.override.yml",
        "down"
    )
}

tasks.register<Exec>("erd") {
    commandLine("bash", "dev-tools/docs/generate-erd.sh")
}

tasks.register<Exec>("seed") {
    commandLine("bash", "dev-tools/seed/seed.sh")
}
