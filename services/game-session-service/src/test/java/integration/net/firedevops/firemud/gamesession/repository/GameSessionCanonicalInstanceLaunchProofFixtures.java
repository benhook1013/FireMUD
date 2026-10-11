package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;

/**
 * Structurally complete but synthetic StartSession and release values for Game Session storage
 * tests. These values are test stipulations only; they do not prove authenticated Account or Game
 * Design owner transport, current authorization, or publication provenance.
 */
final class GameSessionCanonicalInstanceLaunchProofFixtures {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final UUID ACTOR_ID = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER_ID =
      UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID RESERVATION_OWNER_ID =
      UUID.fromString("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_OPERATION_ID =
      UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID CONTROL_UI_TOKEN_ID =
      UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final String RAW_SHA256 = "d".repeat(64);
  private static final String SHA256 = "sha256:" + RAW_SHA256;

  private GameSessionCanonicalInstanceLaunchProofFixtures() {}

  static SyntheticLaunchProof forRuntimeTarget(
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget target,
      AuthoredWorldSourceEvidence source) {
    return create(
        target.targetNamespace(),
        target.controlPlaneRequestId(),
        target.canonicalTenantId(),
        target.worldSlug(),
        source,
        1L,
        1L,
        1L,
        target.launchDescriptorId(),
        target.generationConfigRevision(),
        1L,
        target.publishedReleaseBundleRef(),
        target.canonicalVersionId());
  }

  static SyntheticLaunchProof create(
      String targetNamespace,
      String controlPlaneRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      AuthoredWorldSourceEvidence source,
      long gameTemplateId,
      long versionId,
      long releaseBundleId,
      String launchDescriptorId,
      String generationConfigRevision,
      long versionStateEpoch,
      String publishedReleaseBundleRef) {
    return create(
        targetNamespace,
        controlPlaneRequestId,
        canonicalTenantId,
        worldSlug,
        source,
        gameTemplateId,
        versionId,
        releaseBundleId,
        launchDescriptorId,
        generationConfigRevision,
        versionStateEpoch,
        publishedReleaseBundleRef,
        UUID.randomUUID());
  }

  private static SyntheticLaunchProof create(
      String targetNamespace,
      String controlPlaneRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      AuthoredWorldSourceEvidence source,
      long gameTemplateId,
      long versionId,
      long releaseBundleId,
      String launchDescriptorId,
      String generationConfigRevision,
      long versionStateEpoch,
      String publishedReleaseBundleRef,
      UUID canonicalVersionId) {
    var tuple = tuple(targetNamespace, controlPlaneRequestId, canonicalTenantId, gameTemplateId);
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            targetNamespace,
            controlPlaneRequestId,
            canonicalTenantId,
            worldSlug,
            source.operationId(),
            source.evidenceDigest(),
            gameTemplateId,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            launchDescriptorId,
            versionId,
            false,
            null,
            "{}",
            generationConfigRevision,
            versionStateEpoch,
            releaseBundleId,
            publishedReleaseBundleRef,
            false,
            null);
    String commitId = "synthetic-selected-release-commit";
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                participantKey ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        participantKey,
                        Long.toString(versionId),
                        false,
                        null,
                        commitId,
                        RAW_SHA256,
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            participantKey),
                        "GAME_LOGIC".equals(participantKey),
                        "GAME_LOGIC".equals(participantKey) ? SHA256 : null))
            .toList();
    var release =
        AuthoredWorldReleaseAttestationEvidence.create(
            targetNamespace,
            descriptor.resultDigest(),
            canonicalTenantId,
            canonicalVersionId,
            worldSlug,
            source.operationId(),
            source.evidenceDigest(),
            launchDescriptorId,
            publishedReleaseBundleRef,
            versionStateEpoch,
            "synthetic-publish-workflow",
            commitId,
            participants,
            SHA256,
            1,
            List.of(),
            List.of(),
            List.of(),
            generationConfigRevision);
    return new SyntheticLaunchProof(tuple, new CompleteLaunchBindingEvidence(descriptor, release));
  }

  static UUID targetOwnerId() {
    return TARGET_OWNER_ID;
  }

  static StartSessionPostAuthorizationExecutionTuple withAuditReason(
      StartSessionPostAuthorizationExecutionTuple original, String auditReason) {
    var previous = original.preAuthorizationTuple();
    var previousAction = previous.action();
    var changedAction =
        new StartSessionOperatorAction(
            previousAction.actionFamilySchemaId(),
            previousAction.actionFamilySchemaVersion(),
            previousAction.scope(),
            previousAction.target(),
            previousAction.expectedVersion(),
            previousAction.mutation(),
            auditReason);
    var changedPreTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            previous.controlPlaneRequestId(), previous.actor().accountId(), changedAction);
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        changedPreTuple,
        original.authenticatedWorkloadIdentity(),
        original.authorizationReferenceFingerprint(),
        original.reservationOwnerId(),
        original.reservationClaimFence(),
        bundle(changedPreTuple),
        original.bundleReference());
  }

  static String completeLaunchBindingJson(CompleteLaunchBindingEvidence binding) {
    try {
      return JSON.writeValueAsString(binding);
    } catch (IOException invalid) {
      throw new IllegalStateException("Synthetic launch binding cannot be serialized", invalid);
    }
  }

  private static StartSessionPostAuthorizationExecutionTuple tuple(
      String targetNamespace,
      String controlPlaneRequestId,
      UUID canonicalTenantId,
      long gameTemplateId) {
    var action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(canonicalTenantId, targetNamespace),
            new StartSessionOperatorAction.Target(gameTemplateId, TARGET_OWNER_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "synthetic canonical launch storage fixture");
    var pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            controlPlaneRequestId, ACTOR_ID, action);
    var reference =
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "17", "23", "18446744073709551615");
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/" + targetNamespace + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        RESERVATION_OWNER_ID,
        19L,
        bundle(pre),
        reference);
  }

  private static byte[] bundle(StartSessionPreAuthorizationReservationTuple tuple) {
    String tenantId = tuple.action().scope().tenantId().toString();
    String targetNamespace = tuple.action().scope().targetNamespace();
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenantId, "targetNamespace", targetNamespace),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR_ID.toString(),
                "applicableTenantId", tenantId),
            "accountProjectionEvidence",
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
                "2099-01-01T00:00:00Z",
                "expiresAt",
                "2099-01-01T00:05:00Z"),
            "issuanceOperationIdentity",
            Map.of(
                "issuanceOperationId",
                ISSUANCE_OPERATION_ID.toString(),
                "controlPlaneRequestId",
                tuple.controlPlaneRequestId(),
                "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
                "mutationDigest",
                tuple.mutationDigest()),
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            Map.of(
                "issuerAuthGeneration",
                1L,
                "accountAuthorityGeneration",
                2L,
                "tenantAuthorityGeneration",
                Map.of(tenantId, 3L),
                "membershipAuthorityGeneration",
                Map.of(tenantId, 4L),
                "privateRealmGrantVersions",
                List.of()),
            "membershipVersion",
            Map.of(tenantId, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            Map.of(
                "evidenceType",
                StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                "actorAccountId",
                ACTOR_ID.toString(),
                "controlUiTokenJti",
                CONTROL_UI_TOKEN_ID.toString(),
                "role",
                "tenantAdmin",
                "accountGeneration",
                "2",
                "tenantGeneration",
                "3"));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException invalid) {
      throw new IllegalStateException("Synthetic Account bundle cannot be serialized", invalid);
    }
  }

  record SyntheticLaunchProof(
      StartSessionPostAuthorizationExecutionTuple originalTuple,
      CompleteLaunchBindingEvidence completeLaunchBindingEvidence) {}
}
