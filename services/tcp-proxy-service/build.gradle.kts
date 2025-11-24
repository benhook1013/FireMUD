
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
    implementation("io.netty:netty-all:4.2.7.Final")
    implementation(libs.spring.boot.starter.websocket)
    implementation(project(":common-library"))
    implementation(libs.grpc.spring.boot.starter)
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
}


