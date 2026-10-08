package net.firedevops.firemud.common.world;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;

/** Closed length-framed codec for durable Game Session canonical initial-admission outcomes. */
public final class GameSessionCanonicalInitialAdmissionOwnerProofCodec {
  private static final String SCHEMA = "game-session-canonical-initial-admission-owner-proof/v1";

  private GameSessionCanonicalInitialAdmissionOwnerProofCodec() {}

  public static byte[] canonicalBytes(GameSessionCanonicalInitialAdmissionOwnerProof proof) {
    Objects.requireNonNull(proof, "proof");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(output, SCHEMA);
    DraftAuthorizationFenceBinding.frame(output, proof.holdIdentity().canonicalBytes());
    DraftAuthorizationFenceBinding.frame(output, proof.outcome().name());
    frameOptional(
        output,
        proof.committedPointerVersion() == null
            ? null
            : Long.toString(proof.committedPointerVersion()));
    frameOptional(
        output, proof.auditEventId() == null ? null : Long.toString(proof.auditEventId()));
    frameOptional(output, proof.proofDigest());
    DraftAuthorizationFenceBinding.frame(output, proof.positiveDurableAbort() ? "true" : "false");
    frameOptional(output, proof.terminalAt() == null ? null : proof.terminalAt().toString());
    return output.toByteArray();
  }

  /** Returns the canonical owner outcome only when the complete framed value round-trips. */
  public static GameSessionCanonicalInitialAdmissionOwnerProof fromStored(byte[] stored) {
    Objects.requireNonNull(stored, "stored");
    var reader = new DraftAuthorizationFenceBinding.FrameReader(stored);
    reader.expect(SCHEMA);
    var holdIdentity = WorldCanonicalInitialAdmissionHold.HoldIdentity.fromStored(reader.bytes());
    var outcome = parseOutcome(reader.text());
    Long pointerVersion = optionalPositiveLong(reader.optionalText(), "committedPointerVersion");
    Long auditEventId = optionalPositiveLong(reader.optionalText(), "auditEventId");
    String proofDigest = reader.optionalText();
    boolean positiveDurableAbort = parseBoolean(reader.text());
    String terminalText = reader.optionalText();
    reader.requireEnd();
    Instant terminalAt = terminalText == null ? null : parseInstant(terminalText);
    var proof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            holdIdentity,
            outcome,
            pointerVersion,
            auditEventId,
            proofDigest,
            positiveDurableAbort,
            terminalAt);
    if (!Arrays.equals(stored, canonicalBytes(proof))) {
      throw new IllegalArgumentException("Noncanonical Game Session owner proof");
    }
    return proof;
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof.Outcome parseOutcome(String value) {
    try {
      return GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.valueOf(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(
          "Unsupported canonical initial-admission owner outcome", invalid);
    }
  }

  private static Long optionalPositiveLong(String value, String field) {
    if (value == null) {
      return null;
    }
    if (!value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(field + " must be canonical positive decimal text");
    }
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(field + " exceeds the supported integer range", invalid);
    }
  }

  private static void frameOptional(ByteArrayOutputStream output, String value) {
    if (value == null) {
      DraftAuthorizationFenceBinding.frame(output, "ABSENT");
    } else {
      DraftAuthorizationFenceBinding.frame(output, "PRESENT");
      DraftAuthorizationFenceBinding.frame(output, value);
    }
  }

  private static boolean parseBoolean(String value) {
    return switch (value) {
      case "true" -> true;
      case "false" -> false;
      default -> throw new IllegalArgumentException("Canonical boolean frame required");
    };
  }

  private static Instant parseInstant(String value) {
    try {
      Instant parsed = Instant.parse(value);
      if (!parsed.toString().equals(value)) {
        throw new IllegalArgumentException("Terminal timestamp is not canonical");
      }
      return parsed;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Terminal timestamp is not canonical", invalid);
    }
  }
}
