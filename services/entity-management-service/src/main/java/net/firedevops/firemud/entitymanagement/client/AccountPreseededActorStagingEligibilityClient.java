package net.firedevops.firemud.entitymanagement.client;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountActorStagingEligibilityServiceGrpc;
import net.firedevops.firemud.account.v1.ActorStagingEligibilityCurrentness;
import net.firedevops.firemud.account.v1.ActorStagingEligibilityDecision;
import net.firedevops.firemud.account.v1.ActorStagingEligibilityPurpose;
import net.firedevops.firemud.account.v1.ResolvePreseededActorStagingEligibilityRequest;
import net.firedevops.firemud.account.v1.ResolvePreseededActorStagingEligibilityResponse;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.entitymanagement.service.PreseededActorStagingEligibilityPort;

/** Read-only Entity Management client for non-admitting Account actor-staging evidence. */
public final class AccountPreseededActorStagingEligibilityClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountActorStagingEligibilityServiceGrpc.AccountActorStagingEligibilityServiceBlockingStub>
    implements PreseededActorStagingEligibilityPort {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final long MIN_PROTO_TIMESTAMP_SECONDS = -62_135_596_800L;
  private static final long MAX_PROTO_TIMESTAMP_SECONDS = 253_402_300_799L;
  private static final int MAX_PROTO_TIMESTAMP_NANOS = 999_999_999;

  private final String workloadNamespace;

  public AccountPreseededActorStagingEligibilityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireEntityManagementMtls(tlsProps),
        channelFactory,
        AccountPreseededActorStagingEligibilityClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException(
          "Entity Management workload namespace must be one DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes the client only when an explicit caller owns activation of this handoff. */
  public void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getAccountService();
  }

  @Override
  protected String defaultTarget() {
    return "account-service:6565";
  }

  @Override
  protected AccountActorStagingEligibilityServiceGrpc
          .AccountActorStagingEligibilityServiceBlockingStub
      buildStub(ManagedChannel channel) {
    return AccountActorStagingEligibilityServiceGrpc.newBlockingStub(channel)
        .withInterceptors(
            new GrpcServerPeerIdentityClientInterceptor(
                "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service"))
        .withCompression("gzip");
  }

  /** Reads current Account staging eligibility without granting gameplay admission authority. */
  @Override
  public AccountActorStagingEligibilityEvidence resolvePreseededActorStagingEligibility(
      String canonicalAccountId, String canonicalTenantId, String requestId) {
    UUID accountUuid = parseCanonicalNonNilUuid(canonicalAccountId, "canonical account ID");
    UUID tenantUuid = parseCanonicalNonNilUuid(canonicalTenantId, "canonical tenant ID");
    UUID requestUuid = parseCanonicalNonNilUuid(requestId, "request ID");
    AccountActorStagingEligibilityServiceGrpc.AccountActorStagingEligibilityServiceBlockingStub
        currentStub = stub();
    if (currentStub == null) {
      throw new IllegalStateException(
          "Account actor-staging eligibility client is not initialized");
    }

    ResolvePreseededActorStagingEligibilityResponse response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .resolvePreseededActorStagingEligibility(
                ResolvePreseededActorStagingEligibilityRequest.newBuilder()
                    .setSchemaVersion(AccountActorStagingEligibilityEvidence.SCHEMA_VERSION)
                    .setTargetNamespace(workloadNamespace)
                    .setRequestId(requestUuid.toString())
                    .setCanonicalAccountId(accountUuid.toString())
                    .setCanonicalTenantId(tenantUuid.toString())
                    .setPurpose(ActorStagingEligibilityPurpose.PUBLIC_PRODUCTION_STAGING_ONLY)
                    .build());
    if (response == null) {
      throw new IllegalStateException("Account actor-staging eligibility response is absent");
    }
    if (!response.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalStateException(
          "Account actor-staging eligibility response contains unsupported fields");
    }
    if (!response.hasObservedAt()) {
      throw new IllegalStateException(
          "Account actor-staging eligibility response timestamp is absent");
    }

    AccountActorStagingEligibilityEvidence evidence;
    try {
      evidence =
          new AccountActorStagingEligibilityEvidence(
              response.getSchemaVersion(),
              response.getTargetNamespace(),
              parseCanonicalNonNilUuid(response.getRequestId(), "response request ID"),
              parseCanonicalNonNilUuid(
                  response.getCanonicalAccountId(), "response canonical account ID"),
              parseCanonicalNonNilUuid(
                  response.getCanonicalTenantId(), "response canonical tenant ID"),
              decodePurpose(response.getPurpose()),
              decodeCurrentness(response.getCurrentness()),
              parseObservedAt(response.getObservedAt()),
              decodeDecision(response.getDecision()),
              response.getAccountUuidProvenance(),
              response.getAccountLifecycleState(),
              response.getMembershipLifecycleState(),
              response.getGameplayAdmissionAllowed(),
              response.getMembershipAuthorityProvenance(),
              response.getMembershipVersion(),
              response.getMembershipAuthorityGeneration(),
              response.getTenantProvenanceKind(),
              parseCanonicalNonNilUuid(
                  response.getTenantSourceOperationId(), "tenant source operation ID"),
              response.getTenantProvenanceDigest(),
              response.getMembershipEventSequence(),
              parseCanonicalNonNilUuid(response.getMembershipEventId(), "membership event ID"),
              response.getMembershipEventDigest(),
              response.getLastTransitionInvalidated(),
              response.getEligibilityDecisionDigest(),
              response.getAuthoritySnapshotDigest());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Account actor-staging eligibility response is invalid", exception);
    }

    if (!workloadNamespace.equals(evidence.targetNamespace())
        || !requestUuid.equals(evidence.requestId())
        || !accountUuid.equals(evidence.canonicalAccountId())
        || !tenantUuid.equals(evidence.canonicalTenantId())) {
      throw new IllegalStateException(
          "Account actor-staging eligibility response does not match the exact request");
    }
    return evidence;
  }

  private static AccountActorStagingEligibilityEvidence.Purpose decodePurpose(
      ActorStagingEligibilityPurpose purpose) {
    if (purpose == ActorStagingEligibilityPurpose.PUBLIC_PRODUCTION_STAGING_ONLY) {
      return AccountActorStagingEligibilityEvidence.Purpose.PUBLIC_PRODUCTION_STAGING_ONLY;
    }
    throw new IllegalArgumentException("Unknown Account actor-staging eligibility purpose");
  }

  private static Instant parseObservedAt(Timestamp observedAt) {
    if (!observedAt.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(
          "Account actor-staging eligibility timestamp contains unsupported fields");
    }
    long seconds = observedAt.getSeconds();
    int nanos = observedAt.getNanos();
    if (seconds < MIN_PROTO_TIMESTAMP_SECONDS
        || seconds > MAX_PROTO_TIMESTAMP_SECONDS
        || nanos < 0
        || nanos > MAX_PROTO_TIMESTAMP_NANOS) {
      throw new IllegalArgumentException(
          "Account actor-staging eligibility timestamp is outside protobuf bounds");
    }
    return Instant.ofEpochSecond(seconds, nanos);
  }

  private static AccountActorStagingEligibilityEvidence.Currentness decodeCurrentness(
      ActorStagingEligibilityCurrentness currentness) {
    if (currentness == ActorStagingEligibilityCurrentness.CURRENT_AT_REVALIDATION) {
      return AccountActorStagingEligibilityEvidence.Currentness.CURRENT_AT_REVALIDATION;
    }
    throw new IllegalArgumentException("Unknown Account actor-staging eligibility currentness");
  }

  private static AccountActorStagingEligibilityEvidence.Decision decodeDecision(
      ActorStagingEligibilityDecision decision) {
    return switch (decision) {
      case STAGING_ELIGIBLE -> AccountActorStagingEligibilityEvidence.Decision.STAGING_ELIGIBLE;
      case STAGING_INELIGIBLE -> AccountActorStagingEligibilityEvidence.Decision.STAGING_INELIGIBLE;
      default -> throw new IllegalArgumentException("Unknown Account actor-staging decision");
    };
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required", exception);
    }
    if (new UUID(0L, 0L).equals(parsed) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required");
    }
    return parsed;
  }

  private static CommonGrpcClientProperties requireEntityManagementMtls(
      CommonGrpcClientProperties tlsProps) {
    if (tlsProps == null) {
      throw new IllegalArgumentException("Entity Management gRPC TLS configuration is required");
    }
    if (tlsProps.isPlaintext()) {
      throw new IllegalArgumentException(
          "Actor-staging eligibility reads require Entity Management workload mTLS");
    }
    if (!hasText(tlsProps.getCertChain())
        || !hasText(tlsProps.getPrivateKey())
        || !hasText(tlsProps.getCaCert())) {
      throw new IllegalArgumentException(
          "Actor-staging eligibility reads require Entity Management certificate, key, and CA "
              + "files");
    }
    if (tlsProps.getCertChain().trim().startsWith("classpath:")
        || tlsProps.getPrivateKey().trim().startsWith("classpath:")
        || tlsProps.getCaCert().trim().startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Actor-staging eligibility reads require file-backed Entity Management workload mTLS");
    }
    return tlsProps;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
