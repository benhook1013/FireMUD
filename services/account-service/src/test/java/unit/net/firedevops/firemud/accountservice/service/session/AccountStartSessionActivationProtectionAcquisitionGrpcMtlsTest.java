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
import org.junit.jupiter.api.io.TempDir;

/** Controlled loopback PKI proves only the standalone receiver's mTLS caller boundary. */
class AccountStartSessionActivationProtectionAcquisitionGrpcMtlsTest {
  private static final String NAMESPACE = "world-runtime";
  @TempDir Path temporaryDirectory;

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
