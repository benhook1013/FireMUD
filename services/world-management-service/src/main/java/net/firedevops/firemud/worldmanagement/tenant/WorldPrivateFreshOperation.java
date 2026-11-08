package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.PrivatePreparationProtectionGrpcCodec.Held;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec;
import tools.jackson.databind.json.JsonMapper;

/** Exact private FRESH inputs, never original-operation permission or admission evidence. */
public final class WorldPrivateFreshOperation {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final UUID operationId;
  private final UUID gameInstanceId;
  private final String namespace;
  private final SourceProtectionBinding protection;
  private final String json;
  private final String digest;

  private WorldPrivateFreshOperation(
      UUID operationId,
      UUID gameInstanceId,
      String namespace,
      SourceProtectionBinding protection,
      String json) {
    this.operationId = operationId;
    this.gameInstanceId = gameInstanceId;
    this.namespace = namespace;
    this.protection = Objects.requireNonNull(protection);
    this.json = json;
    digest =
        PrivateWorldPreparationReadEvidence.sha256(
            json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * Requires an actual graph assembly and a current protected-source observation, not permission.
   */
  public static WorldPrivateFreshOperation create(
      UUID operationId,
      WorldPrivatePreparationBuildAssembly.Inputs build,
      WorldPrivatePreparationProtectionClient.Verified protection,
      WorldRuntimeInstallationRegistry.Registration installation) {
    uuid(operationId);
    Objects.requireNonNull(build);
    Objects.requireNonNull(protection);
    Objects.requireNonNull(installation);
    if (!protection.currentRead())
      throw new IllegalArgumentException("Current source protection read required");
    var selected = build.committedReadback();
    var source = selected.source().snapshot();
    Held held = protection.evidence();
    byte[] sourceBytes = PrivateWorldPreparationReadGrpcCodec.stableBytes(source);
    if (!Arrays.equals(
        sourceBytes, PrivateWorldPreparationReadGrpcCodec.stableBytes(held.source().snapshot())))
      throw new IllegalArgumentException("Protection differs from exact selected source");
    var plan = build.topology();
    if (!plan.sourceBinding()
        .freeze()
        .equals(WorldPrivatePreparationBuildAssembly.capture(selected.selectedWorld().request())))
      throw new IllegalArgumentException("Selected frozen graph differs from private build");
    if (!plan.generationRules().isEmpty() || !plan.spawnBindings().isEmpty())
      throw new IllegalArgumentException(
          "Private FRESH generation and spawn intent are unsupported");
    if (plan.regions().stream()
        .anyMatch(
            region ->
                !region.content().getWeather().isEmpty()
                    || !region.content().getGeneratorType().isEmpty()
                    || !region.content().getGeneratorParams().isEmpty()))
      throw new IllegalArgumentException(
          "Private FRESH configured weather and region generation are unsupported");
    if (plan.regions().isEmpty() || plan.zones().isEmpty() || plan.rooms().isEmpty())
      throw new IllegalArgumentException("Private FRESH requires complete playable topology");
    if (!installation.artifact().namespace().equals(source.scope().targetNamespace()))
      throw new IllegalArgumentException("Installation namespace differs from private target");
    installation.artifact().requireResolved();
    var owner = plan.sourceBinding().plan().ownerBinding();
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schema", "world-private-fresh-input/v1");
    fields.put("operationId", operationId.toString());
    fields.put("canonicalGameInstanceId", source.runtimeAllocation().gameInstanceId().toString());
    fields.put("targetNamespace", source.scope().targetNamespace());
    fields.put("canonicalTenantId", source.scope().tenantId().toString());
    fields.put("worldSlug", source.scope().worldSlug());
    fields.put("realmSlug", source.scope().realmSlug());
    fields.put("realmId", source.target().realmId().toString());
    fields.put("lifecycleId", source.preparation().lifecycleId().toString());
    fields.put("namespaceId", source.preparation().namespaceId().toString());
    fields.put("generation", "1");
    fields.put("stateScope", "ISOLATED");
    fields.put("publicProduction", false);
    fields.put("preparationRequestId", source.preparation().requestId().toString());
    fields.put("catalogRevision", Long.toString(source.target().catalogRevision()));
    fields.put("canonicalVersionId", source.source().canonicalVersionId().toString());
    fields.put("publishedReleaseBundleRef", source.source().publishedReleaseBundleRef());
    fields.put("publishedReleaseBundleDigest", source.source().publishedReleaseBundleDigest());
    fields.put("policySetDigest", source.source().policySetDigest());
    fields.put("policyCarrierDigest", source.source().policyCarrierDigest());
    fields.put("targetPolicyId", source.target().targetPolicyId().toString());
    fields.put("targetPolicyDigest", source.target().targetPolicyDigest());
    fields.put("sourceOperationId", source.source().sourceOperationId().toString());
    fields.put("sourceEvidenceDigest", source.source().sourceEvidenceDigest());
    fields.put("gameSessionIntakeOperationId", source.source().intakeOperationId().toString());
    fields.put("worldIntakeOperationId", owner.intakeOperationId().toString());
    fields.put("worldIntakeRequestId", owner.intakeRequestId().toString());
    fields.put("gameSessionIntakeReceiptDigest", source.source().intakeReceiptDigest());
    fields.put("worldIntakeReceiptDigest", owner.intakeReceiptDigest());
    fields.put("sourceBytes", base64(sourceBytes));
    fields.put("sourceDigest", held.sourceDigest());
    fields.put("protectionRequestId", held.requestId().toString());
    fields.put("protectionHoldId", held.holdId().toString());
    fields.put("protectionFence", Long.toString(held.fence()));
    fields.put("forkExpiresAt", source.preparation().forkExpiresAt().toString());
    fields.put("selectedWorldBytes", base64(selected.selectedWorld().canonicalBytes()));
    fields.put("captureId", plan.captureId().toString());
    fields.put("freeze", plan.sourceBinding().freeze());
    fields.put("versionIdentityOperationId", owner.versionIdentityOperationId().toString());
    fields.put("regionCount", plan.regions().size());
    fields.put("zoneCount", plan.zones().size());
    fields.put("roomCount", plan.rooms().size());
    fields.put("exitCount", plan.roomExits().size());
    fields.put("installationRevision", Long.toString(installation.revision()));
    fields.put("installationArtifactDigest", installation.artifact().digest());
    return new WorldPrivateFreshOperation(
        operationId,
        source.runtimeAllocation().gameInstanceId(),
        source.scope().targetNamespace(),
        new SourceProtectionBinding(
            held.requestId(), held.holdId(), held.fence(), held.sourceDigest()),
        JSON.writeValueAsString(fields));
  }

  public UUID operationId() {
    return operationId;
  }

  public UUID gameInstanceId() {
    return gameInstanceId;
  }

  public String namespace() {
    return namespace;
  }

  public SourceProtectionBinding protection() {
    return protection;
  }

  public String inputJson() {
    return json;
  }

  public String inputDigest() {
    return digest;
  }

  public boolean admissionAllowed() {
    return false;
  }

  /** Exact pending source hold binding, separate from original actor-operation permission. */
  public record SourceProtectionBinding(
      UUID requestId, UUID holdId, long fence, String sourceDigest) {
    public SourceProtectionBinding {
      uuid(requestId);
      uuid(holdId);
      if (fence <= 0 || sourceDigest == null || !sourceDigest.matches("sha256:[0-9a-f]{64}"))
        throw new IllegalArgumentException("Exact source protection binding required");
    }
  }

  /**
   * Retained historical owner result. APPLIED means PREPARING rows only, never playable admission.
   */
  public record Terminal(
      UUID operationId,
      UUID gameInstanceId,
      String inputDigest,
      String outcome,
      Long worldInstanceId,
      Long privateGameInstanceKey,
      ByteString evidence) {
    public Terminal {
      uuid(operationId);
      uuid(gameInstanceId);
      if (inputDigest == null || !inputDigest.matches("sha256:[0-9a-f]{64}"))
        throw new IllegalArgumentException("Input digest required");
      if (!("APPLIED".equals(outcome)
              && worldInstanceId != null
              && worldInstanceId > 0
              && privateGameInstanceKey != null
              && privateGameInstanceKey > 0)
          && !("NOT_APPLIED".equals(outcome)
              && worldInstanceId == null
              && privateGameInstanceKey == null))
        throw new IllegalArgumentException("Invalid private terminal result");
      Objects.requireNonNull(evidence);
      evidence = ByteString.copyFrom(evidence.toByteArray());
      if (evidence.isEmpty()) throw new IllegalArgumentException("Terminal evidence required");
    }

    @Override
    public ByteString evidence() {
      return ByteString.copyFrom(evidence.toByteArray());
    }

    public boolean admissionAllowed() {
      return false;
    }
  }

  private static String base64(byte[] value) {
    return Base64.getEncoder().encodeToString(value);
  }

  private static void uuid(UUID value) {
    if (value == null || value.equals(new UUID(0, 0)))
      throw new IllegalArgumentException("Non-nil identity required");
  }
}
