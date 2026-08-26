plugins {
    `java-library`
}

apply(from = "${rootDir}/gradle/proto-convention.gradle")

dependencies {
    api(project(":common-platform-core"))
    implementation(libs.jjwt.api)
    implementation(libs.spring.aop)
    implementation(libs.spring.boot.starter)
    compileOnly(libs.spring.boot.starter.web)
    implementation(libs.aspectjweaver)
    compileOnly(libs.grpc.spring.boot.starter)
    compileOnly(libs.spotbugs.annotations)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    testImplementation(libs.spring.boot.starter.test)
}
