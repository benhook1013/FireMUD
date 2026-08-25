plugins {
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.spring.boot) apply false
}

dependencies {
    testImplementation(project(":common-platform-core"))
    testFixturesImplementation(project(":common-platform-core"))
    testFixturesImplementation(libs.spring.boot.starter.test)
    testFixturesImplementation(libs.spring.boot.starter.webflux)
    testFixturesImplementation(libs.spring.boot.starter.websocket)
    testFixturesImplementation(libs.testcontainers.junit.jupiter)
    testFixturesImplementation(libs.testcontainers.postgresql)
    testFixturesCompileOnly(libs.spotbugs.annotations)
    testFixturesCompileOnly(libs.grpc.spring.boot.starter)
    testFixturesImplementation("io.grpc:grpc-netty-shaded:${libs.versions.grpc.get()}")
}
