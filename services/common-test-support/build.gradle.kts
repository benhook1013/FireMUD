plugins {
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.spring.boot) apply false
}

dependencies {
    testFixturesImplementation(libs.spring.boot.starter.test)
    testFixturesCompileOnly(libs.grpc.spring.boot.starter)
}
