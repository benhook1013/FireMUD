package net.firedevops.firemud.accountservice.service.impl;

import com.google.protobuf.Timestamp;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AccountActorStagingEligibilityServiceGrpc;
import net.firedevops.firemud.account.v1.ActorStagingEligibilityCurrentness;
import net.firedevops.firemud.account.v1.ActorStagingEligibilityDecision;
import net.firedevops.firemud.account.v1.ActorStagingEligibilityPurpose;
import net.firedevops.firemud.account.v1.ResolvePreseededActorStagingEligibilityRequest;
import net.firedevops.firemud.account.v1.ResolvePreseededActorStagingEligibilityResponse;
import net.firedevops.firemud.accountservice.AccountUuidText;
import net.firedevops.firemud.accountservice.service.AccountActorStagingEligibilityService;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence.Currentness;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence.Decision;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence.Purpose;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.jooq.exception.DataAccessException;
import org.jooq.exception.TooManyRowsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.grpc.server.service.GrpcService;

/** Same-namespace Entity-only mTLS transport for Account staging evidence, disabled by default. */
@GrpcService
@ConditionalOnProperty(
    prefix = "firemud.account.actor-staging-eligibility",
    name = "enabled",
    havingValue = "true")
public class AccountActorStagingEligibilityGrpcService
    extends AccountActorStagingEligibilityServiceGrpc
        .AccountActorStagingEligibilityServiceImplBase {
  private final AccountActorStagingEligibilityService eligibilityService;
  private final String workloadNamespace;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "Fail closed when the required internal owner service is absent.")
  public AccountActorStagingEligibilityGrpcService(
      AccountActorStagingEligibilityService eligibilityService,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.eligibilityService = Objects.requireNonNull(eligibilityService);
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void resolvePreseededActorStagingEligibility(
      ResolvePreseededActorStagingEligibilityRequest request,
      StreamObserver<ResolvePreseededActorStagingEligibilityResponse> responseObserver) {
    if (!isAllowedEntityPeer()) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Verified same-namespace Entity Management workload identity is required");
      return;
    }
    if (request.getSchemaVersion() != AccountActorStagingEligibilityEvidence.SCHEMA_VERSION
        || !workloadNamespace.equals(request.getTargetNamespace())
        || !GrpcPeerIdentity.isValidNamespace(request.getTargetNamespace())
        || !request.getUnknownFields().asMap().isEmpty()) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "The exact actor-staging request schema and Account namespace are required");
      return;
    }

    UUID requestId = AccountUuidText.parseOrNull(request.getRequestId());
    UUID accountUuid = AccountUuidText.parseOrNull(request.getCanonicalAccountId());
    UUID tenantUuid = AccountUuidText.parseOrNull(request.getCanonicalTenantId());
    Purpose purpose = decodePurpose(request.getPurpose());
    if (requestId == null || accountUuid == null || tenantUuid == null || purpose == null) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "Canonical nonnil IDs and public-production staging purpose are required");
      return;
    }

    final AccountActorStagingEligibilityEvidence evidence;
    try {
      evidence =
          eligibilityService.resolve(
              request.getSchemaVersion(),
              request.getTargetNamespace(),
              requestId,
              accountUuid,
              tenantUuid,
              purpose);
    } catch (DataAccessResourceFailureException unavailable) {
      fail(
          responseObserver,
          Status.UNAVAILABLE,
          "Account owner evidence is temporarily unavailable");
      return;
    } catch (IllegalStateException
        | IllegalArgumentException
        | TooManyRowsException invalidOwnerState) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Current Account membership evidence is absent or contradictory");
      return;
    } catch (DataAccessException failure) {
      fail(
          responseObserver,
          hasConnectionFailureSqlState(failure) ? Status.UNAVAILABLE : Status.INTERNAL,
          hasConnectionFailureSqlState(failure)
              ? "Account owner evidence is temporarily unavailable"
              : "Account owner evidence could not be read");
      return;
    } catch (RuntimeException failure) {
      fail(responseObserver, Status.INTERNAL, "Account owner evidence could not be produced");
      return;
    }

    responseObserver.onNext(toResponse(evidence));
    responseObserver.onCompleted();
  }

  private boolean isAllowedEntityPeer() {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      return false;
    }
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null
        && workloadNamespace.equals(peer.namespace())
        && peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/entity-management-service");
  }

  private static Purpose decodePurpose(ActorStagingEligibilityPurpose purpose) {
    return purpose == ActorStagingEligibilityPurpose.PUBLIC_PRODUCTION_STAGING_ONLY
        ? Purpose.PUBLIC_PRODUCTION_STAGING_ONLY
        : null;
  }

  private static ResolvePreseededActorStagingEligibilityResponse toResponse(
      AccountActorStagingEligibilityEvidence evidence) {
    return ResolvePreseededActorStagingEligibilityResponse.newBuilder()
        .setSchemaVersion(evidence.schemaVersion())
        .setTargetNamespace(evidence.targetNamespace())
        .setRequestId(evidence.requestId().toString())
        .setCanonicalAccountId(evidence.canonicalAccountId().toString())
        .setCanonicalTenantId(evidence.canonicalTenantId().toString())
        .setPurpose(ActorStagingEligibilityPurpose.PUBLIC_PRODUCTION_STAGING_ONLY)
        .setCurrentness(toWireCurrentness(evidence.currentness()))
        .setObservedAt(timestamp(evidence.observedAt()))
        .setDecision(toWireDecision(evidence.decision()))
        .setAccountUuidProvenance(evidence.accountUuidProvenance())
        .setAccountLifecycleState(evidence.accountLifecycleState())
        .setMembershipLifecycleState(evidence.membershipLifecycleState())
        .setGameplayAdmissionAllowed(evidence.gameplayAdmissionAllowed())
        .setMembershipAuthorityProvenance(evidence.membershipAuthorityProvenance())
        .setMembershipVersion(evidence.membershipVersion())
        .setMembershipAuthorityGeneration(evidence.membershipAuthorityGeneration())
        .setTenantProvenanceKind(evidence.tenantProvenanceKind())
        .setTenantSourceOperationId(evidence.tenantSourceOperationId().toString())
        .setTenantProvenanceDigest(evidence.tenantProvenanceDigest())
        .setMembershipEventSequence(evidence.membershipEventSequence())
        .setMembershipEventId(evidence.membershipEventId().toString())
        .setMembershipEventDigest(evidence.membershipEventDigest())
        .setLastTransitionInvalidated(evidence.lastTransitionInvalidated())
        .setEligibilityDecisionDigest(evidence.eligibilityDecisionDigest())
        .setAuthoritySnapshotDigest(evidence.authoritySnapshotDigest())
        .build();
  }

  private static ActorStagingEligibilityCurrentness toWireCurrentness(Currentness currentness) {
    if (currentness == Currentness.CURRENT_AT_REVALIDATION) {
      return ActorStagingEligibilityCurrentness.CURRENT_AT_REVALIDATION;
    }
    throw new IllegalStateException("Account produced unsupported actor-staging currentness");
  }

  private static ActorStagingEligibilityDecision toWireDecision(Decision decision) {
    return switch (decision) {
      case STAGING_ELIGIBLE -> ActorStagingEligibilityDecision.STAGING_ELIGIBLE;
      case STAGING_INELIGIBLE -> ActorStagingEligibilityDecision.STAGING_INELIGIBLE;
    };
  }

  private static Timestamp timestamp(java.time.Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  private static void fail(
      StreamObserver<ResolvePreseededActorStagingEligibilityResponse> responseObserver,
      Status status,
      String description) {
    responseObserver.onError(status.withDescription(description).asRuntimeException());
  }

  private static boolean hasConnectionFailureSqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException exception) {
        String sqlState = exception.getSQLState();
        if (sqlState != null && sqlState.startsWith("08")) {
          return true;
        }
      }
    }
    return false;
  }
}
