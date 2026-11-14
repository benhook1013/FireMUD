package net.firedevops.firemud.accountservice.client;

import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecutionReadGrpcCodec;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.OriginalNewCapacityExecutionReadServiceGrpc;

/** Account's exact authenticated bounded read-only original-NEW recovery source. Unregistered. */
public final class AccountOriginalNewCapacityExecutionReadClient
    extends AbstractReloadingBlockingGrpcClient<
        OriginalNewCapacityExecutionReadServiceGrpc
            .OriginalNewCapacityExecutionReadServiceBlockingStub>
    implements AccountTenantCapacityRecoveryWorker.ExecutionReadClient,
        AccountTenantCapacityRecoveryWorker.HistoricalFinalReadClient {
  private static final long MAX_READ_MILLIS = 5000;
  private final String namespace;
  private final String peer;
  private final Clock clock;

  public AccountOriginalNewCapacityExecutionReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace,
      Clock clock) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Original NEW capacity execution read"),
        channels,
        AccountOriginalNewCapacityExecutionReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Namespace required");
    this.namespace = namespace;
    this.clock = Objects.requireNonNull(clock);
    peer =
        TenantCapacityAdmission.expectedWorkload(
            namespace, TenantCapacityAdmission.Owner.GAME_SESSION);
  }

  public void init() throws IOException {
    requireNoAmbientSql("Capacity read initialization requires no SQL");
    initOnce("Capacity execution read client is closed", "Capacity execution read unavailable");
  }

  @Override
  public AccountTenantCapacityRecoveryWorker.Observation readExact(
      TenantCapacityAdmission.Request request,
      TenantCapacityAdmission.Result reservation,
      Instant deadline) {
    Deadline rpcDeadline = authorizedDeadline(request, deadline);
    var selector = new OriginalNewCapacityExecutionReadGrpcCodec.Selector(request, reservation);
    var wire = OriginalNewCapacityExecutionReadGrpcCodec.toRequest(selector);
    var response =
        withPublishedInitializedStub(
            "Capacity execution read unavailable",
            stub -> {
              if (rpcDeadline.isExpired()) throw unavailable();
              return stub.withDeadline(rpcDeadline)
                  .withMaxOutboundMessageSize(
                      OriginalNewCapacityExecutionReadGrpcCodec.MAX_REQUEST_BYTES)
                  .withMaxInboundMessageSize(
                      OriginalNewCapacityExecutionReadGrpcCodec.MAX_RESPONSE_BYTES)
                  .readOriginalNewCapacityExecution(wire);
            });
    var readback = OriginalNewCapacityExecutionReadGrpcCodec.fromResponse(selector, response);
    var disposition =
        AccountTenantCapacityRecoveryWorker.Disposition.valueOf(readback.disposition());
    // The complete terminal bytes are generated from actual owner evidence, not a boolean.
    var observed = new java.io.ByteArrayOutputStream();
    observed.writeBytes(readback.canonicalReadback());
    for (String field :
        java.util.List.of(readback.currentOwnerId(), Long.toString(readback.currentOwnerFence()))) {
      byte[] value = field.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
      observed.writeBytes(
          Integer.toString(value.length).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      observed.write(':');
      observed.writeBytes(value);
    }
    byte[] canonical = observed.toByteArray();
    var observation =
        new AccountTenantCapacityRecoveryWorker.Observation(
            peer,
            reservation,
            CapacityAdmissionDigest.preimage(request),
            disposition,
            UUID.nameUUIDFromBytes(canonical),
            readback.currentOwnerFence(),
            readback.physicalDigest(),
            canonical);
    if (rpcDeadline.isExpired() || !clock.instant().isBefore(deadline)) throw unavailable();
    return observation;
  }

  @Override
  public AccountTenantCapacityRecoveryWorker.HistoricalObservation readHistoricalFinal(
      TenantCapacityAdmission.Request request,
      TenantCapacityAdmission.Result reservation,
      Instant deadline) {
    Deadline rpcDeadline = authorizedDeadline(request, deadline);
    var selector = new OriginalNewCapacityExecutionReadGrpcCodec.Selector(request, reservation);
    var wire = OriginalNewCapacityExecutionReadGrpcCodec.toRequest(selector);
    var response =
        withPublishedInitializedStub(
            "Historical FINAL read unavailable",
            stub -> {
              if (rpcDeadline.isExpired()) throw unavailable();
              return stub.withDeadline(rpcDeadline)
                  .withMaxOutboundMessageSize(
                      OriginalNewCapacityExecutionReadGrpcCodec.MAX_REQUEST_BYTES)
                  .withMaxInboundMessageSize(
                      OriginalNewCapacityExecutionReadGrpcCodec.MAX_RESPONSE_BYTES)
                  .readHistoricalOriginalNewCapacityFinal(wire);
            });
    var receipt =
        OriginalNewCapacityExecutionReadGrpcCodec.fromHistoricalResponse(selector, response);
    if (rpcDeadline.isExpired() || !clock.instant().isBefore(deadline)) throw unavailable();
    return new AccountTenantCapacityRecoveryWorker.HistoricalObservation(peer, receipt);
  }

  private Deadline authorizedDeadline(TenantCapacityAdmission.Request request, Instant deadline) {
    Deadline maximum = Deadline.after(MAX_READ_MILLIS, TimeUnit.MILLISECONDS);
    requireNoAmbientSql("Capacity execution read requires no Account transaction");
    if (!namespace.equals(request.targetNamespace())
        || request.context().owner() != TenantCapacityAdmission.Owner.GAME_SESSION
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null) throw unavailable();
    long millis = Math.min(MAX_READ_MILLIS, Duration.between(clock.instant(), deadline).toMillis());
    if (millis <= 0) throw unavailable();
    return maximum.offset(millis - MAX_READ_MILLIS, TimeUnit.MILLISECONDS);
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
  protected OriginalNewCapacityExecutionReadServiceGrpc
          .OriginalNewCapacityExecutionReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    return OriginalNewCapacityExecutionReadServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer));
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Original NEW capacity execution read unavailable");
  }
}
