package net.firedevops.firemud.accountservice.service.session;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import tools.jackson.databind.json.JsonMapper;

/** Minimal complete canonical source request fixture for the unregistered gRPC transport tests. */
final class AccountStartSessionAdmissionProtectionGrpcTestFixture {
  private static final UUID TENANT = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AccountStartSessionAdmissionProtectionGrpcTestFixture() {}

  static AccountStartSessionAdmissionProtectionRequest request(String namespace) {
    var pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "grpc-admission-transport",
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, namespace),
                new StartSessionOperatorAction.Target(
                    91L, UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a")),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "canonical original StartSession admission attempt"));
    var tuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            pre,
            "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
            "arfp/v1/test-key/" + "b".repeat(64),
            UUID.fromString("7c005b65-fcb1-4ac9-a714-f3d0f449edcf"),
            19L,
            authorityBundle(namespace, pre),
            new StartSessionAuthorityEvidenceBundle.BundleReference(
                StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                "17",
                "23",
                "18446744073709551615"));
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            new WorldCanonicalInitialAdmissionHold.Request(
                namespace,
                TENANT,
                "earth",
                UUID.fromString("3916f423-2870-426a-a8aa-5e3f97412613"),
                UUID.fromString("54e6094e-11bb-4f4c-93ee-a52f715b530b"),
                "SHARED",
                UUID.fromString("a55b2e10-9a24-4adb-adb8-6fc66fe3b8e9"),
                UUID.fromString("d7280ec0-5979-4b62-8418-e9f139415184"),
                3L,
                tuple.controlPlaneRequestId(),
                "c".repeat(64),
                WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
                12L,
                null),
            UUID.fromString("0db7344a-1e67-4b95-905a-83dc9c472f0c"),
            UUID.fromString("52a14272-f9e4-4f67-97c9-62247b5fbcc1"));
    return AccountStartSessionAdmissionProtectionRequest.create(
        tuple.canonicalBytes(),
        StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
        UUID.fromString("f1a3ab1e-9147-4667-b6c4-6eb5119e8a31"),
        UUID.fromString("ec13cc04-ec15-4eb8-a018-c2c5e8da65f8"),
        7L,
        Instant.parse("2026-10-09T10:20:30.456789Z"),
        UUID.fromString("47b3be7f-a32f-4e19-8916-8c3b8da07a82"),
        11L,
        hold);
  }

  private static byte[] authorityBundle(
      String namespace, StartSessionPreAuthorizationReservationTuple tuple) {
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
    Map<String, Object> issuance =
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
                "scope", Map.of("tenantId", tenant, "targetNamespace", namespace),
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
            issuance);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(bundle));
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException("Unable to encode gRPC test fixture", impossible);
    }
  }
}
