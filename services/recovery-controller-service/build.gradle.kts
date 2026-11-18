plugins {
    id("net.firedevops.firemud.secured-sql-aop-service-conventions")
    id("net.firedevops.firemud.jooq-conventions")
}

firemudJooq {
    packageName.set("net.firedevops.firemud.recovery.jooq")
    migrationGlob.set("src/main/resources/db/recovery-control-migration/*.sql")
}

dependencies {
    implementation(project(":common-platform-core"))
    testImplementation(project(":common-test-support"))
}

// The shared convention still declares the default migration directory as an input.
// Include the recovery-owned directory so schema edits invalidate generated sources.
tasks.named("generateJooq") {
    inputs.files(fileTree("src/main/resources/db/recovery-control-migration"))
}
