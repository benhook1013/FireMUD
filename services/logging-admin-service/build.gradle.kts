
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    `java-test-fixtures`
    id("net.firedevops.firemud.secured-sql-aop-service-conventions")
    id("net.firedevops.firemud.openapi-conventions")
    id("net.firedevops.firemud.jooq-conventions")
}

firemudJooq {
    packageName.set("net.firedevops.firemud.loggingadmin.jooq")
}

dependencies {
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.grpc.netty.shaded)
    testImplementation(testFixtures(project(":logging-admin-service")))
    testCompileOnly(libs.spotbugs.annotations)
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.jooq)
    testFixturesImplementation(libs.postgresql)
    testFixturesImplementation(libs.spring.boot.starter.test)
    testFixturesImplementation(libs.testcontainers.postgresql)
}
