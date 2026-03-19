
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.service-conventions")
}

dependencies {
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.aop)
    implementation(libs.aspectjweaver)
    implementation(libs.springdoc.openapi)
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}
