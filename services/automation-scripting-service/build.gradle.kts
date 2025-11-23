
apply(from = "${rootDir}/gradle/proto-convention.gradle")

dependencies {
    annotationProcessor(libs.mapstruct.processor)
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.lombok.mapstruct.binding)
    compileOnly(libs.lombok)
    implementation(libs.flyway.core)
    implementation(libs.mapstruct)
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(project(":common-library"))
    implementation(libs.grpc.spring.boot.starter)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.jjwt.api)
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.9.8")
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}



