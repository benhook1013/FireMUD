plugins {
    `java`
    `maven-publish`
}

java {
    // no compiled classes; just package proto files
    sourceSets["main"].java.srcDirs()
    withSourcesJar()
}

// Include proto files as resources
sourceSets["main"].resources.srcDir("${rootProject.projectDir}/protos")

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
            url = uri("https://maven.pkg.github.com/firedevops/firemud")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
