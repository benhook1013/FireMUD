import com.google.protobuf.gradle.*

plugins {
    java
    id("org.springframework.boot") version "3.5.3"
    id("org.flywaydb.flyway") version "11.10.1"
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
    implementation("org.springframework.boot:spring-boot-starter:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-actuator:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa:3.5.3")
    runtimeOnly("org.postgresql:postgresql:42.7.2")
    implementation("org.flywaydb:flyway-core:9.22.3")
    implementation(project(":common-library"))
}
