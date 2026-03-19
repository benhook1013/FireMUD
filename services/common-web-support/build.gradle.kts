plugins {
    `java-library`
    alias(libs.plugins.spring.boot) apply false
}

dependencies {
    api(project(":common-platform-core"))
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.web)
}
