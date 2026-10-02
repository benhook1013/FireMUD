package net.firedevops.firemud.worldmanagement.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetInitialAdmissionBindProofRequest;
import net.firedevops.firemud.gamesession.v1.GetInitialAdmissionBindProofResponse;
import net.firedevops.firemud.gamesession.v1.InitialAdmissionBindOwnerProofOutcome;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindOwnerProof.Outcome;
import net.firedevops.firemud.worldmanagement.entity.InitialAdmissionBindHold;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GrpcGameSessionInitialAdmissionBindProofClientTest {
  private static final String HOLD_ID = "00000000-0000-0000-0000-000000000003";
  private static final String HOLD_FENCE = "00000000-0000-0000-0000-000000000004";
  private static final String REALM_UUID = "00000000-0000-0000-0000-000000000001";
  private static final String NAMESPACE_UUID = "00000000-0000-0000-0000-000000000002";
  private static final String REQUEST_ID = "initial-admission-1";
  private static final String REQUEST_DIGEST = "a".repeat(64);

  @Test
  void sendsFullHoldTupleWithDeadlineAndMapsCommittedOwnerProof() throws Exception {
    var stub =
        mock(GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub.class);
    when(stub.withDeadlineAfter(anyLong(), any())).thenReturn(stub);
    when(stub.getInitialAdmissionBindProof(any()))
        .thenReturn(
            response(
                InitialAdmissionBindOwnerProofOutcome
                    .INITIAL_ADMISSION_BIND_OWNER_PROOF_OUTCOME_COMMITTED,
                "gs-ledger-100",
                "audit-100",
                1L,
                REQUEST_DIGEST,
                false));
    TestClient client = client(stub);
    client.init();

    var proof = client.readOwnerProof(hold("SHARED"));

    ArgumentCaptor<GetInitialAdmissionBindProofRequest> request =
        ArgumentCaptor.forClass(GetInitialAdmissionBindProofRequest.class);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub).getInitialAdmissionBindProof(request.capture());
    assertEquals(HOLD_ID, request.getValue().getHoldId());
    assertEquals(HOLD_FENCE, request.getValue().getHoldFence());
    assertEquals("42", request.getValue().getTenantId());
    assertEquals(REALM_UUID, request.getValue().getRealmUuid());
    assertEquals(NAMESPACE_UUID, request.getValue().getPlayableStateNamespaceUuid());
    assertEquals(
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, request.getValue().getPlayableStateScope());
    assertEquals("101", request.getValue().getGameInstanceId());
    assertEquals("11", request.getValue().getVersionId());
    assertEquals(7L, request.getValue().getActiveLifecycleEpoch());
    assertEquals(REQUEST_ID, request.getValue().getInitialAdmissionRequestId());
    assertEquals(REQUEST_DIGEST, request.getValue().getRequestDigest());
    assertEquals(1L, request.getValue().getExpectedCatalogRevision());
    assertEquals(Outcome.COMMITTED, proof.outcome());
    assertEquals("gs-ledger-100", proof.ownerProofId());
    assertEquals("audit-100", proof.pointerAuditId());
    assertEquals(1L, proof.pointerVersion());
    assertEquals(REQUEST_DIGEST, proof.pointerAuditRequestDigest());
    client.close();
  }

  @Test
  void mapsExactAbortedAttemptTombstoneWithoutGlobalPointerAbsenceClaim() throws Exception {
    var stub =
        mock(GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub.class);
    when(stub.withDeadlineAfter(anyLong(), any())).thenReturn(stub);
    when(stub.getInitialAdmissionBindProof(any()))
        .thenReturn(
            response(
                InitialAdmissionBindOwnerProofOutcome
                    .INITIAL_ADMISSION_BIND_OWNER_PROOF_OUTCOME_ABORTED,
                "gs-ledger-101",
                "",
                0L,
                "",
                true));
    TestClient client = client(stub);
    client.init();

    var proof = client.readOwnerProof(hold("SHARED"));

    assertEquals(Outcome.ABORTED, proof.outcome());
    assertEquals("gs-ledger-101", proof.ownerProofId());
    assertNull(proof.pointerAuditId());
    assertEquals(0L, proof.pointerVersion());
    assertNull(proof.pointerAuditRequestDigest());
    assertEquals(true, proof.futureCommitPrevented());
    client.close();
  }

  @Test
  void unsupportedLocalScopeFailsBeforeCallingGameSession() throws Exception {
    var stub =
        mock(GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub.class);
    TestClient client = client(stub);
    client.init();

    assertThrows(IllegalArgumentException.class, () -> client.readOwnerProof(hold("UNKNOWN")));

    verify(stub, never()).getInitialAdmissionBindProof(any());
    client.close();
  }

  private static GetInitialAdmissionBindProofResponse response(
      InitialAdmissionBindOwnerProofOutcome outcome,
      String ownerProofId,
      String auditId,
      long pointerVersion,
      String auditDigest,
      boolean futureCommitPrevented) {
    return GetInitialAdmissionBindProofResponse.newBuilder()
        .setHoldId(HOLD_ID)
        .setHoldFence(HOLD_FENCE)
        .setTenantId("42")
        .setRealmUuid(REALM_UUID)
        .setPlayableStateNamespaceUuid(NAMESPACE_UUID)
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setGameInstanceId("101")
        .setVersionId("11")
        .setActiveLifecycleEpoch(7L)
        .setInitialAdmissionRequestId(REQUEST_ID)
        .setRequestDigest(REQUEST_DIGEST)
        .setExpectedNoPriorPointer(true)
        .setExpectedCatalogRevision(1L)
        .setOutcome(outcome)
        .setOwnerProofId(ownerProofId)
        .setPointerAuditId(auditId)
        .setPointerVersion(pointerVersion)
        .setPointerAuditRequestDigest(auditDigest)
        .setFutureCommitPrevented(futureCommitPrevented)
        .build();
  }

  private static InitialAdmissionBindHold hold(String scope) {
    Instant now = Instant.parse("2026-10-02T00:00:00Z");
    return new InitialAdmissionBindHold(
        HOLD_ID,
        HOLD_FENCE,
        42L,
        REALM_UUID,
        NAMESPACE_UUID,
        scope,
        101L,
        11L,
        7L,
        REQUEST_ID,
        REQUEST_DIGEST,
        true,
        1L,
        "PENDING",
        now.plusSeconds(300),
        null,
        null,
        null,
        null,
        null,
        now,
        now,
        null,
        0L);
  }

  private static TestClient client(
      GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub stub) {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    return new TestClient(
        endpoints, grpc, new PassingChannelFactory(), BlockingGrpcStubCustomizer.noop(), stub);
  }

  private static final class TestClient extends GrpcGameSessionInitialAdmissionBindProofClient {
    private final GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub
        stub;

    private TestClient(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tlsProps,
        GrpcChannelFactory channelFactory,
        BlockingGrpcStubCustomizer stubCustomizer,
        GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub stub) {
      super(endpoints, tlsProps, channelFactory, stubCustomizer);
      this.stub = stub;
    }

    @Override
    protected GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub
        buildStub(ManagedChannel channel) {
      return stub;
    }
  }

  private static final class PassingChannelFactory extends GrpcChannelFactory {
    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
        throws SSLException {
      return mock(ManagedChannel.class);
    }
  }
}
