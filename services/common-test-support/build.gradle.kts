plugins {
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.spring.boot) apply false
}

dependencies {
    testImplementation(project(":account-service"))
    testFixturesImplementation(project(":account-service"))
    testFixturesImplementation(libs.spring.boot.starter.test)
    testFixturesCompileOnly(libs.grpc.spring.boot.starter)
    testFixturesImplementation("io.grpc:grpc-netty-shaded:${libs.versions.grpc.get()}")
}
