package net.firedevops.firemud.worldmanagement.data;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadGrpcCodec;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociation;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The canonical fixed topology used by the explicitly trusted local run-owned fixture.
 *
 * <p>The preset is a typed expected-content definition. Its seal is calculated before any resource
 * write and binds the fixture claim, both external owners, the Game Design policy seal, and the
 * actual World source and Version allocations. It never reads or adopts the legacy demo rows.
 */
public final class RunOwnedWorldFixedPresetResources {
  public static final String PRESET_ID = "firemud-demo-topology-v1";
  private static final int PRESET_SCHEMA_VERSION = 1;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String CONTENT_DOMAIN = "firemud-world-run-owned-fixed-preset-content/v1";
  private static final String BINDING_DOMAIN = "firemud-world-run-owned-fixed-preset-binding/v1";
  private static final UUID NAMESPACE = UUID.fromString("71ea88a0-6c91-5b2a-a618-b6b59dba9491");

  private static final RegionDefinition REGION =
      new RegionDefinition(templateId("REGION", "demo-region"), "Demo Region", 1.0, 0L);
  private static final ZoneDefinition ZONE =
      new ZoneDefinition(templateId("ZONE", "demo-zone"), REGION.templateId(), "Demo Zone");
  private static final RoomDefinition STARTER_ROOM =
      new RoomDefinition(
          templateId("ROOM", "candle-lit-antechamber"),
          ZONE.templateId(),
          "Candle-lit Antechamber",
          "Stalactites drip along the northern wall while a faint draft carries the smell of damp"
              + " earth from the lower tunnels. Torches flicker in alcoves, casting motion into the"
              + " shadowy archway to the north.");
  private static final RoomDefinition SECONDARY_ROOM =
      new RoomDefinition(
          templateId("ROOM", "smiths-annex"),
          ZONE.templateId(),
          "Smith's Annex",
          "An anvil, banked coals, and orderly tool racks mark this alcove as a working annex off"
              + " the starter chamber.");
  private static final List<ExitDefinition> EXITS =
      List.of(
          new ExitDefinition(
              templateId("ROOM_EXIT", "starter-to-annex-north"),
              STARTER_ROOM.templateId(),
              SECONDARY_ROOM.templateId(),
              "NORTH",
              1),
          new ExitDefinition(
              templateId("ROOM_EXIT", "annex-to-starter-south"),
              SECONDARY_ROOM.templateId(),
              STARTER_ROOM.templateId(),
              "SOUTH",
              1));

  private static final String CONTENT_DIGEST = contentDigest();

  private RunOwnedWorldFixedPresetResources() {}

  /** Returns the immutable expected topology definition shared with the local demo seeder. */
  public static Definition definition() {
    return new Definition(PRESET_ID, REGION, ZONE, List.of(STARTER_ROOM, SECONDARY_ROOM), EXITS);
  }

  /**
   * Builds a complete authorization plan before any World resource write is permitted.
   *
   * <p>The inputs must be the typed results of World source intake, World Version association, and
   * the fixed-server authenticated launch-evidence provider. Parsed wire data alone is not
   * accepted.
   */
  public static FixedPresetPlan plan(
      RunOwnedCanonicalWorldLaunchEvidence evidence,
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      WorldAuthoredVersionIdentityReceipt versionReceipt) {
    RunOwnedCanonicalWorldLaunchEvidence typedEvidence = Objects.requireNonNull(evidence, "evidence");
    typedEvidence.requireFixedGameSessionPeer();
    var descriptor = typedEvidence.launchDescriptor();
    if (descriptor.scriptPatchVersionPresent() || descriptor.remapSetIdPresent()) {
      throw new IllegalArgumentException(
          "RUN_OWNED_WORLD_FIXED_PRESET_OVERRIDES_UNSUPPORTED: fixed preset requires absent script patch and remap set overrides");
    }
    Objects.requireNonNull(sourceReceipt, "sourceReceipt");
    Objects.requireNonNull(versionReceipt, "versionReceipt");

    var fixture = typedEvidence.sourceReceipt();
    var capability = typedEvidence.capability();
    var gameSession = typedEvidence.gameSessionAssociation();
    var versionState = versionReceipt.versionStateEvidence();
    var bundle = fixture.launchReadEvidence().publishedReleaseBundle();
    var artifact = fixture.launchReadEvidence().versionAssetArtifactState();
    var versionLifecycle = versionState.versionState();
    boolean publishedOrActive =
        versionLifecycle
                == net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                    .VERSION_LIFECYCLE_STATE_PUBLISHED
            || versionLifecycle
                == net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                    .VERSION_LIFECYCLE_STATE_ACTIVE;
    if (gameSession == null
        || !sourceReceipt.source().equals(fixture.sourceEvidence())
        || !sourceReceipt.targetNamespace().equals(descriptor.targetNamespace())
        || !capability.worldManagementUriSan().equals(typedEvidence.worldManagementUriSan())
        || !sourceReceipt.canonicalTenantId().equals(descriptor.canonicalTenantId())
        || !sourceReceipt.worldSlug().equals(descriptor.worldSlug())
        || !sourceReceipt.sourceOperationId().equals(descriptor.authoredWorldSourceOperationId())
        || !sourceReceipt
            .sourceEvidenceDigest()
            .equals(descriptor.authoredWorldSourceEvidenceDigest())
        || !descriptor.controlPlaneRequestId().equals(capability.operationId())
        || descriptor.gameTemplateId() != capability.gameTemplateId()
        || !sourceReceipt.equals(versionReceipt.sourceIntakeReceipt())
        || !versionReceipt.canonicalTenantId().equals(sourceReceipt.canonicalTenantId())
        || !versionReceipt.canonicalVersionId().equals(gameSession.canonicalVersionId())
        || !versionReceipt.canonicalVersionId().toString().equals(bundle.getCanonicalVersionId())
        || versionReceipt.gameDesignVersionId() != descriptor.versionId()
        || versionState.request().versionId() != descriptor.versionId()
        || versionState.versionStateEpoch() != descriptor.versionStateEpoch()
        || !publishedOrActive
        || !versionState.sourceEvidence().equals(sourceReceipt.source())
        || bundle.getVersionId() != descriptor.versionId()
        || bundle.getId() != descriptor.releaseBundleId()
        || !bundle.getPublishedReleaseBundleRef().equals(descriptor.publishedReleaseBundleRef())
        || !bundle.getGenerationConfigRevision().equals(descriptor.generationConfigRevision())
        || artifact.getVersionId() != descriptor.versionId()
        || artifact.getArtifactState()
            != net.firedevops.firemud.gamedesign.v1.ArtifactState.ARTIFACT_STATE_PUBLISHED
        || !artifact.getManifestHash().equals(bundle.getManifestHash())
        || !gameSession.request().fixtureOperationId().toString().equals(capability.operationId())
        || !fixture.fixtureOperationId().equals(capability.operationId())
        || !fixture.fixturePolicySeal().operationId().toString().equals(capability.operationId())
        || !gameSession.canonicalTenantId().equals(sourceReceipt.canonicalTenantId())
        || !gameSession.fixturePolicySeal().equals(fixture.fixturePolicySeal())
        || !fixture
            .fixturePolicySeal()
            .evidenceDigest()
            .equals(typedEvidence.fixtureProvenanceDigest())
        || !gameSession
            .request()
            .expectedFixturePolicyEvidenceDigest()
            .equals(fixture.fixturePolicySeal().evidenceDigest())
        || !"SHARED".equals(capability.stateScope())
        || !"NEW_GAME_ROW".equals(sourceReceipt.source().provenanceKind())
        || sourceReceipt.localTenantKey() <= 0
        || versionReceipt.localVersionKey() <= 0) {
      throw new IllegalArgumentException(
          "RUN_OWNED_WORLD_FIXED_PRESET_EVIDENCE_MISMATCH: fixed preset requires exact authenticated claim, source, descriptor, bundle, policy seal, and World owner receipts");
    }

    var policyRequest = gameSession.fixturePolicyRequest();
    if (!Objects.equals(policyRequest.capability(), capability)
        || !Objects.equals(policyRequest.sourceEvidence(), sourceReceipt.source())
        || !Objects.equals(policyRequest.launchReadEvidence(), fixture.launchReadEvidence())
        || !Objects.equals(policyRequest.creationEvidence(), fixture.creationEvidence())) {
      throw new IllegalArgumentException(
          "RUN_OWNED_WORLD_FIXED_PRESET_POLICY_MISMATCH: Game Session retained a different GDS fixture policy request");
    }

    PreparedWorldInstanceRequest originalRequest =
        new PreparedWorldInstanceRequest(
            gameSession.gameSessionTenantId(),
            gameSession.gameInstanceId(),
            capability.gameTemplateId(),
            capability.operationId(),
            descriptor.launchDescriptorId(),
            descriptor.versionId(),
            descriptor.scriptPatchVersionPresent() ? descriptor.scriptPatchVersion() : null,
            descriptor.runtimeFlagsJson(),
            descriptor.generationConfigRevision(),
            descriptor.releaseBundleId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            descriptor.remapSetIdPresent() ? descriptor.remapSetId() : null);
    typedEvidence.requireMatches(originalRequest);
    String lifecycleBindingDigest = "sha256:" + typedEvidence.bindingDigest(originalRequest, 1L);
    String gameSessionResponseBytesDigest =
        sha256Raw(
            CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toFixtureResponse(gameSession)
                .toByteArray());

    return sealPlan(
        capability,
        gameSession,
        sourceReceipt,
        versionReceipt,
        descriptor,
        bundle,
        lifecycleBindingDigest,
        gameSessionResponseBytesDigest);
  }

  /** Rebuilds the expected plan from immutable, typed World-owner receipts for restart reads. */
  public static FixedPresetPlan plan(
      WorldCanonicalInstanceAssociation association,
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      WorldAuthoredVersionIdentityReceipt versionReceipt) {
    Objects.requireNonNull(association, "association");
    Objects.requireNonNull(sourceReceipt, "sourceReceipt");
    Objects.requireNonNull(versionReceipt, "versionReceipt");
    var fixture = association.runOwnedFixture();
    var gameSession = fixture.gameSessionAssociation();
    var policyRequest = gameSession.fixturePolicyRequest();
    RunOwnedInitialAdmissionFixtureWireCodec.Capability capability = policyRequest.capability();
    var sourceEvidence = policyRequest.sourceEvidence();
    var launchReadEvidence = policyRequest.launchReadEvidence();
    var creationEvidence = policyRequest.creationEvidence();
    var descriptor =
        AuthoredWorldLaunchDescriptorGrpcCodec.fromLaunchDescriptorMessage(
            new AuthoredWorldLaunchDescriptorEvidence.Request(
                sourceEvidence.targetNamespace(),
                capability.operationId(),
                sourceEvidence.canonicalTenantId(),
                sourceEvidence.worldSlug(),
                sourceEvidence.operationId(),
                sourceEvidence.evidenceDigest(),
                capability.gameTemplateId(),
                false,
                null,
                false,
                null,
                false,
                null,
                false,
                null),
            launchReadEvidence.launchDescriptor());
    var versionState = versionReceipt.versionStateEvidence();
    var bundle = launchReadEvidence.publishedReleaseBundle();
    var artifact = launchReadEvidence.versionAssetArtifactState();
    var versionLifecycle = versionState.versionState();
    boolean publishedOrActive =
        versionLifecycle
                == net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                    .VERSION_LIFECYCLE_STATE_PUBLISHED
            || versionLifecycle
                == net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                    .VERSION_LIFECYCLE_STATE_ACTIVE;
    var identity = association.identity();
    var localKeys = fixture.worldPrivateKeys();
    String fixtureOperationId = fixture.fixtureOperationId().toString();
    String lifecycleBindingDigest = fixture.preparationInputDigest();
    byte[] exactResponseBytes = fixture.gameSessionAssociationResponseBytes();
    String gameSessionResponseBytesDigest = sha256Raw(exactResponseBytes);
    if (exactResponseBytes.length == 0
        || !lifecycleBindingDigest.matches("sha256:[0-9a-f]{64}")
        || !fixtureOperationId.equals(capability.operationId())
        || !association.versionIdentity().equals(versionReceipt)
        || !fixture.sourceIntakeReceipt().equals(sourceReceipt)
        || !sourceReceipt.source().equals(sourceEvidence)
        || !identity.canonicalGameInstanceId().equals(gameSession.gameInstanceUuid())
        || !identity.targetNamespace().equals(sourceReceipt.targetNamespace())
        || !identity.canonicalTenantId().equals(sourceReceipt.canonicalTenantId())
        || !identity.worldSlug().equals(sourceReceipt.worldSlug())
        || !identity.playableStateNamespaceId().equals(gameSession.playableStateNamespaceId())
        || !identity.playableStateScope().equals(capability.stateScope())
        || identity.publicProduction() != capability.publicProductionRealm()
        || !identity.canonicalRealmId().equals(gameSession.canonicalRealmId())
        || identity.catalogRevision() != gameSession.catalogRevision()
        || !identity.controlPlaneRequestId().equals(fixtureOperationId)
        || !sourceReceipt.targetNamespace().equals(descriptor.targetNamespace())
        || !sourceReceipt.canonicalTenantId().equals(descriptor.canonicalTenantId())
        || !sourceReceipt.worldSlug().equals(descriptor.worldSlug())
        || !sourceReceipt.sourceOperationId().equals(descriptor.authoredWorldSourceOperationId())
        || !sourceReceipt
            .sourceEvidenceDigest()
            .equals(descriptor.authoredWorldSourceEvidenceDigest())
        || descriptor.gameTemplateId() != capability.gameTemplateId()
        || !sourceReceipt.equals(versionReceipt.sourceIntakeReceipt())
        || !versionReceipt.canonicalTenantId().equals(sourceReceipt.canonicalTenantId())
        || !versionReceipt.canonicalVersionId().equals(gameSession.canonicalVersionId())
        || !versionReceipt.canonicalVersionId().toString().equals(bundle.getCanonicalVersionId())
        || versionReceipt.gameDesignVersionId() != descriptor.versionId()
        || versionState.request().versionId() != descriptor.versionId()
        || versionState.versionStateEpoch() != descriptor.versionStateEpoch()
        || !publishedOrActive
        || !versionState.sourceEvidence().equals(sourceReceipt.source())
        || bundle.getVersionId() != descriptor.versionId()
        || bundle.getId() != descriptor.releaseBundleId()
        || !bundle.getPublishedReleaseBundleRef().equals(descriptor.publishedReleaseBundleRef())
        || !bundle.getGenerationConfigRevision().equals(descriptor.generationConfigRevision())
        || artifact.getVersionId() != descriptor.versionId()
        || artifact.getArtifactState()
            != net.firedevops.firemud.gamedesign.v1.ArtifactState.ARTIFACT_STATE_PUBLISHED
        || !artifact.getManifestHash().equals(bundle.getManifestHash())
        || descriptor.scriptPatchVersionPresent()
        || descriptor.remapSetIdPresent()
        || !gameSession.request().fixtureOperationId().toString().equals(fixtureOperationId)
        || !gameSession.fixturePolicySeal().operationId().toString().equals(fixtureOperationId)
        || !gameSession
            .fixturePolicySeal()
            .evidenceDigest()
            .equals(fixture.fixtureProvenanceDigest())
        || !gameSession
            .request()
            .expectedFixturePolicyEvidenceDigest()
            .equals(gameSession.fixturePolicySeal().evidenceDigest())
        || !Objects.equals(policyRequest.capability(), capability)
        || !Objects.equals(policyRequest.sourceEvidence(), sourceReceipt.source())
        || !Objects.equals(policyRequest.launchReadEvidence(), launchReadEvidence)
        || !Objects.equals(policyRequest.creationEvidence(), creationEvidence)
        || !"NEW_GAME_ROW".equals(sourceReceipt.source().provenanceKind())
        || !"SHARED".equals(capability.stateScope())
        || localKeys.tenantId() != sourceReceipt.localTenantKey()
        || localKeys.versionId() != versionReceipt.localVersionKey()
        || localKeys.gameInstanceId() <= 0
        || sourceReceipt.localTenantKey() <= 0
        || versionReceipt.localVersionKey() <= 0) {
      throw new IllegalArgumentException(
          "RUN_OWNED_WORLD_FIXED_PRESET_RETAINED_EVIDENCE_MISMATCH: retained typed World association, source, Version, descriptor, bundle, or fixture policy differs");
    }

    return sealPlan(
        capability,
        gameSession,
        sourceReceipt,
        versionReceipt,
        descriptor,
        bundle,
        lifecycleBindingDigest,
        gameSessionResponseBytesDigest);
  }

  private static FixedPresetPlan sealPlan(
      RunOwnedInitialAdmissionFixtureWireCodec.Capability capability,
      CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult gameSession,
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      WorldAuthoredVersionIdentityReceipt versionReceipt,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      PublishedReleaseBundle bundle,
      String lifecycleBindingDigest,
      String gameSessionResponseBytesDigest) {
    var versionState = versionReceipt.versionStateEvidence();
    FixedPresetPlan unsealed =
        new FixedPresetPlan(
            PRESET_SCHEMA_VERSION,
            PRESET_ID,
            capability.operationId(),
            capability.claimDigest(),
            gameSession.fixturePolicySeal().requestDigest(),
            gameSession.fixturePolicySeal().evidenceDigest(),
            sha256Raw(gameSession.fixturePolicyRequestBytes()),
            lifecycleBindingDigest,
            gameSessionResponseBytesDigest,
            sourceReceipt.targetNamespace(),
            sourceReceipt.canonicalTenantId(),
            sourceReceipt.worldSlug(),
            sourceReceipt.operationId(),
            sourceReceipt.sourceOperationId(),
            sourceReceipt.sourceEvidenceDigest(),
            sourceReceipt.receiptDigest(),
            sourceReceipt.localTenantKey(),
            versionReceipt.operationId(),
            versionReceipt.canonicalVersionId(),
            versionReceipt.gameDesignVersionId(),
            versionState.evidenceDigest(),
            versionReceipt.localVersionKey(),
            descriptor.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            descriptor.runtimeFlagsJson(),
            descriptor.versionId(),
            descriptor.releaseBundleId(),
            descriptor.publishedReleaseBundleRef(),
            bundle.getManifestHash(),
            descriptor.generationConfigRevision(),
            descriptor.versionStateEpoch(),
            gameSession.gameSessionTenantId(),
            gameSession.gameInstanceId(),
            gameSession.gameInstanceUuid(),
            gameSession.canonicalTenantId(),
            gameSession.canonicalVersionId(),
            gameSession.playableStateNamespaceId(),
            gameSession.canonicalRealmId(),
            capability.stateScope(),
            capability.publicProductionRealm(),
            gameSession.catalogRevision(),
            capability.gameTemplateId(),
            CONTENT_DIGEST,
            REGION,
            ZONE,
            List.of(STARTER_ROOM, SECONDARY_ROOM),
            EXITS,
            "");
    return unsealed.withPresetSealDigest(presetSealDigest(unsealed));
  }

  /**
   * Writes the preset and its retained preparation result inside the caller's owner transaction.
   */
  public static MaterializedFixedPreset materialize(
      DSLContext dsl,
      RunOwnedCanonicalWorldLaunchEvidence fixtureEvidence,
      FixedPresetPlan plan,
      long worldInstanceId,
      long privateGameInstanceKey) {
    Objects.requireNonNull(fixtureEvidence, "fixtureEvidence").requireFixedGameSessionPeer();
    Objects.requireNonNull(plan, "plan");
    requireCurrentFixtureMatchesRetainedPlan(fixtureEvidence, plan);
    Objects.requireNonNull(dsl, "dsl");
    requirePositive(worldInstanceId, "worldInstanceId");
    requirePositive(privateGameInstanceKey, "privateGameInstanceKey");
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Run-owned fixed preset materialization requires the existing World owner transaction");
    }
    requireOriginalPreparingRow(dsl, plan, worldInstanceId, privateGameInstanceKey);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM world_materialize_run_owned_fixed_preset(?, ?, ?, ?)",
            plan.planJson(),
            plan.expectedContentDigest(),
            worldInstanceId,
            privateGameInstanceKey);
    MaterializedFixedPreset result =
        requireResult(row, plan, worldInstanceId, privateGameInstanceKey);
    requireOriginalPreparingRow(dsl, plan, worldInstanceId, privateGameInstanceKey);
    return result;
  }

  /**
   * Independently reconstructs the retained result and verifies all fixed resources after commit.
   *
   * <p>Call this from the existing separate committed-read transaction, including on exact replay
   * after a process restart.
   */
  public static MaterializedFixedPreset readCommitted(
      DSLContext dsl, FixedPresetPlan plan, long worldInstanceId, long privateGameInstanceKey) {
    Objects.requireNonNull(dsl, "dsl");
    Objects.requireNonNull(plan, "plan");
    requirePositive(worldInstanceId, "worldInstanceId");
    requirePositive(privateGameInstanceKey, "privateGameInstanceKey");
    requireOriginalPreparingRow(dsl, plan, worldInstanceId, privateGameInstanceKey);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM world_read_run_owned_fixed_preset(?, ?, ?, ?)",
            plan.planJson(),
            plan.expectedContentDigest(),
            worldInstanceId,
            privateGameInstanceKey);
    MaterializedFixedPreset result =
        requireResult(row, plan, worldInstanceId, privateGameInstanceKey);
    requireOriginalPreparingRow(dsl, plan, worldInstanceId, privateGameInstanceKey);
    return result;
  }

  /** Reconstructs the expected plan from the independently reloaded association on every replay. */
  public static MaterializedFixedPreset readCommitted(
      DSLContext dsl,
      WorldCanonicalInstanceAssociation association,
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      WorldAuthoredVersionIdentityReceipt versionReceipt) {
    Objects.requireNonNull(association, "association");
    FixedPresetPlan plan = plan(association, sourceReceipt, versionReceipt);
    return readCommitted(
        dsl,
        plan,
        association.worldInstanceId(),
        association.runOwnedFixture().worldPrivateKeys().gameInstanceId());
  }

  /** The retained seal survives retries; only the independent GS read correlation may change. */
  private static void requireCurrentFixtureMatchesRetainedPlan(
      RunOwnedCanonicalWorldLaunchEvidence evidence, FixedPresetPlan plan) {
    var capability = evidence.capability();
    var receipt = evidence.sourceReceipt();
    var source = receipt.sourceEvidence();
    var gs = evidence.gameSessionAssociation();
    var descriptor = evidence.launchDescriptor();
    PreparedWorldInstanceRequest request =
        new PreparedWorldInstanceRequest(
            gs.gameSessionTenantId(),
            gs.gameInstanceId(),
            capability.gameTemplateId(),
            capability.operationId(),
            descriptor.launchDescriptorId(),
            descriptor.versionId(),
            descriptor.scriptPatchVersionPresent() ? descriptor.scriptPatchVersion() : null,
            descriptor.runtimeFlagsJson(),
            descriptor.generationConfigRevision(),
            descriptor.releaseBundleId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            descriptor.remapSetIdPresent() ? descriptor.remapSetId() : null);
    evidence.requireMatches(request);
    // This original binding commits exact immutable capability/receipt bytes and all recipients.
    // The GS response digest in the retained plan keeps its original read nonce; it is never
    // rebuilt.
    if (!plan.fixtureOperationId().equals(capability.operationId())
        || !plan.claimDigest().equals(capability.claimDigest())
        || !plan.lifecycleBindingDigest().equals("sha256:" + evidence.bindingDigest(request, 1L))
        || !plan.fixturePolicyRequestDigest().equals(receipt.fixturePolicySeal().requestDigest())
        || !plan.fixturePolicyEvidenceDigest().equals(receipt.fixturePolicySeal().evidenceDigest())
        || !plan.fixturePolicyBytesDigest().equals(sha256Raw(gs.fixturePolicyRequestBytes()))
        || !plan.targetNamespace().equals(gs.request().targetNamespace())
        || !plan.targetNamespace().equals(source.targetNamespace())
        || !plan.canonicalTenantId().equals(source.canonicalTenantId())
        || !plan.worldSlug().equals(source.worldSlug())
        || !plan.sourceOperationId().equals(source.operationId())
        || !plan.sourceEvidenceDigest().equals(source.evidenceDigest())
        || !plan.gameSessionGameInstanceUuid().equals(gs.gameInstanceUuid())
        || !plan.gameSessionCanonicalTenantId().equals(gs.canonicalTenantId())
        || !plan.gameSessionCanonicalVersionId().equals(gs.canonicalVersionId())
        || plan.gameSessionTenantId() != gs.gameSessionTenantId()
        || plan.gameSessionGameInstanceId() != gs.gameInstanceId()
        || !plan.playableStateNamespaceId().equals(gs.playableStateNamespaceId())
        || !plan.canonicalRealmId().equals(gs.canonicalRealmId())
        || plan.catalogRevision() != gs.catalogRevision()
        || !plan.playableStateScope().equals(capability.stateScope())
        || plan.publicProduction() != capability.publicProductionRealm()
        || gs.currentGameInstanceStatus()
            != net.firedevops.firemud.common.gamesession
                .CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus
                .STARTING
        || gs.currentRowVersion() != 0L
        || !plan.presetSealDigest().equals(presetSealDigest(plan))) {
      throw new IllegalArgumentException(
          "RUN_OWNED_WORLD_FIXED_PRESET_RETRY_MISMATCH: current fixed peer evidence differs from the original sealed owner plan");
    }
  }

  /** Qualifies only the immutable original PREPARING row, never activation or later lifecycle. */
  private static void requireOriginalPreparingRow(
      DSLContext dsl, FixedPresetPlan plan, long worldInstanceId, long privateGameInstanceKey) {
    Record row = dsl.fetchOne("SELECT * FROM world_instance WHERE id = ?", worldInstanceId);
    if (row == null
        || !"PREPARING".equals(row.get("status", String.class))
        || !Long.valueOf(1L).equals(row.get("lifecycle_epoch", Long.class))
        || !Long.valueOf(0L).equals(row.get("row_version", Long.class))
        || !Long.valueOf(1L)
            .equals(row.get("run_owned_fixture_preparing_lifecycle_epoch", Long.class))
        || !"RUN_OWNED_FIXTURE".equals(row.get("canonical_authority_kind", String.class))
        || !Long.valueOf(plan.worldLocalTenantKey()).equals(row.get("tenant_id", Long.class))
        || !Long.valueOf(plan.worldLocalVersionKey()).equals(row.get("version_id", Long.class))
        || !Long.valueOf(privateGameInstanceKey).equals(row.get("game_instance_id", Long.class))
        || !plan.gameSessionGameInstanceUuid()
            .equals(row.get("canonical_game_instance_id", UUID.class))
        || !plan.fixtureOperationId().equals(row.get("control_plane_request_id", String.class))
        || !plan.fixturePolicyEvidenceDigest()
            .equals(row.get("fixture_provenance_digest", String.class))
        || !plan.lifecycleBindingDigest()
            .equals("sha256:" + row.get("run_owned_fixture_binding_sha256", String.class))) {
      throw new IllegalStateException(
          "RUN_OWNED_WORLD_FIXED_PRESET_PREPARING_MISMATCH: fixed materialization/readback requires the original PREPARING allocation");
    }
  }

  private static MaterializedFixedPreset requireResult(
      Record row, FixedPresetPlan plan, long worldInstanceId, long privateGameInstanceKey) {
    if (row == null) {
      throw new IllegalStateException(
          "RUN_OWNED_WORLD_FIXED_PRESET_RESULT_MISSING: retained resource result was not found");
    }
    MaterializedFixedPreset result =
        new MaterializedFixedPreset(
            requiredLong(row, "world_instance_id"),
            requiredLong(row, "tenant_id"),
            requiredLong(row, "private_game_instance_key"),
            requiredLong(row, "version_id"),
            requiredText(row, "preset_id"),
            requiredText(row, "preset_content_digest"),
            requiredText(row, "preset_binding_digest"),
            requiredText(row, "graph_sha256"),
            requiredInt(row, "region_count"),
            requiredInt(row, "zone_count"),
            requiredInt(row, "room_count"),
            requiredInt(row, "exit_count"));
    if (result.worldInstanceId() != worldInstanceId
        || result.tenantId() != plan.worldLocalTenantKey()
        || result.privateGameInstanceKey() != privateGameInstanceKey
        || result.versionId() != plan.worldLocalVersionKey()
        || !result.presetId().equals(plan.presetId())
        || !result.presetContentDigest().equals(plan.expectedContentDigest())
        || !result.presetBindingDigest().equals(plan.presetSealDigest())
        || !result.graphSha256().equals(plan.expectedGraphSha256())
        || result.regionCount() != 1
        || result.zoneCount() != 1
        || result.roomCount() != 2
        || result.exitCount() != 2) {
      throw new IllegalStateException(
          "RUN_OWNED_WORLD_FIXED_PRESET_RESULT_MISMATCH: retained preparation or resource readback differs from the complete expected preset");
    }
    return result;
  }

  private static String presetSealDigest(FixedPresetPlan plan) {
    MessageDigest digest = newDigest();
    add(digest, BINDING_DOMAIN);
    add(digest, plan.presetId());
    add(digest, plan.expectedContentDigest());
    add(digest, plan.targetNamespace());
    add(digest, plan.fixtureOperationId());
    add(digest, plan.claimDigest());
    add(digest, plan.fixturePolicyRequestDigest());
    add(digest, plan.fixturePolicyEvidenceDigest());
    add(digest, plan.fixturePolicyBytesDigest());
    add(digest, plan.lifecycleBindingDigest());
    add(digest, plan.gameSessionResponseBytesDigest());
    add(digest, plan.canonicalTenantId().toString());
    add(digest, plan.worldSlug());
    add(digest, plan.worldSourceIntakeOperationId().toString());
    add(digest, plan.sourceOperationId().toString());
    add(digest, plan.sourceEvidenceDigest());
    add(digest, plan.sourceReceiptDigest());
    add(digest, Long.toString(plan.worldLocalTenantKey()));
    add(digest, plan.worldVersionIdentityOperationId().toString());
    add(digest, plan.canonicalVersionId().toString());
    add(digest, Long.toString(plan.gameDesignVersionId()));
    add(digest, plan.versionEvidenceDigest());
    add(digest, Long.toString(plan.worldLocalVersionKey()));
    add(digest, plan.launchDescriptorId());
    add(digest, plan.descriptorRequestDigest());
    add(digest, plan.descriptorResultDigest());
    add(digest, plan.descriptorRuntimeFlagsJson());
    add(digest, Long.toString(plan.launchGameDesignVersionId()));
    add(digest, Long.toString(plan.releaseBundleId()));
    add(digest, plan.publishedReleaseBundleRef());
    add(digest, plan.manifestHash());
    add(digest, plan.generationConfigRevision());
    add(digest, Long.toString(plan.versionStateEpoch()));
    add(digest, Long.toString(plan.gameSessionTenantId()));
    add(digest, Long.toString(plan.gameSessionGameInstanceId()));
    add(digest, plan.gameSessionGameInstanceUuid().toString());
    add(digest, plan.gameSessionCanonicalTenantId().toString());
    add(digest, plan.gameSessionCanonicalVersionId().toString());
    add(digest, plan.playableStateNamespaceId().toString());
    add(digest, plan.canonicalRealmId().toString());
    add(digest, plan.playableStateScope());
    add(digest, Boolean.toString(plan.publicProduction()));
    add(digest, Long.toString(plan.catalogRevision()));
    add(digest, Long.toString(plan.gameTemplateId()));
    return digestText(digest.digest());
  }

  private static String contentDigest() {
    MessageDigest digest = newDigest();
    add(digest, CONTENT_DOMAIN);
    add(digest, PRESET_ID);
    add(digest, "REGION");
    add(digest, REGION.templateId().toString());
    add(digest, REGION.name());
    add(digest, Double.toHexString(REGION.spacingMultiplier()));
    add(digest, Long.toString(REGION.generationSeed()));
    add(digest, "ZONE");
    add(digest, ZONE.templateId().toString());
    add(digest, ZONE.parentRegionTemplateId().toString());
    add(digest, ZONE.name());
    for (RoomDefinition room : List.of(STARTER_ROOM, SECONDARY_ROOM)) {
      add(digest, "ROOM");
      add(digest, room.templateId().toString());
      add(digest, room.parentZoneTemplateId().toString());
      add(digest, room.name());
      add(digest, room.description());
    }
    for (ExitDefinition exit : EXITS) {
      add(digest, "ROOM_EXIT");
      add(digest, exit.templateId().toString());
      add(digest, exit.fromRoomTemplateId().toString());
      add(digest, exit.toRoomTemplateId().toString());
      add(digest, exit.direction());
      add(digest, Integer.toString(exit.cost()));
    }
    return digestText(digest.digest());
  }

  private static UUID templateId(String family, String logicalName) {
    return UUID.nameUUIDFromBytes(
        (NAMESPACE + "/" + PRESET_ID + "/" + family + "/" + logicalName)
            .getBytes(StandardCharsets.UTF_8));
  }

  private static void add(MessageDigest digest, String value) {
    byte[] bytes = Objects.requireNonNull(value, "digest value").getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private static String sha256Raw(byte[] bytes) {
    return digestText(newDigest().digest(bytes));
  }

  private static String digestText(byte[] digest) {
    return "sha256:" + HexFormat.of().formatHex(digest);
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String planJson(FixedPresetPlan plan) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schemaVersion", plan.schemaVersion());
    root.put("presetId", plan.presetId());
    root.put("fixtureOperationId", plan.fixtureOperationId());
    root.put("claimDigest", plan.claimDigest());
    root.put("fixturePolicyRequestDigest", plan.fixturePolicyRequestDigest());
    root.put("fixturePolicyEvidenceDigest", plan.fixturePolicyEvidenceDigest());
    root.put("fixturePolicyBytesDigest", plan.fixturePolicyBytesDigest());
    root.put("lifecycleBindingDigest", plan.lifecycleBindingDigest());
    root.put("gameSessionResponseBytesDigest", plan.gameSessionResponseBytesDigest());
    root.put("targetNamespace", plan.targetNamespace());
    root.put("canonicalTenantId", plan.canonicalTenantId().toString());
    root.put("worldSlug", plan.worldSlug());
    root.put("worldSourceIntakeOperationId", plan.worldSourceIntakeOperationId().toString());
    root.put("sourceOperationId", plan.sourceOperationId().toString());
    root.put("sourceEvidenceDigest", plan.sourceEvidenceDigest());
    root.put("sourceReceiptDigest", plan.sourceReceiptDigest());
    root.put("worldLocalTenantKey", plan.worldLocalTenantKey());
    root.put("worldVersionIdentityOperationId", plan.worldVersionIdentityOperationId().toString());
    root.put("canonicalVersionId", plan.canonicalVersionId().toString());
    root.put("gameDesignVersionId", plan.gameDesignVersionId());
    root.put("versionEvidenceDigest", plan.versionEvidenceDigest());
    root.put("worldLocalVersionKey", plan.worldLocalVersionKey());
    root.put("launchDescriptorId", plan.launchDescriptorId());
    root.put("descriptorRequestDigest", plan.descriptorRequestDigest());
    root.put("descriptorResultDigest", plan.descriptorResultDigest());
    root.put("descriptorRuntimeFlagsJson", plan.descriptorRuntimeFlagsJson());
    root.put("launchGameDesignVersionId", plan.launchGameDesignVersionId());
    root.put("releaseBundleId", plan.releaseBundleId());
    root.put("publishedReleaseBundleRef", plan.publishedReleaseBundleRef());
    root.put("manifestHash", plan.manifestHash());
    root.put("generationConfigRevision", plan.generationConfigRevision());
    root.put("versionStateEpoch", plan.versionStateEpoch());
    root.put("gameSessionTenantId", plan.gameSessionTenantId());
    root.put("gameSessionGameInstanceId", plan.gameSessionGameInstanceId());
    root.put("gameSessionGameInstanceUuid", plan.gameSessionGameInstanceUuid().toString());
    root.put("gameSessionCanonicalTenantId", plan.gameSessionCanonicalTenantId().toString());
    root.put("gameSessionCanonicalVersionId", plan.gameSessionCanonicalVersionId().toString());
    root.put("playableStateNamespaceId", plan.playableStateNamespaceId().toString());
    root.put("canonicalRealmId", plan.canonicalRealmId().toString());
    root.put("playableStateScope", plan.playableStateScope());
    root.put("publicProduction", plan.publicProduction());
    root.put("catalogRevision", plan.catalogRevision());
    root.put("gameTemplateId", plan.gameTemplateId());
    root.put("expectedContentDigest", plan.expectedContentDigest());
    root.put("presetSealDigest", plan.presetSealDigest());
    root.put("topology", topologyJson());
    try {
      return JSON.writeValueAsString(root);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Fixed preset plan could not be serialized", exception);
    }
  }

  private static List<Map<String, Object>> topologyJson() {
    List<Map<String, Object>> rows = new ArrayList<>();
    rows.add(
        object(
            "family", "REGION",
            "templateId", REGION.templateId().toString(),
            "name", REGION.name(),
            "spacingMultiplier", REGION.spacingMultiplier(),
            "generationSeed", REGION.generationSeed()));
    rows.add(
        object(
            "family", "ZONE",
            "templateId", ZONE.templateId().toString(),
            "parentRegionTemplateId", ZONE.parentRegionTemplateId().toString(),
            "name", ZONE.name()));
    for (RoomDefinition room : List.of(STARTER_ROOM, SECONDARY_ROOM)) {
      rows.add(
          object(
              "family", "ROOM",
              "templateId", room.templateId().toString(),
              "parentZoneTemplateId", room.parentZoneTemplateId().toString(),
              "name", room.name(),
              "description", room.description()));
    }
    for (ExitDefinition exit : EXITS) {
      rows.add(
          object(
              "family", "ROOM_EXIT",
              "templateId", exit.templateId().toString(),
              "fromRoomTemplateId", exit.fromRoomTemplateId().toString(),
              "toRoomTemplateId", exit.toRoomTemplateId().toString(),
              "direction", exit.direction(),
              "cost", exit.cost()));
    }
    return List.copyOf(rows);
  }

  private static Map<String, Object> object(Object... fields) {
    if ((fields.length & 1) != 0) throw new IllegalArgumentException("JSON fields are incomplete");
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < fields.length; index += 2) {
      values.put((String) fields[index], fields[index + 1]);
    }
    return values;
  }

  private static long requiredLong(Record row, String column) {
    Long value = row.get(column, Long.class);
    if (value == null || value <= 0L) throw invalidResult();
    return value;
  }

  private static int requiredInt(Record row, String column) {
    Integer value = row.get(column, Integer.class);
    if (value == null || value < 0) throw invalidResult();
    return value;
  }

  private static String requiredText(Record row, String column) {
    String value = row.get(column, String.class);
    if (value == null || value.isBlank()) throw invalidResult();
    return value;
  }

  private static IllegalStateException invalidResult() {
    return new IllegalStateException(
        "RUN_OWNED_WORLD_FIXED_PRESET_RESULT_INVALID: owner result is incomplete");
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0L) throw new IllegalArgumentException(name + " must be positive");
  }

  public record Definition(
      String presetId,
      RegionDefinition region,
      ZoneDefinition zone,
      List<RoomDefinition> rooms,
      List<ExitDefinition> exits) {
    public Definition {
      rooms = List.copyOf(rooms);
      exits = List.copyOf(exits);
    }

    public String expectedContentDigest() {
      return CONTENT_DIGEST;
    }
  }

  public record RegionDefinition(
      UUID templateId, String name, double spacingMultiplier, long generationSeed) {}

  public record ZoneDefinition(UUID templateId, UUID parentRegionTemplateId, String name) {}

  public record RoomDefinition(
      UUID templateId, UUID parentZoneTemplateId, String name, String description) {}

  public record ExitDefinition(
      UUID templateId,
      UUID fromRoomTemplateId,
      UUID toRoomTemplateId,
      String direction,
      int cost) {}

  public static final class FixedPresetPlan {
    private final int schemaVersion;
    private final String presetId;
    private final String fixtureOperationId;
    private final String claimDigest;
    private final String fixturePolicyRequestDigest;
    private final String fixturePolicyEvidenceDigest;
    private final String fixturePolicyBytesDigest;
    private final String lifecycleBindingDigest;
    private final String gameSessionResponseBytesDigest;
    private final String targetNamespace;
    private final UUID canonicalTenantId;
    private final String worldSlug;
    private final UUID worldSourceIntakeOperationId;
    private final UUID sourceOperationId;
    private final String sourceEvidenceDigest;
    private final String sourceReceiptDigest;
    private final long worldLocalTenantKey;
    private final UUID worldVersionIdentityOperationId;
    private final UUID canonicalVersionId;
    private final long gameDesignVersionId;
    private final String versionEvidenceDigest;
    private final long worldLocalVersionKey;
    private final String launchDescriptorId;
    private final String descriptorRequestDigest;
    private final String descriptorResultDigest;
    private final String descriptorRuntimeFlagsJson;
    private final long launchGameDesignVersionId;
    private final long releaseBundleId;
    private final String publishedReleaseBundleRef;
    private final String manifestHash;
    private final String generationConfigRevision;
    private final long versionStateEpoch;
    private final long gameSessionTenantId;
    private final long gameSessionGameInstanceId;
    private final UUID gameSessionGameInstanceUuid;
    private final UUID gameSessionCanonicalTenantId;
    private final UUID gameSessionCanonicalVersionId;
    private final UUID playableStateNamespaceId;
    private final UUID canonicalRealmId;
    private final String playableStateScope;
    private final boolean publicProduction;
    private final long catalogRevision;
    private final long gameTemplateId;
    private final String expectedContentDigest;
    private final RegionDefinition region;
    private final ZoneDefinition zone;
    private final List<RoomDefinition> rooms;
    private final List<ExitDefinition> exits;
    private final String presetSealDigest;

    private FixedPresetPlan(
        int schemaVersion,
        String presetId,
        String fixtureOperationId,
        String claimDigest,
        String fixturePolicyRequestDigest,
        String fixturePolicyEvidenceDigest,
        String fixturePolicyBytesDigest,
        String lifecycleBindingDigest,
        String gameSessionResponseBytesDigest,
        String targetNamespace,
        UUID canonicalTenantId,
        String worldSlug,
        UUID worldSourceIntakeOperationId,
        UUID sourceOperationId,
        String sourceEvidenceDigest,
        String sourceReceiptDigest,
        long worldLocalTenantKey,
        UUID worldVersionIdentityOperationId,
        UUID canonicalVersionId,
        long gameDesignVersionId,
        String versionEvidenceDigest,
        long worldLocalVersionKey,
        String launchDescriptorId,
        String descriptorRequestDigest,
        String descriptorResultDigest,
        String descriptorRuntimeFlagsJson,
        long launchGameDesignVersionId,
        long releaseBundleId,
        String publishedReleaseBundleRef,
        String manifestHash,
        String generationConfigRevision,
        long versionStateEpoch,
        long gameSessionTenantId,
        long gameSessionGameInstanceId,
        UUID gameSessionGameInstanceUuid,
        UUID gameSessionCanonicalTenantId,
        UUID gameSessionCanonicalVersionId,
        UUID playableStateNamespaceId,
        UUID canonicalRealmId,
        String playableStateScope,
        boolean publicProduction,
        long catalogRevision,
        long gameTemplateId,
        String expectedContentDigest,
        RegionDefinition region,
        ZoneDefinition zone,
        List<RoomDefinition> rooms,
        List<ExitDefinition> exits,
        String presetSealDigest) {
      this.schemaVersion = schemaVersion;
      this.presetId = Objects.requireNonNull(presetId);
      this.fixtureOperationId = Objects.requireNonNull(fixtureOperationId);
      this.claimDigest = Objects.requireNonNull(claimDigest);
      this.fixturePolicyRequestDigest = Objects.requireNonNull(fixturePolicyRequestDigest);
      this.fixturePolicyEvidenceDigest = Objects.requireNonNull(fixturePolicyEvidenceDigest);
      this.fixturePolicyBytesDigest = Objects.requireNonNull(fixturePolicyBytesDigest);
      this.lifecycleBindingDigest = Objects.requireNonNull(lifecycleBindingDigest);
      this.gameSessionResponseBytesDigest = Objects.requireNonNull(gameSessionResponseBytesDigest);
      this.targetNamespace = Objects.requireNonNull(targetNamespace);
      this.canonicalTenantId = Objects.requireNonNull(canonicalTenantId);
      this.worldSlug = Objects.requireNonNull(worldSlug);
      this.worldSourceIntakeOperationId = Objects.requireNonNull(worldSourceIntakeOperationId);
      this.sourceOperationId = Objects.requireNonNull(sourceOperationId);
      this.sourceEvidenceDigest = Objects.requireNonNull(sourceEvidenceDigest);
      this.sourceReceiptDigest = Objects.requireNonNull(sourceReceiptDigest);
      this.worldLocalTenantKey = worldLocalTenantKey;
      this.worldVersionIdentityOperationId =
          Objects.requireNonNull(worldVersionIdentityOperationId);
      this.canonicalVersionId = Objects.requireNonNull(canonicalVersionId);
      this.gameDesignVersionId = gameDesignVersionId;
      this.versionEvidenceDigest = Objects.requireNonNull(versionEvidenceDigest);
      this.worldLocalVersionKey = worldLocalVersionKey;
      this.launchDescriptorId = Objects.requireNonNull(launchDescriptorId);
      this.descriptorRequestDigest = Objects.requireNonNull(descriptorRequestDigest);
      this.descriptorResultDigest = Objects.requireNonNull(descriptorResultDigest);
      this.descriptorRuntimeFlagsJson = Objects.requireNonNull(descriptorRuntimeFlagsJson);
      this.launchGameDesignVersionId = launchGameDesignVersionId;
      this.releaseBundleId = releaseBundleId;
      this.publishedReleaseBundleRef = Objects.requireNonNull(publishedReleaseBundleRef);
      this.manifestHash = Objects.requireNonNull(manifestHash);
      this.generationConfigRevision = Objects.requireNonNull(generationConfigRevision);
      this.versionStateEpoch = versionStateEpoch;
      this.gameSessionTenantId = gameSessionTenantId;
      this.gameSessionGameInstanceId = gameSessionGameInstanceId;
      this.gameSessionGameInstanceUuid = Objects.requireNonNull(gameSessionGameInstanceUuid);
      this.gameSessionCanonicalTenantId = Objects.requireNonNull(gameSessionCanonicalTenantId);
      this.gameSessionCanonicalVersionId = Objects.requireNonNull(gameSessionCanonicalVersionId);
      this.playableStateNamespaceId = Objects.requireNonNull(playableStateNamespaceId);
      this.canonicalRealmId = Objects.requireNonNull(canonicalRealmId);
      this.playableStateScope = Objects.requireNonNull(playableStateScope);
      this.publicProduction = publicProduction;
      this.catalogRevision = catalogRevision;
      this.gameTemplateId = gameTemplateId;
      this.expectedContentDigest = Objects.requireNonNull(expectedContentDigest);
      this.region = Objects.requireNonNull(region);
      this.zone = Objects.requireNonNull(zone);
      this.rooms = List.copyOf(rooms);
      this.exits = List.copyOf(exits);
      this.presetSealDigest = Objects.requireNonNull(presetSealDigest);
    }

    private FixedPresetPlan withPresetSealDigest(String digest) {
      return new FixedPresetPlan(
          schemaVersion,
          presetId,
          fixtureOperationId,
          claimDigest,
          fixturePolicyRequestDigest,
          fixturePolicyEvidenceDigest,
          fixturePolicyBytesDigest,
          lifecycleBindingDigest,
          gameSessionResponseBytesDigest,
          targetNamespace,
          canonicalTenantId,
          worldSlug,
          worldSourceIntakeOperationId,
          sourceOperationId,
          sourceEvidenceDigest,
          sourceReceiptDigest,
          worldLocalTenantKey,
          worldVersionIdentityOperationId,
          canonicalVersionId,
          gameDesignVersionId,
          versionEvidenceDigest,
          worldLocalVersionKey,
          launchDescriptorId,
          descriptorRequestDigest,
          descriptorResultDigest,
          descriptorRuntimeFlagsJson,
          launchGameDesignVersionId,
          releaseBundleId,
          publishedReleaseBundleRef,
          manifestHash,
          generationConfigRevision,
          versionStateEpoch,
          gameSessionTenantId,
          gameSessionGameInstanceId,
          gameSessionGameInstanceUuid,
          gameSessionCanonicalTenantId,
          gameSessionCanonicalVersionId,
          playableStateNamespaceId,
          canonicalRealmId,
          playableStateScope,
          publicProduction,
          catalogRevision,
          gameTemplateId,
          expectedContentDigest,
          region,
          zone,
          rooms,
          exits,
          digest);
    }

    public int schemaVersion() {
      return schemaVersion;
    }

    public String presetId() {
      return presetId;
    }

    public String fixtureOperationId() {
      return fixtureOperationId;
    }

    public String claimDigest() {
      return claimDigest;
    }

    public String fixturePolicyRequestDigest() {
      return fixturePolicyRequestDigest;
    }

    public String fixturePolicyEvidenceDigest() {
      return fixturePolicyEvidenceDigest;
    }

    public String fixturePolicyBytesDigest() {
      return fixturePolicyBytesDigest;
    }

    public String targetNamespace() {
      return targetNamespace;
    }

    public UUID canonicalTenantId() {
      return canonicalTenantId;
    }

    public String worldSlug() {
      return worldSlug;
    }

    public UUID worldSourceIntakeOperationId() {
      return worldSourceIntakeOperationId;
    }

    public UUID sourceOperationId() {
      return sourceOperationId;
    }

    public String sourceEvidenceDigest() {
      return sourceEvidenceDigest;
    }

    public String sourceReceiptDigest() {
      return sourceReceiptDigest;
    }

    public long worldLocalTenantKey() {
      return worldLocalTenantKey;
    }

    public UUID worldVersionIdentityOperationId() {
      return worldVersionIdentityOperationId;
    }

    public UUID canonicalVersionId() {
      return canonicalVersionId;
    }

    public long gameDesignVersionId() {
      return gameDesignVersionId;
    }

    public String versionEvidenceDigest() {
      return versionEvidenceDigest;
    }

    public long worldLocalVersionKey() {
      return worldLocalVersionKey;
    }

    public String launchDescriptorId() {
      return launchDescriptorId;
    }

    public String descriptorRequestDigest() {
      return descriptorRequestDigest;
    }

    public String descriptorResultDigest() {
      return descriptorResultDigest;
    }

    public String descriptorRuntimeFlagsJson() {
      return descriptorRuntimeFlagsJson;
    }

    public long launchGameDesignVersionId() {
      return launchGameDesignVersionId;
    }

    public long releaseBundleId() {
      return releaseBundleId;
    }

    public String publishedReleaseBundleRef() {
      return publishedReleaseBundleRef;
    }

    public String manifestHash() {
      return manifestHash;
    }

    public String generationConfigRevision() {
      return generationConfigRevision;
    }

    public long versionStateEpoch() {
      return versionStateEpoch;
    }

    public long gameSessionTenantId() {
      return gameSessionTenantId;
    }

    public long gameSessionGameInstanceId() {
      return gameSessionGameInstanceId;
    }

    public UUID gameSessionGameInstanceUuid() {
      return gameSessionGameInstanceUuid;
    }

    public UUID gameSessionCanonicalTenantId() {
      return gameSessionCanonicalTenantId;
    }

    public UUID gameSessionCanonicalVersionId() {
      return gameSessionCanonicalVersionId;
    }

    public String lifecycleBindingDigest() {
      return lifecycleBindingDigest;
    }

    public String gameSessionResponseBytesDigest() {
      return gameSessionResponseBytesDigest;
    }

    public UUID playableStateNamespaceId() {
      return playableStateNamespaceId;
    }

    public UUID canonicalRealmId() {
      return canonicalRealmId;
    }

    public String playableStateScope() {
      return playableStateScope;
    }

    public boolean publicProduction() {
      return publicProduction;
    }

    public long catalogRevision() {
      return catalogRevision;
    }

    public long gameTemplateId() {
      return gameTemplateId;
    }

    public String expectedContentDigest() {
      return expectedContentDigest;
    }

    public RegionDefinition region() {
      return region;
    }

    public ZoneDefinition zone() {
      return zone;
    }

    public List<RoomDefinition> rooms() {
      return rooms;
    }

    public List<ExitDefinition> exits() {
      return exits;
    }

    public String presetSealDigest() {
      return presetSealDigest;
    }

    public String expectedGraphSha256() {
      return sha256Raw(planJson().getBytes(StandardCharsets.UTF_8)).substring("sha256:".length());
    }

    public String planJson() {
      return RunOwnedWorldFixedPresetResources.planJson(this);
    }
  }

  public record MaterializedFixedPreset(
      long worldInstanceId,
      long tenantId,
      long privateGameInstanceKey,
      long versionId,
      String presetId,
      String presetContentDigest,
      String presetBindingDigest,
      String graphSha256,
      int regionCount,
      int zoneCount,
      int roomCount,
      int exitCount) {}
}
