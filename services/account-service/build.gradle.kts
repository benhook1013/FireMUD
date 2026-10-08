
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    `java-test-fixtures`
    id("net.firedevops.firemud.secured-stateful-service-conventions")
    id("net.firedevops.firemud.aop-conventions")
    id("net.firedevops.firemud.jooq-conventions")
}

firemudJooq {
    packageName.set("net.firedevops.firemud.accountservice.jooq")
}

dependencies {
    testCompileOnly(libs.spotbugs.annotations)
    implementation(project(":common-redis-contracts"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.argon2)
    implementation(libs.stripe.java)
    testImplementation(testFixtures(project(":account-service")))
    integrationTestImplementation(testFixtures(project(":account-service")))
}
