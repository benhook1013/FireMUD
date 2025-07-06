import com.github.gradle.node.npm.task.NpxTask
import com.github.spotbugs.snom.SpotBugsTask

plugins {
    java
    id("com.github.node-gradle.node") version "7.1.0"
    id("com.google.protobuf") version "0.9.4" apply false
    id("com.diffplug.spotless") version "6.25.0"
    id("checkstyle")
    id("com.github.spotbugs") version "6.2.1"
    jacoco
}

node {
    version.set("20.11.0")
    download.set(true)
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.google.protobuf")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "checkstyle")
    apply(plugin = "com.github.spotbugs")
    apply(plugin = "jacoco")
    group = "net.firedevops.firemud"
    version = "0.1.0-SNAPSHOT"

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.9.3")
        testImplementation("org.springframework.boot:spring-boot-starter-test:3.2.5")
        implementation("io.grpc:grpc-stub:1.73.0")
        implementation("io.grpc:grpc-protobuf:1.73.0")
        implementation("io.grpc:grpc-netty-shaded:1.73.0")
        implementation("com.google.protobuf:protobuf-java:3.25.3")
        implementation("javax.annotation:javax.annotation-api:1.3.2")
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
        setIgnoreFailures(true)
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

tasks.register("buildDockerImages") {
    dependsOn(
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

tasks.register<Exec>("devUp") {
    dependsOn("buildDockerImages")
    commandLine("docker", "compose", "up", "--build")
}

tasks.register<Exec>("devDown") {
    commandLine("docker", "compose", "down")
}
