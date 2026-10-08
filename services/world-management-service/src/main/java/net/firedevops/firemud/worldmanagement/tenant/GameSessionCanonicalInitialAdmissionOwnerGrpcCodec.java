package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOrigin;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOwnerProofOutcome;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofResponse;

/** Closed wire codec for reading Game Session's exact canonical initial-admission owner proof. */
public final class GameSessionCanonicalInitialAdmissionOwnerGrpcCodec {
  private static final long MIN_PROTOBUF_TIMESTAMP_SECONDS = -62_135_596_800L;
  private static final long MAX_PROTOBUF_TIMESTAMP_SECONDS = 253_402_300_799L;

  private GameSessionCanonicalInitialAdmissionOwnerGrpcCodec() {}

  public static GetCanonicalInitialAdmissionOwnerProofRequest toRequest(HoldIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    Request request = identity.request();
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
            .setHoldId(identity.holdId().toString())
            .setHoldFence(identity.holdFence().toString())
            .setHoldBindingDigest(identity.holdBindingDigest());
    if (request.expectedPriorPointerVersion() != null) {
      builder.setExpectedPriorPointerVersion(request.expectedPriorPointerVersion());
    }
    return builder.build();
  }

  public static GameSessionCanonicalInitialAdmissionOwnerProof fromResponse(
      HoldIdentity expectedIdentity,
      GameSessionCanonicalInitialAdmissionOwnerProof.Outcome expectedOutcome,
      GetCanonicalInitialAdmissionOwnerProofResponse response) {
    Objects.requireNonNull(expectedIdentity, "expectedIdentity");
    Objects.requireNonNull(expectedOutcome, "expectedOutcome");
    Objects.requireNonNull(response, "response");
    if (expectedOutcome == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING) {
      throw invalid("PENDING is not a terminal owner-proof selection");
    }
    if (!response.getUnknownFields().asMap().isEmpty()) {
      throw invalid("Game Session owner proof response contains unknown fields");
    }

    requireExactIdentityEcho(expectedIdentity, response);
    GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome =
        fromWireOutcome(response.getOutcome());
    if (outcome == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING
        || outcome != expectedOutcome) {
      throw invalid("Game Session returned no proof for the exact requested terminal outcome");
    }

    Long committedPointerVersion =
        response.hasCommittedPointerVersion() ? response.getCommittedPointerVersion() : null;
    Long auditEventId = response.hasAuditEventId() ? response.getAuditEventId() : null;
    if (outcome == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED) {
      if (committedPointerVersion == null || auditEventId == null) {
        throw invalid("COMMITTED owner proof requires pointer and audit fields");
      }
      requireExactNextPointerVersion(expectedIdentity, committedPointerVersion);
      if (response.getPositiveDurableAbort()) {
        throw invalid("COMMITTED owner proof cannot assert durable abort");
      }
    } else if (committedPointerVersion != null
        || auditEventId != null
        || !response.getPositiveDurableAbort()) {
      throw invalid("ABORTED owner proof requires positive fencing without commit fields");
    }

    Instant terminalAt = toInstant(response);
    try {
      GameSessionCanonicalInitialAdmissionOwnerProof proof =
          new GameSessionCanonicalInitialAdmissionOwnerProof(
              expectedIdentity,
              outcome,
              committedPointerVersion,
              auditEventId,
              response.getProofDigest(),
              response.getPositiveDurableAbort(),
              terminalAt);
      return GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(
          GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof));
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "Game Session owner proof terminal fields are invalid", malformed);
    }
  }

  private static void requireExactIdentityEcho(
      HoldIdentity expectedIdentity, GetCanonicalInitialAdmissionOwnerProofResponse response) {
    Request expected = expectedIdentity.request();
    if (!expected.targetNamespace().equals(response.getTargetNamespace())
        || !expected.initialAdmissionRequestId().equals(response.getInitialAdmissionRequestId())
        || !expected
            .initialAdmissionRequestDigest()
            .equals(response.getInitialAdmissionRequestDigest())
        || !expected.canonicalTenantId().toString().equals(response.getCanonicalTenantId())
        || !expected.worldSlug().equals(response.getWorldSlug())
        || !expected.realmId().toString().equals(response.getRealmId())
        || !expected
            .playableStateNamespaceId()
            .toString()
            .equals(response.getPlayableStateNamespaceId())
        || !expected.playableStateScope().equals(response.getPlayableStateScope())
        || !expected
            .canonicalGameInstanceId()
            .toString()
            .equals(response.getCanonicalGameInstanceId())
        || !expected.canonicalVersionId().toString().equals(response.getCanonicalVersionId())
        || expected.activeLifecycleEpoch() != response.getActiveLifecycleEpoch()
        || expected.expectedCatalogRevision() != response.getExpectedCatalogRevision()
        || toWireOrigin(expected.initialAdmissionOrigin()) != response.getOrigin()
        || !expectedIdentity.holdId().toString().equals(response.getHoldId())
        || !expectedIdentity.holdFence().toString().equals(response.getHoldFence())
        || !expectedIdentity.holdBindingDigest().equals(response.getHoldBindingDigest())) {
      throw invalid("Game Session owner proof response changed the requested hold identity");
    }
    if (expected.expectedPriorPointerVersion() == null) {
      if (response.hasExpectedPriorPointerVersion()) {
        throw invalid("Game Session owner proof added an absent prior-pointer version");
      }
    } else if (!response.hasExpectedPriorPointerVersion()
        || expected.expectedPriorPointerVersion() != response.getExpectedPriorPointerVersion()) {
      throw invalid("Game Session owner proof changed the expected prior-pointer version");
    }
  }

  private static void requireExactNextPointerVersion(
      HoldIdentity identity, long committedPointerVersion) {
    long expectedPointerVersion;
    try {
      expectedPointerVersion =
          switch (identity.request().initialAdmissionOrigin()) {
            case NO_PRIOR_POINTER -> 1L;
            case EXPECT_CLOSED ->
                Math.addExact(identity.request().expectedPriorPointerVersion(), 1L);
          };
    } catch (ArithmeticException overflow) {
      throw invalid("Expected prior pointer version cannot advance without overflow", overflow);
    }
    if (committedPointerVersion != expectedPointerVersion) {
      throw invalid("COMMITTED owner proof does not name the exact next pointer version");
    }
  }

  private static Instant toInstant(GetCanonicalInitialAdmissionOwnerProofResponse response) {
    if (!response.hasTerminalAt()) {
      throw invalid("Terminal owner proof requires a timestamp");
    }
    Timestamp timestamp = response.getTerminalAt();
    long seconds = timestamp.getSeconds();
    int nanos = timestamp.getNanos();
    if (!timestamp.getUnknownFields().asMap().isEmpty()) {
      throw invalid("Terminal owner proof timestamp contains unknown fields");
    }
    if (seconds < MIN_PROTOBUF_TIMESTAMP_SECONDS || seconds > MAX_PROTOBUF_TIMESTAMP_SECONDS) {
      throw invalid("Terminal owner proof timestamp is outside the protobuf range");
    }
    if (nanos < 0 || nanos >= 1_000_000_000) {
      throw invalid("Terminal owner proof timestamp has invalid nanoseconds");
    }
    if (nanos % 1_000 != 0) {
      throw invalid("Terminal owner proof timestamp exceeds World microsecond precision");
    }
    try {
      return Instant.ofEpochSecond(seconds, nanos);
    } catch (DateTimeException invalidTimestamp) {
      throw invalid("Terminal owner proof timestamp is invalid", invalidTimestamp);
    }
  }

  private static CanonicalInitialAdmissionOrigin toWireOrigin(
      WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin origin) {
    return switch (origin) {
      case NO_PRIOR_POINTER ->
          CanonicalInitialAdmissionOrigin.CANONICAL_INITIAL_ADMISSION_ORIGIN_NO_PRIOR_POINTER;
      case EXPECT_CLOSED ->
          CanonicalInitialAdmissionOrigin.CANONICAL_INITIAL_ADMISSION_ORIGIN_EXPECT_CLOSED;
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
          throw invalid("Unsupported Game Session owner-proof outcome");
    };
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }
}
