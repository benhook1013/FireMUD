
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
import org.gradle.language.jvm.tasks.ProcessResources
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
    implementation(libs.postgresql)

    testFixturesImplementation(platform(libs.spring.boot.dependencies))
    testFixturesImplementation(project(":common-platform-core"))
    testFixturesImplementation(project(":common-security"))
    testFixturesImplementation(project(":common-data-runtime"))
    testFixturesApi(testFixtures(project(":common-test-support")))
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

    // Stripe declares Gson runtime-only; typed SDK response fixtures compile against it.
    testImplementation("com.google.code.gson:gson")
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.grpc.netty.shaded)
    testImplementation(project(":logging-admin-service"))
    testImplementation(testFixtures(project(":account-service")))
    testImplementation(testFixtures(project(":common-platform-core")))
    testCompileOnly(libs.spotbugs.annotations)
    add("integrationTestCompileOnly", libs.spotbugs.annotations)
    integrationTestImplementation(project(path = ":game-session-service", configuration = "gameSessionClassesProof"))
    integrationTestImplementation(project(path = ":entity-management-service", configuration = "entityClassesProof"))
    // Share canonical bootstrap proof values without importing Entity application resources.
    val entityBootstrapProof = testFixtures(project(":entity-management-service"))
        as org.gradle.api.artifacts.ModuleDependency
    entityBootstrapProof.isTransitive = false
    integrationTestImplementation(entityBootstrapProof)
    integrationTestImplementation(testFixtures(project(":common-test-support")))
    integrationTestImplementation(project(":social-groups-service"))
    integrationTestImplementation(project(":spring-cloud-gateway"))
    integrationTestImplementation(libs.spring.cloud.gateway)
    integrationTestImplementation(libs.spring.boot.starter.webflux)
    integrationTestImplementation(libs.spring.boot.starter.data.redis.reactive)
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

// The registered checkout proof imports Gateway handlers, not its application or Flyway resources.
val gatewayIntegrationTestArtifacts = configurations
    .getByName("integrationTestRuntimeClasspath")
    .incoming.artifactView {
        componentFilter { componentId ->
            componentId is ProjectComponentIdentifier
                && componentId.projectPath == ":spring-cloud-gateway"
        }
    }.artifacts

val extractGatewayIntegrationTestClasses = tasks.register<ExtractClassFilesFromArchives>("extractGatewayIntegrationTestClasses") {
    dependsOn(gatewayIntegrationTestArtifacts.artifactFiles)
    archives.from(gatewayIntegrationTestArtifacts.artifactFiles)
    destinationDirectory.set(layout.buildDirectory.dir("account-test-classpath/gateway-integration"))
}

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

// Keep the service policy available under a unique name despite the test application.yml.
tasks.named<ProcessResources>("processIntegrationTestResources") {
    from("src/main/resources/application.yml") {
        rename { "account-main-application.yml" }
    }
}

tasks.named<Test>("integrationTest") {
    // This JVM belongs to Account MVC. The checkout proof imports Gateway's explicit receiving
    // configuration, not SCG's foreign application/route auto-configuration (which rejects MVC).
    systemProperty("spring.cloud.gateway.server.webflux.enabled", "false")
    // SCG Redis auto-configuration has a separate switch and must also stay outside Account.
    systemProperty("spring.cloud.gateway.server.webflux.redis.enabled", "false")
    // Isolate fixture statics in separate JVMs; JUnit methods remain serial within each suite.
    // Keep other platforms' existing single-fork behavior and respect a one-worker invocation.
    if (System.getProperty("os.name").lowercase().startsWith("linux")) {
        maxParallelForks = minOf(2, gradle.startParameter.maxWorkerCount)
    }
    // Composition fixtures load Game Session and Common Saga migrations directly from the filesystem.
    inputs.dir(project(":game-session-service").file("src/main/resources/db/migration"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val entityMigrationDirectory =
        project(":entity-management-service").file("src/main/resources/db/migration")
    inputs.dir(entityMigrationDirectory).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("firemud.entity-management.migration-directory", entityMigrationDirectory.absolutePath)
    inputs.dir(project(":common-saga").file("src/main/resources/db/migration/saga"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val loggingArtifacts = loggingAdminIntegrationTestArtifacts.artifactFiles
    classpath = classpath.filter { it !in loggingArtifacts } + files(extractLoggingAdminIntegrationTestClasses)
    val socialArtifacts = socialIntegrationTestArtifacts.artifactFiles
    classpath = classpath.filter { it !in socialArtifacts } + files(extractSocialIntegrationTestClasses)
    val gatewayArtifacts = gatewayIntegrationTestArtifacts.artifactFiles
    classpath = classpath.filter { it !in gatewayArtifacts } + files(extractGatewayIntegrationTestClasses)
}

// Owner proof exports classes and required registry/projection resources without Account application or
// Flyway resources, so a receiving service retains its own configuration and migration ownership.
val accountOriginalOrderProofJar by tasks.registering(Jar::class) {
    archiveClassifier.set("original-order-proof")
    dependsOn(tasks.named("classes"), tasks.named("testFixturesClasses"))
    from(sourceSets.main.get().output.classesDirs)
    from(sourceSets["testFixtures"].output.classesDirs)
    from("src/main/resources") {
        include("redis/lua/account-control-ui-registry-cas.lua")
        include("redis/lua/account-game-session-delegation-pending-register.lua")
        include("redis/lua/account-game-session-delegation-activate-committed.lua")
        include("redis/lua/account-game-session-delegation-authority-project.lua")
        include("redis/lua/account-initial-gameplay-login-dispose.lua")
        include("redis/account_generation_projection_cas.lua")
        include("redis/scripts/account-game-session-delegation-pending-register.json")
        include("redis/scripts/account-game-session-delegation-activate-committed.json")
        include("redis/scripts/account-game-session-delegation-authority-project-issuer.json")
        include("redis/scripts/account-initial-gameplay-login-dispose.json")
        include("redis/scripts/account-generation-projection-cas.json")
        include("redis/scripts/account-selected-tenant-generation-projection.json")
        include("redis/scripts/account-selected-membership-generation-projection.json")
        include("META-INF/services/net.firedevops.firemud.common.redis.contracts.RedisScriptContribution")
    }
}

val accountOriginalOrderProof by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(configurations.implementation.get())
    outgoing.artifact(accountOriginalOrderProofJar)
}
