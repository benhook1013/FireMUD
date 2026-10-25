package net.firedevops.firemud.accountservice.client;

import com.google.protobuf.ByteString;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.grpc.GrpcTlsMaterialResolver;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceRequest;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceResponse;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidencePurpose;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidenceServiceGrpc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reads and validates Logging/Admin's current StartSession reservation claim snapshot.
 *
 * <p>The result is fresh read evidence only. It does not authorize a mutation and is not a
 * commit-spanning compare-and-set proof.
 */
@Component
@ConditionalOnProperty(
    prefix = "firemud.account.start-session-operator-authorization",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
public final class StartSessionReservationEvidenceClient
    extends AbstractReloadingBlockingGrpcClient<
        StartSessionReservationEvidenceServiceGrpc
            .StartSessionReservationEvidenceServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final String expectedLoggingPeerUri;
  private final Clock clock;
  private final CommonGrpcClientProperties transportTlsProperties;

  @Autowired
  public StartSessionReservationEvidenceClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this(endpoints, tlsProperties, channelFactory, workloadNamespace, Clock.systemUTC());
  }

  StartSessionReservationEvidenceClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace,
      Clock clock) {
    super(endpoints, tlsProperties, channelFactory, StartSessionReservationEvidenceClient.class);
    this.expectedLoggingPeerUri = loggingAdminServerUri(workloadNamespace);
    this.clock = Objects.requireNonNull(clock, "clock is required");
    this.transportTlsProperties =
        Objects.requireNonNull(tlsProperties, "TLS properties are required").copy();
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getLoggingAdminService();
  }

  @Override
  protected String defaultTarget() {
    return "logging-admin-service:6565";
  }

  @Override
  protected StartSessionReservationEvidenceServiceGrpc
          .StartSessionReservationEvidenceServiceBlockingStub
      buildStub(io.grpc.ManagedChannel channel) {
    return StartSessionReservationEvidenceServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedLoggingPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedLoggingPeerUri))
        .withCompression("gzip");
  }

  /** Reads the exact active claim for ISSUE or RECOVER and validates every returned binding. */
  public ReadCurrentClaimEvidenceResponse readCurrentClaimEvidence(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID currentClaimOwnerId,
      long currentClaimFence,
      Purpose purpose) {
    requireWorkloadOnlyContext();
    Objects.requireNonNull(tuple, "reservation tuple is required");
    requireNonNilUuid(reservationOwnerId, "reservationOwnerId");
    requireNonNilUuid(currentClaimOwnerId, "currentClaimOwnerId");
    Objects.requireNonNull(purpose, "read purpose is required");
    if (reservationClaimFence <= 0L || currentClaimFence <= 0L) {
      throw new IllegalArgumentException("reservation claim fences must be positive");
    }
    requirePurposeOwnership(
        purpose, reservationOwnerId, reservationClaimFence, currentClaimOwnerId, currentClaimFence);

    byte[] tupleBytes = tuple.canonicalJson().getBytes(StandardCharsets.UTF_8);
    ReadCurrentClaimEvidenceRequest request =
        ReadCurrentClaimEvidenceRequest.newBuilder()
            .setControlPlaneRequestId(tuple.controlPlaneRequestId())
            .setPreAuthorizationTupleJson(ByteString.copyFrom(tupleBytes))
            .setReservationOwnerId(reservationOwnerId.toString())
            .setReservationClaimFence(reservationClaimFence)
            .setClaimOwnerId(currentClaimOwnerId.toString())
            .setClaimFence(currentClaimFence)
            .setPurpose(toWirePurpose(purpose))
            .build();

    requireWorkloadMtls();
    var currentStub = stub();
    if (currentStub == null) {
      throw new IllegalStateException("Logging/Admin reservation evidence client is unavailable");
    }
    ReadCurrentClaimEvidenceResponse response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readCurrentClaimEvidence(request);
    validateResponse(
        response,
        tuple,
        tupleBytes,
        reservationOwnerId,
        reservationClaimFence,
        currentClaimOwnerId,
        currentClaimFence,
        purpose);
    return response;
  }

  static String loggingAdminServerUri(String targetNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("StartSession target namespace is invalid");
    }
    return "spiffe://firemud/ns/" + targetNamespace + "/sa/logging-admin-service";
  }

  private void requireWorkloadMtls() {
    if (transportTlsProperties.isPlaintext()) {
      throw new IllegalStateException("Logging/Admin reservation evidence requires workload mTLS");
    }
    try {
      var material = new GrpcTlsMaterialResolver().resolve(transportTlsProperties);
      if (material == null) {
        throw new IllegalStateException("Missing workload TLS material");
      }
      try (var certificate = material.certChain().openStream();
          var privateKey = material.privateKey().openStream();
          var ca = material.caCert().openStream()) {
        if (certificate.read() < 0 || privateKey.read() < 0 || ca.read() < 0) {
          throw new IllegalStateException("Empty workload TLS material");
        }
      }
    } catch (IOException | RuntimeException unavailable) {
      throw new IllegalStateException(
          "Logging/Admin reservation evidence requires usable workload mTLS", unavailable);
    }
  }

  private static void requireWorkloadOnlyContext() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Logging/Admin reservation evidence requires no ambient SQL or synchronization");
    }
    if (SessionContext.hasAuthenticatedCallerContext()) {
      throw new IllegalStateException(
          "Logging/Admin reservation evidence requires a workload-only caller context");
    }
  }

  private void validateResponse(
      ReadCurrentClaimEvidenceResponse response,
      StartSessionPreAuthorizationReservationTuple tuple,
      byte[] tupleBytes,
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID currentClaimOwnerId,
      long currentClaimFence,
      Purpose purpose) {
    long localNowEpochMillis = clock.millis();
    if (!response.getUnknownFields().asMap().isEmpty()
        || !tuple.controlPlaneRequestId().equals(response.getControlPlaneRequestId())
        || !Arrays.equals(tupleBytes, response.getPreAuthorizationTupleJson().toByteArray())
        || !tuple.mutationDigest().equals(response.getMutationDigest())
        || !reservationOwnerId.toString().equals(response.getReservationOwnerId())
        || response.getReservationClaimFence() != reservationClaimFence
        || !currentClaimOwnerId.toString().equals(response.getClaimOwnerId())
        || response.getClaimFence() != currentClaimFence
        || response.getPurpose() != toWirePurpose(purpose)
        || response.getObservedAtEpochMillis() <= 0L
        || response.getClaimExpiresAtEpochMillis() <= response.getObservedAtEpochMillis()
        || response.getClaimExpiresAtEpochMillis() <= localNowEpochMillis) {
      throw new IllegalStateException(
          "Logging/Admin returned stale or mismatched StartSession reservation evidence");
    }
  }

  private static void requirePurposeOwnership(
      Purpose purpose,
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID currentClaimOwnerId,
      long currentClaimFence) {
    if (purpose == Purpose.ISSUE
        && (!reservationOwnerId.equals(currentClaimOwnerId)
            || reservationClaimFence != currentClaimFence)) {
      throw new IllegalArgumentException("ISSUE requires the original reservation owner and fence");
    }
    if (purpose == Purpose.RECOVER
        && (reservationOwnerId.equals(currentClaimOwnerId)
            || currentClaimFence <= reservationClaimFence)) {
      throw new IllegalArgumentException("RECOVER requires a fresh recovery owner and fence");
    }
  }

  private static void requireNonNilUuid(UUID value, String fieldName) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(fieldName + " must be a canonical nonnil UUID");
    }
  }

  private static StartSessionReservationEvidencePurpose toWirePurpose(Purpose purpose) {
    return switch (purpose) {
      case ISSUE ->
          StartSessionReservationEvidencePurpose.START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE;
      case RECOVER ->
          StartSessionReservationEvidencePurpose.START_SESSION_RESERVATION_EVIDENCE_PURPOSE_RECOVER;
    };
  }

  public enum Purpose {
    ISSUE,
    RECOVER
  }
}
