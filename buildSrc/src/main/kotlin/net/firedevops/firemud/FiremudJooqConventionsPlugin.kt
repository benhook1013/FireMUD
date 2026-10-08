package net.firedevops.firemud

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

abstract class FiremudJooqExtension @Inject constructor(project: Project) {
    val packageName: Property<String> = project.objects.property(String::class.java)
    val inputSchema: Property<String> = project.objects.property(String::class.java)
    val includes: Property<String> = project.objects.property(String::class.java)
    val migrationGlob: Property<String> = project.objects.property(String::class.java)
    val outputDirectory: DirectoryProperty = project.objects.directoryProperty()
    val accountInventoryMigrationProjection: Property<Boolean> =
        project.objects.property(Boolean::class.java).convention(false)
}

abstract class WriteJooqConfigTask : DefaultTask() {
    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val inputSchema: Property<String>

    @get:Input
    abstract val includes: Property<String>

    @get:Input
    abstract val migrationGlob: Property<String>

    @get:Input
    abstract val accountInventoryMigrationProjection: Property<Boolean>

    @get:Input
    abstract val projectedScriptsPath: Property<String>

    @get:Input
    abstract val scriptsPath: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:OutputFile
    abstract val configFile: org.gradle.api.file.RegularFileProperty

    @TaskAction
    fun writeConfig() {
        val config =
            """
            <configuration xmlns="http://www.jooq.org/xsd/jooq-codegen-3.20.1.xsd">
              <generator>
                <database>
                  <name>org.jooq.meta.extensions.ddl.DDLDatabase</name>
                  <includes>${includes.get()}</includes>
                  <properties>
                    <property>
                      <key>scripts</key>
                      <value>${if (accountInventoryMigrationProjection.get()) projectedScriptsPath.get() else scriptsPath.get()}</value>
                    </property>
                    <property>
                      <key>sort</key>
                      <value>flyway</value>
                    </property>
                    <property>
                      <key>defaultNameCase</key>
                      <value>lower</value>
                    </property>
                  </properties>
                </database>
                <generate>
                  <javaTimeTypes>true</javaTimeTypes>
                  <deprecated>false</deprecated>
                  <records>true</records>
                  <pojos>false</pojos>
                  <fluentSetters>false</fluentSetters>
                </generate>
                <target>
                  <packageName>${packageName.get()}</packageName>
                  <directory>${outputDirectory.get().asFile.absolutePath}</directory>
                </target>
              </generator>
            </configuration>
            """.trimIndent()

        val output = configFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(config)
    }
}

abstract class PrepareAccountJooqMigrationProjectionTask : DefaultTask() {
    @get:Input
    abstract val projectionEnabled: Property<Boolean>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun projectMigrations() {
        val sourceRoot = sourceDirectory.get().asFile
        val outputRoot = outputDirectory.get().asFile
        if (!sourceRoot.isDirectory) {
            throw GradleException("Account Flyway migration source directory is unavailable")
        }
        if (outputRoot.exists() && !outputRoot.deleteRecursively()) {
            throw GradleException("Account jOOQ migration projection could not be refreshed")
        }
        if (!outputRoot.mkdirs() && !outputRoot.isDirectory) {
            throw GradleException("Account jOOQ migration projection directory is unavailable")
        }

        val migrations = sourceRoot.walkTopDown().filter { it.isFile }.toList()
        if (migrations.none { it.name == INVENTORY_MIGRATION }) {
            throw GradleException("Expected Account validator-inventory migration is missing")
        }
        migrations.forEach { source ->
            val destination = outputRoot.resolve(source.relativeTo(sourceRoot).path)
            if (!destination.parentFile.mkdirs() && !destination.parentFile.isDirectory) {
                throw GradleException("Account jOOQ migration projection path is unavailable")
            }
            if (source.name == INVENTORY_MIGRATION) {
                destination.writeBytes(projectInventoryMigration(source.readBytes()))
            } else {
                // Every other migration remains a byte-for-byte copy of Flyway's authority.
                destination.writeBytes(source.readBytes())
            }
        }
    }

    private fun projectInventoryMigration(bytes: ByteArray): ByteArray {
        val source = bytes.toString(Charsets.UTF_8)
        if (!source.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            throw GradleException("Account validator-inventory migration is not valid UTF-8")
        }
        if (source.countOccurrences(IGNORE_END) != 1
            || source.countOccurrences(IGNORE_STOP) != 0
            || source.countOccurrences(INVENTORY_ALTER) != 1) {
            throw GradleException("Account validator-inventory migration projection shape changed")
        }
        val projected =
            source
                .replace(IGNORE_END, IGNORE_STOP)
                .replace(INVENTORY_ALTER, PROJECTED_INVENTORY_ALTERS)
        return projected.toByteArray(Charsets.UTF_8)
    }

    private fun String.countOccurrences(value: String): Int = split(value).size - 1

    private companion object {
        const val INVENTORY_MIGRATION = "V52__account_jwt_validator_inventory_snapshots.sql"
        const val IGNORE_END = "-- [jooq ignore end]"
        const val IGNORE_STOP = "-- [jooq ignore stop]"
        const val INVENTORY_ALTER =
            """ALTER TABLE account_jwt_readiness_probe_plans
    ADD COLUMN inventory_snapshot_digest VARCHAR(64),
    ADD CONSTRAINT account_jwt_readiness_plan_inventory_snapshot_digest_check
        CHECK (inventory_snapshot_digest IS NULL
            OR inventory_snapshot_digest ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT account_jwt_readiness_plan_inventory_snapshot_fk
        FOREIGN KEY (inventory_snapshot_digest, environment_id, cluster_id,
            kubernetes_namespace, expected_cluster_incarnation_uid, expected_namespace_uid)
        REFERENCES account_jwt_validator_inventory_snapshots(
            snapshot_digest, environment_id, cluster_id, kubernetes_namespace,
            cluster_incarnation_uid, namespace_uid)
        ON DELETE RESTRICT;"""
        const val PROJECTED_INVENTORY_ALTERS =
            """ALTER TABLE account_jwt_readiness_probe_plans
    ADD COLUMN inventory_snapshot_digest VARCHAR(64);
ALTER TABLE account_jwt_readiness_probe_plans
    ADD CONSTRAINT account_jwt_readiness_plan_inventory_snapshot_digest_check
        CHECK (inventory_snapshot_digest IS NULL
            OR inventory_snapshot_digest ~ '^[0-9a-f]{64}$');
ALTER TABLE account_jwt_readiness_probe_plans
    ADD CONSTRAINT account_jwt_readiness_plan_inventory_snapshot_fk
        FOREIGN KEY (inventory_snapshot_digest, environment_id, cluster_id,
            kubernetes_namespace, expected_cluster_incarnation_uid, expected_namespace_uid)
        REFERENCES account_jwt_validator_inventory_snapshots(
            snapshot_digest, environment_id, cluster_id, kubernetes_namespace,
            cluster_incarnation_uid, namespace_uid)
        ON DELETE RESTRICT;"""
    }
}

class FiremudJooqConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        plugins.withId("java") {
            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            val generatedDir = layout.buildDirectory.dir("generated-src/jooq/main")
            val defaultPackage =
                project.name
                    .removeSuffix("-service")
                    .split("-")
                    .filter { it.isNotBlank() }
                    .joinToString(separator = "")

            val extension =
                extensions.create<FiremudJooqExtension>("firemudJooq", project).apply {
                    packageName.convention("net.firedevops.firemud.$defaultPackage.jooq")
                    inputSchema.convention("public")
                    includes.convention(".*")
                    migrationGlob.convention("src/main/resources/db/migration/*.sql")
                    outputDirectory.convention(generatedDir)
                }
            val migrationProjectionDirectory =
                layout.buildDirectory.dir("tmp/jooq/account-migration-projection")
            val migrationProjection =
                tasks.register<PrepareAccountJooqMigrationProjectionTask>(
                    "projectAccountJooqMigrations"
                ) {
                    projectionEnabled.set(extension.accountInventoryMigrationProjection)
                    sourceDirectory.set(layout.projectDirectory.dir("src/main/resources/db/migration"))
                    outputDirectory.set(migrationProjectionDirectory)
                    onlyIf { projectionEnabled.get() }
                }

            val jooqCodegen = configurations.create("jooqCodegen")
            dependencies {
                add("implementation", libs.findLibrary("spring-boot-starter-jooq").get())
                add("implementation", libs.findLibrary("spring.boot.starter.jdbc").get())
                add("implementation", libs.findLibrary("jooq").get())
                add("runtimeOnly", libs.findLibrary("postgresql").get())

                add("jooqCodegen", libs.findLibrary("jooq-codegen").get())
                add("jooqCodegen", libs.findLibrary("jooq-meta-extensions").get())
                add("jooqCodegen", libs.findLibrary("postgresql").get())
            }

            val writeConfig =
                tasks.register<WriteJooqConfigTask>("writeJooqConfig") {
                    group = "jooq"
                    description = "Write the shared FireMUD jOOQ code generation config."
                    packageName.set(extension.packageName)
                    inputSchema.set(extension.inputSchema)
                    includes.set(extension.includes)
                    migrationGlob.set(extension.migrationGlob)
                    scriptsPath.set(layout.projectDirectory.asFile.absolutePath + "/" + extension.migrationGlob.get())
                    accountInventoryMigrationProjection.set(extension.accountInventoryMigrationProjection)
                    projectedScriptsPath.set(
                        migrationProjectionDirectory.map { "${it.asFile.absolutePath}/*.sql" })
                    outputDirectory.set(extension.outputDirectory)
                    configFile.set(layout.buildDirectory.file("tmp/jooq/config.xml"))
                }

            tasks.register<JavaExec>("generateJooq") {
                group = "jooq"
                description = "Generate jOOQ sources from Flyway-owned SQL migrations."
                dependsOn(writeConfig)
                dependsOn(migrationProjection)
                classpath = jooqCodegen
                mainClass.set("org.jooq.codegen.GenerationTool")
                inputs.files(fileTree(layout.projectDirectory.dir("src/main/resources/db/migration")))
                inputs.files(
                    extension.accountInventoryMigrationProjection.map { enabled ->
                        if (enabled) files(migrationProjectionDirectory) else files()
                    }
                )
                inputs.file(writeConfig.flatMap { it.configFile })
                outputs.dir(extension.outputDirectory)
                val configPath = writeConfig.flatMap { it.configFile }.map { it.asFile.absolutePath }
                doFirst {
                    args = listOf(configPath.get())
                }
            }

            extensions.getByType(SourceSetContainer::class.java).named("main").configure {
                java.srcDir(extension.outputDirectory)
            }

            tasks.named("compileJava") {
                dependsOn("generateJooq")
            }
        }
    }
}
