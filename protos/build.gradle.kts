plugins {
    `java-library`
    `maven-publish`
}

tasks.register<Jar>("sourcesJar") {
    from(".") { include("**/*.proto") }
    archiveClassifier.set("sources")
}

artifacts {
    add("archives", tasks.named("sourcesJar"))
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "firemud-protos"
        }
    }
    repositories {
        maven {
            name = "github"
            url = uri("https://maven.pkg.github.com/benhook1013/firemud")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
