
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.secured-stateful-service-conventions")
    id("net.firedevops.firemud.aop-conventions")
    id("net.firedevops.firemud.jooq-conventions")
}

firemudJooq {
    packageName.set("net.firedevops.firemud.accountservice.jooq")
}

dependencies {
    implementation(project(":common-redis-contracts"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.argon2)
    implementation(libs.stripe.java)
    // Test-only JDBC mocks need narrowly scoped resource-analysis annotations.
    testCompileOnly(libs.spotbugs.annotations)
    testImplementation(libs.bouncycastle.pkix)
    // Integration analysis follows the real producer's annotated Account collaborators.
    add("integrationTestImplementation", "com.github.spotbugs:spotbugs-annotations:4.9.8")
}
