package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionProtectedActivationGrpcCodec;
import net.firedevops.firemud.test.TlsTestSupport;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalStartSessionProtectedActivationGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalStartSessionProtectedActivationService;
import net.firedevops.firemud.worldmanagement.v1.ActivateOriginalStartSessionProtectedResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldOriginalStartSessionProtectedActivationServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class WorldOriginalStartSessionProtectedActivationGrpcMtlsTest {
  @TempDir Path temporaryDirectory;

  @Test
  void sameNamespaceGameSessionCertificateReturnsExactCanonicalTerminal() throws Exception {
    var fixture = WorldOriginalStartSessionActivationTransportTestSupport.fixture();
    var pki =
        WorldOriginalStartSessionActivationTransportTestSupport.TestPki.create(temporaryDirectory);
    var owner = mock(WorldOriginalStartSessionProtectedActivationService.class);
    when(owner.activate(any())).thenReturn(fixture.terminal());
    var service = new WorldOriginalStartSessionProtectedActivationGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();
    var sentResponse = new AtomicReference<Object>();

    try (var server =
            WorldOriginalStartSessionActivationTransportTestSupport.startServer(
                pki, service, handlerCalls, false, sentResponse::set);
        var client =
            WorldOriginalStartSessionActivationTransportTestSupport.protectedClient(
                pki, temporaryDirectory, server.port(), pki.gameSessionClient())) {
      client.init();

      WorldOriginalStartSessionActivationTerminalEvidence actual = client.activate(fixture.input());

      assertThat(handlerCalls).hasValue(1);
      assertThat(actual.canonicalBytes()).containsExactly(fixture.terminal().canonicalBytes());
      assertThat(actual.accountProtectionEvidence().canonicalBytes())
          .containsExactly(fixture.protection().canonicalBytes());
      assertThat(actual.activationResult().canonicalBytes())
          .containsExactly(fixture.terminal().activationResult().canonicalBytes());
      assertThat(actual.activationRequestId())
          .isEqualTo(fixture.input().worldActivationRequest().activationRequestId());
      assertThat(actual.outcome())
          .isEqualTo(
              net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation.Outcome
                  .COMMITTED);

      assertThat(sentResponse.get())
          .isInstanceOf(ActivateOriginalStartSessionProtectedResponse.class);
      var response = (ActivateOriginalStartSessionProtectedResponse) sentResponse.get();
      assertThat(response.getSchemaVersionList()).containsExactly(1);
      assertThat(response.getTerminalEvidenceList())
          .containsExactly(ByteString.copyFrom(fixture.terminal().canonicalBytes()));

      ArgumentCaptor<AccountStartSessionActivationProtectionAcquisitionInput> captured =
          ArgumentCaptor.forClass(AccountStartSessionActivationProtectionAcquisitionInput.class);
      verify(owner).activate(captured.capture());
      assertThat(captured.getValue().originalPostAuthorizationTuple())
          .containsExactly(fixture.input().originalPostAuthorizationTuple());
      assertThat(captured.getValue().gameSessionOwnerMutationId())
          .isEqualTo(fixture.input().gameSessionOwnerMutationId());
      assertThat(captured.getValue().gameSessionOwnerAttemptId())
          .isEqualTo(fixture.input().gameSessionOwnerAttemptId());
      assertThat(captured.getValue().gameSessionOwnerFence())
          .isEqualTo(fixture.input().gameSessionOwnerFence());
      assertThat(captured.getValue().worldActivationRequest().canonicalRequestBytes())
          .containsExactly(fixture.input().worldActivationRequest().canonicalRequestBytes());
      assertThat(captured.getValue().worldActivationRequest().preparingEvidenceBytes())
          .containsExactly(fixture.input().worldActivationRequest().preparingEvidenceBytes());
    }
  }

  @Test
  void trustedWrongServiceAndWrongNamespaceCertificatesAreDeniedBeforeOwner() throws Exception {
    var fixture = WorldOriginalStartSessionActivationTransportTestSupport.fixture();
    var pki =
        WorldOriginalStartSessionActivationTransportTestSupport.TestPki.create(temporaryDirectory);
    var owner = mock(WorldOriginalStartSessionProtectedActivationService.class);
    var service = new WorldOriginalStartSessionProtectedActivationGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();

    try (var server =
        WorldOriginalStartSessionActivationTransportTestSupport.startServer(
            pki, service, handlerCalls, false, null)) {
      for (var identity :
          java.util.List.of(pki.accountClient(), pki.otherNamespaceGameSessionClient())) {
        try (var client =
            WorldOriginalStartSessionActivationTransportTestSupport.protectedClient(
                pki, temporaryDirectory, server.port(), identity)) {
          client.init();

          assertThatThrownBy(() -> client.activate(fixture.input()))
              .isInstanceOf(StatusRuntimeException.class)
              .satisfies(
                  failure ->
                      assertThat(Status.fromThrowable(failure).getCode())
                          .isEqualTo(Status.Code.PERMISSION_DENIED));
        }
      }
    }

    assertThat(handlerCalls).hasValue(2);
    verifyNoInteractions(owner);
  }

  @Test
  void missingClientCertificateIsRejectedByTlsBeforeHandlerOrOwner() throws Exception {
    var fixture = WorldOriginalStartSessionActivationTransportTestSupport.fixture();
    var pki =
        WorldOriginalStartSessionActivationTransportTestSupport.TestPki.create(temporaryDirectory);
    var owner = mock(WorldOriginalStartSessionProtectedActivationService.class);
    var service = new WorldOriginalStartSessionProtectedActivationGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();

    try (var server =
            WorldOriginalStartSessionActivationTransportTestSupport.startServer(
                pki, service, handlerCalls, false, null);
        var unauthenticatedClient =
            WorldOriginalStartSessionActivationTransportTestSupport.clientWithoutIdentity(
                pki, server.port())) {
      var request =
          WorldOriginalStartSessionProtectedActivationGrpcCodec.toRequest(fixture.input(), "test");

      assertThatThrownBy(
              () ->
                  WorldOriginalStartSessionProtectedActivationServiceGrpc.newBlockingStub(
                          unauthenticatedClient.channel())
                      .withDeadlineAfter(3, TimeUnit.SECONDS)
                      .activateOriginalStartSessionProtected(request))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(TlsTestSupport.isTlsHandshakeRejection(failure))
                      .as(
                          "missing client certificate must fail during TLS negotiation: %s",
                          failure)
                      .isTrue());
    }

    assertThat(handlerCalls).hasValue(0);
    verifyNoInteractions(owner);
  }

  @Test
  void authenticatedEndUserContextIsDeniedBeforeOwner() throws Exception {
    var fixture = WorldOriginalStartSessionActivationTransportTestSupport.fixture();
    var pki =
        WorldOriginalStartSessionActivationTransportTestSupport.TestPki.create(temporaryDirectory);
    var owner = mock(WorldOriginalStartSessionProtectedActivationService.class);
    var service = new WorldOriginalStartSessionProtectedActivationGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();

    try (var server =
            WorldOriginalStartSessionActivationTransportTestSupport.startServer(
                pki, service, handlerCalls, true, null);
        var client =
            WorldOriginalStartSessionActivationTransportTestSupport.protectedClient(
                pki, temporaryDirectory, server.port(), pki.gameSessionClient())) {
      client.init();

      assertThatThrownBy(() -> client.activate(fixture.input()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    assertThat(handlerCalls).hasValue(1);
    verifyNoInteractions(owner);
  }
}
