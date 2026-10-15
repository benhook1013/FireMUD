import org.gradle.api.plugins.jvm.JvmTestSuite

apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.secured-sql-aop-service-conventions")
    id("net.firedevops.firemud.jooq-conventions")
    id("net.firedevops.firemud.temporal-conventions")
}

firemudJooq {
    packageName.set("net.firedevops.firemud.gamedesign.jooq")
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            sources.java.srcDir("src/sharedTest/java")
        }
        named<JvmTestSuite>("integrationTest") {
            sources.java.srcDir("src/sharedTest/java")
        }
        named<JvmTestSuite>("crossServiceTest") {
            sources.java.srcDir("src/sharedTest/java")
        }
    }
}

dependencies {
    compileOnly(libs.spotbugs.annotations)
    implementation(libs.aws.sdk.s3)
    testCompileOnly(libs.spotbugs.annotations)
    add("integrationTestCompileOnly", libs.spotbugs.annotations)
    add("crossServiceTestImplementation", project(":game-session-service"))
    add(
        "crossServiceTestImplementation",
        project(mapOf("path" to ":world-management-service", "configuration" to "worldClassesProof"))
    )
    add(
        "crossServiceTestImplementation",
        project(mapOf("path" to ":account-service", "configuration" to "accountOriginalOrderProof"))
    )
    add(
        "crossServiceTestImplementation",
        project(mapOf("path" to ":game-logic-service", "configuration" to "gameLogicClassesProof"))
    )
    testImplementation(testFixtures(project(":common-platform-core")))
    add("crossServiceTestCompileOnly", libs.spotbugs.annotations)
    add("crossServiceTestImplementation", "io.grpc:grpc-util:${libs.versions.grpc.get()}")
    testImplementation(libs.bouncycastle.pkix)
}

// Export GDS owner classes for the GL composed proof without application or Flyway resources.
val gameDesignClassesProofJar by tasks.registering(Jar::class) {
    archiveClassifier.set("classes-proof")
    dependsOn(tasks.named("classes"))
    from(sourceSets.main.get().output.classesDirs)
}

val gameDesignClassesProof by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(configurations.implementation.get())
    outgoing.artifact(gameDesignClassesProofJar)
}

// Composed owner proofs load foreign migrations from disk; classes-only artifacts omit them.
tasks.named("crossServiceTest") {
    inputs.dir(rootProject.layout.projectDirectory.dir("services/game-logic-service/src/main/resources/db/migration"))
        .withPropertyName("gameLogicOwnerMigrations")
    inputs.dir(rootProject.layout.projectDirectory.dir("services/world-management-service/src/main/resources/db/migration"))
        .withPropertyName("worldOwnerMigrations")
}
