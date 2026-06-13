plugins {
    `java-library`
}

apply(from = "${rootDir}/gradle/proto-convention.gradle")

dependencies {
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.spring.boot.configuration.processor)
    compileOnly(libs.lombok)

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation(libs.micrometer.core)
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    compileOnly(libs.spring.boot.starter.web)
    compileOnly(libs.spring.boot.starter.webflux)
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    compileOnly(libs.grpc.spring.boot.starter)

    compileOnly("com.github.spotbugs:spotbugs-annotations:4.10.2")
    testImplementation(libs.spring.boot.starter.test)
}
