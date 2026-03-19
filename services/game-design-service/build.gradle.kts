
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.service-conventions")
}

dependencies {
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.aop)
    implementation(libs.aspectjweaver)
    implementation(libs.aws.sdk.s3)
    runtimeOnly(libs.postgresql)
}
