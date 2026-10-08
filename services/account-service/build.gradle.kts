
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    `java-test-fixtures`
    id("net.firedevops.firemud.secured-stateful-service-conventions")
    id("net.firedevops.firemud.aop-conventions")
    id("net.firedevops.firemud.jooq-conventions")
}

firemudJooq {
    packageName.set("net.firedevops.firemud.accountservice.jooq")
    accountInventoryMigrationProjection.set(true)
}

// The extracted owner setup directly constructs main collaborators, not replacement test doubles.
configurations.named("testFixturesImplementation") {
    extendsFrom(configurations.implementation.get())
}

dependencies {
    testFixturesImplementation(platform(libs.spring.boot.dependencies))
    testFixturesImplementation(project(":common-platform-core"))
    testFixturesImplementation(project(":common-security"))
    testFixturesImplementation(project(":common-data-runtime"))
    testFixturesImplementation(project(":common-redis-contracts"))
    testFixturesImplementation(libs.spring.boot.starter.test)
    testFixturesImplementation(libs.spring.boot.starter.jdbc)
    testFixturesImplementation(libs.spring.boot.starter.data.redis)
    testFixturesImplementation(libs.spring.aop)
    testFixturesImplementation(libs.mapstruct)
    testFixturesImplementation(libs.argon2)
    testFixturesImplementation(libs.jooq)
    testFixturesImplementation(libs.flyway.core)
    testFixturesImplementation("com.fasterxml.jackson.core:jackson-databind")
    testFixturesImplementation("tools.jackson.core:jackson-databind")
    testFixturesImplementation("com.google.protobuf:protobuf-java:${libs.versions.protobuf.get()}")
    testFixturesImplementation("io.grpc:grpc-stub:${libs.versions.grpc.get()}")
    testCompileOnly(libs.spotbugs.annotations)
    add("integrationTestCompileOnly", libs.spotbugs.annotations)
    implementation(project(":common-redis-contracts"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.argon2)
    implementation(libs.stripe.java)
    testImplementation(testFixtures(project(":account-service")))
    integrationTestImplementation(testFixtures(project(":account-service")))
}

// Opt-in proof artifact: never expose Account migrations/application resources to World's tests.
val accountOriginalOrderProofJar by tasks.registering(Jar::class) {
    archiveClassifier.set("original-order-proof")
    from(sourceSets.main.get().output.classesDirs)
    from(sourceSets["testFixtures"].output.classesDirs)
    from("src/main/resources") {
        include("redis/lua/account-control-ui-registry-cas.lua")
    }
}

val accountOriginalOrderProof by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(configurations.implementation.get())
    outgoing.artifact(accountOriginalOrderProofJar)
}
