package net.firedevops.firemud.worldmanagement.tenant;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Input;
import tools.jackson.databind.json.JsonMapper;

/** Explicitly synthetic World-only fixture identity; it is not Account or Game Session proof. */
final class WorldCanonicalInstanceExecutionTestFixtures {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private WorldCanonicalInstanceExecutionTestFixtures() {}

  static WorldCanonicalInstanceExecutionIdentity identity(Input input) {
    return identity(input, Instant.now().plusSeconds(900));
  }

  static WorldCanonicalInstanceExecutionIdentity identity(Input input, Instant expiresAt) {
    var world = input.gameSessionReadEvidence();
    var descriptor = input.completeLaunchBinding().evidence().descriptor();
    UUID actor = UUID.randomUUID();
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(
                world.canonicalTenantId(), world.targetNamespace()),
            new StartSessionOperatorAction.Target(descriptor.gameTemplateId(), actor),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "synthetic World execution fixture");
    StartSessionPreAuthorizationReservationTuple preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            world.controlPlaneRequestId(), actor, action);
    byte[] bundle = authorityBundle(preTuple, actor, expiresAt);
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            preTuple,
            "spiffe://firemud/ns/" + world.targetNamespace() + "/sa/logging-admin-service",
            "arfp/v1/test-key/" + "b".repeat(64),
            UUID.randomUUID(),
            19L,
            bundle,
            new StartSessionAuthorityEvidenceBundle.BundleReference(
                StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                "17",
                "23",
                "18446744073709551615"));
    return new WorldCanonicalInstanceExecutionIdentity(
        tuple.canonicalBytes(),
        UUID.randomUUID(),
        31L,
        UUID.randomUUID(),
        37L,
        world.canonicalGameInstanceId(),
        WorldCanonicalInstancePreparationRepository.inputJson(input));
  }

  static byte[] authorityBundle(
      StartSessionPreAuthorizationReservationTuple tuple, UUID actor, Instant expiresAt) {
    String tenantId = tuple.action().scope().tenantId().toString();
    Instant now = Instant.now();
    Map<String, Object> projection =
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
            canonicalTimestamp(now.minusSeconds(30)),
            "expiresAt",
            canonicalTimestamp(expiresAt));
    Map<String, Object> operation =
        Map.of(
            "issuanceOperationId", UUID.randomUUID().toString(),
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
            actor.toString(),
            "controlUiTokenJti",
            UUID.randomUUID().toString(),
            "role",
            "tenantAdmin",
            "accountGeneration",
            "2",
            "tenantGeneration",
            "3");
    Map<String, Object> bundle =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope",
                Map.of(
                    "tenantId",
                    tenantId,
                    "targetNamespace",
                    tuple.action().scope().targetNamespace()),
                "actionFamily",
                tuple.actionFamily(),
                "applicableAccountId",
                actor.toString(),
                "applicableTenantId",
                tenantId),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            operation,
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
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(bundle));
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Could not encode synthetic World authority fixture", exception);
    }
  }

  private static String canonicalTimestamp(Instant instant) {
    return Instant.ofEpochMilli(instant.toEpochMilli()).toString();
  }
}
