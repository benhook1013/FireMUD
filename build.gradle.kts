import com.github.gradle.node.npm.task.NpxTask
import com.github.spotbugs.snom.SpotBugsTask
import java.io.File

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    }
}

plugins {
    java
    id("com.github.node-gradle.node") version "7.1.0"
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.flyway) apply false
    id("com.diffplug.spotless") version "7.2.1"
    id("checkstyle")
    id("com.github.spotbugs") version "6.2.3"
    jacoco
}

node {
    version.set("20.11.0")
    // Don't download Node in CI; use the version provided by the environment
    download.set(System.getenv("CI") == null)
}

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

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    if (projectDir.parentFile.name == "services" && name != "common-library") {
        apply(plugin = "org.springframework.boot")
        apply(plugin = "org.flywaydb.flyway")
    }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    dependencies {
        implementation(libs.findLibrary("protobuf-java").get())
        implementation(libs.findLibrary("grpc-netty-shaded").get())
        implementation(libs.findLibrary("grpc-protobuf").get())
        implementation(libs.findLibrary("grpc-stub").get())
        implementation("javax.annotation:javax.annotation-api:1.3.2")
        testImplementation(libs.findLibrary("spring-boot-starter-test").get())
        testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
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

    tasks.withType<SpotBugsTask>().configureEach {
        excludeFilter.set(rootProject.file("config/spotbugs/spotbugs-exclude.xml"))
        setIgnoreFailures(true)
        // Exclude generated sources such as Protobuf classes from analysis
        val generatedDir = "${File.separator}generated${File.separator}"
        classDirs.setFrom(classDirs.files.filterNot { it.path.contains(generatedDir) })
        sourceDirs.setFrom(sourceDirs.files.filterNot { it.path.contains(generatedDir) })
    }

    // SpotBugs analyzes the compiled classes, so ensure it runs after Java
    // compilation to avoid implicit dependency issues reported by Gradle.
    tasks.named("spotbugsMain") {
        dependsOn("compileJava")
    }
    tasks.named("spotbugsTest") {
        dependsOn("compileTestJava")
    }

    tasks.test {
        useJUnitPlatform()
        finalizedBy("jacocoTestReport")
    }

    tasks.jacocoTestReport {
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
        "**/*.md",
        "!**/node_modules/**",
        "!**/build/**",
        "!**/.gradle/**",
        "!**/.git/**"
    ))
}

tasks.register<NpxTask>("lintMarkdownFix") {
    dependsOn(tasks.npmInstall)
    command.set("markdownlint-cli2")
    args.set(listOf(
        "--config", "config/markdownlint/.markdownlint-cli2.jsonc",
        "--fix",
        "**/*.md",
        "!**/node_modules/**",
        "!**/build/**",
        "!**/.gradle/**",
        "!**/.git/**"
    ))
}

tasks.register<Exec>("linkCheck") {
    commandLine("bash", "./dev-tools/link-check.sh")
}

tasks.named("check") {
    dependsOn(
        "lintMarkdown",
        "checkstyleMain",
        "spotbugsMain",
        "jacocoTestReport",
        "linkCheck"
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
    commandLine("bash", "generate-dev-certs.sh")
}

tasks.register<Exec>("devUp") {
    dependsOn("generateDevCerts", "buildDockerImages")
    commandLine("docker", "compose", "up", "--build")
}

tasks.register<Exec>("devDown") {
    commandLine("docker", "compose", "down")
}
