package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class AccountStartSessionAdmissionProtectionEvidenceTest {
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID GAME_SESSION_MUTATION = uuid("f1a3ab1e-9147-4667-b6c4-6eb5119e8a31");
  private static final UUID GAME_SESSION_ATTEMPT = uuid("ec13cc04-ec15-4eb8-a018-c2c5e8da65f8");
  private static final UUID ACCOUNT_PARTICIPATION = uuid("47b3be7f-a32f-4e19-8916-8c3b8da07a82");
  private static final UUID WORLD_HOLD = uuid("0db7344a-1e67-4b95-905a-83dc9c472f0c");
  private static final UUID WORLD_HOLD_FENCE = uuid("52a14272-f9e4-4f67-97c9-62247b5fbcc1");
  private static final UUID ACCOUNT_PROTECTION = uuid("6b763f1d-c5bc-4080-b499-d29debc0a7b8");
  private static final Instant ORIGINAL_LEASE_EXPIRY = Instant.parse("2026-10-09T10:20:30.456789Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void redemptionProjectionRetainsTheOriginalNumericIssuanceFenceRepresentation()
      throws IOException {
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple("admission-projection");

    byte[] projection = StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple);
    Map<String, Object> projectionValue = readObject(projection);

    Object issuanceFence = projectionValue.get("issuanceFence");
    assertThat(issuanceFence).isInstanceOf(Number.class);
    assertThat(((Number) issuanceFence).toString()).isEqualTo("23");
    assertThat(((Map<?, ?>) projectionValue.get("authorityEvidenceBundle")).get("issuanceFence"))
        .isEqualTo("23");
  }

  @Test
  void requestAndEvidenceRoundTripCompleteOriginalIdentityAndSourceChildren() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple("admission-roundtrip");
    AccountStartSessionAdmissionProtectionRequest request = request(tuple);
    SourceEvidence issuerSource =
        new SourceEvidence(
            SourceKind.ISSUER,
            "https://issuer.example",
            null,
            "9",
            null,
            null,
            new byte[] {0, 1, 2, (byte) 0xff});
    SourceEvidence accountSource = source("account-source-before");
    byte[] captureReference = captureReference(tuple, "17", "23", "18446744073709551615");
    AccountStartSessionAdmissionProtectionEvidence evidence =
        AccountStartSessionAdmissionProtectionEvidence.create(
            request,
            ACCOUNT_PROTECTION,
            Long.MAX_VALUE,
            captureReference,
            sha256(captureReference),
            List.of(issuerSource, accountSource));

    AccountStartSessionAdmissionProtectionRequest decodedRequest =
        AccountStartSessionAdmissionProtectionRequest.decode(request.canonicalBytes());
    AccountStartSessionAdmissionProtectionEvidence decodedEvidence =
        AccountStartSessionAdmissionProtectionEvidence.decode(evidence.canonicalBytes());

    assertThat(decodedRequest.canonicalBytes()).containsExactly(request.canonicalBytes());
    assertThat(decodedRequest.originalPostAuthorizationTupleBytes())
        .containsExactly(tuple.canonicalBytes());
    assertThat(decodedRequest.accountRedemptionProjectionBytes())
        .containsExactly(StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple));
    assertThat(decodedRequest.gameSessionOwnerMutationId()).isEqualTo(GAME_SESSION_MUTATION);
    assertThat(decodedRequest.gameSessionOwnerAttemptId()).isEqualTo(GAME_SESSION_ATTEMPT);
    assertThat(decodedRequest.gameSessionOwnerFence()).isEqualTo(Long.MAX_VALUE);
    assertThat(decodedRequest.originalLeaseExpiresAt()).isEqualTo(ORIGINAL_LEASE_EXPIRY);
    assertThat(decodedRequest.accountWorldParticipationId()).isEqualTo(ACCOUNT_PARTICIPATION);
    assertThat(decodedRequest.accountWorldParticipationFence()).isEqualTo(Long.MAX_VALUE);
    assertThat(decodedRequest.worldAdmissionHoldIdentityBytes())
        .containsExactly(request.worldAdmissionHoldIdentity().canonicalBytes());
    assertThat(decodedEvidence.canonicalBytes()).containsExactly(evidence.canonicalBytes());
    assertThat(decodedEvidence.request().canonicalBytes())
        .containsExactly(request.canonicalBytes());
    assertThat(decodedEvidence.accountProtectionId()).isEqualTo(ACCOUNT_PROTECTION);
    assertThat(decodedEvidence.accountProtectionFence()).isEqualTo(Long.MAX_VALUE);
    assertThat(decodedEvidence.originalSourceCaptureReferenceBytes())
        .containsExactly(evidence.originalSourceCaptureReferenceBytes());
    assertThat(decodedEvidence.originalSourceCaptureReferenceSha256())
        .isEqualTo(sha256(evidence.originalSourceCaptureReferenceBytes()));
    assertThat(decodedEvidence.sourceEvidenceVector())
        .extracting(SourceEvidence::key)
        .containsExactly("ACCOUNT:" + TENANT, "ISSUER:https://issuer.example");
    assertThat(decodedEvidence.sourceEvidenceVector().get(0).canonicalBytes())
        .containsExactly(accountSource.canonicalBytes());
    assertThat(decodedEvidence.sourceEvidenceVector().get(1).canonicalBytes())
        .containsExactly(issuerSource.canonicalBytes());
  }

  @Test
  void rejectsRequestProjectionHoldScopeExpiryAndCounterSubstitutions() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple("admission-binding");
    byte[] expectedProjection = StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple);
    byte[] substitutedProjection = expectedProjection.clone();
    substitutedProjection[substitutedProjection.length - 2] ^= 1;

    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionRequest.create(
                    tuple.canonicalBytes(),
                    substitutedProjection,
                    GAME_SESSION_MUTATION,
                    GAME_SESSION_ATTEMPT,
                    1L,
                    ORIGINAL_LEASE_EXPIRY,
                    ACCOUNT_PARTICIPATION,
                    2L,
                    hold(tuple)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionRequest.create(
                    tuple.canonicalBytes(),
                    expectedProjection,
                    GAME_SESSION_MUTATION,
                    GAME_SESSION_ATTEMPT,
                    1L,
                    ORIGINAL_LEASE_EXPIRY.plusNanos(1),
                    ACCOUNT_PARTICIPATION,
                    2L,
                    hold(tuple)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("microsecond");

    Map<String, Object> finerPrecision = readObject(request(tuple).canonicalBytes());
    finerPrecision.put("originalLeaseExpiresAt", "2026-10-09T10:20:30.456789001Z");
    assertThatThrownBy(
            () -> AccountStartSessionAdmissionProtectionRequest.decode(canonical(finerPrecision)))
        .isInstanceOf(IllegalArgumentException.class);
    Map<String, Object> alternateFraction = readObject(request(tuple).canonicalBytes());
    alternateFraction.put("originalLeaseExpiresAt", "2026-10-09T10:20:30.45678Z");
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionRequest.decode(canonical(alternateFraction)))
        .isInstanceOf(IllegalArgumentException.class);
    Map<String, Object> alternateOffset = readObject(request(tuple).canonicalBytes());
    alternateOffset.put("originalLeaseExpiresAt", "2026-10-09T10:20:30.456789+00:00");
    assertThatThrownBy(
            () -> AccountStartSessionAdmissionProtectionRequest.decode(canonical(alternateOffset)))
        .isInstanceOf(IllegalArgumentException.class);

    WorldCanonicalInitialAdmissionHold.HoldIdentity wrongNamespace =
        hold(tuple, TENANT, "another-world-runtime");
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionRequest.create(
                    tuple.canonicalBytes(),
                    expectedProjection,
                    GAME_SESSION_MUTATION,
                    GAME_SESSION_ATTEMPT,
                    1L,
                    ORIGINAL_LEASE_EXPIRY,
                    ACCOUNT_PARTICIPATION,
                    2L,
                    wrongNamespace))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("World hold scope");

    Map<String, Object> overflow = readObject(request(tuple).canonicalBytes());
    overflow.put("gameSessionOwnerFence", "9223372036854775808");
    assertThatThrownBy(
            () -> AccountStartSessionAdmissionProtectionRequest.decode(canonical(overflow)))
        .isInstanceOf(IllegalArgumentException.class);
    Map<String, Object> missing = readObject(request(tuple).canonicalBytes());
    missing.remove("originalLeaseExpiresAt");
    assertThatThrownBy(
            () -> AccountStartSessionAdmissionProtectionRequest.decode(canonical(missing)))
        .isInstanceOf(IllegalArgumentException.class);
    Map<String, Object> extra = readObject(request(tuple).canonicalBytes());
    extra.put("opaqueAuthorizationReference", "must-not-be-retained");
    assertThatThrownBy(() -> AccountStartSessionAdmissionProtectionRequest.decode(canonical(extra)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionRequest.decode(
                    withDuplicateSchema(request(tuple).canonicalBytes())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bindsCaptureReferenceToTupleAndRetainsCompleteChangedSourceEvidence() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple("admission-evidence");
    AccountStartSessionAdmissionProtectionRequest request = request(tuple);
    byte[] captureBytes = captureReference(tuple, "17", "23", "18446744073709551615");
    String digest = sha256(captureBytes);
    SourceEvidence originalSource = source("complete-account-source-before");
    AccountStartSessionAdmissionProtectionEvidence original =
        AccountStartSessionAdmissionProtectionEvidence.create(
            request, ACCOUNT_PROTECTION, 4L, captureBytes, digest, List.of(originalSource));
    SourceEvidence changedSource = source("complete-account-source-after");
    AccountStartSessionAdmissionProtectionEvidence changed =
        AccountStartSessionAdmissionProtectionEvidence.create(
            request, ACCOUNT_PROTECTION, 4L, captureBytes, digest, List.of(changedSource));

    assertThat(changed.canonicalBytes()).isNotEqualTo(original.canonicalBytes());
    assertThat(
            AccountStartSessionAdmissionProtectionEvidence.decode(changed.canonicalBytes())
                .sourceEvidenceVector()
                .get(0)
                .canonicalBytes())
        .containsExactly(changedSource.canonicalBytes());

    byte[] wrongReference = captureReference(tuple, "18", "23", "18446744073709551615");
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionEvidence.create(
                    request,
                    ACCOUNT_PROTECTION,
                    4L,
                    wrongReference,
                    sha256(wrongReference),
                    List.of(originalSource)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("original tuple bundle");
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionEvidence.create(
                    request,
                    ACCOUNT_PROTECTION,
                    4L,
                    captureBytes,
                    "0".repeat(64),
                    List.of(originalSource)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest differs");
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionEvidence.create(
                    request,
                    ACCOUNT_PROTECTION,
                    4L,
                    captureBytes,
                    digest,
                    List.of(originalSource, originalSource)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate source keys");
  }

  @Test
  void rejectsEvidenceShapeDuplicatesTrailingBytesAndOverflowingCounters() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple("admission-shape");
    byte[] captureReference = captureReference(tuple, "17", "23", "18446744073709551615");
    AccountStartSessionAdmissionProtectionEvidence evidence =
        AccountStartSessionAdmissionProtectionEvidence.create(
            request(tuple),
            ACCOUNT_PROTECTION,
            4L,
            captureReference,
            sha256(captureReference),
            List.of(source("shape-source")));

    Map<String, Object> overflow = readObject(evidence.canonicalBytes());
    overflow.put("accountProtectionFence", "9223372036854775808");
    assertThatThrownBy(
            () -> AccountStartSessionAdmissionProtectionEvidence.decode(canonical(overflow)))
        .isInstanceOf(IllegalArgumentException.class);
    Map<String, Object> missing = readObject(evidence.canonicalBytes());
    missing.remove("accountProtectionId");
    assertThatThrownBy(
            () -> AccountStartSessionAdmissionProtectionEvidence.decode(canonical(missing)))
        .isInstanceOf(IllegalArgumentException.class);
    Map<String, Object> extra = readObject(evidence.canonicalBytes());
    extra.put("accountCredential", "not-carried");
    assertThatThrownBy(
            () -> AccountStartSessionAdmissionProtectionEvidence.decode(canonical(extra)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionEvidence.decode(
                    withDuplicateSchema(evidence.canonicalBytes())))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] trailing =
        Arrays.copyOf(evidence.canonicalBytes(), evidence.canonicalBytes().length + 1);
    trailing[trailing.length - 1] = (byte) ' ';
    assertThatThrownBy(() -> AccountStartSessionAdmissionProtectionEvidence.decode(trailing))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AccountStartSessionAdmissionProtectionRequest request(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    return AccountStartSessionAdmissionProtectionRequest.create(
        tuple.canonicalBytes(),
        StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
        GAME_SESSION_MUTATION,
        GAME_SESSION_ATTEMPT,
        Long.MAX_VALUE,
        ORIGINAL_LEASE_EXPIRY,
        ACCOUNT_PARTICIPATION,
        Long.MAX_VALUE,
        hold(tuple));
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(String requestId) {
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            requestId,
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, "world-runtime"),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "canonical original StartSession admission attempt"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/world-runtime/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf"),
        19L,
        authorityBundle(preTuple),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    String tenant = TENANT.toString();
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-09T00:05:00Z");
    Map<String, Object> operation =
        Map.of(
            "issuanceOperationId", "f5d044bd-7e5f-4e2d-9859-9025cbdcc60f",
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> authority =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 2L,
            "tenantAuthorityGeneration", Map.of(tenant, 3L),
            "membershipAuthorityGeneration", Map.of(tenant, 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> issuanceEvidence =
        Map.of(
            "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId", ACTOR.toString(),
            "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
            "role", "tenantAdmin",
            "accountGeneration", "2",
            "tenantGeneration", "3");
    Map<String, Object> bundle =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenant, "targetNamespace", "world-runtime"),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", tenant),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            operation,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(tenant, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            issuanceEvidence);
    return canonical(bundle);
  }

  private static WorldCanonicalInitialAdmissionHold.HoldIdentity hold(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    return hold(tuple, TENANT, "world-runtime");
  }

  private static WorldCanonicalInitialAdmissionHold.HoldIdentity hold(
      StartSessionPostAuthorizationExecutionTuple tuple, UUID tenant, String namespace) {
    return new WorldCanonicalInitialAdmissionHold.HoldIdentity(
        new WorldCanonicalInitialAdmissionHold.Request(
            namespace,
            tenant,
            "earth",
            uuid("3916f423-2870-426a-a8aa-5e3f97412613"),
            uuid("54e6094e-11bb-4f4c-93ee-a52f715b530b"),
            "SHARED",
            uuid("a55b2e10-9a24-4adb-adb8-6fc66fe3b8e9"),
            uuid("d7280ec0-5979-4b62-8418-e9f139415184"),
            3L,
            tuple.controlPlaneRequestId(),
            "c".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null),
        WORLD_HOLD,
        WORLD_HOLD_FENCE);
  }

  private static byte[] captureReference(
      StartSessionPostAuthorizationExecutionTuple tuple,
      String sourceVersion,
      String sourceFence,
      String linearization) {
    return canonical(
        Map.of(
            "schema",
                AccountStartSessionAdmissionProtectionEvidence.SOURCE_CAPTURE_REFERENCE_SCHEMA,
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "capturedAt", "2026-10-09T10:11:12.123Z",
            "bundleReference",
                Map.of(
                    "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                    "sourceVersion", sourceVersion,
                    "sourceFence", sourceFence,
                    "linearization", linearization),
            "snapshotSha256", "d".repeat(64)));
  }

  private static SourceEvidence source(String exactEvidence) {
    return new SourceEvidence(
        SourceKind.ACCOUNT,
        TENANT.toString(),
        "2",
        "17",
        null,
        null,
        exactEvidence.getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, Object> readObject(byte[] bytes) throws IOException {
    return JSON.readValue(bytes, new TypeReference<>() {});
  }

  private static byte[] canonical(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static byte[] withDuplicateSchema(byte[] original) {
    String json = new String(original, StandardCharsets.UTF_8);
    String duplicated =
        json.replaceFirst(
            "\\{", "{\"schema\":\"account-start-session-admission-protection-request/v1\",");
    return duplicated.getBytes(StandardCharsets.UTF_8);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
