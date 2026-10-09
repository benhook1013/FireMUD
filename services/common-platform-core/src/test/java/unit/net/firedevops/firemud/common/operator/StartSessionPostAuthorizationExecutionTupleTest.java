package net.firedevops.firemud.common.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StartSessionPostAuthorizationExecutionTupleTest {
  private static final UUID TENANT = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID RESERVATION_OWNER =
      UUID.fromString("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String WORKLOAD =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);

  @Test
  void retainsTheCompletePreTupleAndExactAuthorityEvidenceThroughCanonicalRoundTrip()
      throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = create("post-tuple-roundtrip");
    byte[] bytes = tuple.canonicalBytes();
    StartSessionPostAuthorizationExecutionTuple decoded =
        StartSessionPostAuthorizationExecutionTuple.decode(bytes);

    assertThat(decoded.canonicalBytes()).containsExactly(bytes);
    assertThat(decoded.preAuthorizationTuple().canonicalJson())
        .isEqualTo(tuple.preAuthorizationTuple().canonicalJson());
    assertThat(decoded.controlPlaneRequestId()).isEqualTo("post-tuple-roundtrip");
    assertThat(decoded.mutationDigest()).matches("[0-9a-f]{64}");
    assertThat(decoded.issuanceKind()).isEqualTo("human_operator");
    assertThat(decoded.authenticatedWorkloadIdentity()).isEqualTo(WORKLOAD);
    assertThat(decoded.authorizationReferenceFingerprint()).isEqualTo(FINGERPRINT);
    assertThat(decoded.reservationOwnerId()).isEqualTo(RESERVATION_OWNER);
    assertThat(decoded.reservationClaimFence()).isEqualTo(19L);
    assertThat(decoded.issuanceFence()).isEqualTo("23");
    assertJsonNumber(decoded.authorityTuple().get("accountAuthorityGeneration"), 2L);
    assertJsonNumber(decoded.membershipVersion().get(TENANT.toString()), 5L);
  }

  @Test
  void rejectsWorkloadFingerprintReferenceAndDirectTupleSubstitution() throws Exception {
    StartSessionPreAuthorizationReservationTuple pre = preTuple("post-tuple-substitution");
    byte[] bundle = bundle(pre, "17");
    StartSessionAuthorityEvidenceBundle.BundleReference reference = reference("17");

    assertThatThrownBy(
            () ->
                StartSessionPostAuthorizationExecutionTuple.createHuman(
                    pre,
                    "spiffe://firemud/ns/another-runtime/sa/logging-admin-service",
                    FINGERPRINT,
                    RESERVATION_OWNER,
                    19L,
                    bundle,
                    reference))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("same-namespace");
    assertThatThrownBy(
            () ->
                StartSessionPostAuthorizationExecutionTuple.createHuman(
                    pre,
                    WORKLOAD,
                    "sha256:" + "b".repeat(64),
                    RESERVATION_OWNER,
                    19L,
                    bundle,
                    reference))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fingerprint");
    assertThatThrownBy(
            () ->
                StartSessionPostAuthorizationExecutionTuple.createHuman(
                    pre, WORKLOAD, FINGERPRINT, RESERVATION_OWNER, 19L, bundle, reference("18")))
        .isInstanceOf(IllegalArgumentException.class);

    Map<String, Object> changed =
        JSON.readValue(tupleBytes(create("post-tuple-substitution")), Map.class);
    changed.put("controlPlaneRequestId", "different-request");
    byte[] changedBytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(changed));
    assertThatThrownBy(() -> StartSessionPostAuthorizationExecutionTuple.decode(changedBytes))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void returnsDefensiveEvidenceCopiesAndRejectsNonCanonicalOrUnknownTupleFields() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = create("post-tuple-defensive");
    byte[] exposedTuple = tuple.canonicalBytes();
    byte[] exposedBundle = tuple.authorityEvidenceBundleBytes();
    exposedTuple[0] = (byte) '!';
    exposedBundle[0] = (byte) '!';
    assertThat(tuple.canonicalBytes()[0]).isNotEqualTo((byte) '!');
    assertThat(tuple.authorityEvidenceBundleBytes()[0]).isNotEqualTo((byte) '!');

    assertThatThrownBy(
            () ->
                StartSessionPostAuthorizationExecutionTuple.decode(
                    (tuple.canonicalJson() + " ")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);

    Map<String, Object> unknown = JSON.readValue(tuple.canonicalBytes(), Map.class);
    unknown.put("authorizationReference", "not persisted here");
    byte[] unknownBytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(unknown));
    assertThatThrownBy(() -> StartSessionPostAuthorizationExecutionTuple.decode(unknownBytes))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static StartSessionPostAuthorizationExecutionTuple create(String requestId) {
    StartSessionPreAuthorizationReservationTuple pre = preTuple(requestId);
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre, WORKLOAD, FINGERPRINT, RESERVATION_OWNER, 19L, bundle(pre, "17"), reference("17"));
  }

  private static StartSessionPreAuthorizationReservationTuple preTuple(String requestId) {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT, "world-runtime"),
            new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "canonical StartSession owner attempt");
    return StartSessionPreAuthorizationReservationTuple.createHuman(requestId, ACTOR, action);
  }

  private static byte[] bundle(StartSessionPreAuthorizationReservationTuple tuple, String version) {
    String tenantId = TENANT.toString();
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", version,
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-09T00:05:00Z");
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
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 2L,
            "tenantAuthorityGeneration", Map.of(tenantId, 3L),
            "membershipAuthorityGeneration", Map.of(tenantId, 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> evidence =
        Map.of(
            "evidenceType",
            StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId",
            ACTOR.toString(),
            "controlUiTokenJti",
            TOKEN_JTI.toString(),
            "role",
            "tenantAdmin",
            "accountGeneration",
            "2",
            "tenantGeneration",
            "3");
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenantId, "targetNamespace", "world-runtime"),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
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
            evidence);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static StartSessionAuthorityEvidenceBundle.BundleReference reference(String version) {
    return new StartSessionAuthorityEvidenceBundle.BundleReference(
        StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, version, "23", "18446744073709551615");
  }

  private static byte[] tupleBytes(StartSessionPostAuthorizationExecutionTuple tuple) {
    return tuple.canonicalBytes();
  }

  private static void assertJsonNumber(Object actual, long expected) {
    assertThat(actual).isInstanceOf(Number.class);
    assertThat(((Number) actual).longValue()).isEqualTo(expected);
  }
}
