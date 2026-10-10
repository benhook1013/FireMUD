package net.firedevops.firemud.common.gamesession;

import java.util.Objects;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;

/** Exact terminal owner result bound to the complete Account protection read request. */
public final class OriginalStartSessionAdmissionTerminalResult {
  public static final int MAX_CANONICAL_BYTES =
      AccountStartSessionAdmissionProtectionRequest.MAX_HOLD_IDENTITY_BYTES + 512;

  private final OriginalStartSessionAdmissionTerminalRequest request;
  private final GameSessionCanonicalInitialAdmissionOwnerProof ownerProof;
  private final byte[] canonicalBytes;

  public OriginalStartSessionAdmissionTerminalResult(
      OriginalStartSessionAdmissionTerminalRequest request,
      GameSessionCanonicalInitialAdmissionOwnerProof ownerProof) {
    this.request = Objects.requireNonNull(request, "request is required");
    this.ownerProof = Objects.requireNonNull(ownerProof, "ownerProof is required");
    requireExactTerminalProof(request, ownerProof);
    this.canonicalBytes =
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(ownerProof);
    if (canonicalBytes.length == 0 || canonicalBytes.length > MAX_CANONICAL_BYTES) {
      throw new IllegalArgumentException(
          "Canonical Game Session owner proof exceeds its byte bound");
    }
  }

  public OriginalStartSessionAdmissionTerminalRequest request() {
    return request;
  }

  public GameSessionCanonicalInitialAdmissionOwnerProof ownerProof() {
    return ownerProof;
  }

  /** Returns the existing canonical owner-proof representation without introducing a new schema. */
  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  static void requireExactTerminalProof(
      OriginalStartSessionAdmissionTerminalRequest request,
      GameSessionCanonicalInitialAdmissionOwnerProof proof) {
    var expectedHold = request.protectionEvidence().request().worldAdmissionHoldIdentity();
    if (!expectedHold.equals(proof.holdIdentity())) {
      throw new IllegalArgumentException(
          "Game Session owner proof differs from the exact Account World hold identity");
    }
    switch (proof.outcome()) {
      case PENDING ->
          throw new IllegalArgumentException("Pending owner proof is not terminal evidence");
      case COMMITTED -> {
        var holdRequest = expectedHold.request();
        long expectedVersion;
        try {
          expectedVersion =
              switch (holdRequest.initialAdmissionOrigin()) {
                case NO_PRIOR_POINTER -> 1L;
                case EXPECT_CLOSED -> Math.addExact(holdRequest.expectedPriorPointerVersion(), 1L);
              };
        } catch (ArithmeticException overflow) {
          throw new IllegalArgumentException(
              "Expected pointer version cannot advance without overflow", overflow);
        }
        if (!Long.valueOf(expectedVersion).equals(proof.committedPointerVersion())) {
          throw new IllegalArgumentException(
              "Committed owner proof does not name the exact next pointer version");
        }
      }
      case ABORTED -> {
        if (!proof.positiveDurableAbort()) {
          throw new IllegalArgumentException(
              "Aborted owner proof must exclude future or in-flight commits");
        }
      }
    }
  }
}
