package net.firedevops.firemud

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

class FiremudServiceConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        plugins.withId("java") {
            val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

            dependencies.add("annotationProcessor", libs.findLibrary("mapstruct.processor").get())
            dependencies.add("annotationProcessor", libs.findLibrary("lombok").get())
            dependencies.add("annotationProcessor", libs.findLibrary("lombok.mapstruct.binding").get())
            dependencies.add("compileOnly", libs.findLibrary("lombok").get())
            dependencies.add("compileOnly", "com.github.spotbugs:spotbugs-annotations:4.9.8")

            dependencies.add("implementation", libs.findLibrary("mapstruct").get())
            dependencies.add("implementation", libs.findLibrary("spring.boot.starter").get())
            dependencies.add("implementation", libs.findLibrary("spring.boot.starter.actuator").get())
            dependencies.add("implementation", project.project(":common-library"))
            dependencies.add("implementation", libs.findLibrary("grpc.spring.boot.starter").get())
            dependencies.add("implementation", libs.findLibrary("micrometer.core").get())
            dependencies.add("implementation", libs.findLibrary("micrometer.registry.prometheus").get())
            dependencies.add("implementation", libs.findLibrary("opentelemetry.api").get())
            dependencies.add("implementation", libs.findLibrary("opentelemetry.sdk").get())
            dependencies.add("implementation", libs.findLibrary("opentelemetry.exporter.otlp").get())
        }
    }
}
