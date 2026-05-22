plugins {
    `java-library`
    alias(libs.plugins.spring.boot) apply false
}

dependencies {
    api(project(":common-platform-core"))
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.spring.boot.configuration.processor)
    compileOnly(libs.lombok)
    implementation("tools.jackson.core:jackson-databind")
    implementation(libs.micrometer.core)
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.data.redis)

    compileOnly("com.github.spotbugs:spotbugs-annotations:4.9.8")

    testImplementation(libs.spring.boot.starter.test)
}
