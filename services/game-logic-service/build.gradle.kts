
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
    compileOnly(libs.spotbugs.annotations)
    implementation(project(":common-security"))
    annotationProcessor(libs.spring.boot.configuration.processor)
    add("crossServiceTestCompileOnly", libs.spotbugs.annotations)
    add(
        "crossServiceTestImplementation",
        project(mapOf("path" to ":account-service", "configuration" to "accountOriginalOrderProof"))
    )
    add(
        "crossServiceTestImplementation",
        project(mapOf("path" to ":game-design-service", "configuration" to "gameDesignClassesProof"))
    )
    add("crossServiceTestImplementation", libs.bouncycastle.pkix)
    add("crossServiceTestImplementation", "io.grpc:grpc-util:${libs.versions.grpc.get()}")
}

// The genuine GDS-to-GL proof consumes owner classes without adding foreign owners to GL runtime.
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
