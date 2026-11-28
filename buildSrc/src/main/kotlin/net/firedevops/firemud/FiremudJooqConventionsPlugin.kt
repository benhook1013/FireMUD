package net.firedevops.firemud

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
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
                      <value>${scriptsPath.get()}</value>
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

            val recoveryService = project.name == "recovery-controller-service"
            val recoveryMigrationGlob = "src/main/resources/db/recovery-control-migration/*.sql"
            val defaultMigrationGlob =
                if (recoveryService) recoveryMigrationGlob else "src/main/resources/db/migration/*.sql"

            val extension =
                extensions.create<FiremudJooqExtension>("firemudJooq", project).apply {
                    packageName.convention("net.firedevops.firemud.$defaultPackage.jooq")
                    inputSchema.convention("public")
                    includes.convention(".*")
                    migrationGlob.convention(defaultMigrationGlob)
                    outputDirectory.convention(generatedDir)
                }

            val qualifiedMigrationGlob =
                extension.migrationGlob.map { glob ->
                    require(!recoveryService || glob == recoveryMigrationGlob) {
                        "Recovery jOOQ must generate from the packaged recovery-control migrations"
                    }
                    glob
                }
            val projectDirectory = layout.projectDirectory.asFile

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
                    migrationGlob.set(qualifiedMigrationGlob)
                    scriptsPath.set(qualifiedMigrationGlob.map { glob -> projectDirectory.absolutePath + "/" + glob })
                    outputDirectory.set(extension.outputDirectory)
                    configFile.set(layout.buildDirectory.file("tmp/jooq/config.xml"))
                }

            tasks.register<JavaExec>("generateJooq") {
                group = "jooq"
                description = "Generate jOOQ sources from Flyway-owned SQL migrations."
                dependsOn(writeConfig)
                classpath = jooqCodegen
                mainClass.set("org.jooq.codegen.GenerationTool")
                inputs.files(
                    qualifiedMigrationGlob.map { glob ->
                        fileTree(projectDirectory).include(glob)
                    },
                ).withPathSensitivity(PathSensitivity.RELATIVE)
                inputs.file(writeConfig.flatMap { it.configFile })
                    .withPropertyName("jooqConfiguration")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
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
