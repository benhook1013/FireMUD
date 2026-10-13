
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.service-conventions")
    id("net.firedevops.firemud.openapi-conventions")
    id("net.firedevops.firemud.sql-postgres-conventions")
    id("net.firedevops.firemud.jooq-conventions")
}

firemudJooq {
    packageName.set("net.firedevops.firemud.gamelogic.jooq")
}

dependencies {
    integrationTestImplementation(project(mapOf("path" to ":account-service", "configuration" to "accountOriginalOrderProof")))
    integrationTestImplementation(libs.bouncycastle.pkix)
    compileOnly(libs.spotbugs.annotations)
    implementation(project(":common-security"))
    annotationProcessor(libs.spring.boot.configuration.processor)
}

// Opt-in proof artifact: expose Game Logic classes to owner integration tests without its application resources.
val gameLogicClassesProofJar by tasks.registering(Jar::class) {
    archiveClassifier.set("classes-proof")
    dependsOn(tasks.named("classes"))
    from(sourceSets.main.get().output.classesDirs)
}

val gameLogicClassesProof by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(configurations.implementation.get())
    outgoing.artifact(gameLogicClassesProofJar)
}
