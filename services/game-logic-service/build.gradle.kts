
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
