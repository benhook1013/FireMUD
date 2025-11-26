
import org.springframework.boot.gradle.tasks.run.BootRun

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
    testImplementation(libs.micrometer.registry.prometheus)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    testImplementation(libs.spring.boot.starter.webflux)
}

tasks.named<BootRun>("bootRun") {
    systemProperty("spring.profiles.active", System.getProperty("spring.profiles.active") ?: "dev")
}

tasks.named<BootRun>("bootRun") {
    val activeProfile = System.getProperty("spring.profiles.active") ?: System.getenv("SPRING_PROFILES_ACTIVE")

    if (activeProfile == null) {
        systemProperty("spring.profiles.active", "dev")
    }
}

tasks.register<BootRun>("bootRunLogOnly") {
    group = "application"
    description = "Start the TCP proxy in dev with log-only Telnet handling"
    mainClass.set("net.firedevops.firemud.TcpProxyServiceApplication")
    classpath = sourceSets.main.get().runtimeClasspath
    systemProperty("spring.profiles.active", "dev")
    environment("TCP_PROXY_LOG_ONLY", "true")
    environment("GATEWAY_WS_URL", "ws://localhost:8080/dev/echo")
}
