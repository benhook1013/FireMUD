plugins {
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.spring.boot) apply false
}

dependencies {
    testImplementation(project(":account-service"))
    testImplementation(project(":common-platform-core"))
    testFixturesImplementation(project(":account-service"))
    testFixturesImplementation(project(":common-platform-core"))
    testFixturesImplementation(libs.spring.boot.starter.test)
    testFixturesImplementation(libs.spring.boot.starter.webflux)
    testFixturesImplementation(libs.spring.boot.starter.websocket)
    testFixturesCompileOnly("com.github.spotbugs:spotbugs-annotations:4.9.8")
    testFixturesCompileOnly(libs.grpc.spring.boot.starter)
    testFixturesImplementation("io.grpc:grpc-netty-shaded:${libs.versions.grpc.get()}")
}
