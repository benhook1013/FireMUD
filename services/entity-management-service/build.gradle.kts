
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    `java-test-fixtures`
    id("net.firedevops.firemud.secured-stateful-service-conventions")
    id("net.firedevops.firemud.jooq-conventions")
}

firemudJooq {
    packageName.set("net.firedevops.firemud.entitymanagement.jooq")
}

dependencies {
    testFixturesCompileOnly(libs.spotbugs.annotations)
    implementation(project(":common-platform-core"))
    implementation(project(":common-security"))
    implementation(libs.spring.boot.starter.cache)
    testImplementation(testFixtures(project(":common-platform-core")))
    testImplementation(testFixtures(project(":common-test-support")))
    testFixturesImplementation(testFixtures(project(":common-platform-core")))
    testFixturesImplementation(testFixtures(project(":common-test-support")))
    testFixturesImplementation("io.grpc:grpc-netty-shaded:${libs.versions.grpc.get()}")
    testFixturesImplementation("io.grpc:grpc-protobuf:${libs.versions.grpc.get()}")
    testFixturesImplementation("io.grpc:grpc-stub:${libs.versions.grpc.get()}")
    testFixturesImplementation("com.google.protobuf:protobuf-java:${libs.versions.protobuf.get()}")
}

// Export only owner classes for composed proofs; application and Flyway resources stay local.
val entityClassesProofJar by tasks.registering(Jar::class) {
    archiveClassifier.set("classes-proof")
    dependsOn(tasks.named("classes"))
    from(sourceSets.main.get().output.classesDirs)
}

val entityClassesProof by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(configurations.implementation.get())
    outgoing.artifact(entityClassesProofJar)
}
