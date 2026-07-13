
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    id("net.firedevops.firemud.secured-stateful-service-conventions")
    id("net.firedevops.firemud.jooq-conventions")
}

firemudJooq {
    packageName.set("net.firedevops.firemud.entitymanagement.jooq")
}

dependencies {
    implementation(project(":common-platform-core"))
    implementation(project(":common-security"))
    implementation(libs.spring.boot.starter.cache)
}
