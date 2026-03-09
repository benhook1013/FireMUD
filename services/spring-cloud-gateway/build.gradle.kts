
import org.springframework.boot.gradle.tasks.run.BootRun

apply(from = "${rootDir}/gradle/proto-convention.gradle")

dependencies {
    annotationProcessor(libs.mapstruct.processor)
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.lombok.mapstruct.binding)
    compileOnly(libs.lombok)
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.9.8")
    implementation(libs.mapstruct)
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.cloud.gateway)
    implementation(libs.grpc.spring.boot.starter)
    implementation(libs.spring.boot.starter.data.redis.reactive)
    implementation(project(":common-library"))
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    testImplementation("io.projectreactor:reactor-test:3.8.3")
    testImplementation(libs.spring.boot.starter.websocket)
    testImplementation(libs.spring.boot.starter.webflux)
    testImplementation("com.h2database:h2:2.4.240")
    testRuntimeOnly("io.grpc:grpc-netty:1.79.0")
}

tasks.named<BootRun>("bootRun") {
    val activeProfile =
        System.getProperty("spring.profiles.active") ?: System.getenv("SPRING_PROFILES_ACTIVE")

    if (activeProfile.isNullOrBlank()) {
        systemProperty("spring.profiles.active", "dev")
    }
}

tasks.register<BootRun>("bootRunDevIsolated") {
    group = "application"
    description = "Start the gateway in dev with dev-isolated WebSocket handling"
    mainClass.set("net.firedevops.firemud.springcloudgateway.SpringCloudGatewayApplication")
    classpath = sourceSets.main.get().runtimeClasspath
    systemProperty("spring.profiles.active", "dev")
    environment("TCP_PROXY_DEV_ISOLATED", "true")
}
