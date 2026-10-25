package net.firedevops.firemud.common.gamesession;

import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOwnerProofOutcome;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofResponse;

/** Closed Game Session producer codec for the exact World initial-admission owner tuple. */
public final class CanonicalInitialAdmissionOwnerGrpcCodec {
  private static final long MIN_TIMESTAMP_SECONDS = -62_135_596_800L;
  private static final long MAX_TIMESTAMP_SECONDS = 253_402_300_799L;

  private CanonicalInitialAdmissionOwnerGrpcCodec() {}

  public static GetCanonicalInitialAdmissionOwnerProofRequest toRequest(
      HoldIdentity expectedIdentity) {
    Objects.requireNonNull(expectedIdentity, "expectedIdentity");
    Request request = expectedIdentity.request();
    var builder =
        GetCanonicalInitialAdmissionOwnerProofRequest.newBuilder()
            .setTargetNamespace(request.targetNamespace())
            .setInitialAdmissionRequestId(request.initialAdmissionRequestId())
            .setInitialAdmissionRequestDigest(request.initialAdmissionRequestDigest())
            .setCanonicalTenantId(request.canonicalTenantId().toString())
            .setWorldSlug(request.worldSlug())
            .setRealmId(request.realmId().toString())
            .setPlayableStateNamespaceId(request.playableStateNamespaceId().toString())
            .setPlayableStateScope(request.playableStateScope())
            .setCanonicalGameInstanceId(request.canonicalGameInstanceId().toString())
            .setCanonicalVersionId(request.canonicalVersionId().toString())
            .setActiveLifecycleEpoch(request.activeLifecycleEpoch())
            .setExpectedCatalogRevision(request.expectedCatalogRevision())
            .setOrigin(toWireOrigin(request.initialAdmissionOrigin()))
            .setHoldId(expectedIdentity.holdId().toString())
            .setHoldFence(expectedIdentity.holdFence().toString())
            .setHoldBindingDigest(expectedIdentity.holdBindingDigest());
    if (request.expectedPriorPointerVersion() != null) {
      builder.setExpectedPriorPointerVersion(request.expectedPriorPointerVersion());
    }
    return builder.build();
  }

  /** Parses only an exact response for this hold and returns its canonical closed owner proof. */
  public static GameSessionCanonicalInitialAdmissionOwnerProof requireResponse(
      HoldIdentity expectedIdentity, GetCanonicalInitialAdmissionOwnerProofResponse response) {
    Objects.requireNonNull(expectedIdentity, "expectedIdentity");
    Objects.requireNonNull(response, "response");
    if (!response.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Owner proof response contains unknown fields");
    }
    if (response.hasTerminalAt()
        && !response.getTerminalAt().getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Owner proof terminal time contains unknown fields");
    }

    GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome =
        fromWireOutcome(response.getOutcome());
    Instant terminalAt =
        response.hasTerminalAt() ? canonicalInstant(response.getTerminalAt()) : null;
    if (outcome != GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING
        && terminalAt == null) {
      throw new IllegalArgumentException("Terminal owner proof is missing terminal time");
    }
    GameSessionCanonicalInitialAdmissionOwnerProof proof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            expectedIdentity,
            outcome,
            response.hasCommittedPointerVersion() ? response.getCommittedPointerVersion() : null,
            response.hasAuditEventId() ? response.getAuditEventId() : null,
            response.getProofDigest().isEmpty() ? null : response.getProofDigest(),
            response.getPositiveDurableAbort(),
            terminalAt);
    proof =
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(
            GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof));
    GetCanonicalInitialAdmissionOwnerProofResponse canonicalResponse =
        toResponse(expectedIdentity, proof);
    if (!canonicalResponse.equals(response)) {
      throw new IllegalArgumentException(
          "Owner proof response changed the exact echoed tuple or canonical result fields");
    }
    return proof;
  }

  public static HoldIdentity parseRequest(GetCanonicalInitialAdmissionOwnerProofRequest request) {
    Objects.requireNonNull(request, "request");
    if (!request.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Owner proof request contains unknown fields");
    }
    Request identityRequest =
        new Request(
            request.getTargetNamespace(),
            canonicalUuid(request.getCanonicalTenantId(), "canonical_tenant_id"),
            request.getWorldSlug(),
            canonicalUuid(request.getRealmId(), "realm_id"),
            canonicalUuid(request.getPlayableStateNamespaceId(), "playable_state_namespace_id"),
            request.getPlayableStateScope(),
            canonicalUuid(request.getCanonicalGameInstanceId(), "canonical_game_instance_id"),
            canonicalUuid(request.getCanonicalVersionId(), "canonical_version_id"),
            request.getActiveLifecycleEpoch(),
            request.getInitialAdmissionRequestId(),
            request.getInitialAdmissionRequestDigest(),
            fromWireOrigin(request.getOrigin()),
            request.getExpectedCatalogRevision(),
            request.hasExpectedPriorPointerVersion()
                ? request.getExpectedPriorPointerVersion()
                : null);
    HoldIdentity identity =
        new HoldIdentity(
            identityRequest,
            canonicalUuid(request.getHoldId(), "hold_id"),
            canonicalUuid(request.getHoldFence(), "hold_fence"));
    if (!identity.holdBindingDigest().equals(request.getHoldBindingDigest())) {
      throw new IllegalArgumentException("hold_binding_digest does not bind the complete request");
    }
    return identity;
  }

  public static GetCanonicalInitialAdmissionOwnerProofResponse toResponse(
      HoldIdentity expectedIdentity, GameSessionCanonicalInitialAdmissionOwnerProof ownerProof) {
    Objects.requireNonNull(expectedIdentity, "expectedIdentity");
    Objects.requireNonNull(ownerProof, "ownerProof");
    if (!expectedIdentity.equals(ownerProof.holdIdentity())) {
      throw new IllegalArgumentException("Owner proof changed the requested hold identity");
    }
    GameSessionCanonicalInitialAdmissionOwnerProof proof =
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(
            GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(ownerProof));
    if (proof.outcome() == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED) {
      requireExactNextPointerVersion(expectedIdentity, proof.committedPointerVersion());
    }

    Request request = expectedIdentity.request();
    var builder =
        GetCanonicalInitialAdmissionOwnerProofResponse.newBuilder()
            .setTargetNamespace(request.targetNamespace())
            .setInitialAdmissionRequestId(request.initialAdmissionRequestId())
            .setInitialAdmissionRequestDigest(request.initialAdmissionRequestDigest())
            .setCanonicalTenantId(request.canonicalTenantId().toString())
            .setWorldSlug(request.worldSlug())
            .setRealmId(request.realmId().toString())
            .setPlayableStateNamespaceId(request.playableStateNamespaceId().toString())
            .setPlayableStateScope(request.playableStateScope())
            .setCanonicalGameInstanceId(request.canonicalGameInstanceId().toString())
            .setCanonicalVersionId(request.canonicalVersionId().toString())
            .setActiveLifecycleEpoch(request.activeLifecycleEpoch())
            .setExpectedCatalogRevision(request.expectedCatalogRevision())
            .setOrigin(toWireOrigin(request.initialAdmissionOrigin()))
            .setHoldId(expectedIdentity.holdId().toString())
            .setHoldFence(expectedIdentity.holdFence().toString())
            .setHoldBindingDigest(expectedIdentity.holdBindingDigest())
            .setOutcome(toWireOutcome(proof.outcome()));
    if (request.expectedPriorPointerVersion() != null) {
      builder.setExpectedPriorPointerVersion(request.expectedPriorPointerVersion());
    }
    if (proof.committedPointerVersion() != null) {
      builder.setCommittedPointerVersion(proof.committedPointerVersion());
    }
    if (proof.auditEventId() != null) {
      builder.setAuditEventId(proof.auditEventId());
    }
    if (proof.proofDigest() != null) {
      builder.setProofDigest(proof.proofDigest());
    }
    if (proof.terminalAt() != null) {
      builder.setTerminalAt(
          Timestamp.newBuilder()
              .setSeconds(proof.terminalAt().getEpochSecond())
              .setNanos(proof.terminalAt().getNano())
              .build());
    }
    builder.setPositiveDurableAbort(proof.positiveDurableAbort());
    return builder.build();
  }

  private static void requireExactNextPointerVersion(HoldIdentity identity, Long actualVersion) {
    long expectedVersion;
    try {
      expectedVersion =
          switch (identity.request().initialAdmissionOrigin()) {
            case NO_PRIOR_POINTER -> 1L;
            case EXPECT_CLOSED ->
                Math.addExact(identity.request().expectedPriorPointerVersion(), 1L);
          };
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(
          "Expected prior pointer version cannot advance without overflow", overflow);
    }
    if (actualVersion == null || actualVersion != expectedVersion) {
      throw new IllegalArgumentException(
          "COMMITTED owner proof does not name the exact next pointer version");
    }
  }

  private static UUID canonicalUuid(String value, String field) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw new IllegalArgumentException(field + " must use canonical UUID text");
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(field + " must use canonical UUID text", invalid);
    }
  }

  private static InitialAdmissionOrigin fromWireOrigin(
      net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOrigin origin) {
    return switch (origin) {
      case CANONICAL_INITIAL_ADMISSION_ORIGIN_NO_PRIOR_POINTER ->
          InitialAdmissionOrigin.NO_PRIOR_POINTER;
      case CANONICAL_INITIAL_ADMISSION_ORIGIN_EXPECT_CLOSED -> InitialAdmissionOrigin.EXPECT_CLOSED;
      case CANONICAL_INITIAL_ADMISSION_ORIGIN_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("origin is unsupported");
    };
  }

  private static net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOrigin toWireOrigin(
      InitialAdmissionOrigin origin) {
    return switch (origin) {
      case NO_PRIOR_POINTER ->
          net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOrigin
              .CANONICAL_INITIAL_ADMISSION_ORIGIN_NO_PRIOR_POINTER;
      case EXPECT_CLOSED ->
          net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOrigin
              .CANONICAL_INITIAL_ADMISSION_ORIGIN_EXPECT_CLOSED;
    };
  }

  private static CanonicalInitialAdmissionOwnerProofOutcome toWireOutcome(
      GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome) {
    return switch (outcome) {
      case PENDING ->
          CanonicalInitialAdmissionOwnerProofOutcome
              .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_PENDING;
      case COMMITTED ->
          CanonicalInitialAdmissionOwnerProofOutcome
              .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_COMMITTED;
      case ABORTED ->
          CanonicalInitialAdmissionOwnerProofOutcome
              .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_ABORTED;
    };
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof.Outcome fromWireOutcome(
      CanonicalInitialAdmissionOwnerProofOutcome outcome) {
    return switch (outcome) {
      case CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_PENDING ->
          GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING;
      case CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_COMMITTED ->
          GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED;
      case CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_ABORTED ->
          GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED;
      case CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("Owner proof outcome is unsupported");
    };
  }

  private static Instant canonicalInstant(Timestamp timestamp) {
    long seconds = timestamp.getSeconds();
    int nanos = timestamp.getNanos();
    if (seconds < MIN_TIMESTAMP_SECONDS
        || seconds > MAX_TIMESTAMP_SECONDS
        || nanos < 0
        || nanos >= 1_000_000_000) {
      throw new IllegalArgumentException("Owner proof terminal time is not a canonical Timestamp");
    }
    Instant instant = Instant.ofEpochSecond(seconds, nanos);
    if (instant.getEpochSecond() != seconds || instant.getNano() != nanos) {
      throw new IllegalArgumentException("Owner proof terminal time is not canonical");
    }
    return instant;
  }
}
