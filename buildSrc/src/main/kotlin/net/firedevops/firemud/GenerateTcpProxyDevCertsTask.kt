package net.firedevops.firemud

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class GenerateTcpProxyDevCertsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val shouldGenerate: Property<Boolean>

    @get:Input
    abstract val scriptWorkingDirPath: Property<String>

    @TaskAction
    fun generate() {
        if (!shouldGenerate.get()) {
            return
        }
        val certDirectory = outputDir.get().asFile
        val devCert = File(certDirectory, "dev-cert.pem")
        if (devCert.exists()) {
            return
        }
        certDirectory.mkdirs()
        val scriptDir = File(scriptWorkingDirPath.get())
        val normalizedPath =
            scriptDir
                .toPath()
                .relativize(certDirectory.toPath())
                .toString()
                .replace('\\', '/')
        val process =
            ProcessBuilder(
                    "bash",
                    "-c",
                    "CERT_DIR=$normalizedPath dev-tools/certs/generate-dev-certs.sh")
                .directory(scriptDir)
                .inheritIO()
                .start()
        if (process.waitFor() != 0) {
            throw GradleException("Failed to generate dev TLS certificates")
        }
    }
}
