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

dependencies {
    integrationTestImplementation(project(mapOf("path" to ":account-service", "configuration" to "accountOriginalOrderProof")))
    integrationTestImplementation(project(mapOf("path" to ":game-logic-service", "configuration" to "gameLogicClassesProof")))
    integrationTestImplementation(project(mapOf("path" to ":world-management-service", "configuration" to "worldClassesProof")))
    integrationTestImplementation("io.grpc:grpc-util:${libs.versions.grpc.get()}")
    compileOnly(libs.spotbugs.annotations)
    testCompileOnly(libs.spotbugs.annotations)
    add("integrationTestCompileOnly", libs.spotbugs.annotations)
    testImplementation(libs.bouncycastle.pkix)
    integrationTestImplementation(libs.bouncycastle.pkix)
    implementation(libs.aws.sdk.s3)
}
