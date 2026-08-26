plugins {
    `java-library`
}

apply(from = "${rootDir}/gradle/proto-convention.gradle")

dependencies {
    annotationProcessor(libs.lombok)
    implementation(project(":common-data-runtime"))
    implementation(libs.micrometer.core)
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.jdbc)
    compileOnly(libs.lombok)
    compileOnly(libs.spotbugs.annotations)

    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)
}
