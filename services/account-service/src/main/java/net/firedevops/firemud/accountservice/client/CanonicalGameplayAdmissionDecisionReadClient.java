package net.firedevops.firemud.accountservice.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionNeverAdmittedGrpcCodec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Evidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.RequestEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.ResolvedPublishedTarget;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionDecisionReadServiceGrpc;

/** Explicit, manually composed Account mTLS client for Game Session's provisional decision read. */
public final class CanonicalGameplayAdmissionDecisionReadClient
    extends AbstractReloadingBlockingGrpcClient<
        CanonicalGameplayAdmissionDecisionReadServiceGrpc
            .CanonicalGameplayAdmissionDecisionReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  // Two complete publication reads can each make two five-second policy calls, plus World.
  private static final long PUBLISHED_REALM_READ_DEADLINE_SECONDS = 30L;
  private final String workloadNamespace;

  public CanonicalGameplayAdmissionDecisionReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties, "Canonical gameplay admission decision read"),
        channelFactory,
        CanonicalGameplayAdmissionDecisionReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when an owning Account service explicitly composes this client. */
  public void init() throws SSLException, IOException {
    requireNoAmbientSql(
        "Gameplay admission decision client initialization requires no ambient SQL transaction");
    initOnce(
        "Gameplay admission decision client is closed",
        "Gameplay admission decision client is not initialized");
  }

  /** Returns only a closed typed response from the exact same-namespace Game Session peer. */
  public Evidence read(
      UUID bindingDecisionId, AccountGameplayAdmissionLeaseEvidence expectedLeaseEvidence) {
    requireNoAmbientSql("Gameplay admission decision read requires no ambient Account transaction");
    if (SessionContext.hasAuthenticatedCallerContext() || SessionContext.isInternalService()) {
      throw new IllegalStateException(
          "Gameplay admission decision read requires a workload-only caller context");
    }
    Objects.requireNonNull(bindingDecisionId, "bindingDecisionId");
    Objects.requireNonNull(expectedLeaseEvidence, "expectedLeaseEvidence");
    if (!workloadNamespace.equals(expectedLeaseEvidence.carrier().get("targetNamespace"))) {
      throw new IllegalArgumentException(
          "Gameplay admission decision read must use the configured workload namespace");
    }

    var wireRequest =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.toRequest(
            bindingDecisionId, expectedLeaseEvidence);
    RequestEvidence expected =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(wireRequest);
    var response =
        requireAvailableStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .getCanonicalGameplayAdmissionDecision(wireRequest);
    try {
      return CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromResponse(expected, response);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Game Session returned invalid canonical gameplay admission evidence", malformed);
    }
  }

  /** Independently reads only the original NEW negative-purpose qualified closure. */
  public AccountGameplayAdmissionNeverAdmittedGrpcCodec.NegativeDecisionEvidence
      readOriginalNewNeverAdmitted(
          UUID transitionId, UUID abandonmentId, AccountGameplayAdmissionLeaseEvidence lease) {
    requireNoAmbientSql("Never-admitted read requires no ambient Account transaction");
    if (SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null)
      throw new IllegalStateException("Never-admitted read requires workload-only caller context");
    Objects.requireNonNull(lease, "Original NEW lease evidence required");
    if (!workloadNamespace.equals(lease.carrier().get("targetNamespace")))
      throw new IllegalArgumentException(
          "Never-admitted read requires configured workload namespace");
    var request =
        AccountGameplayAdmissionNeverAdmittedGrpcCodec.toDecisionRequest(
            transitionId, abandonmentId, lease);
    var expected = AccountGameplayAdmissionNeverAdmittedGrpcCodec.parseDecisionRequest(request);
    var response =
        requireAvailableStub()
            .withMaxInboundMessageSize(
                AccountGameplayAdmissionNeverAdmittedGrpcCodec.MAX_WIRE_BYTES)
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .getOriginalNewNeverAdmittedDecision(request);
    try {
      return AccountGameplayAdmissionNeverAdmittedGrpcCodec.fromDecisionResponse(
          expected, response);
    } catch (RuntimeException malformed) {
      throw new IllegalStateException(
          "Game Session negative closure evidence unavailable", malformed);
    }
  }

  /** Reads the complete canonical discovery collection; no player admission or World hold. */
  public CanonicalGameplayAdmissionDecisionReadGrpcCodec.VisiblePublicProductionTargets
      listVisiblePublicProductionAdmissionTargets(UUID readRequestId) {
    requireNoAmbientSql("Canonical discovery requires no ambient Account transaction");
    requirePublishedSourceContext();
    var expected =
        new CanonicalGameplayAdmissionDecisionReadGrpcCodec.DiscoveryRequest(
            readRequestId, workloadNamespace);
    var request = CanonicalGameplayAdmissionDecisionReadGrpcCodec.toDiscoveryRequest(expected);
    var response =
        requireAvailableStub()
            .withMaxInboundMessageSize(
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.MAX_DISCOVERY_RESPONSE_WIRE_BYTES)
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .listVisiblePublicProductionAdmissionTargets(request);
    try {
      return CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromDiscoveryResponse(
          expected, response);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Game Session returned invalid canonical discovery evidence", malformed);
    }
  }

  /**
   * Resolves missing strict-read selectors from actual current Game Session owner rows. The caller
   * supplies only its retained target tuple; the result is selection evidence, not admission.
   */
  public ResolvedPublishedTarget resolvePublishedRealmAdmissionTarget(
      RetainedTargetSelector selector) {
    requireNoAmbientSql("Published target resolution requires no ambient Account transaction");
    requirePublishedSourceContext();
    Objects.requireNonNull(selector, "selector");
    if (!workloadNamespace.equals(selector.targetNamespace())) {
      throw new IllegalArgumentException(
          "Published target resolution requires configured namespace");
    }
    var request =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetResolutionRequest(selector);
    var response =
        requireAvailableStub()
            .withMaxInboundMessageSize(
                CanonicalGameplayAdmissionDecisionReadGrpcCodec
                    .MAX_TARGET_RESOLUTION_RESPONSE_WIRE_BYTES)
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .resolvePublishedRealmAdmissionTarget(request);
    try {
      return CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromTargetResolutionResponse(
          selector, response);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Game Session returned invalid current target evidence", malformed);
    }
  }

  /**
   * Reads the current published route and complete sealed source before Account lease allocation.
   * Selector account identity and expected counters are correlation only, never authorization.
   */
  public PublishedRealmEvidence readPublishedRealmAdmissionOwner(
      CanonicalGameplayRosterOwnerReadEvidence.Request selector) {
    requireNoAmbientSql("Published realm owner read requires no ambient Account transaction");
    requirePublishedSourceContext();
    Objects.requireNonNull(selector, "selector");
    if (!workloadNamespace.equals(selector.targetNamespace())) {
      throw new IllegalArgumentException(
          "Published realm owner read must use the configured workload namespace");
    }

    var wireRequest =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmRequest(selector);
    var expected =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(wireRequest);
    var response =
        requireAvailableStub()
            .withMaxInboundMessageSize(
                CanonicalGameplayAdmissionDecisionReadGrpcCodec
                    .MAX_PUBLISHED_REALM_RESPONSE_WIRE_BYTES)
            .withDeadlineAfter(PUBLISHED_REALM_READ_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .getPublishedRealmAdmissionOwnerRead(wireRequest);
    try {
      return CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
          expected, response);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Game Session returned invalid published realm owner evidence", malformed);
    }
  }

  private CanonicalGameplayAdmissionDecisionReadServiceGrpc
          .CanonicalGameplayAdmissionDecisionReadServiceBlockingStub
      requireAvailableStub() {
    try {
      return requireInitializedStub("Gameplay admission decision client is not initialized");
    } catch (IllegalStateException lifecycleFailure) {
      throw new ClientUnavailableException(lifecycleFailure);
    }
  }

  /** A source read cannot run before explicit initialization or after this client is closed. */
  public static final class ClientUnavailableException extends IllegalStateException {
    public ClientUnavailableException(IllegalStateException cause) {
      super("Canonical gameplay admission decision client is unavailable", cause);
    }
  }

  private static void requirePublishedSourceContext() {
    if (SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null) {
      throw new IllegalStateException(
          "Published source reads require workload-only caller context");
    }
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
  protected CanonicalGameplayAdmissionDecisionReadServiceGrpc
          .CanonicalGameplayAdmissionDecisionReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    return CanonicalGameplayAdmissionDecisionReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(
            CanonicalGameplayAdmissionDecisionReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer))
        .withCompression("gzip");
  }
}
