
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.service-conventions")
    id("net.firedevops.firemud.openapi-conventions")
    id("net.firedevops.firemud.sql-postgres-conventions")
    id("net.firedevops.firemud.jooq-conventions")
}

firemudJooq {
    packageName.set("net.firedevops.firemud.gamelogic.jooq")
}

dependencies {
    compileOnly(libs.spotbugs.annotations)
    implementation(project(":common-security"))
    annotationProcessor(libs.spring.boot.configuration.processor)
}
