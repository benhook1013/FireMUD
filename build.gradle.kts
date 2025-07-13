import com.github.gradle.node.npm.task.NpxTask
import com.github.spotbugs.snom.SpotBugsTask
import java.io.File

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.fasterxml.jackson.core:jackson-databind:2.19.1")
    }
}

plugins {
    java
    id("com.github.node-gradle.node") version "7.1.0"
    id("com.google.protobuf") version "0.9.5" apply false
    id("com.diffplug.spotless") version "7.1.0"
    id("checkstyle")
    id("com.github.spotbugs") version "6.2.2"
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

    dependencies {
        implementation("com.google.protobuf:protobuf-java:4.31.1")
        implementation("io.grpc:grpc-netty-shaded:1.73.0")
        implementation("io.grpc:grpc-protobuf:1.73.0")
        implementation("io.grpc:grpc-stub:1.73.0")
        implementation("javax.annotation:javax.annotation-api:1.3.2")
        testImplementation("org.springframework.boot:spring-boot-starter-test:3.5.3")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.3")
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
        "--fix",
        "**/*.md",
        "!**/node_modules/**",
        "!**/build/**",
        "!**/.gradle/**",
        "!**/.git/**"
    ))
}

tasks.named("check") {
    dependsOn(
        "lintMarkdown",
        "checkstyleMain",
        "spotbugsMain",
        "jacocoTestReport"
    )
}

tasks.register<Exec>("buildBaseImage") {
    commandLine(
        "docker",
        "build",
        "-f",
        "docker/base.Dockerfile",
        "-t",
        "ghcr.io/firedevops/firemud-base:latest",
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
