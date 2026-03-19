plugins {
    `java-library`
}

apply(from = "${rootDir}/gradle/proto-convention.gradle")

dependencies {
    annotationProcessor(libs.lombok)
    implementation(libs.micrometer.core)
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.data.jpa)
    compileOnly(libs.lombok)
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.9.8")

    testImplementation(libs.spring.boot.starter.test)
}
