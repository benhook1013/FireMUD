
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.secured-stateful-service-conventions")
    id("net.firedevops.firemud.openapi-conventions")
    id("net.firedevops.firemud.temporal-conventions")
    id("net.firedevops.firemud.jooq-conventions")
}

firemudJooq {
    packageName.set("net.firedevops.firemud.worldmanagement.jooq")
}

dependencies {
    implementation(project(":common-security"))
  testImplementation(testFixtures(project(":common-platform-core")))
  testImplementation(libs.bouncycastle.pkix)
  testCompileOnly(libs.spotbugs.annotations)
  add("integrationTestCompileOnly", libs.spotbugs.annotations)
  add("crossServiceTestCompileOnly", libs.spotbugs.annotations)
  add(
      "crossServiceTestImplementation",
      project(mapOf("path" to ":account-service", "configuration" to "accountOriginalOrderProof"))
  )
  add(
      "crossServiceTestImplementation",
      project(mapOf("path" to ":game-session-service", "configuration" to "gameSessionClassesProof"))
  )
  add("crossServiceTestImplementation", testFixtures(project(":common-test-support")))
  add("crossServiceTestImplementation", "io.grpc:grpc-util:${libs.versions.grpc.get()}")
}

// Receiving owner proof uses World classes without its application or Flyway resources.
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
