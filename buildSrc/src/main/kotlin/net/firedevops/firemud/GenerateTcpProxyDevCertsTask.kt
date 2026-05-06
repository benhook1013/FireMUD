package net.firedevops.firemud

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.charset.StandardCharsets

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
        if (!certDirectory.exists() && !certDirectory.mkdirs()) {
            throw GradleException("Failed to create dev TLS certificate directory: ${certDirectory.absolutePath}")
        }
        val scriptDir = File(scriptWorkingDirPath.get())
        val scriptFile = File(scriptDir, "dev-tools/certs/generate-dev-certs.sh")
        if (!scriptFile.isFile) {
            throw GradleException("Missing dev TLS certificate generator script: ${scriptFile.absolutePath}")
        }
        val process =
            ProcessBuilder(
                    "bash",
                    scriptFile.absolutePath)
                .directory(scriptDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["CERT_DIR"] = certDirectory.absolutePath
                }
                .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        if (output.isNotBlank()) {
            logger.lifecycle(output.trimEnd())
        }
        if (exitCode != 0) {
            throw GradleException(
                "Failed to generate dev TLS certificates (exit code $exitCode) in ${certDirectory.absolutePath}")
        }
    }
}
