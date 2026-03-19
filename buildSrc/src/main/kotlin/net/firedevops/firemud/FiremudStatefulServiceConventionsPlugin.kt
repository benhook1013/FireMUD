package net.firedevops.firemud

import org.gradle.api.Plugin
import org.gradle.api.Project

class FiremudStatefulServiceConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("net.firedevops.firemud.service-conventions")
        pluginManager.apply("net.firedevops.firemud.jpa-postgres-conventions")
        pluginManager.apply("net.firedevops.firemud.redis-conventions")
    }
}
