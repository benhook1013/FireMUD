
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.stateful-service-conventions")
    id("net.firedevops.firemud.jwt-conventions")
}

dependencies {
    implementation(libs.spring.boot.starter.cache)
}
