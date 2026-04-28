plugins {
    `java-test-fixtures`
    id("net.firedevops.firemud.stateful-service-conventions")
}

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.OutputDirectories
import org.gradle.api.tasks.TaskAction
import org.springframework.boot.gradle.tasks.run.BootRun

abstract class CreateDirectoriesTask : DefaultTask() {
    @get:OutputDirectories abstract val outputDirectories: ConfigurableFileCollection

    @TaskAction
    fun createDirectories() {
        outputDirectories.files.forEach { it.mkdirs() }
    }
}

val gameSessionProto = rootDir.resolve("protos/game-session/v1/game_session_service.proto")

apply(from = "${rootDir}/gradle/proto-convention.gradle")

tasks.named("generateProto") {
    // Make sure the Game Session proto definition is part of the stub generation inputs.
    inputs.file(gameSessionProto)
}

val generatedTestFixturesJavaDir = layout.buildDirectory.dir("generated/sources/proto/testFixtures/java")
val generatedTestFixturesGrpcDir = layout.buildDirectory.dir("generated/sources/proto/testFixtures/grpc")

val createEmptyTestFixturesProtoDirs =
    tasks.register<CreateDirectoriesTask>("createEmptyTestFixturesProtoDirs") {
        outputDirectories.from(generatedTestFixturesJavaDir, generatedTestFixturesGrpcDir)
    }

tasks.named("compileTestFixturesJava") {
    dependsOn(createEmptyTestFixturesProtoDirs)
}

tasks.named("generateTestFixturesProto") {
    finalizedBy(createEmptyTestFixturesProtoDirs)
}

dependencies {
    annotationProcessor(libs.spring.boot.configuration.processor)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation(libs.spring.boot.starter.websocket)
    implementation(project(":common-security"))
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    testFixturesApi(project(":entity-management-service"))
    testFixturesApi(project(":game-logic-service"))
    testFixturesApi(project(":social-groups-service"))
    testFixturesApi(project(":world-management-service"))
    testFixturesImplementation(project(":common-data-runtime"))
    testFixturesImplementation(libs.spring.boot.starter.actuator)
    testFixturesImplementation("io.grpc:grpc-netty-shaded:${libs.versions.grpc.get()}")
    testFixturesImplementation("io.grpc:grpc-protobuf:${libs.versions.grpc.get()}")
    testFixturesImplementation("io.grpc:grpc-stub:${libs.versions.grpc.get()}")
    testFixturesImplementation("com.google.protobuf:protobuf-java:${libs.versions.protobuf.get()}")
    testFixturesImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.grpc.inprocess)
    testImplementation(testFixtures(project(":game-session-service")))
    testImplementation(project(":game-logic-service"))
}

tasks.named<BootRun>("bootRun") {
    val activeProfile =
        System.getProperty("spring.profiles.active") ?: System.getenv("SPRING_PROFILES_ACTIVE")
    if (!activeProfile.isNullOrBlank()) {
        systemProperty("spring.profiles.active", activeProfile)
    }
}

val isWindows = System.getProperty("os.name").lowercase().contains("windows")

tasks.withType<Test>().configureEach {
    if (isWindows) {
        maxParallelForks = 1
        forkEvery = 0
        doNotTrackState("Workaround for Windows file locking on test binary output")
    }
}
