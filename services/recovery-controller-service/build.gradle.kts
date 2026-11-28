plugins {
    id("net.firedevops.firemud.secured-sql-aop-service-conventions")
    id("net.firedevops.firemud.jooq-conventions")
}

firemudJooq {
    packageName.set("net.firedevops.firemud.recovery.jooq")
}

dependencies {
    implementation(project(":common-platform-core"))
    testImplementation(project(":common-test-support"))
}
