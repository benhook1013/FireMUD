package net.firedevops.firemud.common.world;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Closed typed Game Session owner outcome bound to the exact World-issued hold identity. */
public record GameSessionCanonicalInitialAdmissionOwnerProof(
    WorldCanonicalInitialAdmissionHold.HoldIdentity holdIdentity,
    Outcome outcome,
    Long committedPointerVersion,
    Long auditEventId,
    String proofDigest,
    boolean positiveDurableAbort,
    Instant terminalAt) {
  private static final Pattern PREFIXED_SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

  public enum Outcome {
    PENDING,
    COMMITTED,
    ABORTED
  }

  public GameSessionCanonicalInitialAdmissionOwnerProof {
    Objects.requireNonNull(holdIdentity, "holdIdentity");
    Objects.requireNonNull(outcome, "outcome");
    if (outcome == Outcome.PENDING) {
      if (committedPointerVersion != null
          || auditEventId != null
          || proofDigest != null
          || positiveDurableAbort
          || terminalAt != null) {
        throw new IllegalArgumentException("PENDING is not terminal owner proof");
      }
    } else if (outcome == Outcome.COMMITTED) {
      requirePositive(committedPointerVersion, "committedPointerVersion");
      requirePositive(auditEventId, "auditEventId");
      requireProofDigest(proofDigest);
      Objects.requireNonNull(terminalAt, "terminalAt");
      if (positiveDurableAbort) {
        throw new IllegalArgumentException("COMMITTED cannot assert durable abort");
      }
    } else {
      if (committedPointerVersion != null || auditEventId != null) {
        throw new IllegalArgumentException("ABORTED cannot carry pointer or audit commit fields");
      }
      requireProofDigest(proofDigest);
      Objects.requireNonNull(terminalAt, "terminalAt");
      if (!positiveDurableAbort) {
        throw new IllegalArgumentException("ABORTED requires positive durable fencing");
      }
    }
  }

  private static void requirePositive(Long value, String field) {
    if (value == null || value <= 0L) {
      throw new IllegalArgumentException(field + " must be present and positive");
    }
  }

  private static void requireProofDigest(String value) {
    if (value == null || !PREFIXED_SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException("Terminal owner proof requires a prefixed SHA-256 digest");
    }
  }
}
