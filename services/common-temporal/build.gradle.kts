plugins {
    `java-library`
}

dependencies {
    annotationProcessor(libs.spring.boot.configuration.processor)
    implementation(libs.spring.boot.starter)
    api(libs.temporal.sdk)
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.9.8")

    testImplementation(libs.spring.boot.starter.test)
}
