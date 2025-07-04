plugins {
    java
    id("org.springframework.boot") version "3.2.5" 
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter:3.2.5")
    implementation(project(":common-library"))
}
