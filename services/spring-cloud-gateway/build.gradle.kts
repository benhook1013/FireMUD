import com.google.protobuf.gradle.*

plugins {
    java
    id("org.springframework.boot") version "3.5.3"
    id("org.flywaydb.flyway") version "9.22.3"
    id("com.google.protobuf")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.mapstruct:mapstruct:1.5.5.Final")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.5.5.Final")
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
    implementation("org.springframework.boot:spring-boot-starter:3.2.5")
    implementation("org.springframework.boot:spring-boot-starter-actuator:3.2.5")
    implementation("org.flywaydb:flyway-core:9.22.3")
    implementation("org.springframework.cloud:spring-cloud-starter-gateway:4.3.0")
    implementation(project(":common-library"))
}
