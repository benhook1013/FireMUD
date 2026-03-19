
plugins {
    `java-library`
    alias(libs.plugins.spring.boot) apply false
    `maven-publish`
}

dependencies {
    api(project(":common-platform-core"))
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.web)

    api(libs.spring.boot.starter.web)
    api(libs.spring.boot.starter.validation)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "firemud-common"
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
