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
import net.firedevops.firemud.common.gamesession.PrivatePreparationProtectionGrpcCodec;
import net.firedevops.firemud.common.gamesession.PrivatePreparationProtectionGrpcCodec.Acquire;
import net.firedevops.firemud.common.gamesession.PrivatePreparationProtectionGrpcCodec.Held;
import net.firedevops.firemud.common.gamesession.PrivatePreparationProtectionGrpcCodec.Read;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Evidence;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.PrivatePreparationProtectionServiceGrpc;

/**
 * Explicit peer-qualified pending source protection; no settlement, actor or activation authority.
 */
public final class WorldPrivatePreparationProtectionClient
    extends AbstractReloadingBlockingGrpcClient<
        PrivatePreparationProtectionServiceGrpc.PrivatePreparationProtectionServiceBlockingStub> {
  private final String namespace;
  private final Clock clock;

  public WorldPrivatePreparationProtectionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace,
      Clock clock) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Private source protection"),
        channels,
        WorldPrivatePreparationProtectionClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Namespace required");
    this.namespace = namespace;
    this.clock = Objects.requireNonNull(clock);
  }

  public void init() throws IOException {
    requireNoAmbientSql("Private protection initialization requires no SQL");
    initOnce("Private protection client is closed", "Private protection client is unavailable");
  }

  /**
   * The caller retains this request ID across uncertain outcomes. An exact replay can be historical
   * after expiry: acquisition evidence is not permission to execute a World operation.
   */
  public Verified acquire(UUID requestId, Evidence expectedSource) {
    Deadline deadline = callDeadline();
    if (!namespace.equals(expectedSource.snapshot().scope().targetNamespace()))
      throw new IllegalArgumentException("Wrong private source namespace");
    var request = new Acquire(requestId, expectedSource);
    var response =
        stub(deadline)
            .acquirePrivatePreparationProtection(
                PrivatePreparationProtectionGrpcCodec.toWire(request));
    live(deadline);
    return new Verified(
        PrivatePreparationProtectionGrpcCodec.fromResponse(request, response), false);
  }

  /** Requalifies exact unexpired source; the durable hold is not actor or post-expiry authority. */
  public Verified readCurrent(Verified prior) {
    Deadline deadline = callDeadline();
    Held held = Objects.requireNonNull(prior).evidence();
    if (!namespace.equals(held.source().snapshot().scope().targetNamespace()))
      throw new IllegalArgumentException("Wrong private source namespace");
    var request =
        new Read(
            UUID.randomUUID(), held.requestId(), held.holdId(), held.fence(), held.sourceDigest());
    var response =
        stub(deadline)
            .readCurrentPrivatePreparationProtection(
                PrivatePreparationProtectionGrpcCodec.toWire(request));
    live(deadline);
    Held result = PrivatePreparationProtectionGrpcCodec.fromResponse(request, response);
    result.source().snapshot().requireCurrentAt(clock.instant());
    live(deadline);
    return new Verified(result, true);
  }

  private PrivatePreparationProtectionServiceGrpc.PrivatePreparationProtectionServiceBlockingStub
      stub(Deadline deadline) {
    return requireInitializedStub("Private protection client is unavailable")
        .withDeadline(deadline)
        .withMaxInboundMessageSize(PrivatePreparationProtectionGrpcCodec.MAX_WIRE_BYTES)
        .withMaxOutboundMessageSize(PrivatePreparationProtectionGrpcCodec.MAX_WIRE_BYTES);
  }

  private Deadline callDeadline() {
    requireNoAmbientSql("Private source protection requires no ambient SQL");
    if (SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null)
      throw new IllegalStateException("Workload-only World context required");
    Deadline deadline =
        Deadline.after(PrivatePreparationProtectionGrpcCodec.DEADLINE_SECONDS, TimeUnit.SECONDS);
    if (Context.current().getDeadline() != null)
      deadline = deadline.minimum(Context.current().getDeadline());
    live(deadline);
    return deadline;
  }

  private static void live(Deadline deadline) {
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
  protected PrivatePreparationProtectionServiceGrpc.PrivatePreparationProtectionServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/game-session-service";
    return PrivatePreparationProtectionServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer));
  }

  /** Only completed exact-peer calls create this value; neither flag grants execution authority. */
  public static final class Verified {
    private final Held evidence;
    private final boolean currentRead;

    private Verified(Held evidence, boolean currentRead) {
      this.evidence = evidence;
      this.currentRead = currentRead;
    }

    public Held evidence() {
      return evidence;
    }

    public boolean currentRead() {
      return currentRead;
    }

    public boolean admissionAllowed() {
      return false;
    }
  }
}
