package net.firedevops.firemud.common.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class StartSessionAuthorityEvidenceBundleTest {
  private static final UUID TENANT_ID = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR_ID = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID OWNER_ACCOUNT_ID =
      UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID ISSUANCE_ID = UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final String EVALUATED_AT = "2026-10-09T00:00:00Z";
  private static final String EXPIRES_AT = "2026-10-09T00:05:00Z";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void decodesCanonicalTenantAdminBundleAndBindsTupleAndSeparateReference() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("bundle-βeta");
    byte[] bytes = canonical(validValue(tuple));
    StartSessionAuthorityEvidenceBundle.BundleReference reference = reference();

    StartSessionAuthorityEvidenceBundle decoded = StartSessionAuthorityEvidenceBundle.decode(bytes);
    decoded.requireTupleBinding(tuple);
    decoded.requireReferenceBinding(reference);

    assertThat(decoded.canonicalBytes()).containsExactly(bytes);
    assertThat(decoded.actionFamily()).isEqualTo("StartSession");
    assertThat(decoded.actorAccountId()).isEqualTo(ACTOR_ID);
    assertThat(decoded.tenantId()).isEqualTo(TENANT_ID);
    assertThat(decoded.targetNamespace()).isEqualTo("world-runtime");
    assertThat(decoded.controlPlaneRequestId()).isEqualTo("bundle-βeta");
    assertThat(decoded.mutationDigest()).isEqualTo(tuple.mutationDigest()).matches("[0-9a-f]{64}");
    assertThat(decoded.expiresAt()).isEqualTo(Instant.parse(EXPIRES_AT));
    assertThat(decoded.issuanceFence()).isEqualTo("23");
    assertThat(decoded.evidenceType())
        .isEqualTo(StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE);
    assertThat(decoded.role()).isEqualTo("tenantAdmin");

    byte[] exposed = decoded.canonicalBytes();
    exposed[0] = (byte) '!';
    assertThat(decoded.canonicalBytes()).containsExactly(bytes);
  }

  @Test
  void exposesOnlyDeeplyImmutableJsonValue() throws Exception {
    byte[] bytes = canonical(validValue(tuple("bundle-immutable-json")));
    StartSessionAuthorityEvidenceBundle decoded = StartSessionAuthorityEvidenceBundle.decode(bytes);
    Map<String, Object> jsonValue = decoded.jsonValue();
    Map<String, Object> authorityTuple = object(jsonValue.get("authorityTuple"));
    List<?> grantVersions = (List<?>) authorityTuple.get("privateRealmGrantVersions");

    assertThatThrownBy(() -> jsonValue.put("unexpected", true))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> authorityTuple.put("unexpected", true))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(grantVersions::clear).isInstanceOf(UnsupportedOperationException.class);
    assertThat(decoded.canonicalBytes()).containsExactly(bytes);
  }

  @Test
  void rejectsChangedTupleActorScopeRequestOrRawMutationDigest() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("bundle-binding");
    byte[] bytes = canonical(validValue(tuple));
    StartSessionOperatorAction changedAction =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, "world-runtime"),
            new StartSessionOperatorAction.Target(91L, OWNER_ACCOUNT_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(
                StartSessionOperatorAction.ClientIp.of("203.0.113.19")),
            "bundle codec contract");
    StartSessionPreAuthorizationReservationTuple changedDigest =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            tuple.controlPlaneRequestId(), ACTOR_ID, changedAction);
    StartSessionPreAuthorizationReservationTuple changedRequest = tuple("other-request");

    assertThatThrownBy(
            () ->
                StartSessionAuthorityEvidenceBundle.decode(bytes)
                    .requireTupleBinding(changedDigest))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                StartSessionAuthorityEvidenceBundle.decode(bytes)
                    .requireTupleBinding(changedRequest))
        .isInstanceOf(IllegalArgumentException.class);

    Map<String, Object> prefixedDigest = read(bytes);
    object(prefixedDigest.get("issuanceOperationIdentity"))
        .put("mutationDigest", "sha256:" + tuple.mutationDigest());
    assertThatThrownBy(() -> StartSessionAuthorityEvidenceBundle.decode(canonical(prefixedDigest)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresRawLowercaseSha256MutationDigestAndRejectsChangedIdentityFields() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("bundle-operation");
    Map<String, Object> changedIdentity = mutableValue(tuple);
    object(
            object(changedIdentity.get("issuanceOperationIdentity"))
                .get("actionFamilyRequestIdentity"))
        .put("requestId", "different-request");
    assertThatThrownBy(() -> StartSessionAuthorityEvidenceBundle.decode(canonical(changedIdentity)))
        .isInstanceOf(IllegalArgumentException.class);

    Map<String, Object> uppercaseDigest = mutableValue(tuple);
    object(uppercaseDigest.get("issuanceOperationIdentity")).put("mutationDigest", "A".repeat(64));
    assertThatThrownBy(() -> StartSessionAuthorityEvidenceBundle.decode(canonical(uppercaseDigest)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAutomationGlobalRoleExtraAssuranceAndUnknownFields() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("bundle-closed");

    Map<String, Object> automation = mutableValue(tuple);
    automation.put("issuanceKind", "automation_operator");
    assertThatThrownBy(() -> StartSessionAuthorityEvidenceBundle.decode(canonical(automation)))
        .isInstanceOf(IllegalArgumentException.class);

    Map<String, Object> globalRole = mutableValue(tuple);
    object(globalRole.get("issuanceEvidence")).put("role", "platformAdmin");
    assertThatThrownBy(() -> StartSessionAuthorityEvidenceBundle.decode(canonical(globalRole)))
        .isInstanceOf(IllegalArgumentException.class);

    Map<String, Object> assurance = mutableValue(tuple);
    object(assurance.get("issuanceEvidence")).put("assurance", "not-applicable");
    assertThatThrownBy(() -> StartSessionAuthorityEvidenceBundle.decode(canonical(assurance)))
        .isInstanceOf(IllegalArgumentException.class);

    Map<String, Object> unknown = mutableValue(tuple);
    unknown.put("sourceReference", Map.of("unexpected", true));
    assertThatThrownBy(() -> StartSessionAuthorityEvidenceBundle.decode(canonical(unknown)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMalformedUtf8DuplicateKeysTrailingTokensAndNoncanonicalBytes() throws Exception {
    byte[] valid = canonical(validValue(tuple("bundle-json")));
    byte[] malformedUtf8 = Arrays.copyOf(valid, valid.length + 1);
    malformedUtf8[malformedUtf8.length - 1] = (byte) 0xff;
    String duplicate =
        new String(valid, StandardCharsets.UTF_8)
            .replaceFirst("\\{", "{\"bundleVersion\":\"authorityEvidenceBundle/v1\",");

    assertThatThrownBy(() -> StartSessionAuthorityEvidenceBundle.decode(malformedUtf8))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                StartSessionAuthorityEvidenceBundle.decode(
                    duplicate.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                StartSessionAuthorityEvidenceBundle.decode(
                    (new String(valid, StandardCharsets.UTF_8) + "{}")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                StartSessionAuthorityEvidenceBundle.decode(
                    (new String(valid, StandardCharsets.UTF_8) + " ")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                StartSessionAuthorityEvidenceBundle.decode(Arrays.copyOf(valid, valid.length - 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInvalidExpiryAndReferenceOverflowOrMismatch() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("bundle-expiry");
    Map<String, Object> expiredAtEvaluation = mutableValue(tuple);
    object(expiredAtEvaluation.get("accountProjectionEvidence")).put("expiresAt", EVALUATED_AT);
    assertThatThrownBy(
            () -> StartSessionAuthorityEvidenceBundle.decode(canonical(expiredAtEvaluation)))
        .isInstanceOf(IllegalArgumentException.class);

    StartSessionAuthorityEvidenceBundle decoded =
        StartSessionAuthorityEvidenceBundle.decode(canonical(validValue(tuple)));
    assertThatThrownBy(
            () ->
                decoded.requireReferenceBinding(
                    new StartSessionAuthorityEvidenceBundle.BundleReference(
                        StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "18", "23", "17")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                decoded.requireReferenceBinding(
                    new StartSessionAuthorityEvidenceBundle.BundleReference(
                        StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "17", "24", "17")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StartSessionAuthorityEvidenceBundle.BundleReference(
                    StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                    "9223372036854775808",
                    "23",
                    "17"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StartSessionAuthorityEvidenceBundle.BundleReference(
                    StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                    "17",
                    "23",
                    "18446744073709551616"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOversizedBundlesAndOutOfScopeTenantAuthority() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("bundle-bounds");
    assertThatThrownBy(
            () ->
                StartSessionAuthorityEvidenceBundle.decode(
                    new byte[StartSessionAuthorityEvidenceBundle.MAX_BUNDLE_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);

    Map<String, Object> widened = mutableValue(tuple);
    Map<String, Object> authority = object(widened.get("authorityTuple"));
    authority.put(
        "tenantAuthorityGeneration",
        Map.of(TENANT_ID.toString(), 5L, UUID.randomUUID().toString(), 2L));
    assertThatThrownBy(() -> StartSessionAuthorityEvidenceBundle.decode(canonical(widened)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static StartSessionPreAuthorizationReservationTuple tuple(String requestId) {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, "world-runtime"),
            new StartSessionOperatorAction.Target(91L, OWNER_ACCOUNT_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "bundle codec contract");
    return StartSessionPreAuthorizationReservationTuple.createHuman(requestId, ACTOR_ID, action);
  }

  private static Map<String, Object> validValue(
      StartSessionPreAuthorizationReservationTuple tuple) {
    String tenantId = TENANT_ID.toString();
    Map<String, Object> projection =
        new LinkedHashMap<>(
            Map.of(
                "sourceType",
                "ACCOUNT",
                "sourceEvidenceId",
                "sha256:" + "a".repeat(64),
                "sourceEvidenceVersion",
                "17",
                "projectionStatus",
                "CURRENT",
                "evaluatedAt",
                EVALUATED_AT,
                "expiresAt",
                EXPIRES_AT));
    Map<String, Object> identity =
        Map.of(
            "issuanceOperationId", ISSUANCE_ID.toString(),
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> authority =
        new LinkedHashMap<>(
            Map.of(
                "issuerAuthGeneration", 1L,
                "accountAuthorityGeneration", 2L,
                "tenantAuthorityGeneration", Map.of(tenantId, 3L),
                "membershipAuthorityGeneration", Map.of(tenantId, 4L),
                "privateRealmGrantVersions", List.of()));
    Map<String, Object> evidence =
        new LinkedHashMap<>(
            Map.of(
                "evidenceType",
                StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                "actorAccountId",
                ACTOR_ID.toString(),
                "controlUiTokenJti",
                TOKEN_JTI.toString(),
                "role",
                "tenantAdmin",
                "accountGeneration",
                "2",
                "tenantGeneration",
                "3"));
    return new LinkedHashMap<>(
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenantId, "targetNamespace", "world-runtime"),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR_ID.toString(),
                "applicableTenantId", tenantId),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            identity,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(tenantId, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            evidence));
  }

  private static Map<String, Object> mutableValue(
      StartSessionPreAuthorizationReservationTuple tuple) throws Exception {
    return read(canonical(validValue(tuple)));
  }

  private static StartSessionAuthorityEvidenceBundle.BundleReference reference() {
    return new StartSessionAuthorityEvidenceBundle.BundleReference(
        StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "17", "23", "18446744073709551615");
  }

  private static byte[] canonical(Object value) throws IOException {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static Map<String, Object> read(byte[] bytes) throws IOException {
    return JSON.readValue(bytes, new TypeReference<>() {});
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }
}
