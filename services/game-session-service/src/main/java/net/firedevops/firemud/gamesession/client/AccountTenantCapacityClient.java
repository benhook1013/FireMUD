package net.firedevops.firemud.gamesession.client;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AccountServiceGrpc.AccountServiceBlockingStub;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionRequest;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionResponse;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Result;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Refusal;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/**
 * Unregistered exact Account capacity adapter. It performs no admission or owner activation CAS.
 */
public final class AccountTenantCapacityClient
    extends AbstractReloadingBlockingGrpcClient<AccountServiceGrpc.AccountServiceBlockingStub> {
  private final String namespace;
  private final Clock clock;
  private final String expectedPeer;

  public AccountTenantCapacityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace,
      Clock clock) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Tenant capacity commitment"),
        channels,
        AccountTenantCapacityClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Namespace required");
    this.namespace = namespace;
    this.expectedPeer = "spiffe://firemud/ns/" + namespace + "/sa/account-service";
    this.clock = Objects.requireNonNull(clock);
  }

  public void init() throws IOException {
    requireNoAmbientSql("Capacity client initialization requires no SQL transaction");
    initOnce("Capacity client is closed", "Account capacity unavailable");
  }

  /**
   * The caller must obtain its typed context from current owned player state, not text/metadata.
   * Retries retain this exact request. The Account call consumes the remaining absolute admission
   * budget, capped at five seconds and the incoming deadline. A timeout or an inexact response
   * leaves the commitment indeterminate. Collect read-only evidence for the original attempt first;
   * only replay this mutation with the exact original request when reconciliation permits it.
   */
  public Result commit(Request request, long absoluteDeadlineMillis) {
    var response =
        invoke(
            request,
            absoluteDeadlineMillis,
            AccountServiceBlockingStub::commitTenantCapacityAdmission);
    try {
      var refusal = TenantCapacityAdmissionGrpcCodec.readRefusalDetails(request, response);
      if (refusal.isPresent()) throw new CapacityRefusedException(refusal.orElseThrow());
      // Exact retry may replay an expired/terminal reservation. The final owner still checks it.
      return TenantCapacityAdmissionGrpcCodec.requireResponse(request, response);
    } catch (IllegalArgumentException inexactResponse) {
      throw Status.UNKNOWN
          .withDescription("Account capacity commitment response was inexact")
          .asRuntimeException();
    }
  }

  /** Recovers an exact immutable denial while the caller owns the original held attempt. */
  public Refusal readRetainedAdmissionDenial(Request request, long absoluteDeadlineMillis) {
    var response =
        invoke(
            request,
            absoluteDeadlineMillis,
            AccountServiceBlockingStub::readTenantCapacityAdmissionDenial);
    try {
      return TenantCapacityAdmissionGrpcCodec.readRefusalDetails(request, response)
          .filter(refusal -> refusal.durableDenialId() != null)
          .orElseThrow(IllegalArgumentException::new);
    } catch (IllegalArgumentException inexactResponse) {
      throw Status.UNKNOWN
          .withDescription("Account retained admission denial was not proven")
          .asRuntimeException();
    }
  }

  private CommitTenantCapacityAdmissionResponse invoke(
      Request request,
      long absoluteDeadlineMillis,
      BiFunction<
              AccountServiceBlockingStub,
              CommitTenantCapacityAdmissionRequest,
              CommitTenantCapacityAdmissionResponse>
          call) {
    long maximumMillis =
        TimeUnit.SECONDS.toMillis(TenantCapacityAdmissionGrpcCodec.CALL_DEADLINE_SECONDS);
    Deadline maximum = Deadline.after(maximumMillis, TimeUnit.MILLISECONDS);
    requireNoAmbientSql("Capacity request requires no ambient SQL");
    // Anchor conversion near the wall-clock sample, not at invocation entry. A pause
    // during sampling can only shorten the bound; it must never extend the caller budget.
    Deadline sampledAt = Deadline.after(0, TimeUnit.NANOSECONDS);
    Instant sampledInstant = clock.instant();
    var wire = TenantCapacityAdmissionGrpcCodec.toRequest(request);
    if (request.context().owner() != TenantCapacityAdmission.Owner.GAME_SESSION
        || !namespace.equals(request.targetNamespace()))
      throw new IllegalArgumentException("Exact Game Session context required");
    Deadline deadline =
        selectDeadline(
            maximum,
            sampledAt,
            Context.current().getDeadline(),
            absoluteDeadlineMillis,
            sampledInstant);
    try {
      return withPublishedInitializedStub(
          "Account capacity unavailable",
          stub -> {
            if (deadline.isExpired()) throw Status.DEADLINE_EXCEEDED.asRuntimeException();
            var bounded =
                stub.withDeadline(deadline)
                    .withMaxOutboundMessageSize(
                        TenantCapacityAdmissionGrpcCodec.MAX_REQUEST_WIRE_BYTES)
                    .withMaxInboundMessageSize(
                        TenantCapacityAdmissionGrpcCodec.MAX_RESPONSE_WIRE_BYTES);
            return call.apply(bounded, wire);
          });
    } catch (StatusRuntimeException failure) {
      throw TenantCapacityAdmissionGrpcCodec.validateFailure(failure);
    }
  }

  /** Converts paired clock samples without resetting the invocation or incoming cap. */
  static Deadline selectDeadline(
      Deadline maximum,
      Deadline sampledAt,
      Deadline incoming,
      long absoluteDeadlineMillis,
      Instant sampledInstant) {
    Duration maximumBudget =
        Duration.ofSeconds(TenantCapacityAdmissionGrpcCodec.CALL_DEADLINE_SECONDS);
    Duration remaining =
        Duration.between(sampledInstant, Instant.ofEpochMilli(absoluteDeadlineMillis));
    // Cap before converting to nanos: extreme epoch-millisecond deadlines may exceed long nanos.
    long remainingNanos =
        remaining.isNegative()
            ? 0
            : (remaining.compareTo(maximumBudget) > 0 ? maximumBudget : remaining).toNanos();
    Deadline absoluteDeadline = sampledAt.offset(remainingNanos, TimeUnit.NANOSECONDS);
    Deadline selected = maximum.minimum(absoluteDeadline);
    return incoming == null ? selected : selected.minimum(incoming);
  }

  /** Exact Account domain refusal without a capacity commitment. */
  public static final class CapacityRefusedException extends IllegalStateException {
    private final TenantCapacityAdmissionGrpcCodec.Failure code;
    private final UUID durableDenialId;

    private CapacityRefusedException(TenantCapacityAdmissionGrpcCodec.Refusal refusal) {
      super(refusal.code().name());
      this.code = refusal.code();
      this.durableDenialId = refusal.durableDenialId();
    }

    /** Replays a denial after the local owner verifies its exact retained binding. */
    public static CapacityRefusedException retainedAdmissionDenial(Refusal denial) {
      if (denial == null || denial.durableDenialId() == null) {
        throw new IllegalArgumentException("A retained capacity denial requires its decision ID");
      }
      return new CapacityRefusedException(denial);
    }

    public TenantCapacityAdmissionGrpcCodec.Failure code() {
      return code;
    }

    /** The exact Account denial receipt, if the authenticated response carried one. */
    public UUID durableDenialId() {
      return durableDenialId;
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    String target = endpoints.getAccountService();
    return target == null || target.isBlank() ? null : target;
  }

  @Override
  protected String defaultTarget() {
    throw new IllegalStateException("Explicit private Account capacity target is required");
  }

  @Override
  protected boolean channelRetriesEnabled() {
    // Even a trailers-only failure is indeterminate until the exact original is reconciled.
    return false;
  }

  @Override
  protected AccountServiceGrpc.AccountServiceBlockingStub buildStub(ManagedChannel channel) {
    return AccountServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeer));
  }
}
