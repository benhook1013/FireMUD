package net.firedevops.firemud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Real incremental/cache behavior using the declaration applied by the World project. */
public class WorldProofMigrationInputsTest {
  private static final String GS = "game-session-service";
  private static final String WORLD = "world-management-service";
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void bytesMembershipAndRootIdentityInvalidateButUnrelatedFilesDoNot() throws Exception {
    Path root = fixture("checkout");
    Path gs = migration(root, GS, "V1__owner.sql");
    Path world = migration(root, WORLD, "V1__owner.sql");
    write(gs, "select 'gs';");
    write(world, "select 'world';");
    outcome(root, "crossServiceTest", TaskOutcome.SUCCESS);
    outcome(root, "crossServiceTest", TaskOutcome.UP_TO_DATE);
    write(root.resolve("design/unrelated.md"), "not a migration");
    write(root.resolve("services/unrelated/src/main/resources/db/migration/V1__other.sql"), "select 9;");
    outcome(root, "crossServiceTest", TaskOutcome.UP_TO_DATE);
    write(gs, "select 'edited';");
    outcome(root, "crossServiceTest", TaskOutcome.SUCCESS);
    Path added = migration(root, GS, "V2__added.sql");
    write(added, "select 'added';");
    outcome(root, "crossServiceTest", TaskOutcome.SUCCESS);
    Files.delete(added);
    outcome(root, "crossServiceTest", TaskOutcome.FROM_CACHE);
    Path renamed = migration(root, GS, "V3__renamed.sql");
    Files.move(gs, renamed);
    outcome(root, "crossServiceTest", TaskOutcome.SUCCESS);
    Files.move(renamed, gs);
    outcome(root, "crossServiceTest", TaskOutcome.FROM_CACHE);
    String gsBytes = Files.readString(gs);
    String worldBytes = Files.readString(world);
    write(gs, worldBytes);
    write(world, gsBytes);
    outcome(root, "crossServiceTest", TaskOutcome.SUCCESS);
    Path config = migration(root, GS, "V1__owner.sql.conf");
    write(config, "executeInTransaction=false");
    outcome(root, "crossServiceTest", TaskOutcome.SUCCESS);
    write(config, "executeInTransaction=true");
    outcome(root, "crossServiceTest", TaskOutcome.SUCCESS);
  }

  @Test
  public void relocatedCheckoutReusesTheSameCacheEntry() throws Exception {
    Path first = fixture("first");
    Path relocated = fixture("relocated");
    write(migration(first, GS, "V1__owner.sql"), "select 'same';");
    write(migration(relocated, GS, "V1__owner.sql"), "select 'same';");
    outcome(first, "crossServiceTest", TaskOutcome.SUCCESS);
    outcome(relocated, "crossServiceTest", TaskOutcome.FROM_CACHE);
    assertEquals(Files.readString(first.resolve("build/crossServiceTest.txt")),
        Files.readString(relocated.resolve("build/crossServiceTest.txt")));
  }

  @Test
  public void sharedDeclarationSelectsExactOwnerRootsAndWorldAppliesIt() throws Exception {
    String worldBuild = Files.readString(Path.of(System.getProperty("firemud.worldBuildScript")));
    assertTrue(worldBuild.contains("apply(from = \"${rootDir}/gradle/world-proof-migration-inputs.gradle\")"));
    Path root = fixture("roots");
    for (String owner : List.of(WORLD, GS, "account-service", "entity-management-service", "common-saga")) {
      write(migration(root, owner, "V1__owner.sql"), owner);
    }
    write(root.resolve("services/common-saga/src/main/resources/db/migration/not-saga.sql"), "excluded");
    outcome(root, "integrationTest", TaskOutcome.SUCCESS);
    assertEquals(List.of(relative(GS), relative("common-saga"), relative(WORLD)).stream().sorted().toList(),
        selected(root, "integrationTest"));
    outcome(root, "crossServiceTest", TaskOutcome.SUCCESS);
    assertEquals(List.of(relative("account-service"), relative("entity-management-service"),
        relative(GS), relative("common-saga"), relative(WORLD)).stream().sorted().toList(),
        selected(root, "crossServiceTest"));
    write(migration(root, "account-service", "V1__owner.sql"), "changed Account");
    outcome(root, "integrationTest", TaskOutcome.UP_TO_DATE);
    outcome(root, "crossServiceTest", TaskOutcome.SUCCESS);
  }

  private static List<String> selected(Path root, String task) throws IOException {
    return Files.readAllLines(root.resolve("build/" + task + ".txt")).stream()
        .map(line -> line.substring(0, line.indexOf('='))).toList();
  }

  private Path fixture(String name) throws IOException {
    Path root = temporary.getRoot().toPath().resolve(name);
    Files.createDirectories(root);
    Path cache = temporary.getRoot().toPath().resolve("local-cache");
    write(root.resolve("settings.gradle"), "rootProject.name='proof-input-fixture'\n"
        + "buildCache { local { directory = file('" + cache.toString().replace("\\", "/") + "') } }\n");
    write(root.resolve("gradle.properties"), "org.gradle.jvmargs=-Xmx256m\norg.gradle.workers.max=1\n");
    Files.copy(Path.of(System.getProperty("firemud.proofInputsScript")), root.resolve("proof-inputs.gradle"));
    write(root.resolve("build.gradle"), """
        import org.gradle.api.tasks.*
        @CacheableTask
        abstract class Probe extends DefaultTask {
            @OutputFile abstract RegularFileProperty getResult()
            @TaskAction void capture() {
                def root = project.rootProject.projectDir.toPath()
                result.get().asFile.text = inputs.files.files.collect {
                    root.relativize(it.toPath()).toString() + '=' + it.text
                }.sort().join('\\n')
            }
        }
        ['integrationTest', 'crossServiceTest'].each { taskName ->
            tasks.register(taskName, Probe) {
                result = layout.buildDirectory.file(taskName + '.txt')
            }
        }
        apply from: 'proof-inputs.gradle'
        """);
    return root;
  }

  private void outcome(Path root, String task, TaskOutcome expected) {
    var result = GradleRunner.create()
        .withProjectDir(root.toFile())
        .withGradleInstallation(Path.of(System.getProperty("firemud.testGradleHome")).toFile())
        .withTestKitDir(temporary.getRoot().toPath().resolve("test-kit").toFile())
        .withArguments(task, "--build-cache", "--stacktrace", "--max-workers=1")
        .build();
    assertEquals(result.getOutput(), expected, Objects.requireNonNull(result.task(":" + task)).getOutcome());
  }

  private static String relative(String owner) {
    return "services/" + owner + "/src/main/resources/db/migration/"
        + (owner.equals("common-saga") ? "saga/" : "") + "V1__owner.sql";
  }

  private static Path migration(Path root, String owner, String file) {
    return root.resolve(relative(owner)).resolveSibling(file);
  }

  private static void write(Path file, String value) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, value);
  }
}
