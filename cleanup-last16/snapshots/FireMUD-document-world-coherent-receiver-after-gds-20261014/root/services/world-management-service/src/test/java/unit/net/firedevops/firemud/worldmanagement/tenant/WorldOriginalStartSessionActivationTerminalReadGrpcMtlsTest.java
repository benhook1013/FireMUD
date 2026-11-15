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
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.world.GrpcWorldOriginalStartSessionActivationTerminalReadClient;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalReadGrpcCodec;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalReadInput;
import net.firedevops.firemud.test.TlsTestSupport;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceActivationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalStartSessionActivationTerminalReadGrpcService;
import net.firedevops.firemud.worldmanagement.v1.ReadOriginalStartSessionActivationTerminalEvidenceResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldOriginalStartSessionActivationTerminalReadServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loopback mTLS transport proof only; the World owner is mocked and remains unregistered. */
class WorldOriginalStartSessionActivationTerminalReadGrpcMtlsTest {
  @TempDir Path temporaryDirectory;

  @Test
  void sameNamespaceAccountCertificateReturnsExactCanonicalTerminalAndCorrelation()
      throws Exception {
    var fixture = WorldOriginalStartSessionActivationTransportTestSupport.fixture();
    var pki =
        WorldOriginalStartSessionActivationTransportTestSupport.TestPki.create(temporaryDirectory);
    var input =
        new WorldOriginalStartSessionActivationTerminalReadInput(
            java.util.UUID.fromString("a0a0a0a0-a0a0-40a0-80a0-a0a0a0a0a0a0"),
            fixture.protection());
    var owner = mock(WorldCanonicalInstanceActivationService.class);
    when(owner.readTerminalEvidence(any())).thenReturn(Optional.of(fixture.terminal()));
    var service = new WorldOriginalStartSessionActivationTerminalReadGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();
    var sentResponse = new AtomicReference<Object>();

    try (var server =
            WorldOriginalStartSessionActivationTransportTestSupport.startServer(
                pki, service, handlerCalls, false, sentResponse::set);
        GrpcWorldOriginalStartSessionActivationTerminalReadClient client =
            WorldOriginalStartSessionActivationTransportTestSupport.terminalReadClient(
                pki, temporaryDirectory, server.port(), pki.accountClient())) {
      client.init();

      var actual = client.read(input);

      assertThat(handlerCalls).hasValue(1);
      assertThat(actual.canonicalBytes()).containsExactly(fixture.terminal().canonicalBytes());
      assertThat(actual.accountProtectionEvidence().canonicalBytes())
          .containsExactly(fixture.protection().canonicalBytes());
      assertThat(actual.activationResult().canonicalBytes())
          .containsExactly(fixture.terminal().activationResult().canonicalBytes());
      assertThat(actual.activationRequestId()).isEqualTo(fixture.terminal().activationRequestId());
      assertThat(actual.outcome())
          .isEqualTo(
              net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation.Outcome
                  .COMMITTED);

      assertThat(sentResponse.get())
          .isInstanceOf(ReadOriginalStartSessionActivationTerminalEvidenceResponse.class);
      var response =
          (ReadOriginalStartSessionActivationTerminalEvidenceResponse) sentResponse.get();
      assertThat(response.getReadRequestIdList()).containsExactly(input.readRequestId().toString());
      assertThat(response.getTerminalEvidenceList())
          .containsExactly(ByteString.copyFrom(fixture.terminal().canonicalBytes()));

      var captured =
          org.mockito.ArgumentCaptor.forClass(
              net.firedevops.firemud.common.account.startsession
                  .AccountStartSessionActivationProtectionEvidence.class);
      verify(owner).readTerminalEvidence(captured.capture());
      assertThat(captured.getValue().canonicalBytes())
          .containsExactly(fixture.protection().canonicalBytes());
    }
  }

  @Test
  void trustedWrongServiceAndWrongNamespaceCertificatesAreDeniedBeforeOwner() throws Exception {
    var fixture = WorldOriginalStartSessionActivationTransportTestSupport.fixture();
    var pki =
        WorldOriginalStartSessionActivationTransportTestSupport.TestPki.create(temporaryDirectory);
    var input =
        new WorldOriginalStartSessionActivationTerminalReadInput(
            java.util.UUID.fromString("a1a1a1a1-a1a1-41a1-81a1-a1a1a1a1a1a1"),
            fixture.protection());
    var owner = mock(WorldCanonicalInstanceActivationService.class);
    var service = new WorldOriginalStartSessionActivationTerminalReadGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();

    try (var server =
        WorldOriginalStartSessionActivationTransportTestSupport.startServer(
            pki, service, handlerCalls, false, null)) {
      for (var identity :
          java.util.List.of(pki.gameSessionClient(), pki.otherNamespaceAccountClient())) {
        try (var client =
            WorldOriginalStartSessionActivationTransportTestSupport.terminalReadClient(
                pki, temporaryDirectory, server.port(), identity)) {
          client.init();

          assertThatThrownBy(() -> client.read(input))
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
    var input =
        new WorldOriginalStartSessionActivationTerminalReadInput(
            java.util.UUID.fromString("a2a2a2a2-a2a2-42a2-82a2-a2a2a2a2a2a2"),
            fixture.protection());
    var owner = mock(WorldCanonicalInstanceActivationService.class);
    var service = new WorldOriginalStartSessionActivationTerminalReadGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();

    try (var server =
            WorldOriginalStartSessionActivationTransportTestSupport.startServer(
                pki, service, handlerCalls, false, null);
        var unauthenticatedClient =
            WorldOriginalStartSessionActivationTransportTestSupport.clientWithoutIdentity(
                pki, server.port())) {
      var request =
          WorldOriginalStartSessionActivationTerminalReadGrpcCodec.toRequest(input, "test");

      assertThatThrownBy(
              () ->
                  WorldOriginalStartSessionActivationTerminalReadServiceGrpc.newBlockingStub(
                          unauthenticatedClient.channel())
                      .withDeadlineAfter(3, TimeUnit.SECONDS)
                      .readOriginalStartSessionActivationTerminalEvidence(request))
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
    var input =
        new WorldOriginalStartSessionActivationTerminalReadInput(
            java.util.UUID.fromString("a3a3a3a3-a3a3-43a3-83a3-a3a3a3a3a3a3"),
            fixture.protection());
    var owner = mock(WorldCanonicalInstanceActivationService.class);
    var service = new WorldOriginalStartSessionActivationTerminalReadGrpcService(owner, "test");
    var handlerCalls = new AtomicInteger();

    try (var server =
            WorldOriginalStartSessionActivationTransportTestSupport.startServer(
                pki, service, handlerCalls, true, null);
        GrpcWorldOriginalStartSessionActivationTerminalReadClient client =
            WorldOriginalStartSessionActivationTransportTestSupport.terminalReadClient(
                pki, temporaryDirectory, server.port(), pki.accountClient())) {
      client.init();

      assertThatThrownBy(() -> client.read(input))
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
