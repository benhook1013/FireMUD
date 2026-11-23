
apply(from = "${rootDir}/gradle/proto-convention.gradle")

plugins {
    `java-test-fixtures`
    id("net.firedevops.firemud.secured-stateful-service-conventions")
    id("net.firedevops.firemud.jooq-conventions")
}

firemudJooq {
    packageName.set("net.firedevops.firemud.socialgroups.jooq")
}

// This suite supplies its own TLS material and boots with Social's main application.yml.
tasks.named<ProcessResources>("processCrossServiceTestResources") {
    exclude("application.yml")
}

tasks.named<Test>("crossServiceTest") {
    val fixtureProperties = listOf("loggingAdminFixtureImage", "loggingAdminFixtureImageId")
    val fixtureInputs = fixtureProperties.associateWith { providers.gradleProperty(it).orNull }
    fixtureInputs.forEach { (name, value) ->
        inputs.property(name, value)
        if (value != null) {
            systemProperty(name, value)
        }
    }

    // The daemon's image store and container startup are not represented by the input strings.
    val fixtureRequested = fixtureInputs.values.any { it != null }
    outputs.upToDateWhen { !fixtureRequested }
    outputs.doNotCacheIf("Explicit Logging & Admin fixture proof must run against local Docker") { fixtureRequested }
}
