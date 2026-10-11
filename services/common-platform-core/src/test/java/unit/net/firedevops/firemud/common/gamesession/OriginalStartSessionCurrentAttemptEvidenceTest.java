package unit.net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OriginalStartSessionCurrentAttemptEvidenceTest {
  private static final UUID READ_ID = uuid("01111111-1111-4111-8111-111111111111");
  private static final UUID ATTEMPT = uuid("02222222-2222-4222-8222-222222222222");
  private static final UUID MUTATION = uuid("03333333-3333-4333-8333-333333333333");

  @Test
  void requestAndResultRetainExactOriginalBytesWithDefensiveCopies() {
    StartSessionPostAuthorizationExecutionTuple tuple = CurrentAttemptTestData.tuple();
    byte[] tupleBytes = tuple.canonicalBytes();
    Request request = new Request(READ_ID, "world-runtime", tupleBytes, ATTEMPT, MUTATION, 8L);
    tupleBytes[0] ^= 0x7f;
    byte[] projection =
        "exact-account-projection".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Result result =
        new Result(request, java.time.Instant.parse("2030-01-01T00:00:00Z"), projection);
    projection[0] ^= 0x7f;

    assertThat(request.canonicalPostAuthorizationTuple()).containsExactly(tuple.canonicalBytes());
    assertThat(request.originalTuple().canonicalBytes()).containsExactly(tuple.canonicalBytes());
    assertThat(result.accountRedemptionProjection())
        .containsExactly(
            "exact-account-projection".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(result.accountRedemptionProjectionDigest())
        .isEqualTo(
            OriginalStartSessionCurrentAttemptEvidence.projectionDigest(
                "exact-account-projection".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(result.phaseState())
        .isEqualTo(OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE);
  }

  @Test
  void rejectsChangedNamespaceNilCorrelationAttemptMutationAndNonpositiveFence() {
    byte[] tuple = CurrentAttemptTestData.tuple().canonicalBytes();
    assertThatThrownBy(() -> new Request(READ_ID, "other-runtime", tuple, ATTEMPT, MUTATION, 8L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact requested namespace");
    assertThatThrownBy(
            () -> new Request(new UUID(0L, 0L), "world-runtime", tuple, ATTEMPT, MUTATION, 8L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Request(READ_ID, "world-runtime", tuple, new UUID(0L, 0L), MUTATION, 8L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Request(READ_ID, "world-runtime", tuple, ATTEMPT, new UUID(0L, 0L), 8L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(READ_ID, "world-runtime", tuple, ATTEMPT, MUTATION, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(
            () ->
                new Result(
                    new Request(READ_ID, "world-runtime", tuple, ATTEMPT, MUTATION, 8L),
                    java.time.Instant.EPOCH,
                    new byte[0]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("projection");
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}

/** Shared same-package tuple fixture for the two focused codec/model tests. */
final class CurrentAttemptTestData {
  private static final UUID TENANT = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID RESERVATION_OWNER =
      UUID.fromString("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private CurrentAttemptTestData() {}

  static StartSessionPostAuthorizationExecutionTuple tuple() {
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "current-attempt-test",
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, "world-runtime"),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "canonical StartSession owner attempt"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/world-runtime/sa/logging-admin-service",
        FINGERPRINT,
        RESERVATION_OWNER,
        19L,
        authorityBundle(pre),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    String tenant = TENANT.toString();
    Map<String, Object> value =
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
            Map.of(
                "sourceType", "ACCOUNT",
                "sourceEvidenceId", digest('a'),
                "sourceEvidenceVersion", "17",
                "projectionStatus", "CURRENT",
                "evaluatedAt", "2026-10-09T00:00:00Z",
                "expiresAt", "2026-10-09T00:05:00Z"),
            "issuanceOperationIdentity",
            Map.of(
                "issuanceOperationId", ISSUANCE_ID.toString(),
                "controlPlaneRequestId", tuple.controlPlaneRequestId(),
                "actionFamilyRequestIdentity",
                    Map.of(
                        "requestIdentityKind",
                        "controlPlaneRequestId",
                        "requestId",
                        tuple.controlPlaneRequestId()),
                "mutationDigest", tuple.mutationDigest()),
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            Map.of(
                "issuerAuthGeneration", 1L,
                "accountAuthorityGeneration", 2L,
                "tenantAuthorityGeneration", Map.of(tenant, 3L),
                "membershipAuthorityGeneration", Map.of(tenant, 4L),
                "privateRealmGrantVersions", List.of()),
            "membershipVersion",
            Map.of(tenant, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
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
                "3"));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }
}
