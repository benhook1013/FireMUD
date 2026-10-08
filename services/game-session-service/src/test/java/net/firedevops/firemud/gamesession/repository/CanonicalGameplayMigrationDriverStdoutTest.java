package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class CanonicalGameplayMigrationDriverStdoutTest {
  private static final String DIAGNOSTIC_MARKER = "migration-driver-diagnostic-probe";
  private static final String PROTOCOL_FRAME = "{\"protocol\":\"ready\"}";

  @Test
  void slf4jDiagnosticsUseStderrAndProtocolFramesKeepOriginalStdout(@TempDir Path tempDir)
      throws Exception {
    String classpath = System.getProperty("firemud.migration-driver-test-classpath");
    assertThat(classpath).isNotBlank();
    String javaExecutable =
        System.getProperty("os.name").toLowerCase().contains("windows") ? "java.exe" : "java";
    Path java = Path.of(System.getProperty("java.home"), "bin", javaExecutable);
    Path argumentFile = tempDir.resolve("migration-driver.args");
    Files.writeString(
        argumentFile,
        javaArgument("-cp")
            + System.lineSeparator()
            + javaArgument(classpath)
            + System.lineSeparator()
            + javaArgument(LoggingProbe.class.getName())
            + System.lineSeparator(),
        StandardCharsets.UTF_8);
    Path stdoutFile = tempDir.resolve("child.stdout");
    Path stderrFile = tempDir.resolve("child.stderr");
    Process process =
        new ProcessBuilder(java.toString(), "@" + argumentFile)
            .redirectOutput(stdoutFile.toFile())
            .redirectError(stderrFile.toFile())
            .start();

    boolean exited = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
    if (!exited) {
      process.destroyForcibly();
      process.waitFor();
    }
    byte[] stdout = Files.readAllBytes(stdoutFile);
    byte[] stderr = Files.readAllBytes(stderrFile);

    assertThat(exited).isTrue();
    assertThat(process.exitValue()).isZero();
    assertThat(new String(stdout, StandardCharsets.UTF_8)).isEqualTo(PROTOCOL_FRAME + "\n");
    assertThat(new String(stderr, StandardCharsets.UTF_8)).contains(DIAGNOSTIC_MARKER);
  }

  private static String javaArgument(String argument) {
    return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  public static final class LoggingProbe {
    private LoggingProbe() {}

    public static void main(String[] args) {
      var protocolOutput =
          CanonicalGameplayMigrationDriverLauncher.preserveProtocolOutputAndRedirectDiagnostics();
      LoggerFactory.getLogger(LoggingProbe.class).warn(DIAGNOSTIC_MARKER);
      protocolOutput.println(PROTOCOL_FRAME);
    }
  }
}
