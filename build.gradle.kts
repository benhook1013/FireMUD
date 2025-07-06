import com.github.gradle.node.npm.task.NpxTask

plugins {
    java
    id("com.github.node-gradle.node") version "7.1.0"
    id("com.google.protobuf") version "0.9.4" apply false
    id("com.diffplug.spotless") version "6.25.0"
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
    group = "net.firedevops.firemud"
    version = "0.1.0-SNAPSHOT"

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.9.3")
        testImplementation("org.springframework.boot:spring-boot-starter-test:3.2.5")
        implementation("io.grpc:grpc-stub:1.61.0")
        implementation("io.grpc:grpc-protobuf:1.61.0")
        implementation("io.grpc:grpc-netty-shaded:1.61.0")
        implementation("com.google.protobuf:protobuf-java:3.25.3")
        implementation("javax.annotation:javax.annotation-api:1.3.2")
    }

    spotless {
        java {
            googleJavaFormat()
        }
    }

    tasks.test {
        useJUnitPlatform()
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
    dependsOn("lintMarkdown")
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
