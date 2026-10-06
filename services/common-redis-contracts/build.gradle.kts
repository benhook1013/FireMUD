plugins {
    `java-library`
}

dependencies {
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(libs.spring.boot.starter.test)
}
