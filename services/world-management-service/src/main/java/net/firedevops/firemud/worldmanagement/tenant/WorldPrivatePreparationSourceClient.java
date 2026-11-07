package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationBuildGrpcCodec;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Evidence;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.PrivateWorldPreparationReadServiceGrpc;

/**
 * Manually composed exact-peer source reader; no runtime, materialization or admission authority.
 */
public final class WorldPrivatePreparationSourceClient
    extends AbstractReloadingBlockingGrpcClient<
        PrivateWorldPreparationReadServiceGrpc.PrivateWorldPreparationReadServiceBlockingStub> {
  private final String namespace;
  private final Clock clock;

  public WorldPrivatePreparationSourceClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace,
      Clock clock) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Private World preparation source"),
        channels,
        WorldPrivatePreparationSourceClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Namespace required");
    this.namespace = namespace;
    this.clock = Objects.requireNonNull(clock);
  }

  public void init() throws IOException {
    requireNoAmbientSql("Private World source initialization requires no SQL or synchronization");
    initOnce("Private World source client is closed", "Private World source client is unavailable");
  }

  public Verified read(Scope scope, UUID expectedPreparationRequestId) {
    return read(
        scope,
        expectedPreparationRequestId,
        Deadline.after(
            PrivateWorldPreparationReadGrpcCodec.CALL_DEADLINE_SECONDS, TimeUnit.SECONDS));
  }

  /** Uses the same absolute budget when a composing World owner performs independent rereads. */
  public Verified read(Scope scope, UUID expectedPreparationRequestId, Deadline overall) {
    requireNoAmbientSql("Private World source read requires no SQL or synchronization");
    if (SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null)
      throw new IllegalStateException("Workload-only World context required");
    Objects.requireNonNull(scope);
    if (!namespace.equals(scope.targetNamespace()))
      throw new IllegalArgumentException("Wrong source namespace");
    Deadline deadline =
        Objects.requireNonNull(overall)
            .minimum(
                Deadline.after(
                    PrivateWorldPreparationReadGrpcCodec.CALL_DEADLINE_SECONDS, TimeUnit.SECONDS));
    if (Context.current().getDeadline() != null)
      deadline = deadline.minimum(Context.current().getDeadline());
    requireLiveCall(deadline);
    var request =
        new PrivateWorldPreparationReadGrpcCodec.Request(
            UUID.randomUUID(), scope, expectedPreparationRequestId);
    var response =
        requireInitializedStub("Private World source client is unavailable")
            .withDeadline(deadline)
            .withMaxOutboundMessageSize(PrivateWorldPreparationReadGrpcCodec.MAX_REQUEST_WIRE_BYTES)
            .withMaxInboundMessageSize(PrivateWorldPreparationReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
            .readPrivateWorldPreparation(PrivateWorldPreparationReadGrpcCodec.toRequest(request));
    requireLiveCall(deadline);
    Evidence evidence = PrivateWorldPreparationReadGrpcCodec.fromResponse(request, response);
    evidence.snapshot().requireCurrentAt(clock.instant());
    requireLiveCall(deadline);
    return new Verified(evidence);
  }

  /** Uses the same absolute budget when a composing World owner performs independent rereads. */
  public VerifiedBuild readBuild(Scope scope, UUID expectedPreparationRequestId, Deadline overall) {
    requireNoAmbientSql("Private World source read requires no SQL or synchronization");
    if (SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null)
      throw new IllegalStateException("Workload-only World context required");
    Objects.requireNonNull(scope);
    if (!namespace.equals(scope.targetNamespace()))
      throw new IllegalArgumentException("Wrong source namespace");
    Deadline deadline =
        Objects.requireNonNull(overall)
            .minimum(
                Deadline.after(
                    PrivateWorldPreparationReadGrpcCodec.CALL_DEADLINE_SECONDS, TimeUnit.SECONDS));
    if (Context.current().getDeadline() != null)
      deadline = deadline.minimum(Context.current().getDeadline());
    requireLiveCall(deadline);
    var request =
        new PrivateWorldPreparationReadGrpcCodec.Request(
            UUID.randomUUID(), scope, expectedPreparationRequestId);
    var response =
        requireInitializedStub("Private World source client is unavailable")
            .withDeadline(deadline)
            .withMaxOutboundMessageSize(PrivateWorldPreparationReadGrpcCodec.MAX_REQUEST_WIRE_BYTES)
            .withMaxInboundMessageSize(
                PrivateWorldPreparationBuildGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
            .readPrivateWorldPreparationBuild(
                PrivateWorldPreparationReadGrpcCodec.toRequest(request));
    requireLiveCall(deadline);
    var evidence = PrivateWorldPreparationBuildGrpcCodec.fromResponse(request, response);
    evidence.source().snapshot().requireCurrentAt(clock.instant());
    requireLiveCall(deadline);
    return new VerifiedBuild(evidence);
  }

  private static void requireLiveCall(Deadline deadline) {
    if (Context.current().isCancelled()) throw Status.CANCELLED.asRuntimeException();
    if (deadline.isExpired()) throw Status.DEADLINE_EXCEEDED.asRuntimeException();
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
  protected PrivateWorldPreparationReadServiceGrpc.PrivateWorldPreparationReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/game-session-service";
    return PrivateWorldPreparationReadServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer));
  }

  /** Actual exact-peer projection of the retained selected World build; no write permission. */
  public static final class VerifiedBuild {
    private final PrivateWorldPreparationBuildGrpcCodec.Build evidence;

    private VerifiedBuild(PrivateWorldPreparationBuildGrpcCodec.Build evidence) {
      this.evidence = evidence;
    }

    public PrivateWorldPreparationBuildGrpcCodec.Build evidence() {
      return evidence;
    }

    public boolean admissionAllowed() {
      return false;
    }
  }

  /** Only an actual peer-qualified client call can create this wrapper. */
  public static final class Verified {
    private final Evidence evidence;

    private Verified(Evidence evidence) {
      this.evidence = evidence;
    }

    public Evidence evidence() {
      return evidence;
    }
  }
}
