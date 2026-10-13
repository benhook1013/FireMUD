
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    `java-test-fixtures`
    id("net.firedevops.firemud.secured-sql-aop-service-conventions")
    id("net.firedevops.firemud.openapi-conventions")
    id("net.firedevops.firemud.jooq-conventions")
}

firemudJooq {
    packageName.set("net.firedevops.firemud.loggingadmin.jooq")
}

dependencies {
    testImplementation(testFixtures(project(":common-platform-core")))
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.grpc.netty.shaded)
    testImplementation(testFixtures(project(":logging-admin-service")))
    testCompileOnly(libs.spotbugs.annotations)
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.jooq)
    testFixturesImplementation(libs.postgresql)
    testFixturesImplementation(libs.spring.boot.starter.test)
    testFixturesImplementation(libs.testcontainers.postgresql)
}

// Opt-in cross-owner proof: exclude Logging application and migration resources from Account.
val loggingStartSessionClaimProofJar by tasks.registering(Jar::class) {
    archiveClassifier.set("start-session-claim-proof")
    dependsOn(tasks.named("classes"), tasks.named("testFixturesClasses"))
    from(sourceSets.main.get().output.classesDirs)
    from(sourceSets["testFixtures"].output.classesDirs)
}

val loggingStartSessionClaimProof by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(configurations.implementation.get())
    outgoing.artifact(loggingStartSessionClaimProofJar)
}
