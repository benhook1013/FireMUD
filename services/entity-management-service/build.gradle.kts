
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.secured-stateful-service-conventions")
}

dependencies {
    implementation(libs.spring.boot.starter.cache)
}
