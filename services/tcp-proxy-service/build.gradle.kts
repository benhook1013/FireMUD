plugins {
    id("org.springframework.boot") version "3.5.3" apply false
    id("org.flywaydb.flyway") version "11.10.1"
}

dependencies {
    implementation("org.mapstruct:mapstruct:1.6.3")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")
    implementation("org.flywaydb:flyway-core:11.10.1")
    implementation(project(":common-library"))
}
