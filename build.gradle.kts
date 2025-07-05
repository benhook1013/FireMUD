import com.github.gradle.node.npm.task.NpxTask

plugins {
    java
    id("com.github.node-gradle.node") version "7.1.0"
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
    group = "net.fire-devops.firemud"
    version = "0.1.0-SNAPSHOT"

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.9.3")
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
        "!**/.gradle/**"
    ))
}

tasks.register<NpxTask>("lintMarkdownFix") {
    command.set("markdownlint-cli2")
    args.set(listOf(
        "--fix",
        "**/*.md",
        "!**/node_modules/**",
        "!**/build/**",
        "!**/.gradle/**"
    ))
}

tasks.named("check") {
    dependsOn("lintMarkdown")
}
