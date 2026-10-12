package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository.StoredEvidence;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle.BundleReference;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture.Values;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import org.junit.jupiter.api.Test;

/** Unit proof of exact closed-codec observation binding; no Game Session producer is exercised. */
class AccountStartSessionWorldOriginalAttemptEvidenceRepositoryTest {
  private static final String REQUEST_ID =
      "start-session/original-attempt/2355f292-ab9c-458c-8d52-aef18b9254e8";
  private static final UUID PARTICIPATION_ID =
      UUID.fromString("fa3555ac-e039-4827-9e04-eb0fa8cd37d4");
  private static final UUID ACTOR_ID = UUID.fromString("d888ddc4-4a62-4b25-9bab-c4f94860c2ca");
  private static final UUID TENANT_ID = UUID.fromString("6d1e5ce5-6127-4d35-88b8-7a6f40692038");
  private static final UUID TARGET_ACCOUNT_ID =
      UUID.fromString("1df91ae6-5125-4e47-9f1d-2e45eab8a4a0");
  private static final UUID RESERVATION_OWNER_ID =
      UUID.fromString("ca1b63bf-f09f-4a55-96bf-a1fd2e22349e");
  private static final UUID GAME_INSTANCE_ID =
      UUID.fromString("51c67424-324e-486d-b3ad-45b6e85c1a0d");
  private static final UUID OWNER_ATTEMPT_ID =
      UUID.fromString("c0df9691-cba5-4274-a49d-0bc7b2158ef7");
  private static final UUID OWNER_MUTATION_ID =
      UUID.fromString("f7811486-2bd4-41a0-9bf7-a351ce3a0c8f");
  private static final long OWNER_FENCE = 47L;
  private static final String NAMESPACE = "world-runtime";
  private static final String LOGGING_URI =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";
  private static final BundleReference BUNDLE_REFERENCE =
      new BundleReference("authorityEvidenceBundle/v1", "11", "13", "17");
  private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

  @Test
  void closedCodecReadbackBindsCompleteTupleProjectionAndRejectsSubmicrosecondExpiry() {
    Fixture fixture = fixture();
    Request expected = request(fixture, UUID.fromString("6a3922eb-96f5-4cc5-8e43-85c1bb3f49c0"));
    Result exact =
        new Result(
            expected, Instant.parse("2026-10-09T00:02:00.123456Z"), fixture.accountProjection());
    byte[] completeResponse =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(exact).toByteArray();

    Result decoded =
        AccountStartSessionWorldOriginalAttemptEvidenceRepository.validateResponse(
            expected, completeResponse, fixture.parent());

    assertThat(decoded.originalLeaseExpiresAt()).isEqualTo(exact.originalLeaseExpiresAt());
    assertThat(decoded.accountRedemptionProjection()).containsExactly(fixture.accountProjection());
    assertThat(decoded.request().expectedOwnerMutationId()).isEqualTo(OWNER_MUTATION_ID);
    assertThat(decoded.phaseState())
        .isEqualTo(OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE);

    Result subMicrosecond =
        new Result(
            expected, Instant.parse("2026-10-09T00:02:00.123456789Z"), fixture.accountProjection());
    byte[] roundedTimestampCandidate =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(subMicrosecond)
            .toByteArray();
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldOriginalAttemptEvidenceRepository.validateResponse(
                    expected, roundedTimestampCandidate, fixture.parent()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");

    byte[] changedProjection = "not the exact Account projection".getBytes(StandardCharsets.UTF_8);
    Result wrongProjection =
        new Result(expected, exact.originalLeaseExpiresAt(), changedProjection);
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldOriginalAttemptEvidenceRepository.validateResponse(
                    expected,
                    OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(wrongProjection)
                        .toByteArray(),
                    fixture.parent()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");
  }

  @Test
  void freshReadCorrelationIsNotDurableIdentityButEveryOwnerBindingFieldIs() {
    Fixture fixture = fixture();
    Request first = request(fixture, UUID.fromString("6a3922eb-96f5-4cc5-8e43-85c1bb3f49c0"));
    Request retry = request(fixture, UUID.fromString("21fa4d95-98f0-4c19-a5b6-864bd0467f94"));

    assertThatCode(
            () ->
                AccountStartSessionWorldOriginalAttemptEvidenceRepository
                    .requireSameSemanticRequest(first, retry))
        .doesNotThrowAnyException();

    assertMismatch(
        first,
        fixture.namespace(),
        fixture.parent().originalPostAuthorizationTuple(),
        OWNER_ATTEMPT_ID,
        OWNER_MUTATION_ID,
        OWNER_FENCE + 1L);
    assertMismatch(
        first,
        fixture.namespace(),
        fixture.parent().originalPostAuthorizationTuple(),
        UUID.fromString("274c7a54-2f2a-409f-9eac-7b8a7733e590"),
        OWNER_MUTATION_ID,
        OWNER_FENCE);
    assertMismatch(
        first,
        fixture.namespace(),
        fixture.parent().originalPostAuthorizationTuple(),
        OWNER_ATTEMPT_ID,
        UUID.fromString("1f2d6f4e-1202-4bdb-bcd4-f3c14e4cc764"),
        OWNER_FENCE);
    assertThatThrownBy(
            () ->
                new Request(
                    UUID.randomUUID(),
                    "other-runtime",
                    fixture.parent().originalPostAuthorizationTuple(),
                    OWNER_ATTEMPT_ID,
                    OWNER_MUTATION_ID,
                    OWNER_FENCE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("target the exact requested namespace");

    Request changedTuple =
        new Request(
            UUID.fromString("578bc4d6-6a96-4bb9-a6f9-2d4d54f0c547"),
            fixture.namespace(),
            tupleFor("Changed exact tuple").canonicalBytes(),
            OWNER_ATTEMPT_ID,
            OWNER_MUTATION_ID,
            OWNER_FENCE);
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldOriginalAttemptEvidenceRepository
                    .requireSameSemanticRequest(first, changedTuple))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");
  }

  @Test
  void storedResponseIsDefensiveAndItsDigestCoversTheCompleteResponseBytes() {
    Fixture fixture = fixture();
    Request request = request(fixture, UUID.fromString("6a3922eb-96f5-4cc5-8e43-85c1bb3f49c0"));
    Result result = new Result(request, NOW.plusSeconds(120), fixture.accountProjection());
    byte[] bytes =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(result).toByteArray();
    String digest = "sha256:" + sha256Hex(bytes);
    StoredEvidence stored =
        new StoredEvidence(
            PARTICIPATION_ID,
            OWNER_MUTATION_ID,
            result.originalLeaseExpiresAt(),
            bytes,
            digest,
            result);
    bytes[0] ^= 0x01;

    assertThat(stored.originalResponseBytes())
        .containsExactly(
            OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(result).toByteArray());
    assertThat(stored.originalResponseDigest()).isEqualTo(digest);
    assertThat(stored.toString())
        .contains("projection=<redacted>")
        .contains("responseBytes=<redacted>");
    assertThatThrownBy(
            () ->
                new StoredEvidence(
                    PARTICIPATION_ID,
                    OWNER_MUTATION_ID,
                    result.originalLeaseExpiresAt(),
                    new byte[] {1},
                    digest,
                    result))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest differs");
  }

  private static void assertMismatch(
      Request expected, String namespace, byte[] tuple, UUID attempt, UUID mutation, long fence) {
    Request changed = new Request(UUID.randomUUID(), namespace, tuple, attempt, mutation, fence);
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldOriginalAttemptEvidenceRepository
                    .requireSameSemanticRequest(expected, changed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");
  }

  private static Request request(Fixture fixture, UUID readId) {
    return new Request(
        readId,
        fixture.namespace(),
        fixture.parent().originalPostAuthorizationTuple(),
        OWNER_ATTEMPT_ID,
        OWNER_MUTATION_ID,
        OWNER_FENCE);
  }

  private static Fixture fixture() {
    StartSessionPostAuthorizationExecutionTuple tuple =
        tupleFor("Original attempt evidence repository proof");
    String preparationJson = "{\"identity\":{\"worldSlug\":\"arena\"}}";
    StoredParticipation parent =
        new StoredParticipation(
            PARTICIPATION_ID,
            41L,
            REQUEST_ID,
            tuple.canonicalBytes(),
            NAMESPACE,
            TENANT_ID,
            GAME_INSTANCE_ID,
            OWNER_ATTEMPT_ID,
            OWNER_FENCE,
            preparationJson,
            "sha256:" + sha256Hex(preparationJson.getBytes(StandardCharsets.UTF_8)),
            2_147_483_651L,
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
            List.of());
    return new Fixture(parent, tuple, accountProjection(tuple));
  }

  private static StartSessionPostAuthorizationExecutionTuple tupleFor(String auditReason) {
    StartSessionPreAuthorizationReservationTuple preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            REQUEST_ID,
            ACTOR_ID,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT_ID, NAMESPACE),
                new StartSessionOperatorAction.Target(17L, TARGET_ACCOUNT_ID),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                auditReason));
    byte[] bundle = authorityBundle(preTuple);
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            preTuple,
            LOGGING_URI,
            "arfp/v1/key-1/" + "a".repeat(64),
            RESERVATION_OWNER_ID,
            7L,
            bundle,
            BUNDLE_REFERENCE);
    return tuple;
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    return StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
        tuple,
        new Values(
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            "sha256:" + "c".repeat(64),
            BUNDLE_REFERENCE.sourceVersion(),
            "CURRENT",
            NOW,
            NOW.plusSeconds(180),
            1L,
            1L,
            1L,
            1L,
            2L,
            9L,
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            "1",
            "1"));
  }

  private static byte[] accountProjection(StartSessionPostAuthorizationExecutionTuple tuple) {
    StartSessionAuthorityEvidenceBundle bundle =
        StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
    return AccountControlUiAuthority.canonical(
        Map.of(
            "projectionSchemaId",
            "accountStartSessionRedemptionProjection",
            "projectionSchemaVersion",
            "1",
            "authorizationReferenceFingerprint",
            tuple.authorizationReferenceFingerprint(),
            "authorityEvidenceBundle",
            bundle.jsonValue(),
            "issuanceOperationId",
            bundle.issuanceOperationId().toString(),
            "issuanceFence",
            9L));
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private record Fixture(
      StoredParticipation parent,
      StartSessionPostAuthorizationExecutionTuple tuple,
      byte[] accountProjection) {
    private Fixture {
      accountProjection = accountProjection.clone();
    }

    private String namespace() {
      return NAMESPACE;
    }

    @Override
    public byte[] accountProjection() {
      return accountProjection.clone();
    }
  }
}
