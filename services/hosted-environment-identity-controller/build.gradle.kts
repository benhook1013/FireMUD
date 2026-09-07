plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.java.operator.sdk.spring.boot.starter)
    implementation(libs.bouncycastle)
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("hosted-environment-identity-controller.jar")
}
