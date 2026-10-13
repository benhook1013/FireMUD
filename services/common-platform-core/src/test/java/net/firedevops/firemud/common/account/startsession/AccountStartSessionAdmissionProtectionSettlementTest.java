package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalRequest;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalResult;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic integrity tests; these fixtures provide no peer, database, or genuine GS proof. */
class AccountStartSessionAdmissionProtectionSettlementTest {
  private static final String PROOF_DIGEST = "sha256:" + "d".repeat(64);
  private static final UUID ALTERED_PROTECTION_ID =
      UUID.fromString("621a42c0-49c9-4b08-8f1c-ae9db562dd23");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void roundTripsCompleteAccountEvidenceAndCommittedOrAbortedOwnerProof() throws Exception {
    var committedResult = result(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    var committed = AccountStartSessionAdmissionProtectionSettlement.create(committedResult);
    var decodedCommitted =
        AccountStartSessionAdmissionProtectionSettlement.decode(committed.canonicalBytes());

    assertThat(decodedCommitted.canonicalBytes()).containsExactly(committed.canonicalBytes());
    assertThat(decodedCommitted.protectionEvidence().canonicalBytes())
        .containsExactly(committedResult.request().protectionEvidence().canonicalBytes());
    assertThat(decodedCommitted.ownerProof()).isEqualTo(committedResult.ownerProof());
    assertThat(decodedCommitted.result().canonicalBytes())
        .containsExactly(committedResult.canonicalBytes());
    assertThat(decodedCommitted.outcome())
        .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    assertThat(decodedCommitted.digest()).isEqualTo(digest(committed.canonicalBytes()));
    assertThat(decodedCommitted.digest()).matches("^sha256:[0-9a-f]{64}$");
    assertThat(decodedCommitted).isEqualTo(committed);
    assertThat(decodedCommitted.hashCode()).isEqualTo(committed.hashCode());
    byte[] escapedBytes = committed.canonicalBytes();
    escapedBytes[0] ^= 1;
    assertThat(committed.canonicalBytes()).containsExactly(decodedCommitted.canonicalBytes());
    Map<String, Object> committedEnvelope = readObject(committed.canonicalBytes());
    assertThat(committedEnvelope.keySet())
        .containsExactlyInAnyOrder(
            "schema",
            "canonicalAccountProtectionEvidenceBytesBase64",
            "canonicalGameSessionOwnerProofBytesBase64");
    assertThat(
            Base64.getDecoder()
                .decode(
                    (String)
                        committedEnvelope.get("canonicalAccountProtectionEvidenceBytesBase64")))
        .containsExactly(committedResult.request().protectionEvidence().canonicalBytes());
    assertThat(
            Base64.getDecoder()
                .decode(
                    (String) committedEnvelope.get("canonicalGameSessionOwnerProofBytesBase64")))
        .containsExactly(committedResult.canonicalBytes());

    var abortedResult = result(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
    var aborted = AccountStartSessionAdmissionProtectionSettlement.create(abortedResult);
    var decodedAborted =
        AccountStartSessionAdmissionProtectionSettlement.decode(aborted.canonicalBytes());

    assertThat(decodedAborted.canonicalBytes()).containsExactly(aborted.canonicalBytes());
    assertThat(decodedAborted.ownerProof().positiveDurableAbort()).isTrue();
    assertThat(decodedAborted.outcome())
        .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
    assertThat(aborted).isNotEqualTo(committed);
  }

  @Test
  void changedAccountProtectionBytesAreNotAnExactReplay() {
    var originalResult = result(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    var original = AccountStartSessionAdmissionProtectionSettlement.create(originalResult);
    var evidence = originalResult.request().protectionEvidence();
    var changedEvidence =
        AccountStartSessionAdmissionProtectionEvidence.create(
            evidence.request(),
            ALTERED_PROTECTION_ID,
            evidence.accountProtectionFence(),
            evidence.originalSourceCaptureReferenceBytes(),
            evidence.originalSourceCaptureReferenceSha256(),
            evidence.sourceEvidenceVector());
    var changedResult =
        new OriginalStartSessionAdmissionTerminalResult(
            new OriginalStartSessionAdmissionTerminalRequest(changedEvidence.canonicalBytes()),
            originalResult.ownerProof());
    var changed = AccountStartSessionAdmissionProtectionSettlement.create(changedResult);

    assertThat(changed.protectionEvidence().accountProtectionId()).isEqualTo(ALTERED_PROTECTION_ID);
    assertThat(changed.canonicalBytes()).isNotEqualTo(original.canonicalBytes());
    assertThat(AccountStartSessionAdmissionProtectionSettlement.decode(changed.canonicalBytes()))
        .isNotEqualTo(original);
    // This is only an exact-byte conflict signal. Comparing the ID with retained Account rows is
    // the settlement repository's responsibility; decoding alone cannot authenticate provenance.
  }

  @Test
  void rejectsPendingSubstitutedHoldAndWrongNextPointerResults() {
    var request = request();
    var hold = request.protectionEvidence().request().worldAdmissionHoldIdentity();
    var pending =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            hold,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING,
            null,
            null,
            null,
            false,
            null);
    assertThatThrownBy(() -> new OriginalStartSessionAdmissionTerminalResult(request, pending))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Pending");

    var wrongPointer =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            hold,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            2L,
            23L,
            PROOF_DIGEST,
            false,
            Instant.parse("2026-10-09T10:20:30Z"));
    assertThatThrownBy(() -> new OriginalStartSessionAdmissionTerminalResult(request, wrongPointer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact next pointer version");

    var changedHold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            hold.request(), hold.holdId(), UUID.fromString("c3527a86-eae1-4a1e-b8a6-13c831f7a665"));
    var substitutedHoldProof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            changedHold,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED,
            null,
            null,
            PROOF_DIGEST,
            true,
            Instant.parse("2026-10-09T10:20:30Z"));
    assertThatThrownBy(
            () -> new OriginalStartSessionAdmissionTerminalResult(request, substitutedHoldProof))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact Account World hold identity");

    assertThatThrownBy(
            () ->
                new GameSessionCanonicalInitialAdmissionOwnerProof(
                    hold,
                    GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED,
                    null,
                    null,
                    PROOF_DIGEST,
                    false,
                    Instant.parse("2026-10-09T10:20:30Z")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive durable fencing");
  }

  @Test
  void rejectsMissingExtraDuplicateNoncanonicalMalformedAndOversizedEnvelopes() throws Exception {
    byte[] valid =
        AccountStartSessionAdmissionProtectionSettlement.create(
                result(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED))
            .canonicalBytes();
    Map<String, Object> fields = readObject(valid);

    Map<String, Object> missing = new LinkedHashMap<>(fields);
    missing.remove("canonicalAccountProtectionEvidenceBytesBase64");
    assertRejected(canonical(missing));

    Map<String, Object> extra = new LinkedHashMap<>(fields);
    extra.put("unexpected", "field");
    assertRejected(canonical(extra));

    Map<String, Object> wrongSchema = new LinkedHashMap<>(fields);
    wrongSchema.put("schema", "account-start-session-admission-protection-settlement/v2");
    assertRejected(canonical(wrongSchema));

    String text = new String(valid, StandardCharsets.UTF_8);
    String schema =
        "\"schema\":\"" + AccountStartSessionAdmissionProtectionSettlement.SCHEMA + "\"";
    String duplicate = text.replaceFirst(schema, schema + "," + schema);
    assertThat(duplicate).isNotEqualTo(text);
    assertRejected(duplicate.getBytes(StandardCharsets.UTF_8));

    assertRejected(Arrays.copyOf(valid, valid.length + 1));
    assertRejected((text + "{}").getBytes(StandardCharsets.UTF_8));
    assertRejected((text + " ").getBytes(StandardCharsets.UTF_8));

    Map<String, Object> badBase64 = new LinkedHashMap<>(fields);
    badBase64.put("canonicalGameSessionOwnerProofBytesBase64", "%%%not-base64%%%");
    assertRejected(canonical(badBase64));

    byte[] malformedUtf8 = valid.clone();
    malformedUtf8[0] = (byte) 0xff;
    assertRejected(malformedUtf8);

    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionSettlement.decode(
                    new byte
                        [AccountStartSessionAdmissionProtectionSettlement.MAX_CANONICAL_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("byte limit");
  }

  private static OriginalStartSessionAdmissionTerminalRequest request() {
    return new OriginalStartSessionAdmissionTerminalRequest(
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodecTest.evidence(
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodecTest.input())
            .canonicalBytes());
  }

  private static OriginalStartSessionAdmissionTerminalResult result(
      GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome) {
    var request = request();
    var hold = request.protectionEvidence().request().worldAdmissionHoldIdentity();
    var proof =
        switch (outcome) {
          case COMMITTED ->
              new GameSessionCanonicalInitialAdmissionOwnerProof(
                  hold,
                  outcome,
                  1L,
                  23L,
                  PROOF_DIGEST,
                  false,
                  Instant.parse("2026-10-09T10:20:30Z"));
          case ABORTED ->
              new GameSessionCanonicalInitialAdmissionOwnerProof(
                  hold,
                  outcome,
                  null,
                  null,
                  PROOF_DIGEST,
                  true,
                  Instant.parse("2026-10-09T10:20:30Z"));
          case PENDING -> throw new IllegalArgumentException("Pending is not terminal");
        };
    return new OriginalStartSessionAdmissionTerminalResult(request, proof);
  }

  private static void assertRejected(byte[] bytes) {
    assertThatThrownBy(() -> AccountStartSessionAdmissionProtectionSettlement.decode(bytes))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static byte[] canonical(Map<String, Object> value) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static Map<String, Object> readObject(byte[] bytes) throws Exception {
    return JSON.readValue(new String(bytes, StandardCharsets.UTF_8), new TypeReference<>() {});
  }

  private static String digest(byte[] value) throws Exception {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }
}
