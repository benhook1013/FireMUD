package net.firedevops.firemud.gamesession.repository;

import com.google.protobuf.MessageLite;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import net.firedevops.firemud.gamesession.client.CanonicalPreseededActorRuntimeReferenceClient;
import net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus;
import net.firedevops.firemud.gamesession.service.CanonicalInitialPlayerLocationRegionResolver;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceResponse;

/** Closed digest projections for exact bridge sources and independently observed readback. */
final class CanonicalGameplayRegionBindingBridgeProofs {
  private CanonicalGameplayRegionBindingBridgeProofs() {}

  static String entityReferenceDigest(
      CanonicalPreseededActorRuntimeReferenceClient.RuntimeActorReference reference) {
    var response =
        ReadPreseededActorRuntimeReferenceResponse.newBuilder()
            .setSchemaVersion(1)
            .setAssignmentUuid(reference.assignmentUuid().toString())
            .setCanonicalAccountUuid(reference.canonicalAccountUuid().toString())
            .setCharacterUuid(reference.characterUuid().toString())
            .setIntentDigest(reference.intentDigest())
            .setExpectedTarget(reference.target())
            .setPublishedOwnerProofDigest(reference.publishedOwnerProofDigest())
            .setRuntimeEntityId(reference.runtimeEntityId().value())
            .setProjectionGeneration(reference.projectionGeneration())
            .setProjectionFence(reference.projectionFence())
            .setCorePayload(reference.corePayload())
            .build();
    return sha256(
        reference.workloadNamespace().getBytes(StandardCharsets.UTF_8), response.toByteArray());
  }

  static String worldSourceDigest(
      CanonicalInitialPlayerLocationRequest request,
      CanonicalInitialPlayerLocationRegionResolver.ResolvedRegion resolved) {
    var assignment = resolved.lifecycleAssignment();
    return sha256(
        request.toByteArray(),
        resolved.observation().toByteArray(),
        assignment.request().canonicalBytes(),
        assignment.lifecycleStatus().getBytes(StandardCharsets.US_ASCII),
        Long.toString(assignment.lifecycleEpoch()).getBytes(StandardCharsets.US_ASCII),
        Long.toString(assignment.lifecycleRowVersion()).getBytes(StandardCharsets.US_ASCII),
        assignment.canonicalRegionId().toString().getBytes(StandardCharsets.US_ASCII),
        assignment.operationalRegionId().toString().getBytes(StandardCharsets.US_ASCII),
        assignment.captureId().toString().getBytes(StandardCharsets.US_ASCII),
        assignment.graphSha256().getBytes(StandardCharsets.US_ASCII),
        assignment.preparationInputDigest().getBytes(StandardCharsets.US_ASCII));
  }

  static String timelinePreparationDigest(
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      CanonicalInitialPlayerLocationRequest request,
      CanonicalInitialPlayerLocationRegionResolver.ResolvedRegion resolved,
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      String worldDigest,
      CanonicalGameplayRegionIncarnationSource.StableEvidence incarnation,
      String absenceProofDigest) {
    var identity = evidence.candidate().identity();
    return sha256(
        uuid(evidence.transition().transitionId()),
        evidence.candidate().bindingRef().bytes(),
        identity.accountId().toString().getBytes(StandardCharsets.US_ASCII),
        identity.tenantId().toString().getBytes(StandardCharsets.US_ASCII),
        identity.playableStateNamespaceId().toString().getBytes(StandardCharsets.US_ASCII),
        identity.characterId().toString().getBytes(StandardCharsets.US_ASCII),
        identity.playableStateScope().getBytes(StandardCharsets.US_ASCII),
        identity.gameInstanceId().toString().getBytes(StandardCharsets.US_ASCII),
        Long.toString(identity.runtimeGameInstanceId()).getBytes(StandardCharsets.US_ASCII),
        identity.sessionId().getBytes(StandardCharsets.US_ASCII),
        evidence.candidate().bindingGeneration().toString().getBytes(StandardCharsets.US_ASCII),
        identity.regionId().toString().getBytes(StandardCharsets.US_ASCII),
        identity.regionEpoch().toString().getBytes(StandardCharsets.US_ASCII),
        source.canonicalRegionId().toString().getBytes(StandardCharsets.US_ASCII),
        source.regionId().toString().getBytes(StandardCharsets.US_ASCII),
        CanonicalGameplayRegionRedisKeys.tenantRegionTag(
                source.tenantId(), source.gameInstanceId(), source.regionId())
            .getBytes(StandardCharsets.US_ASCII),
        source.lifecycleRequest().canonicalBytes(),
        Long.toString(source.lifecycleEpoch()).getBytes(StandardCharsets.US_ASCII),
        Long.toString(source.lifecycleRowVersion()).getBytes(StandardCharsets.US_ASCII),
        source.captureId().toString().getBytes(StandardCharsets.US_ASCII),
        source.graphSha256().getBytes(StandardCharsets.US_ASCII),
        source.preparationInputDigest().getBytes(StandardCharsets.US_ASCII),
        worldDigest.getBytes(StandardCharsets.US_ASCII),
        incarnation.scopeBytes(),
        incarnation.canonicalBytes(),
        absenceProofDigest.getBytes(StandardCharsets.US_ASCII),
        request.toByteArray(),
        resolved.observation().toByteArray());
  }

  static String initialMetaDigest(String tag, UUID operationId, String sourceDigest) {
    String baseline =
        "region_epoch=1\n"
            + "current_tick_id=-1\n"
            + "current_tick_state=APPLIED\n"
            + "first_timeline_operation_id="
            + operationId
            + "\nfirst_timeline_source_digest="
            + sourceDigest;
    return sha256(tag.getBytes(StandardCharsets.US_ASCII), baseline.getBytes(StandardCharsets.US_ASCII));
  }

  private static byte[] uuid(UUID value) {
    return value.toString().getBytes(StandardCharsets.US_ASCII);
  }

  static String redisReadbackDigest(
      CanonicalGameplayRegionRuntimeStatus status,
      CanonicalPreseededActorRuntimeReferenceClient.RuntimeEntityId runtimeEntityId,
      String sessionId,
      String bindingGeneration) {
    String exact =
        status.tenantRegionTag()
            + "\n"
            + CanonicalGameplayRegionRedisKeys.sessionBinding(
                status.tenantId(),
                status.gameInstanceId(),
                status.regionId(),
                runtimeEntityId.value())
            + "\n"
            + runtimeEntityId.tenantId()
            + "\n"
            + runtimeEntityId.gameInstanceId()
            + "\n"
            + runtimeEntityId.value()
            + "\n"
            + sessionId
            + "\n"
            + bindingGeneration
            + "\n"
            + status.regionEpoch()
            + "\n"
            + status.executorFence()
            + "\n"
            + status.leaseTokenCorrelation();
    return sha256(exact.getBytes(StandardCharsets.US_ASCII));
  }

  private static String sha256(MessageLite... values) {
    byte[][] parts = new byte[values.length][];
    for (int index = 0; index < values.length; index++) parts[index] = values[index].toByteArray();
    return sha256(parts);
  }

  private static String sha256(byte[]... parts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (byte[] part : parts) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(part.length).array());
        digest.update(part);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
