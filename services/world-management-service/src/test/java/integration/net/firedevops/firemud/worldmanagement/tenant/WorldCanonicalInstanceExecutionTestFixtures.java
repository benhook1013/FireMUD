package net.firedevops.firemud.worldmanagement.tenant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Input;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.OriginalStartSessionOperationInput;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wire-valid but synthetic Account and Game Session observation used only by isolated World proofs.
 */
final class WorldCanonicalInstanceExecutionTestFixtures {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant DEFAULT_EXPIRY = Instant.parse("2099-01-01T00:15:00Z");

  private WorldCanonicalInstanceExecutionTestFixtures() {}

  static WorldCanonicalInstanceExecutionIdentity identity(Input input) {
    return identity(input, DEFAULT_EXPIRY);
  }

  static WorldCanonicalInstanceExecutionIdentity identity(Input input, Instant expiresAt) {
    var descriptor = input.completeLaunchBinding().evidence().descriptor();
    return identity(
        input,
        operationInput(input.gameSessionReadRequest(), descriptor.gameTemplateId(), expiresAt));
  }

  static WorldCanonicalInstanceExecutionIdentity identity(
      Input input, OriginalStartSessionOperationInput operationInput) {
    String seed =
        operationSeed(
            input.gameSessionReadRequest(),
            input.completeLaunchBinding().evidence().descriptor().gameTemplateId());
    String exactInputJson = WorldCanonicalInstancePreparationRepository.inputJson(input);
    var world = input.gameSessionReadEvidence();
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(
            operationInput.originalPostAuthorizationTuple());
    UUID accountParticipationId = stableUuid(seed, "account-participation");
    long accountParticipationFence = 31L;
    UUID ownerMutationId = operationInput.gameSessionOwnerMutationId();
    UUID ownerAttemptId = operationInput.gameSessionOwnerAttemptId();
    long ownerFence = operationInput.gameSessionOwnerFence();
    var ownerReadRequest =
        new OriginalStartSessionCurrentAttemptEvidence.Request(
            stableUuid(seed, "game-session-read-request"),
            world.targetNamespace(),
            tuple.canonicalBytes(),
            ownerAttemptId,
            ownerMutationId,
            ownerFence);
    var observation =
        new OriginalStartSessionCurrentAttemptEvidence.Result(
            ownerReadRequest,
            StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes())
                .expiresAt(),
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple));
    byte[] exactObservationBytes =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(observation).toByteArray();
    return new WorldCanonicalInstanceExecutionIdentity(
        tuple.canonicalBytes(),
        accountParticipationId,
        accountParticipationFence,
        ownerMutationId,
        ownerAttemptId,
        ownerFence,
        observation,
        world.canonicalGameInstanceId(),
        exactInputJson,
        exactObservationBytes);
  }

  static OriginalStartSessionOperationInput operationInput(
      WorldCanonicalInstanceAssociation.GameSessionReadRequest world, long gameTemplateId) {
    return operationInput(world, gameTemplateId, DEFAULT_EXPIRY);
  }

  private static OriginalStartSessionOperationInput operationInput(
      WorldCanonicalInstanceAssociation.GameSessionReadRequest world,
      long gameTemplateId,
      Instant expiresAt) {
    String seed = operationSeed(world, gameTemplateId);
    UUID actor = stableUuid(seed, "actor");
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(
                world.canonicalTenantId(), world.targetNamespace()),
            new StartSessionOperatorAction.Target(gameTemplateId, actor),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "synthetic World execution fixture");
    StartSessionPreAuthorizationReservationTuple preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            world.controlPlaneRequestId(), actor, action);
    byte[] bundle = authorityBundle(preTuple, actor, expiresAt, seed);
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            preTuple,
            "spiffe://firemud/ns/" + world.targetNamespace() + "/sa/logging-admin-service",
            "arfp/v1/test-key/" + "b".repeat(64),
            stableUuid(seed, "reservation-owner"),
            19L,
            bundle,
            new StartSessionAuthorityEvidenceBundle.BundleReference(
                StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                "17",
                "23",
                "18446744073709551615"));
    return new OriginalStartSessionOperationInput(
        tuple.canonicalBytes(),
        stableUuid(seed, "game-session-mutation"),
        stableUuid(seed, "game-session-attempt"),
        37L);
  }

  static OriginalStartSessionOperationInput operationInput(
      WorldCanonicalInstanceExecutionIdentity identity) {
    return new OriginalStartSessionOperationInput(
        identity.originalPostAuthorizationTuple(),
        identity.gameSessionOwnerMutationId(),
        identity.gameSessionOwnerAttemptId(),
        identity.gameSessionOwnerFence());
  }

  static OwnerOperation ownerOperation(Input input) {
    OriginalStartSessionOperationInput operationInput =
        operationInput(
            input.gameSessionReadRequest(),
            input.completeLaunchBinding().evidence().descriptor().gameTemplateId());
    return new OwnerOperation(operationInput, identity(input, operationInput));
  }

  private static String operationSeed(
      WorldCanonicalInstanceAssociation.GameSessionReadRequest world, long gameTemplateId) {
    return world.targetNamespace()
        + ":"
        + world.canonicalTenantId()
        + ":"
        + world.controlPlaneRequestId()
        + ":"
        + gameTemplateId;
  }

  static WorldCanonicalInstanceExecutionIdentity withFences(
      WorldCanonicalInstanceExecutionIdentity identity,
      long accountParticipationFence,
      long ownerFence) {
    var previousObservation = observation(identity);
    var ownerReadRequest =
        new OriginalStartSessionCurrentAttemptEvidence.Request(
            identity.originalAttemptReadRequestId(),
            identity.targetNamespace(),
            identity.originalPostAuthorizationTuple(),
            identity.gameSessionOwnerAttemptId(),
            identity.gameSessionOwnerMutationId(),
            ownerFence);
    var updatedObservation =
        new OriginalStartSessionCurrentAttemptEvidence.Result(
            ownerReadRequest,
            previousObservation.originalLeaseExpiresAt(),
            previousObservation.accountRedemptionProjection());
    byte[] updatedObservationBytes =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(updatedObservation)
            .toByteArray();
    return new WorldCanonicalInstanceExecutionIdentity(
        identity.originalPostAuthorizationTuple(),
        identity.accountWorldParticipationId(),
        accountParticipationFence,
        identity.gameSessionOwnerMutationId(),
        identity.gameSessionOwnerAttemptId(),
        ownerFence,
        updatedObservation,
        identity.canonicalGameInstanceId(),
        identity.preparationInputJson(),
        updatedObservationBytes);
  }

  static WorldCanonicalInstanceExecutionIdentity withParticipationId(
      WorldCanonicalInstanceExecutionIdentity identity, UUID participationId) {
    return copyWithOwnerObservation(
        identity,
        participationId,
        identity.gameSessionOwnerAttemptId(),
        identity.gameSessionOwnerFence());
  }

  static WorldCanonicalInstanceExecutionIdentity withOwnerAttemptId(
      WorldCanonicalInstanceExecutionIdentity identity, UUID ownerAttemptId) {
    return copyWithOwnerObservation(
        identity,
        identity.accountWorldParticipationId(),
        ownerAttemptId,
        identity.gameSessionOwnerFence());
  }

  static WorldCanonicalInstanceExecutionIdentity withPreparationInputJson(
      WorldCanonicalInstanceExecutionIdentity identity, String preparationInputJson) {
    return new WorldCanonicalInstanceExecutionIdentity(
        identity.originalPostAuthorizationTuple(),
        identity.accountWorldParticipationId(),
        identity.accountWorldParticipationFence(),
        identity.gameSessionOwnerMutationId(),
        identity.gameSessionOwnerAttemptId(),
        identity.gameSessionOwnerFence(),
        observation(identity),
        identity.canonicalGameInstanceId(),
        preparationInputJson,
        identity.originalAttemptEvidenceBytes());
  }

  private static WorldCanonicalInstanceExecutionIdentity copyWithOwnerObservation(
      WorldCanonicalInstanceExecutionIdentity identity,
      UUID participationId,
      UUID ownerAttemptId,
      long ownerFence) {
    var previousObservation = observation(identity);
    var ownerReadRequest =
        new OriginalStartSessionCurrentAttemptEvidence.Request(
            identity.originalAttemptReadRequestId(),
            identity.targetNamespace(),
            identity.originalPostAuthorizationTuple(),
            ownerAttemptId,
            identity.gameSessionOwnerMutationId(),
            ownerFence);
    var updatedObservation =
        new OriginalStartSessionCurrentAttemptEvidence.Result(
            ownerReadRequest,
            previousObservation.originalLeaseExpiresAt(),
            previousObservation.accountRedemptionProjection());
    byte[] updatedObservationBytes =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(updatedObservation)
            .toByteArray();
    return new WorldCanonicalInstanceExecutionIdentity(
        identity.originalPostAuthorizationTuple(),
        participationId,
        identity.accountWorldParticipationFence(),
        identity.gameSessionOwnerMutationId(),
        ownerAttemptId,
        ownerFence,
        updatedObservation,
        identity.canonicalGameInstanceId(),
        identity.preparationInputJson(),
        updatedObservationBytes);
  }

  private static OriginalStartSessionCurrentAttemptEvidence.Result observation(
      WorldCanonicalInstanceExecutionIdentity identity) {
    try {
      var wire =
          net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionCurrentAttemptResponse
              .parseFrom(identity.originalAttemptEvidenceBytes());
      return OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromRequest(wire.getRequest()), wire);
    } catch (com.google.protobuf.InvalidProtocolBufferException malformed) {
      throw new IllegalArgumentException(
          "Synthetic exact owner observation bytes are malformed", malformed);
    }
  }

  record OwnerOperation(
      OriginalStartSessionOperationInput operationInput,
      WorldCanonicalInstanceExecutionIdentity identity) {}

  static byte[] authorityBundle(
      StartSessionPreAuthorizationReservationTuple tuple, UUID actor, Instant expiresAt) {
    return authorityBundle(tuple, actor, expiresAt, UUID.randomUUID().toString());
  }

  private static byte[] authorityBundle(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID actor,
      Instant expiresAt,
      String seed) {
    String tenantId = tuple.action().scope().tenantId().toString();
    Instant now = expiresAt.minusSeconds(60L);
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
            "issuanceOperationId", stableUuid(seed, "issuance-operation").toString(),
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
            stableUuid(seed, "control-ui-token").toString(),
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

  private static UUID stableUuid(String seed, String label) {
    UUID value = UUID.nameUUIDFromBytes((seed + ":" + label).getBytes(StandardCharsets.UTF_8));
    return value.equals(new UUID(0L, 0L))
        ? UUID.nameUUIDFromBytes((seed + ":" + label + ":nonzero").getBytes(StandardCharsets.UTF_8))
        : value;
  }
}
