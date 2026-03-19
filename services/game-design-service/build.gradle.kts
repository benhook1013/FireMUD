
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.secured-jpa-aop-service-conventions")
}

dependencies {
    implementation(libs.aws.sdk.s3)
}
