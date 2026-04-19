
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.service-conventions")
    id("net.firedevops.firemud.openapi-conventions")
}

dependencies {
    implementation(project(":common-security"))
    annotationProcessor(libs.spring.boot.configuration.processor)
}
