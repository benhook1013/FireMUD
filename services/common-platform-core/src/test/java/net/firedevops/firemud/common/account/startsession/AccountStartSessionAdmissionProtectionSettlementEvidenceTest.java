package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof.Outcome;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exact SQL-compatible value proof using controlled originals, not authenticated owner authority.
 */
class AccountStartSessionAdmissionProtectionSettlementEvidenceTest {
  private static final String PROTECTION = "canonicalAccountProtectionEvidenceBytesBase64";
  private static final String TERMINAL = "canonicalGameSessionOwnerProofBytesBase64";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void committedAndDefinitiveAbortedRoundTripExactEnvelopeDigestAndDefensiveBytes()
      throws Exception {
    for (Outcome outcome : List.of(Outcome.COMMITTED, Outcome.ABORTED)) {
      var fixture = AccountInitialAdmissionSettlementTestFixtures.evidence(outcome);
      var value =
          AccountStartSessionAdmissionProtectionSettlementEvidence.create(
              fixture.protection(), fixture.terminal());
      byte[] original = value.canonicalBytes();
      assertThat(original)
          .containsExactly(
              canonical(
                  Map.of(
                      "schema",
                      AccountStartSessionAdmissionProtectionSettlementEvidence.SCHEMA,
                      PROTECTION,
                      Base64.getEncoder().encodeToString(fixture.protection().canonicalBytes()),
                      TERMINAL,
                      Base64.getEncoder()
                          .encodeToString(
                              GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                                  fixture.terminal())))));
      assertThat(value.digest())
          .isEqualTo(
              "sha256:"
                  + HexFormat.of()
                      .formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
      var decoded = AccountStartSessionAdmissionProtectionSettlementEvidence.decode(original);
      assertThat(decoded.outcome()).isEqualTo(outcome);
      assertThat(decoded.canonicalBytes()).containsExactly(original);
      assertThat(decoded.protection().canonicalBytes())
          .containsExactly(fixture.protection().canonicalBytes());
      assertThat(
              GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                  decoded.terminal()))
          .containsExactly(
              GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                  fixture.terminal()));
      byte[] returned = value.canonicalBytes();
      returned[0] ^= 1;
      original[0] ^= 1;
      assertThat(decoded.canonicalBytes()).containsExactly(value.canonicalBytes());
      assertThat(decoded.digest()).isEqualTo(value.digest());
    }
  }

  @Test
  void pendingMismatchedHoldAndWrongNextPointerCannotSettle() {
    var fixture = AccountInitialAdmissionSettlementTestFixtures.evidence(Outcome.COMMITTED);
    var terminal = fixture.terminal();
    var pending =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            terminal.holdIdentity(), Outcome.PENDING, null, null, null, false, null);
    var wrongHold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            terminal.holdIdentity().request(),
            UUID.randomUUID(),
            terminal.holdIdentity().holdFence());
    var substituted =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            wrongHold,
            Outcome.ABORTED,
            null,
            null,
            terminal.proofDigest(),
            true,
            terminal.terminalAt());
    var wrongPointer =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            terminal.holdIdentity(),
            Outcome.COMMITTED,
            2L,
            terminal.auditEventId(),
            terminal.proofDigest(),
            false,
            terminal.terminalAt());
    for (var invalid : List.of(pending, substituted, wrongPointer)) {
      assertThatThrownBy(
              () ->
                  AccountStartSessionAdmissionProtectionSettlementEvidence.create(
                      fixture.protection(), invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void expectedClosedCommitMustUseExactlyPriorPointerPlusOne() {
    var fixture = AccountInitialAdmissionSettlementTestFixtures.evidence(Outcome.COMMITTED);
    var prior = fixture.terminal().holdIdentity();
    var request = prior.request();
    var closedHold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            new WorldCanonicalInitialAdmissionHold.Request(
                request.targetNamespace(),
                request.canonicalTenantId(),
                request.worldSlug(),
                request.realmId(),
                request.playableStateNamespaceId(),
                request.playableStateScope(),
                request.canonicalGameInstanceId(),
                request.canonicalVersionId(),
                request.activeLifecycleEpoch(),
                request.initialAdmissionRequestId(),
                request.initialAdmissionRequestDigest(),
                WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.EXPECT_CLOSED,
                request.expectedCatalogRevision(),
                5L),
            prior.holdId(),
            prior.holdFence());
    var original = fixture.protection().request();
    var reboundRequest =
        AccountStartSessionAdmissionProtectionRequest.create(
            original.originalPostAuthorizationTupleBytes(),
            original.accountRedemptionProjectionBytes(),
            original.gameSessionOwnerMutationId(),
            original.gameSessionOwnerAttemptId(),
            original.gameSessionOwnerFence(),
            original.originalLeaseExpiresAt(),
            original.accountWorldParticipationId(),
            original.accountWorldParticipationFence(),
            closedHold);
    var protection =
        AccountStartSessionAdmissionProtectionEvidence.create(
            reboundRequest,
            fixture.protection().accountProtectionId(),
            fixture.protection().accountProtectionFence(),
            fixture.protection().originalSourceCaptureReferenceBytes(),
            fixture.protection().originalSourceCaptureReferenceSha256(),
            fixture.protection().sourceEvidenceVector());
    var exact =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            closedHold,
            Outcome.COMMITTED,
            6L,
            7L,
            fixture.terminal().proofDigest(),
            false,
            fixture.terminal().terminalAt());
    assertThat(
            AccountStartSessionAdmissionProtectionSettlementEvidence.create(protection, exact)
                .outcome())
        .isEqualTo(Outcome.COMMITTED);
    var changed =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            closedHold,
            Outcome.COMMITTED,
            7L,
            7L,
            fixture.terminal().proofDigest(),
            false,
            fixture.terminal().terminalAt());
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionSettlementEvidence.create(
                    protection, changed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact next pointer");
  }

  @Test
  void rejectsUnknownDuplicateTrailingNoncanonicalAndUnsupportedShape() throws Exception {
    var fixture = AccountInitialAdmissionSettlementTestFixtures.evidence(Outcome.COMMITTED);
    byte[] bytes =
        AccountStartSessionAdmissionProtectionSettlementEvidence.create(
                fixture.protection(), fixture.terminal())
            .canonicalBytes();
    String json = new String(bytes, StandardCharsets.UTF_8);
    var unknown = fields(bytes);
    unknown.put("extra", "not supported");
    var missing = fields(bytes);
    missing.remove(PROTECTION);
    var wrongType = fields(bytes);
    wrongType.put(TERMINAL, 1);
    for (byte[] invalid :
        List.of(
            canonical(unknown),
            canonical(missing),
            canonical(wrongType),
            (json + "{}").getBytes(StandardCharsets.UTF_8),
            (json + " ").getBytes(StandardCharsets.UTF_8),
            (" " + json).getBytes(StandardCharsets.UTF_8),
            ("{\"schema\":\"duplicate\"," + json.substring(1)).getBytes(StandardCharsets.UTF_8))) {
      assertThatThrownBy(
              () -> AccountStartSessionAdmissionProtectionSettlementEvidence.decode(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsMalformedNoncanonicalBase64TruncatedAndOversizedProofs() throws Exception {
    var fixture = AccountInitialAdmissionSettlementTestFixtures.evidence(Outcome.ABORTED);
    byte[] bytes =
        AccountStartSessionAdmissionProtectionSettlementEvidence.create(
                fixture.protection(), fixture.terminal())
            .canonicalBytes();
    byte[] proof =
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(fixture.terminal());
    for (String invalid :
        List.of(
            "~",
            "",
            Base64.getEncoder().encodeToString(new byte[66_049]),
            Base64.getEncoder().encodeToString(Arrays.copyOf(proof, proof.length - 1)),
            Base64.getEncoder().encodeToString(proof) + "\n")) {
      var changed = fields(bytes);
      changed.put(TERMINAL, invalid);
      assertThatThrownBy(
              () ->
                  AccountStartSessionAdmissionProtectionSettlementEvidence.decode(
                      canonical(changed)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var changedProtection = fields(bytes);
    changedProtection.put(PROTECTION, Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionSettlementEvidence.decode(
                    canonical(changedProtection)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionSettlementEvidence.decode(
                    new byte
                        [AccountStartSessionAdmissionProtectionSettlementEvidence
                                .MAX_CANONICAL_BYTES
                            + 1]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Map<String, Object> fields(byte[] bytes) throws IOException {
    return new LinkedHashMap<>(JSON.readValue(bytes, new TypeReference<Map<String, Object>>() {}));
  }

  private static byte[] canonical(Object value) throws IOException {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }
}
