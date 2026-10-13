
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.secured-stateful-service-conventions")
    id("net.firedevops.firemud.jooq-conventions")
}

firemudJooq {
    packageName.set("net.firedevops.firemud.entitymanagement.jooq")
}

dependencies {
    implementation(project(":common-platform-core"))
    implementation(project(":common-security"))
    implementation(libs.spring.boot.starter.cache)
}

// Opt-in proof artifact: expose Entity classes without application or migration resources.
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
