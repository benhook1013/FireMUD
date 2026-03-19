package net.firedevops.firemud

import org.gradle.api.Plugin
import org.gradle.api.Project

class FiremudSecuredStatefulServiceConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("net.firedevops.firemud.stateful-service-conventions")
        pluginManager.apply("net.firedevops.firemud.jwt-conventions")
    }
}
