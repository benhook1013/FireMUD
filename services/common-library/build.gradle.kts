import com.github.spotbugs.snom.SpotBugsTask

plugins {
    `java-library`
    id("org.springframework.boot") version "3.5.3" apply false
}


dependencies {
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
    annotationProcessor("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.5.3")
    compileOnly("org.projectlombok:lombok:1.18.38")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    implementation("io.micrometer:micrometer-core:1.15.1")
    implementation("org.mapstruct:mapstruct:1.6.3")
    implementation("org.springframework.boot:spring-boot-starter-data-redis:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-jdbc:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-validation:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-web:3.5.3")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    api("org.springframework.boot:spring-boot-starter-web:3.5.3")
    api("org.springframework.boot:spring-boot-starter-validation:3.5.3")
}

tasks.named<SpotBugsTask>("spotbugsMain") {
    dependsOn(tasks.named("compileJava"))
    (classes as org.gradle.api.file.ConfigurableFileCollection).setFrom(fileTree("$buildDir/classes/java/main") {
        exclude("**/proto/**")
        exclude("**/*OuterClass.class")
    })
}

tasks.named<SpotBugsTask>("spotbugsTest") {
    dependsOn(tasks.named("compileTestJava"))
    (classes as org.gradle.api.file.ConfigurableFileCollection).setFrom(fileTree("$buildDir/classes/java/test") {
        exclude("**/proto/**")
        exclude("**/*OuterClass.class")
    })
}

