package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Metadata;
import io.grpc.Status;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewCapacityExecutionOwner;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayOriginalNewCapacityExecutionReadWorkloadGuard;

/**
 * Explicit typed receiving seam, not a registered gRPC service or live route. The transport adapter
 * must supply the actual inbound metadata and certificate-derived GrpcPeerIdentity context. The
 * existing execution-read service is unchanged and never invokes this mutation.
 */
public final class CanonicalGameplayCapacityOriginRecoveryReceiver {
  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Shares the injected custody owner without exposing it")
  private final CanonicalGameplayOriginalNewCapacityExecutionOwner owner;

  private final CanonicalGameplayOriginalNewCapacityExecutionReadWorkloadGuard identityGuard;
  private final String namespace;
  private final Clock clock;

  public CanonicalGameplayCapacityOriginRecoveryReceiver(
      CanonicalGameplayOriginalNewCapacityExecutionOwner owner, String namespace, Clock clock) {
    this.owner = Objects.requireNonNull(owner);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Namespace required");
    this.namespace = namespace;
    this.clock = Objects.requireNonNull(clock);
    this.identityGuard =
        new CanonicalGameplayOriginalNewCapacityExecutionReadWorkloadGuard(namespace);
  }

  /** Actual Account caller must admit work before asking Account to resolve the origin. */
  public net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation.Permit
      beginObservation(
          net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request request,
          Instant deadline,
          Metadata inboundHeaders) {
    identityGuard.requireAccountCaller();
    if (inboundHeaders == null || inboundHeaders.containsKey(AUTHORIZATION))
      throw Status.PERMISSION_DENIED.asRuntimeException();
    if (request == null || !namespace.equals(request.targetNamespace()))
      throw Status.INVALID_ARGUMENT.asRuntimeException();
    Instant now = clock.instant();
    if (deadline == null || !now.isBefore(deadline) || deadline.isAfter(now.plusSeconds(5)))
      throw Status.DEADLINE_EXCEEDED.asRuntimeException();
    long absolute = deadline.toEpochMilli();
    var inbound = io.grpc.Context.current().getDeadline();
    if (inbound != null) {
      long remaining = inbound.timeRemaining(java.util.concurrent.TimeUnit.MILLISECONDS);
      if (remaining <= 0) throw Status.DEADLINE_EXCEEDED.asRuntimeException();
      absolute = Math.min(absolute, Math.addExact(clock.millis(), remaining));
    }
    try {
      return owner.beginOriginObservation(request, absolute);
    } catch (RuntimeException held) {
      throw Status.UNAVAILABLE.withDescription("Origin continuation held").asRuntimeException();
    }
  }

  /** One bounded mutation attempt; caller retains Account authority and durable continuation. */
  public void receiveExact(
      AccountCapacityOriginResolution resolution,
      net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation.Permit
          permit,
      Metadata inboundHeaders) {
    identityGuard.requireAccountCaller();
    if (inboundHeaders == null || inboundHeaders.containsKey(AUTHORIZATION))
      throw Status.PERMISSION_DENIED
          .withDescription("Account workload command required")
          .asRuntimeException();
    if (resolution == null || !namespace.equals(resolution.request().targetNamespace()))
      throw Status.INVALID_ARGUMENT
          .withDescription("Exact origin namespace required")
          .asRuntimeException();
    Instant deadline = permit == null ? null : permit.deadline();
    Instant now = clock.instant();
    if (deadline == null || !now.isBefore(deadline) || deadline.isAfter(now.plusSeconds(5)))
      throw Status.DEADLINE_EXCEEDED.asRuntimeException();
    var inboundDeadline = io.grpc.Context.current().getDeadline();
    if (inboundDeadline != null && inboundDeadline.isExpired())
      throw Status.DEADLINE_EXCEEDED.asRuntimeException();
    long absolute = deadline.toEpochMilli();
    if (inboundDeadline != null) {
      long remaining = inboundDeadline.timeRemaining(java.util.concurrent.TimeUnit.MILLISECONDS);
      if (remaining <= 0) throw Status.DEADLINE_EXCEEDED.asRuntimeException();
      absolute = Math.min(absolute, Math.addExact(clock.millis(), remaining));
    }
    try {
      owner.receiveOriginResolution(resolution, permit, absolute);
    } catch (RuntimeException unavailable) {
      throw Status.UNAVAILABLE
          .withDescription("Exact origin custody unavailable")
          .asRuntimeException();
    }
  }
}
