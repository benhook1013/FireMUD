package net.firedevops.firemud.common.world;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/** Private fixture-only terminal read. Not a Human owner proof or caller-supplied authority. */
public record WorldFixtureInitialAdmissionTerminalProof(
    WorldFixtureInitialAdmissionHoldRequest request,
    UUID holdId,
    UUID holdFence,
    Outcome outcome,
    Long pointerVersion,
    Long auditEventId,
    boolean positiveDurableAbort,
    Instant terminalAt) {
  public enum Outcome {
    PENDING,
    COMMITTED,
    ABORTED
  }

  public WorldFixtureInitialAdmissionTerminalProof {
    Objects.requireNonNull(request, "request");
    requireId(holdId, "holdId");
    requireId(holdFence, "holdFence");
    Objects.requireNonNull(outcome, "outcome");
    if (outcome == Outcome.PENDING) {
      if (pointerVersion != null
          || auditEventId != null
          || positiveDurableAbort
          || terminalAt != null) {
        throw new IllegalArgumentException("Pending fixture proof cannot carry terminal evidence");
      }
    } else if (outcome == Outcome.ABORTED) {
      if (!positiveDurableAbort
          || pointerVersion != null
          || auditEventId != null
          || terminalAt == null) {
        throw new IllegalArgumentException("Fixture abort requires definitive durable fencing");
      }
    } else {
      long expected =
          switch (request.selector().initialAdmissionOrigin()) {
            case NO_PRIOR_POINTER -> 1L;
            case EXPECT_CLOSED ->
                Math.addExact(request.selector().expectedPriorPointerVersion(), 1L);
          };
      if (pointerVersion == null
          || pointerVersion != expected
          || auditEventId == null
          || auditEventId <= 0
          || positiveDurableAbort
          || terminalAt == null) {
        throw new IllegalArgumentException(
            "Fixture commit requires exact pointer and audit evidence");
      }
    }
  }

  /** Decodes exact canonical value bytes; this is not owner authentication. */
  public static WorldFixtureInitialAdmissionTerminalProof fromStored(byte[] bytes) {
    return FixtureInitialAdmissionFrames.terminal(bytes);
  }

  /** Exact fixture terminal bytes; a digest of these bytes is not authentication. */
  public byte[] canonicalBytes() {
    String json =
        "{\"schema\":\"world-fixture-initial-admission-terminal/v1\","
            + "\"requestBytesBase64\":\""
            + Base64.getEncoder().encodeToString(request.canonicalBytes())
            + "\",\"holdId\":\""
            + holdId
            + "\",\"holdFence\":\""
            + holdFence
            + "\",\"outcome\":\""
            + outcome
            + "\",\"pointerVersion\":"
            + text(pointerVersion)
            + ",\"auditEventId\":"
            + text(auditEventId)
            + ",\"positiveDurableAbort\":"
            + positiveDurableAbort
            + ",\"terminalAt\":"
            + text(terminalAt)
            + "}";
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(json);
    } catch (IOException impossible) {
      throw new IllegalStateException("Fixture terminal proof cannot be canonicalized", impossible);
    }
  }

  private static String text(Object value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  private static void requireId(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L)))
      throw new IllegalArgumentException(name + " must not be nil");
  }
}
