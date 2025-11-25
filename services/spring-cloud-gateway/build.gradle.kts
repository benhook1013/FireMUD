
import org.springframework.boot.gradle.tasks.run.BootRun

apply(from = "${rootDir}/gradle/proto-convention.gradle")

dependencies {
    annotationProcessor(libs.mapstruct.processor)
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.lombok.mapstruct.binding)
    compileOnly(libs.lombok)
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.9.8")
    implementation(libs.flyway.core)
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
    testImplementation("io.projectreactor:reactor-test:3.6.8")
    testRuntimeOnly("io.grpc:grpc-netty:1.77.0")
}

tasks.named<BootRun>("bootRun") {
    val jvmProfile = System.getProperty("spring.profiles.active")
    val envProfile = System.getenv("SPRING_PROFILES_ACTIVE")

    when {
        jvmProfile != null -> systemProperty("spring.profiles.active", jvmProfile)
        envProfile != null -> {
        }
        else -> systemProperty("spring.profiles.active", "dev")
    }
}

tasks.register<BootRun>("bootRunLogOnly") {
    group = "application"
    description = "Start the gateway in dev with log-only WebSocket handling"
    mainClass.set("net.firedevops.firemud.SpringCloudGatewayApplication")
    classpath = sourceSets.main.get().runtimeClasspath
    systemProperty("spring.profiles.active", "dev")
    environment("GATEWAY_WS_LOG_ONLY", "true")
}



