
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
    testImplementation(libs.h2)
}
