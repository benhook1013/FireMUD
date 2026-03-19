
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.secured-stateful-service-conventions")
    id("net.firedevops.firemud.aop-conventions")
}

dependencies {
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.argon2)
    implementation(libs.stripe.java)
    implementation(libs.otp.java)
}
