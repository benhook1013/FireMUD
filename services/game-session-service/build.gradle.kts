
import org.springframework.boot.gradle.tasks.run.BootRun

val gameSessionProto = rootDir.resolve("protos/game-session/v1/game_session_service.proto")

apply(from = "${rootDir}/gradle/proto-convention.gradle")

tasks.named("generateProto") {
    // Make sure the Game Session proto definition is part of the stub generation inputs.
    inputs.file(gameSessionProto)
}

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
    runtimeOnly(libs.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.9.8")
}

tasks.named<BootRun>("bootRun") {
    val activeProfile = System.getProperty("spring.profiles.active")
        ?: System.getenv("SPRING_PROFILES_ACTIVE")
        ?: "dev"

    systemProperty("spring.profiles.active", activeProfile)
    val logOnly = System.getProperty("game-session.log-only")
        ?: System.getenv("GAME_SESSION_LOG_ONLY")
        ?: "true"
    systemProperty("game-session.log-only", logOnly)
    environment("GAME_SESSION_LOG_ONLY", logOnly)
}

tasks.register<BootRun>("bootRunLogOnly") {
    group = "application"
    description = "Start the game session service in dev with log-only handling"
    mainClass.set("net.firedevops.firemud.GameSessionServiceApplication")
    classpath = sourceSets.main.get().runtimeClasspath
    systemProperty("spring.profiles.active", "dev")
    environment("GAME_SESSION_LOG_ONLY", "true")
    systemProperty("game-session.log-only", "true")
}

