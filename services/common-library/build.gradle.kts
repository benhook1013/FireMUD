import com.github.spotbugs.snom.SpotBugsTask

plugins {
    `java-library`
    id("org.springframework.boot") version "3.5.3" apply false
    `maven-publish`
}


dependencies {
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
    annotationProcessor("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.5.3")
    compileOnly("org.projectlombok:lombok:1.18.38")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    implementation("io.micrometer:micrometer-core:1.15.1")
    implementation("io.opentelemetry:opentelemetry-api:1.52.0")
    implementation("org.mapstruct:mapstruct:1.6.3")
    implementation("org.springframework.boot:spring-boot-starter-data-redis:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-jdbc:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-validation:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-web:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-aop:3.5.3")
    compileOnly("io.github.lognet:grpc-spring-boot-starter:5.2.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    compileOnly("com.github.spotbugs:spotbugs-annotations:4.9.3")

    api("org.springframework.boot:spring-boot-starter-web:3.5.3")
    api("org.springframework.boot:spring-boot-starter-validation:3.5.3")
}

tasks.named<SpotBugsTask>("spotbugsMain") {
    dependsOn(tasks.named("compileJava"))
    classes = files(
        fileTree(project.layout.buildDirectory.dir("classes/java/main")) {
            exclude("**/proto/**")
        }
    )
}

tasks.named<SpotBugsTask>("spotbugsTest") {
    dependsOn(tasks.named("compileTestJava"))
    classes = files(
        fileTree(project.layout.buildDirectory.dir("classes/java/test")) {
            exclude("**/proto/**")
        }
    )
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
            url = uri("https://maven.pkg.github.com/firedevops/firemud")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

