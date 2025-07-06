plugins {
    java
    id("org.springframework.boot") version "3.5.3" apply false
    id("org.flywaydb.flyway") version "11.10.1"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.mapstruct:mapstruct:1.5.5.Final")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.5.5.Final")
    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")
    implementation("org.flywaydb:flyway-core:9.22.3")
    implementation(project(":common-library"))
}
