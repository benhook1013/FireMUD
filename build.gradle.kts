import com.github.gradle.node.npm.task.NpxTask
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File
import org.flywaydb.gradle.FlywayExtension
import org.springframework.boot.gradle.tasks.run.BootRun

buildscript {
    dependencies {
        // The Flyway Gradle plugin resolves database support from the buildscript classpath,
        // not from each service's runtime dependencies.
        classpath("org.flywaydb:flyway-database-postgresql:12.9.0")
        classpath("org.postgresql:postgresql:42.7.12")
    }
}

plugins {
    java
    id("com.github.node-gradle.node") version "7.1.0"
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.flyway) apply false
    id("com.diffplug.spotless") version "8.8.0"
    id("checkstyle")
    id("com.github.spotbugs") version "6.5.8"
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
val checkstyleToolVersion = "13.7.0"
val spotbugsToolVersion = "4.10.2"
val platformSettingsMetadataFiles = listOf(
    file("services/game-session-service/src/main/resources/META-INF/additional-spring-configuration-metadata.json"),
    file("services/game-logic-service/src/main/resources/META-INF/additional-spring-configuration-metadata.json")
)
val platformSettingsPublicationSpec = file("config/settings/platform-settings-publication.json")
val platformSettingsGeneratorScript = file("dev-tools/docs/generate-platform-settings-docs.py")
val checkedInPlatformSettingsSchema = file("design/architecture/generated/platform-settings-schema.json")
val checkedInPlatformSettingsReference = file("design/architecture/generated/platform-settings-reference.md")

allprojects {
    repositories {
        mavenCentral()
    }
}

checkstyle {
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    toolVersion = checkstyleToolVersion
}

spotbugs {
    toolVersion.set(spotbugsToolVersion)
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
        val sourceSets = the<SourceSetContainer>()
        sourceSets.named("test") {
            compileClasspath += sourceSets.named("main").get().output
            runtimeClasspath += output + sourceSets.named("main").get().output
        }
        val integrationTestsDir = file("src/test/java/integration")
        val crossServiceTestsDir = file("src/test/java/crossservice")
        val categorizedTestRootsExist = integrationTestsDir.exists() || crossServiceTestsDir.exists()
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(21)
            if (name.contains("Test")) {
                // Ignore the current @MockBean removal warnings until Spring Boot 4.0 lands so tests stay clean.
                options.compilerArgs.add("-Xlint:-removal")
            }
        }
        testing {
            suites {
                named<JvmTestSuite>("test") {
                    useJUnitJupiter()
                    sources {
                        java {
                            setSrcDirs(listOf(file("src/test/java")))
                            exclude("integration/**", "crossservice/**")
                        }
                        resources {
                            setSrcDirs(listOf(file("src/test/resources")))
                        }
                    }
                }

                if (integrationTestsDir.exists()) {
                    register<JvmTestSuite>("integrationTest") {
                        useJUnitJupiter()
                        dependencies {
                            implementation(project())
                        }
                        sources {
                            java.setSrcDirs(listOf(integrationTestsDir))
                            resources.setSrcDirs(listOf(file("src/test/resources")))
                        }
                        targets {
                            all {
                                testTask.configure {
                                    shouldRunAfter(tasks.named("test"))
                                }
                            }
                        }
                    }
                    configurations.named("integrationTestImplementation").configure {
                        extendsFrom(configurations.named("testImplementation").get())
                    }
                    configurations.named("integrationTestRuntimeOnly").configure {
                        extendsFrom(configurations.named("testRuntimeOnly").get())
                    }
                    tasks.named("check") {
                        dependsOn("integrationTest")
                    }
                }

                if (crossServiceTestsDir.exists()) {
                    register<JvmTestSuite>("crossServiceTest") {
                        useJUnitJupiter()
                        dependencies {
                            implementation(project())
                        }
                        sources {
                            java.setSrcDirs(listOf(crossServiceTestsDir))
                            resources.setSrcDirs(listOf(file("src/test/resources")))
                        }
                        targets {
                            all {
                                testTask.configure {
                                    shouldRunAfter(tasks.named("test"))
                                }
                            }
                        }
                    }
                    configurations.named("crossServiceTestImplementation").configure {
                        extendsFrom(configurations.named("testImplementation").get())
                    }
                    configurations.named("crossServiceTestRuntimeOnly").configure {
                        extendsFrom(configurations.named("testRuntimeOnly").get())
                    }
                    tasks.named("check") {
                        dependsOn("crossServiceTest")
                    }
                }
            }
        }
        if (categorizedTestRootsExist) {
            tasks.withType<JavaCompile>().configureEach {
                if (name == "compileTestJava" || name == "compileIntegrationTestJava" || name == "compileCrossServiceTestJava") {
                    // Categorized test roots have produced stale cache hits where selectors miss real test classes.
                    outputs.cacheIf { false }
                }
            }
        }
    }

    if (projectDir.parentFile.name == "services" && !name.startsWith("common-")) {
        apply(plugin = "org.springframework.boot")
        apply(plugin = "org.flywaydb.flyway")

        dependencies {
            implementation(libs.findLibrary("spring.boot.flyway").get())
            implementation(libs.findLibrary("flyway-core").get())
            implementation(libs.findLibrary("flyway-database-postgresql").get())
            implementation(libs.findLibrary("postgresql").get())
            add("developmentOnly", libs.findLibrary("h2").get())
            testRuntimeOnly(libs.findLibrary("h2").get())
        }

        tasks.named<BootRun>("bootRun") {
            val activeProfile =
                System.getProperty("spring.profiles.active")
                    ?: System.getenv("SPRING_PROFILES_ACTIVE")
            if (!activeProfile.isNullOrBlank()) {
                systemProperty("spring.profiles.active", activeProfile)
            }
        }

        tasks.withType<Test>().configureEach {
            systemProperty(
                "spring.profiles.active",
                System.getProperty("spring.profiles.active") ?: System.getenv("SPRING_PROFILES_ACTIVE") ?: "test"
            )
        }

        extensions.configure(FlywayExtension::class.java) {
            configurations = arrayOf("runtimeClasspath")
        }
    }

    dependencies {
        implementation(platform(libs.findLibrary("spring-boot-dependencies").get()))
        testImplementation(platform(libs.findLibrary("spring-boot-dependencies").get()))
        implementation(libs.findLibrary("protobuf-java").get())
        implementation(libs.findLibrary("grpc-netty-shaded").get())
        implementation(libs.findLibrary("grpc-protobuf").get())
        implementation(libs.findLibrary("grpc-stub").get())
        implementation("javax.annotation:javax.annotation-api:1.3.2")
        testImplementation(libs.findLibrary("spring-boot-starter-test").get())
        testImplementation(libs.findLibrary("spring-boot-starter-restclient-test").get())
        testImplementation(libs.findLibrary("spring-boot-resttestclient").get())
        testImplementation(libs.findLibrary("spring-boot-starter-webmvc-test").get())
        testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
        if (projectDir.parentFile.name == "services" && !name.startsWith("common-")) {
            testImplementation(testFixtures(project(":common-test-support")))
        }
    }

    spotless {
        java {
            googleJavaFormat()
            targetExclude("build/generated/**", "build/generated-src/**")
        }
    }

    checkstyle {
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        toolVersion = checkstyleToolVersion
    }

    spotbugs {
        toolVersion.set(spotbugsToolVersion)
    }

    // --- Gate Checkstyle & SpotBugs ---
    tasks.withType<Checkstyle>().configureEach {
        enabled = fullCheck
    }

    tasks.withType<SpotBugsTask>().configureEach {
        enabled = fullCheck
        excludeFilter.set(rootProject.file("config/spotbugs/spotbugs-exclude.xml"))
        when (name) {
            "spotbugsMain" -> dependsOn("compileJava", "processResources")
            "spotbugsTest" -> dependsOn("compileTestJava", "processTestResources")
            "spotbugsIntegrationTest" -> dependsOn("compileIntegrationTestJava", "processIntegrationTestResources")
            "spotbugsCrossServiceTest" -> dependsOn("compileCrossServiceTestJava", "processCrossServiceTestResources")
        }
        // Exclude generated sources and protobuf-generated packages from analysis
        val generatedDir = "${File.separator}generated${File.separator}"
        val protoAndJooqPackages = listOf(
            "net/firedevops/firemud/**/v1/**",
            "net/firedevops/firemud/**/jooq/**"
        )
        val originalClassDirs = classDirs.files
        classDirs.setFrom(
            originalClassDirs
                .filterNot { it.path.contains(generatedDir) }
                .map { fileTree(it) { exclude(protoAndJooqPackages) } }
        )
        sourceDirs.setFrom(
            sourceDirs.files
                .filterNot { it.path.contains(generatedDir) }
                .map { fileTree(it) { exclude(protoAndJooqPackages) } }
        )
        auxClassPaths.from(originalClassDirs)
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

tasks.register<Exec>("checkFlywayVersions") {
    group = "verification"
    description = "Checks service Flyway migrations for duplicate or out-of-order versions."
    commandLine("bash", "dev-tools/validation/check-flyway-versions.sh")
}

tasks.register<Exec>("checkGrpcTransportConfig") {
    group = "verification"
    description = "Checks gRPC transport configuration for stale server TLS property usage."
    commandLine("bash", "dev-tools/validation/check-grpc-transport-config.sh")
}

tasks.register<Exec>("checkGrpcPublicMethods") {
    group = "verification"
    description = "Checks gRPC public-method allowlists against proto declarations."
    commandLine("bash", "dev-tools/validation/check-grpc-public-methods.sh")
}

tasks.register<Exec>("checkProtoTimeFields") {
    group = "verification"
    description = "Checks proto time-related field names for explicit time domains or units."
    commandLine("python3", "dev-tools/validation/check-proto-time-fields.py")
}

tasks.register<Exec>("validateObservabilityContract") {
    group = "verification"
    description = "Validates the design-level observability contract (metric names/labels) against dashboards and snippets."
    commandLine("python3", "dev-tools/observability/validate-observability-contract.py")
}

tasks.register<Exec>("generatePlatformSettingsDocs") {
    group = "documentation"
    description = "Generates the consolidated surfaced platform settings schema and Markdown reference."
    inputs.files(platformSettingsMetadataFiles + listOf(platformSettingsPublicationSpec, platformSettingsGeneratorScript))
    outputs.files(checkedInPlatformSettingsSchema, checkedInPlatformSettingsReference)
    commandLine(
        "python3",
        "dev-tools/docs/generate-platform-settings-docs.py",
        "--mode",
        "write"
    )
}

tasks.register<Exec>("updatePlatformSettingsDocs") {
    group = "documentation"
    description = "Regenerates and updates the checked-in surfaced platform settings schema and Markdown reference."
    inputs.files(platformSettingsMetadataFiles + listOf(platformSettingsPublicationSpec, platformSettingsGeneratorScript))
    outputs.files(checkedInPlatformSettingsSchema, checkedInPlatformSettingsReference)
    commandLine(
        "python3",
        "dev-tools/docs/generate-platform-settings-docs.py",
        "--mode",
        "write"
    )
}

tasks.register<Exec>("verifyPlatformSettingsDocs") {
    group = "verification"
    description = "Fails if the checked-in surfaced platform settings docs drift from the generator inputs."
    inputs.files(platformSettingsMetadataFiles + listOf(platformSettingsPublicationSpec, platformSettingsGeneratorScript, checkedInPlatformSettingsSchema, checkedInPlatformSettingsReference))
    mustRunAfter("updatePlatformSettingsDocs")
    commandLine(
        "python3",
        "dev-tools/docs/generate-platform-settings-docs.py",
        "--mode",
        "check"
    )
}

tasks.named("check") {
    // Always run Markdown lint and link checks; they are relatively fast.
    dependsOn(
        "lintMarkdown",
        "linkCheck",
        "checkFlywayVersions",
        "checkGrpcTransportConfig",
        "checkGrpcPublicMethods",
        "checkProtoTimeFields",
        "validateObservabilityContract",
        "verifyPlatformSettingsDocs"
    )
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

tasks.register("codeqlClasses") {
    group = "build"
    description = "Compiles the source sets needed for CodeQL analysis without running tests."
    dependsOn(
        subprojects.map { project -> "${project.path}:classes" }
    )
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

tasks.register<Exec>("buildPgDumpCronImage") {
    commandLine(
        "docker",
        "build",
        "-f",
        "docker/pg-dump-cron.Dockerfile",
        "-t",
        "pg-dump-cron:0.1.0",
        "."
    )
}

tasks.register("buildDockerImages") {
    dependsOn(
        "buildBaseImage",
        "buildPgDumpCronImage",
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

tasks.register("buildDockerImagesSmoke") {
    dependsOn(
        "buildBaseImage",
        ":account-service:bootBuildImage",
        ":entity-management-service:bootBuildImage",
        ":game-logic-service:bootBuildImage",
        ":game-session-service:bootBuildImage",
        ":spring-cloud-gateway:bootBuildImage",
        ":tcp-proxy-service:bootBuildImage",
        ":world-management-service:bootBuildImage"
    )
}

tasks.register<Exec>("generateDevCerts") {
    commandLine("bash", "dev-tools/certs/generate-dev-certs.sh")
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
        "-f",
        "docker/docker-compose.local-images.override.yml",
        "up",
        "-d",
        "--wait",
        "--wait-timeout",
        "180",
        "--no-build",
        "postgres",
        "redis-coord",
        "redis-cache",
        "minio",
        "redisinsight",
        "pg-dump-cron",
        "account-service",
        "gateway",
        "automation-scripting-service",
        "entity-management-service",
        "game-design-service",
        "game-logic-service",
        "game-session-service",
        "logging-admin-service",
        "social-groups-service",
        "tcp-proxy-service",
        "world-management-service"
    )
}

tasks.register<Exec>("devUpSmoke") {
    dependsOn("generateDevCerts", "buildDockerImagesSmoke")
    commandLine(
        "docker",
        "compose",
        "-f",
        "docker/docker-compose.yml",
        "-f",
        "docker/docker-compose.override.yml",
        "-f",
        "docker/docker-compose.local-images.override.yml",
        "up",
        "-d",
        "--wait",
        "--wait-timeout",
        "180",
        "--no-build",
        "postgres",
        "redis-coord",
        "redis-cache",
        "account-service",
        "entity-management-service",
        "gateway",
        "game-logic-service",
        "game-session-service",
        "tcp-proxy-service",
        "world-management-service"
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
        "-f",
        "docker/docker-compose.local-images.override.yml",
        "down"
    )
}

tasks.register<Exec>("devDownSmoke") {
    commandLine(
        "docker",
        "compose",
        "-f",
        "docker/docker-compose.yml",
        "-f",
        "docker/docker-compose.override.yml",
        "-f",
        "docker/docker-compose.local-images.override.yml",
        "down"
    )
}

tasks.register<Exec>("erd") {
    commandLine("bash", "dev-tools/docs/generate-erd.sh")
}

tasks.register<Exec>("seed") {
    commandLine("bash", "dev-tools/seed/seed.sh")
}
