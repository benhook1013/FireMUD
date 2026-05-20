
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.secured-jpa-aop-service-conventions")
    id("net.firedevops.firemud.temporal-conventions")
}

dependencies {
    implementation(libs.aws.sdk.s3)
}
