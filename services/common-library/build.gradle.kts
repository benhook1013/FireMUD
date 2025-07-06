plugins {
    `java-library`
    id("org.springframework.boot") version "3.5.3" apply false
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.mapstruct:mapstruct:1.5.5.Final")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.5.5.Final")
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-web:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-validation:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-data-redis:3.5.3")
    implementation("org.springframework.boot:spring-boot-starter-jdbc:3.5.3")
    implementation("io.micrometer:micrometer-core:1.15.1")
    implementation("io.jsonwebtoken:jjwt-api:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")

    api("org.springframework.boot:spring-boot-starter-web:3.5.3")
    api("org.springframework.boot:spring-boot-starter-validation:3.5.3")
}
