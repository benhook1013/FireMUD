plugins {
    `java-library`
}

apply(from = "${rootDir}/gradle/proto-convention.gradle")

dependencies {
    api(project(":common-platform-core"))
    implementation(libs.jjwt.api)
    implementation(libs.spring.aop)
    implementation(libs.spring.boot.starter)
    implementation(libs.aspectjweaver)
    compileOnly(libs.grpc.spring.boot.starter)
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.9.8")
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    testImplementation(libs.spring.boot.starter.test)
}
