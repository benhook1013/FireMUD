package net.firedevops.firemud

import org.gradle.api.Plugin
import org.gradle.api.Project

class FiremudTemporalConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        plugins.withId("java") {
            dependencies.add("implementation", project.project(":common-temporal"))
        }
    }
}
