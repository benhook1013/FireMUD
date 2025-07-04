plugins {
    java
    id("org.springframework.boot") version "3.2.5" apply false
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web:3.2.5")
    implementation("org.springframework.boot:spring-boot-starter-validation:3.2.5")
}
