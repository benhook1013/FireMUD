plugins {
    `java-library`
    id("org.springframework.boot") version "3.2.5" apply false
}

repositories {
    mavenCentral()
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-web:3.2.5")
    api("org.springframework.boot:spring-boot-starter-validation:3.2.5")
}
