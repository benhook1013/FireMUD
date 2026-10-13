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
    }
}

// Narrow classes-only proof artifact for Game Session repository tests. It contains no GD
// application resources or migrations and is built without running GD test or integration tasks.
val gameDesignCanonicalPublicationFixtureProofJar by tasks.registering(Jar::class) {
    archiveClassifier.set("canonical-publication-fixture-proof")
    dependsOn(tasks.named("compileTestJava"))
    from(sourceSets.main.get().output.classesDirs) {
        include("net/firedevops/firemud/gamedesign/publication/GameDesignPublicationOperation.class")
    }
    from(sourceSets.test.get().output.classesDirs) {
        include("net/firedevops/firemud/gamedesign/publication/IsolatedPublicationOperationFixtures*.class")
        include("net/firedevops/firemud/gamedesign/service/impl/PublishedWorldSelectorFixtures*.class")
    }
}

val gameDesignCanonicalPublicationFixtureProof by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(gameDesignCanonicalPublicationFixtureProofJar)
}

dependencies {
    integrationTestImplementation(project(mapOf("path" to ":account-service", "configuration" to "accountOriginalOrderProof")))
    integrationTestImplementation(project(mapOf("path" to ":logging-admin-service", "configuration" to "loggingStartSessionClaimProof")))
    integrationTestImplementation(project(mapOf("path" to ":game-logic-service", "configuration" to "gameLogicClassesProof")))
    integrationTestImplementation(
        project(
            mapOf(
                "path" to ":game-session-service",
                "configuration" to "gameSessionClassesProof"
            )
        )
    )
    integrationTestImplementation(
        project(
            mapOf(
                "path" to ":world-management-service",
                "configuration" to "worldSelectedOwnerInventoryProof"
            )
        )
    )
    integrationTestImplementation(
        project(
            mapOf(
                "path" to ":automation-scripting-service",
                "configuration" to "automationClassesProof"
            )
        )
    )
    integrationTestImplementation(
        project(
            mapOf(
                "path" to ":entity-management-service",
                "configuration" to "entityClassesProof"
            )
        )
    )
    integrationTestImplementation("io.grpc:grpc-util:${libs.versions.grpc.get()}")
    compileOnly(libs.spotbugs.annotations)
    testCompileOnly(libs.spotbugs.annotations)
    add("integrationTestCompileOnly", libs.spotbugs.annotations)
    testImplementation(libs.bouncycastle.pkix)
    integrationTestImplementation(libs.bouncycastle.pkix)
    implementation(libs.aws.sdk.s3)
}
