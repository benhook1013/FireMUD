plugins {
    java
    id("org.springframework.boot") version "3.2.5" apply false
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":common-library"))
}
