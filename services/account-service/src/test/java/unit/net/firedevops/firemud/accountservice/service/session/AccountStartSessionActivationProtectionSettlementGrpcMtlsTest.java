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
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionActivationProtectionRequest;
import net.firedevops.firemud.account.v1.AccountStartSessionActivationProtectionSettlementServiceGrpc;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionActivationProtectionSettlementService;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Controlled loopback PKI proves only the standalone historical-settlement receiver boundary. */
class AccountStartSessionActivationProtectionSettlementGrpcMtlsTest {
  private static final String NAMESPACE = "world-runtime";

  @TempDir Path temporaryDirectory;

  @Test
  void rejectsNonWorldWorkloadCertificatesBeforeHistoricalOwnerAccess() throws Exception {
    var pki = ActivationProtectionGrpcMtlsTestSupport.TestPki.create(temporaryDirectory);
    var owner = mock(AccountStartSessionActivationProtectionSettlementService.class);
    var handlers = new AtomicInteger();
    try (var server = ActivationProtectionGrpcMtlsTestSupport.startServer(
            pki,
            new AccountStartSessionActivationProtectionSettlementGrpcService(owner, NAMESPACE),
            handlers);
        var world = ActivationProtectionGrpcMtlsTestSupport.client(
            pki, temporaryDirectory, server.getPort(), pki.worldClient());
        var wrongWorkload = ActivationProtectionGrpcMtlsTestSupport.client(
            pki, temporaryDirectory, server.getPort(), pki.wrongWorkloadClient());
        var wrongNamespace = ActivationProtectionGrpcMtlsTestSupport.client(
            pki, temporaryDirectory, server.getPort(), pki.otherNamespaceClient())) {
      var malformed =
          SettleOriginalStartSessionActivationProtectionRequest.newBuilder()
              .addTargetNamespace(NAMESPACE)
              .build();
      assertStatus(Status.Code.INVALID_ARGUMENT, () -> settle(world.channel(), malformed));
      assertStatus(Status.Code.PERMISSION_DENIED, () -> settle(wrongWorkload.channel(), malformed));
      assertStatus(Status.Code.PERMISSION_DENIED, () -> settle(wrongNamespace.channel(), malformed));
      assertThat(handlers).hasValue(3);
      verifyNoInteractions(owner);
    }
  }

  @Test
  void missingClientCertificateFailsAtTlsHandshakeBeforeHandler() throws Exception {
    var pki = ActivationProtectionGrpcMtlsTestSupport.TestPki.create(temporaryDirectory);
    var owner = mock(AccountStartSessionActivationProtectionSettlementService.class);
    var handlers = new AtomicInteger();
    try (var server = ActivationProtectionGrpcMtlsTestSupport.startServer(
            pki,
            new AccountStartSessionActivationProtectionSettlementGrpcService(owner, NAMESPACE),
            handlers);
        var client = ActivationProtectionGrpcMtlsTestSupport.clientWithoutIdentity(
            pki, temporaryDirectory, server.getPort())) {
      assertThatThrownBy(
              () ->
                  settle(
                      client.channel(),
                      SettleOriginalStartSessionActivationProtectionRequest.getDefaultInstance()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(failure -> assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isTrue());
      assertThat(handlers).hasValue(0);
      verifyNoInteractions(owner);
    }
  }

  private static void settle(
      ManagedChannel channel,
      SettleOriginalStartSessionActivationProtectionRequest request) {
    AccountStartSessionActivationProtectionSettlementServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(3, TimeUnit.SECONDS)
        .settleOriginalStartSessionActivationProtection(request);
  }

  private static void assertStatus(Status.Code expected, Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }
}
