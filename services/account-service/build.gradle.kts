
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import javax.inject.Inject

apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    `java-test-fixtures`
    id("net.firedevops.firemud.secured-stateful-service-conventions")
    id("net.firedevops.firemud.aop-conventions")
    id("net.firedevops.firemud.jooq-conventions")
}

abstract class ExtractClassFilesFromArchives : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val archives: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val destinationDirectory: DirectoryProperty

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun extractClassFiles() {
        fileSystemOperations.sync {
            archives.forEach { archive ->
                from(archiveOperations.zipTree(archive)) {
                    include("**/*.class")
                }
            }
            into(destinationDirectory.get().asFile)
        }
    }
}

firemudJooq {
    packageName.set("net.firedevops.firemud.accountservice.jooq")
}

configurations.named("testFixturesImplementation") {
    extendsFrom(configurations.implementation.get())
}

dependencies {
    implementation(project(":common-redis-contracts"))
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.argon2)
    implementation(libs.stripe.java)

    testFixturesImplementation(platform(libs.spring.boot.dependencies))
    testFixturesImplementation(project(":common-platform-core"))
    testFixturesImplementation(project(":common-security"))
    testFixturesImplementation(project(":common-data-runtime"))
    testFixturesImplementation(libs.spring.boot.starter.test)
    testFixturesImplementation(libs.spring.boot.starter.jdbc)
    testFixturesImplementation(libs.spring.boot.starter.data.redis)
    testFixturesImplementation(libs.spring.aop)
    testFixturesImplementation(libs.mapstruct)
    testFixturesImplementation(libs.jooq)
    testFixturesImplementation(libs.flyway.core)
    testFixturesImplementation("com.fasterxml.jackson.core:jackson-databind")
    testFixturesImplementation("tools.jackson.core:jackson-databind")
    testFixturesImplementation("com.google.protobuf:protobuf-java:${libs.versions.protobuf.get()}")
    testFixturesImplementation("io.grpc:grpc-stub:${libs.versions.grpc.get()}")

    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.grpc.netty.shaded)
    testImplementation(project(":logging-admin-service"))
    testImplementation(testFixtures(project(":account-service")))
    testImplementation(testFixtures(project(":common-platform-core")))
    testCompileOnly(libs.spotbugs.annotations)
    add("integrationTestCompileOnly", libs.spotbugs.annotations)
    integrationTestImplementation(project(path = ":game-session-service", configuration = "gameSessionClassesProof"))
    integrationTestImplementation(project(":social-groups-service"))
    integrationTestImplementation(testFixtures(project(":account-service")))
    integrationTestImplementation(testFixtures(project(":logging-admin-service")))
}

val loggingAdminProjectArtifacts = { configurationName: String ->
    configurations
        .getByName(configurationName)
        .incoming
        .artifactView {
            componentFilter { componentId ->
                componentId is ProjectComponentIdentifier
                    && componentId.projectPath == ":logging-admin-service"
            }
        }
        .artifacts
}

val loggingAdminTestArtifacts = loggingAdminProjectArtifacts("testRuntimeClasspath")
val loggingAdminIntegrationTestArtifacts =
    loggingAdminProjectArtifacts("integrationTestRuntimeClasspath")

// Exercise the actual Social guard/handler while retaining Account's application and Flyway resources.
val socialIntegrationTestArtifacts = configurations
    .getByName("integrationTestRuntimeClasspath")
    .incoming.artifactView {
        componentFilter { componentId ->
            componentId is ProjectComponentIdentifier
                && componentId.projectPath == ":social-groups-service"
        }
    }.artifacts

val extractSocialIntegrationTestClasses = tasks.register<ExtractClassFilesFromArchives>("extractSocialIntegrationTestClasses") {
    dependsOn(socialIntegrationTestArtifacts.artifactFiles)
    archives.from(socialIntegrationTestArtifacts.artifactFiles)
    destinationDirectory.set(layout.buildDirectory.dir("account-test-classpath/social-integration"))
}

val extractLoggingAdminTestClasses =
    tasks.register<ExtractClassFilesFromArchives>("extractLoggingAdminTestClasses") {
        dependsOn(loggingAdminTestArtifacts.artifactFiles)
        archives.from(loggingAdminTestArtifacts.artifactFiles)
        destinationDirectory.set(layout.buildDirectory.dir("account-test-classpath/logging-main"))
    }

val extractLoggingAdminIntegrationTestClasses =
    tasks.register<ExtractClassFilesFromArchives>("extractLoggingAdminIntegrationTestClasses") {
        dependsOn(loggingAdminIntegrationTestArtifacts.artifactFiles)
        archives.from(loggingAdminIntegrationTestArtifacts.artifactFiles)
        destinationDirectory.set(
            layout.buildDirectory.dir("account-test-classpath/logging-integration")
        )
    }

tasks.named<Test>("test") {
    val loggingArtifacts = loggingAdminTestArtifacts.artifactFiles
    classpath = classpath.filter { it !in loggingArtifacts } + files(extractLoggingAdminTestClasses)
}

tasks.named<Test>("integrationTest") {
    // Isolate fixture statics in separate JVMs; JUnit methods remain serial within each suite.
    // Keep other platforms' existing single-fork behavior and respect a one-worker invocation.
    if (System.getProperty("os.name").lowercase().startsWith("linux")) {
        maxParallelForks = minOf(2, gradle.startParameter.maxWorkerCount)
    }
    // Composition fixtures load Game Session and Common Saga migrations directly from the filesystem.
    inputs.dir(project(":game-session-service").file("src/main/resources/db/migration"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(project(":common-saga").file("src/main/resources/db/migration/saga"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val loggingArtifacts = loggingAdminIntegrationTestArtifacts.artifactFiles
    classpath = classpath.filter { it !in loggingArtifacts } + files(extractLoggingAdminIntegrationTestClasses)
    val socialArtifacts = socialIntegrationTestArtifacts.artifactFiles
    classpath = classpath.filter { it !in socialArtifacts } + files(extractSocialIntegrationTestClasses)
}

// Owner proof exports classes and its required registry script without Account application or
// Flyway resources, so a receiving service retains its own configuration and migration ownership.
val accountOriginalOrderProofJar by tasks.registering(Jar::class) {
    archiveClassifier.set("original-order-proof")
    dependsOn(tasks.named("classes"), tasks.named("testFixturesClasses"))
    from(sourceSets.main.get().output.classesDirs)
    from(sourceSets["testFixtures"].output.classesDirs)
    from("src/main/resources") {
        include("redis/lua/account-control-ui-registry-cas.lua")
    }
}

val accountOriginalOrderProof by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(configurations.implementation.get())
    outgoing.artifact(accountOriginalOrderProofJar)
}
