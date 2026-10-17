package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.v1.AccountStartSessionActivationProtectionAcquisitionServiceGrpc;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionActivationProtectionRequest;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionActivationProtectionAcquisitionGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionActivationProtectionAcquisitionService;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Controlled loopback PKI proves only the standalone receiver's mTLS caller boundary. */
class AccountStartSessionActivationProtectionAcquisitionGrpcMtlsTest {
  private static final String NAMESPACE = "world-runtime";
  @TempDir Path temporaryDirectory;

  @Test
  @Timeout(15)
  void keytoolTimeoutTerminatesAndReapsTheStalledChild() throws Exception {
    Process process = keytoolAwaitingPassword();
    try {
      assertThatThrownBy(
              () ->
                  ActivationProtectionGrpcMtlsTestSupport.TestPki.awaitKeytoolExit(
                      process, 1, TimeUnit.SECONDS))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("execution deadline");
      assertThat(process.isAlive()).isFalse();
      assertThat(process.exitValue()).isNotZero();
    } finally {
      cleanupKeytool(process);
    }
  }

  @Test
  @Timeout(15)
  void keytoolInterruptionReapsTheChildAndPreservesTheInterruptFlag() throws Exception {
    Process process = keytoolAwaitingPassword();
    try {
      Thread.currentThread().interrupt();
      assertThatThrownBy(
              () ->
                  ActivationProtectionGrpcMtlsTestSupport.TestPki.awaitKeytoolExit(
                      process, 30, TimeUnit.SECONDS))
          .isInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      Thread.interrupted();
      assertThat(process.isAlive()).isFalse();
      assertThat(process.exitValue()).isNotZero();
    } finally {
      Thread.interrupted();
      cleanupKeytool(process);
    }
  }

  private Process keytoolAwaitingPassword() throws Exception {
    Path keytool =
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
            temporaryDirectory.resolve("pending-password.p12").toString(),
            "-storetype",
            "PKCS12")
        .redirectErrorStream(true)
        .start();
  }

  private static void cleanupKeytool(Process process) throws Exception {
    if (process.isAlive()) process.destroyForcibly();
    assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
    process.getOutputStream().close();
    process.getInputStream().close();
    process.getErrorStream().close();
  }

  @Test
  void onlyExactSameNamespaceWorldCertificateReachesMalformedRequestDecoder() throws Exception {
    var pki = ActivationProtectionGrpcMtlsTestSupport.TestPki.create(temporaryDirectory);
    var owner = mock(AccountStartSessionActivationProtectionAcquisitionService.class);
    var handlers = new AtomicInteger();
    try (var server =
            ActivationProtectionGrpcMtlsTestSupport.startServer(
                pki,
                new AccountStartSessionActivationProtectionAcquisitionGrpcService(owner, NAMESPACE),
                handlers);
        var world =
            ActivationProtectionGrpcMtlsTestSupport.client(
                pki, temporaryDirectory, server.getPort(), pki.worldClient());
        var wrongWorkload =
            ActivationProtectionGrpcMtlsTestSupport.client(
                pki, temporaryDirectory, server.getPort(), pki.wrongWorkloadClient());
        var wrongNamespace =
            ActivationProtectionGrpcMtlsTestSupport.client(
                pki, temporaryDirectory, server.getPort(), pki.otherNamespaceClient())) {
      var malformed =
          AcquireOriginalStartSessionActivationProtectionRequest.newBuilder()
              .addTargetNamespace(NAMESPACE)
              .build();
      assertStatus(Status.Code.INVALID_ARGUMENT, () -> acquire(world.channel(), malformed));
      assertStatus(
          Status.Code.PERMISSION_DENIED, () -> acquire(wrongWorkload.channel(), malformed));
      assertStatus(
          Status.Code.PERMISSION_DENIED, () -> acquire(wrongNamespace.channel(), malformed));
      assertThat(handlers).hasValue(3);
      verifyNoInteractions(owner);
    }
  }

  @Test
  void missingClientCertificateFailsAtTlsHandshake() throws Exception {
    var pki = ActivationProtectionGrpcMtlsTestSupport.TestPki.create(temporaryDirectory);
    var owner = mock(AccountStartSessionActivationProtectionAcquisitionService.class);
    var handlers = new AtomicInteger();
    try (var server =
            ActivationProtectionGrpcMtlsTestSupport.startServer(
                pki,
                new AccountStartSessionActivationProtectionAcquisitionGrpcService(owner, NAMESPACE),
                handlers);
        var client =
            ActivationProtectionGrpcMtlsTestSupport.clientWithoutIdentity(
                pki, temporaryDirectory, server.getPort())) {
      assertThatThrownBy(
              () ->
                  acquire(
                      client.channel(),
                      AcquireOriginalStartSessionActivationProtectionRequest.getDefaultInstance()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure -> assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isTrue());
      assertThat(handlers).hasValue(0);
      verifyNoInteractions(owner);
    }
  }

  private static void acquire(
      ManagedChannel channel, AcquireOriginalStartSessionActivationProtectionRequest request) {
    AccountStartSessionActivationProtectionAcquisitionServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(3, TimeUnit.SECONDS)
        .acquireOriginalStartSessionActivationProtection(request);
  }

  private static void assertStatus(Status.Code expected, Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }
}
