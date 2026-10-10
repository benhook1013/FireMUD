
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    `java-test-fixtures`
    id("net.firedevops.firemud.secured-stateful-service-conventions")
    id("net.firedevops.firemud.openapi-conventions")
    id("net.firedevops.firemud.temporal-conventions")
    id("net.firedevops.firemud.jooq-conventions")
}

// Test fixtures compose package-private World owners without widening production visibility.
configurations.named("testFixturesImplementation") {
    extendsFrom(configurations.implementation.get())
}

firemudJooq {
    packageName.set("net.firedevops.firemud.worldmanagement.jooq")
}

dependencies {
    integrationTestImplementation(project(mapOf("path" to ":account-service", "configuration" to "accountOriginalOrderProof")))
    implementation(project(":common-security"))
    testImplementation(libs.bouncycastle.pkix)
    testCompileOnly(libs.spotbugs.annotations)
    add("integrationTestCompileOnly", libs.spotbugs.annotations)
}

// Opt-in owner proof: expose compiled World classes without its application or Flyway resources.
val worldClassesProofJar by tasks.registering(Jar::class) {
    archiveClassifier.set("classes-proof")
    dependsOn(tasks.named("classes"))
    from(sourceSets.main.get().output.classesDirs)
}

val worldClassesProof by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(configurations.implementation.get())
    outgoing.artifact(worldClassesProofJar)
}

// Opt-in owner proof: expose World classes and the selected-owner fixture without application or
// migration resources.
val worldSelectedOwnerInventoryProofJar by tasks.registering(Jar::class) {
    archiveClassifier.set("owner-inventory-proof")
    dependsOn(tasks.named("classes"), tasks.named("testFixturesClasses"))
    from(sourceSets.main.get().output.classesDirs)
    from(sourceSets["testFixtures"].output.classesDirs)
}

val worldSelectedOwnerInventoryProof by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(configurations.implementation.get())
    outgoing.artifact(worldSelectedOwnerInventoryProofJar)
}
