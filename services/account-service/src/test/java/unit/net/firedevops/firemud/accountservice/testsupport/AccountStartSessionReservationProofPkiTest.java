package net.firedevops.firemud.accountservice.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Actual stalled JDK children exercise only this fixture's process deadline and cleanup. */
class AccountStartSessionReservationProofPkiTest {
  @TempDir Path directory;

  @Test
  @Timeout(15)
  void timeoutTerminatesAndReapsTheStalledChild() throws Exception {
    Process process = awaitingPassword();
    try {
      assertThatThrownBy(
              () ->
                  AccountStartSessionReservationProofPki.awaitKeytoolExit(
                      process, 1, TimeUnit.SECONDS))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("execution deadline");
      assertThat(process.isAlive()).isFalse();
      assertThat(process.exitValue()).isNotZero();
    } finally {
      cleanup(process);
    }
  }

  @Test
  @Timeout(15)
  void interruptionTerminatesAndReapsTheChildAndRestoresTheInterruptFlag() throws Exception {
    Process process = awaitingPassword();
    try {
      Thread.currentThread().interrupt();
      assertThatThrownBy(
              () ->
                  AccountStartSessionReservationProofPki.awaitKeytoolExit(
                      process, 30, TimeUnit.SECONDS))
          .isInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      Thread.interrupted();
      assertThat(process.isAlive()).isFalse();
      assertThat(process.exitValue()).isNotZero();
    } finally {
      Thread.interrupted();
      cleanup(process);
    }
  }

  private Process awaitingPassword() throws Exception {
    var keytool =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")
                ? "keytool.exe"
                : "keytool");
    return new ProcessBuilder(
            keytool.toString(),
            "-genkeypair",
            "-alias",
            "controlled-timeout",
            "-keyalg",
            "RSA",
            "-keystore",
            directory.resolve("pending-password.p12").toString(),
            "-storetype",
            "PKCS12")
        .redirectErrorStream(true)
        .start();
  }

  private static void cleanup(Process process) throws Exception {
    if (process.isAlive()) process.destroyForcibly();
    assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
    process.getOutputStream().close();
    process.getInputStream().close();
    process.getErrorStream().close();
  }
}
