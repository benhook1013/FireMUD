
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.secured-stateful-service-conventions")
    id("net.firedevops.firemud.openapi-conventions")
}

dependencies {
    implementation(project(":common-security"))
    testImplementation(libs.h2)
}
