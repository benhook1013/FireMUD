package net.firedevops.firemud.worldmanagement.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetInitialAdmissionBindProofRequest;
import net.firedevops.firemud.gamesession.v1.GetInitialAdmissionBindProofResponse;
import net.firedevops.firemud.gamesession.v1.InitialAdmissionBindOwnerProofOutcome;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindOwnerProof;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindOwnerProof.Outcome;
import net.firedevops.firemud.worldmanagement.entity.InitialAdmissionBindHold;
import org.springframework.stereotype.Component;

/** Authenticated gRPC readback from the Game Session owner ledger. */
@Component
public class GrpcGameSessionInitialAdmissionBindProofClient
    extends AbstractReloadingBlockingGrpcClient<
        GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub>
    implements GameSessionInitialAdmissionBindProofClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  public GrpcGameSessionInitialAdmissionBindProofClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(
        endpoints,
        tlsProps,
        channelFactory,
        stubCustomizer,
        GrpcGameSessionInitialAdmissionBindProofClient.class);
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameSessionService();
  }

  @Override
  protected String defaultTarget() {
    return "game-session-service:6565";
  }

  @Override
  protected GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        GameSessionControlPlaneServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  @Override
  public InitialAdmissionBindOwnerProof readOwnerProof(InitialAdmissionBindHold hold) {
    if (hold == null) {
      throw new IllegalArgumentException("initial admission hold is required");
    }
    GetInitialAdmissionBindProofResponse response =
        stub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .getInitialAdmissionBindProof(toRequest(hold));
    if (response.hasError()) {
      throw new IllegalStateException(
          "Game Session initial admission owner read failed: " + response.getError().getCode());
    }
    return new InitialAdmissionBindOwnerProof(
        fromWireOutcome(response.getOutcome()),
        response.getHoldId(),
        response.getHoldFence(),
        parsePositive(response.getTenantId(), "tenant_id"),
        response.getRealmUuid(),
        response.getPlayableStateNamespaceUuid(),
        fromWireScope(response.getPlayableStateScope()),
        parsePositive(response.getGameInstanceId(), "game_instance_id"),
        parsePositive(response.getVersionId(), "version_id"),
        response.getActiveLifecycleEpoch(),
        response.getInitialAdmissionRequestId(),
        response.getRequestDigest(),
        response.getExpectedNoPriorPointer(),
        response.getExpectedCatalogRevision(),
        nullIfBlank(response.getOwnerProofId()),
        nullIfBlank(response.getPointerAuditId()),
        response.getPointerVersion(),
        nullIfBlank(response.getPointerAuditRequestDigest()),
        response.getFutureCommitPrevented());
  }

  private GetInitialAdmissionBindProofRequest toRequest(InitialAdmissionBindHold hold) {
    return GetInitialAdmissionBindProofRequest.newBuilder()
        .setHoldId(hold.holdId())
        .setHoldFence(hold.holdFence())
        .setTenantId(Long.toString(hold.tenantId()))
        .setRealmUuid(hold.realmUuid())
        .setPlayableStateNamespaceUuid(hold.playableStateNamespaceUuid())
        .setPlayableStateScope(toWireScope(hold.playableStateScope()))
        .setGameInstanceId(Long.toString(hold.gameInstanceId()))
        .setVersionId(Long.toString(hold.versionId()))
        .setActiveLifecycleEpoch(hold.activeLifecycleEpoch())
        .setInitialAdmissionRequestId(hold.initialAdmissionRequestId())
        .setRequestDigest(hold.requestDigest())
        .setExpectedNoPriorPointer(hold.expectedNoPriorPointer())
        .setExpectedCatalogRevision(hold.expectedCatalogRevision())
        .build();
  }

  private PlayableStateScope toWireScope(String scope) {
    return switch (scope) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> throw new IllegalArgumentException("Unsupported playable state scope");
    };
  }

  private String fromWireScope(PlayableStateScope scope) {
    return switch (scope) {
      case PLAYABLE_STATE_SCOPE_SHARED -> "SHARED";
      case PLAYABLE_STATE_SCOPE_ISOLATED -> "ISOLATED";
      case PLAYABLE_STATE_SCOPE_UNSPECIFIED, UNRECOGNIZED -> null;
    };
  }

  private Outcome fromWireOutcome(InitialAdmissionBindOwnerProofOutcome outcome) {
    return switch (outcome) {
      case INITIAL_ADMISSION_BIND_OWNER_PROOF_OUTCOME_COMMITTED -> Outcome.COMMITTED;
      case INITIAL_ADMISSION_BIND_OWNER_PROOF_OUTCOME_ABORTED -> Outcome.ABORTED;
      case INITIAL_ADMISSION_BIND_OWNER_PROOF_OUTCOME_NOT_FOUND -> Outcome.NOT_FOUND;
      case INITIAL_ADMISSION_BIND_OWNER_PROOF_OUTCOME_PENDING -> Outcome.PENDING;
      case INITIAL_ADMISSION_BIND_OWNER_PROOF_OUTCOME_UNAVAILABLE -> Outcome.UNAVAILABLE;
      case INITIAL_ADMISSION_BIND_OWNER_PROOF_OUTCOME_UNSPECIFIED,
          INITIAL_ADMISSION_BIND_OWNER_PROOF_OUTCOME_ERROR,
          UNRECOGNIZED ->
          Outcome.ERROR;
    };
  }

  private long parsePositive(String value, String fieldName) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0L) {
        throw new IllegalArgumentException(fieldName + " must be positive");
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(fieldName + " must be a positive number", exception);
    }
  }

  private String nullIfBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
