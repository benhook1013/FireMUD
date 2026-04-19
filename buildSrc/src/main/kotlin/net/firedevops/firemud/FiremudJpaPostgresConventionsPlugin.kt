package net.firedevops.firemud

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

class FiremudJpaPostgresConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        plugins.withId("java") {
            val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

            dependencies.add("implementation", libs.findLibrary("spring.boot.starter.data.jpa").get())
            dependencies.add("implementation", libs.findLibrary("spring-boot-flyway").get())
            dependencies.add("runtimeOnly", libs.findLibrary("flyway-database-postgresql").get())
            dependencies.add("runtimeOnly", libs.findLibrary("postgresql").get())
        }
    }
}
