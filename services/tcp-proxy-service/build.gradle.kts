import com.github.spotbugs.snom.SpotBugsTask

plugins {
    // Apply the Spring Boot plugin so `bootBuildImage` is available for Docker builds
    id("org.springframework.boot") version "3.5.3"
    id("org.flywaydb.flyway") version "11.10.1"
}

dependencies {
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
    annotationProcessor("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")
    compileOnly("org.projectlombok:lombok:1.18.38")
    implementation("org.flywaydb:flyway-core:11.10.1")
    implementation("org.mapstruct:mapstruct:1.6.3")
    implementation(project(":common-library"))
}

tasks.named<SpotBugsTask>("spotbugsMain") {
    dependsOn(tasks.named("compileJava"))
    classes = files(
        fileTree("$buildDir/classes/java/main") {
            exclude("**/proto/**")
        }
    )
}

tasks.named<SpotBugsTask>("spotbugsTest") {
    dependsOn(tasks.named("compileTestJava"))
    classes = files(
        fileTree("$buildDir/classes/java/test") {
            exclude("**/proto/**")
        }
    )
}

