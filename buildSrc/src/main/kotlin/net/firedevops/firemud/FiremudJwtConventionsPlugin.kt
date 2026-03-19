package net.firedevops.firemud

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

class FiremudJwtConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        plugins.withId("java") {
            val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

            dependencies.add("implementation", libs.findLibrary("jjwt.api").get())
            dependencies.add("runtimeOnly", libs.findLibrary("jjwt.impl").get())
            dependencies.add("runtimeOnly", libs.findLibrary("jjwt.jackson").get())
        }
    }
}
