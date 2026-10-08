package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class CanonicalGameplayMigrationDriverStdoutTest {
  private static final String DIAGNOSTIC_MARKER = "migration-driver-diagnostic-probe";
  private static final String PROTOCOL_FRAME = "{\"protocol\":\"ready\"}";

  @Test
  void slf4jDiagnosticsUseStderrAndProtocolFramesKeepOriginalStdout() throws Exception {
    String classpath = System.getProperty("firemud.migration-driver-test-classpath");
    assertThat(classpath).isNotBlank();
    String javaExecutable =
        System.getProperty("os.name").toLowerCase().contains("windows") ? "java.exe" : "java";
    Path java = Path.of(System.getProperty("java.home"), "bin", javaExecutable);
    Process process =
        new ProcessBuilder(java.toString(), "-cp", classpath, LoggingProbe.class.getName()).start();

    boolean exited = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
    if (!exited) {
      process.destroyForcibly();
      process.waitFor();
    }
    byte[] stdout = process.getInputStream().readAllBytes();
    byte[] stderr = process.getErrorStream().readAllBytes();

    assertThat(exited).isTrue();
    assertThat(process.exitValue()).isZero();
    assertThat(new String(stdout, StandardCharsets.UTF_8)).isEqualTo(PROTOCOL_FRAME + "\n");
    assertThat(new String(stderr, StandardCharsets.UTF_8)).contains(DIAGNOSTIC_MARKER);
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
