plugins {
    `java-library`
}

dependencies {
    annotationProcessor(libs.spring.boot.configuration.processor)
    implementation(libs.spring.boot.starter)
    api(libs.temporal.sdk)
    compileOnly(libs.spotbugs.annotations)

    testImplementation(libs.spring.boot.starter.test)
}
