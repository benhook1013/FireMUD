package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.gamesession.v1.GetRunOwnedFixtureLaunchAssociationResponse;

/**
 * Owner-local immutable correlation between a claimed canonical Game Session instance and one
 * insert-born World instance row.
 *
 * <p>This association is not Game Session producer authentication, released-content authority,
 * lifecycle state, activation, JOIN, or admission proof. A caller must establish the authenticated
 * same-namespace producer and complete canonical prepare/materialization proof independently.
 */
public record WorldCanonicalInstanceAssociation(
    CanonicalIdentity identity,
    long worldInstanceId,
    WorldCompleteLaunchBindingReceipt completeLaunchBinding,
    WorldAuthoredVersionIdentityReceipt versionIdentity,
    WorldPrepareFields worldPrepareFields,
    RunOwnedFixture runOwnedFixture) {
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

  /** Existing Human association constructor retained at its call sites. */
  public WorldCanonicalInstanceAssociation(
      CanonicalIdentity identity,
      long worldInstanceId,
      WorldCompleteLaunchBindingReceipt completeLaunchBinding,
      WorldAuthoredVersionIdentityReceipt versionIdentity,
      WorldPrepareFields worldPrepareFields) {
    this(
        identity,
        worldInstanceId,
        completeLaunchBinding,
        versionIdentity,
        worldPrepareFields,
        null);
  }

  public WorldCanonicalInstanceAssociation {
    Objects.requireNonNull(identity, "identity");
    requirePositive(worldInstanceId, "worldInstanceId");
    if (runOwnedFixture == null) {
      Objects.requireNonNull(completeLaunchBinding, "completeLaunchBinding");
      Objects.requireNonNull(versionIdentity, "versionIdentity");
      Objects.requireNonNull(worldPrepareFields, "worldPrepareFields");
      if (worldInstanceId != worldPrepareFields.worldInstanceId()) {
        throw new IllegalArgumentException(
            "World association row id differs from its prepare fields");
      }
      requireExactLaunchEvidence(identity, completeLaunchBinding, versionIdentity);
      worldPrepareFields.requireMatches(identity, completeLaunchBinding, versionIdentity);
    } else {
      if (completeLaunchBinding != null || worldPrepareFields != null) {
        throw new IllegalArgumentException(
            "Run-owned fixture association cannot carry Human launch or graph preparation fields");
      }
      Objects.requireNonNull(versionIdentity, "versionIdentity");
      runOwnedFixture.requireMatches(identity, worldInstanceId, versionIdentity);
    }
  }

  public UUID canonicalVersionId() {
    if (runOwnedFixture != null) return runOwnedFixture.canonicalVersionId();
    return completeLaunchBinding.evidence().releaseAttestation().canonicalVersionId();
  }

  public UUID launchBindingOperationId() {
    if (runOwnedFixture != null) return null;
    return completeLaunchBinding.operationId();
  }

  public UUID versionIdentityOperationId() {
    return versionIdentity.operationId();
  }

  public boolean isRunOwnedFixture() {
    return runOwnedFixture != null;
  }

  /** World-private numeric keys are kept distinct from the authenticated Game Session keys. */
  public WorldPrivateKeys worldPrivateKeys() {
    if (runOwnedFixture != null) return runOwnedFixture.worldPrivateKeys();
    return new WorldPrivateKeys(
        worldPrepareFields.privateTenantKey(),
        worldPrepareFields.privateGameInstanceKey(),
        worldPrepareFields.localVersionKey());
  }

  public record WorldPrivateKeys(long tenantId, long gameInstanceId, long versionId) {
    public WorldPrivateKeys {
      requirePositive(tenantId, "tenantId");
      requirePositive(gameInstanceId, "gameInstanceId");
      requirePositive(versionId, "versionId");
    }
  }

  /** Typed persisted fixture association; it is not an attestation or graph proof. */
  public record RunOwnedFixture(
      WorldPrivateKeys worldPrivateKeys,
      long gameSessionTenantId,
      long gameSessionGameInstanceId,
      long gameTemplateId,
      UUID fixtureOperationId,
      String fixtureProvenanceDigest,
      String preparationInputDigest,
      long preparingLifecycleEpoch,
      byte[] gameSessionAssociationResponseBytes,
      CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult gameSessionAssociation,
      WorldAuthoredSourceIntakeReceipt sourceIntakeReceipt) {
    public RunOwnedFixture {
      Objects.requireNonNull(worldPrivateKeys, "worldPrivateKeys");
      requirePositive(gameSessionTenantId, "gameSessionTenantId");
      requirePositive(gameSessionGameInstanceId, "gameSessionGameInstanceId");
      requirePositive(gameTemplateId, "gameTemplateId");
      requireNonNil(fixtureOperationId, "fixtureOperationId");
      requireDigest(fixtureProvenanceDigest, "fixtureProvenanceDigest");
      requireDigest(preparationInputDigest, "preparationInputDigest");
      if (preparingLifecycleEpoch != 1L) {
        throw new IllegalArgumentException(
            "Fixture association requires its original PREPARING epoch");
      }
      gameSessionAssociationResponseBytes =
          Objects.requireNonNull(
                  gameSessionAssociationResponseBytes, "gameSessionAssociationResponseBytes")
              .clone();
      Objects.requireNonNull(gameSessionAssociation, "gameSessionAssociation");
      Objects.requireNonNull(sourceIntakeReceipt, "sourceIntakeReceipt");
      if (gameSessionAssociationResponseBytes.length == 0) {
        throw new IllegalArgumentException(
            "Exact Game Session fixture response bytes are required");
      }
      try {
        var decoded =
            CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromFixtureResponse(
                gameSessionAssociation.request(),
                GetRunOwnedFixtureLaunchAssociationResponse.parseFrom(
                    gameSessionAssociationResponseBytes));
        if (!decoded.equals(gameSessionAssociation)
            || !Arrays.equals(
                gameSessionAssociationResponseBytes,
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toFixtureResponse(decoded)
                    .toByteArray())) {
          throw new IllegalArgumentException(
              "Stored Game Session fixture response bytes differ from their exact decoded result");
        }
      } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
        throw new IllegalArgumentException(
            "Stored Game Session fixture response bytes are invalid", invalid);
      }
    }

    @Override
    public byte[] gameSessionAssociationResponseBytes() {
      return gameSessionAssociationResponseBytes.clone();
    }

    public UUID canonicalVersionId() {
      return gameSessionAssociation.canonicalVersionId();
    }

    private void requireMatches(
        CanonicalIdentity identity,
        long worldInstanceId,
        WorldAuthoredVersionIdentityReceipt versionIdentity) {
      var gs = gameSessionAssociation;
      var policy = gs.fixturePolicyRequest();
      var capability = policy.capability();
      var source = policy.sourceEvidence();
      if (gs.gameSessionTenantId() != gameSessionTenantId
          || gs.gameInstanceId() != gameSessionGameInstanceId
          || !gs.gameInstanceUuid().equals(identity.canonicalGameInstanceId())
          || !gs.canonicalTenantId().equals(identity.canonicalTenantId())
          || !gs.request().targetNamespace().equals(identity.targetNamespace())
          || !source.worldSlug().equals(identity.worldSlug())
          || !gs.playableStateNamespaceId().equals(identity.playableStateNamespaceId())
          || !capability.stateScope().equals(identity.playableStateScope())
          || capability.publicProductionRealm() != identity.publicProduction()
          || !gs.canonicalRealmId().equals(identity.canonicalRealmId())
          || gs.catalogRevision() != identity.catalogRevision()
          || !fixtureOperationId.equals(gs.request().fixtureOperationId())
          || !identity.controlPlaneRequestId().equals(fixtureOperationId.toString())
          || !fixtureProvenanceDigest.equals(gs.fixturePolicySeal().evidenceDigest())
          || !identity.canonicalTenantId().equals(source.canonicalTenantId())
          || !identity.targetNamespace().equals(source.targetNamespace())
          || !identity.worldSlug().equals(source.worldSlug())
          || !identity.worldSlug().equals(capability.worldSlug())
          || !sourceIntakeReceipt.source().equals(source)
          || !sourceIntakeReceipt.targetNamespace().equals(identity.targetNamespace())
          || !sourceIntakeReceipt.canonicalTenantId().equals(identity.canonicalTenantId())
          || !sourceIntakeReceipt.worldSlug().equals(identity.worldSlug())
          || worldPrivateKeys.tenantId() != sourceIntakeReceipt.localTenantKey()
          || worldPrivateKeys.versionId() != versionIdentity.localVersionKey()
          || versionIdentity.sourceIntakeReceipt().localTenantKey()
              != sourceIntakeReceipt.localTenantKey()
          || !versionIdentity.sourceIntakeReceipt().equals(sourceIntakeReceipt)
          || !versionIdentity.canonicalVersionId().equals(gs.canonicalVersionId())
          || versionIdentity.versionStateEvidence().versionState()
              != policy.launchReadEvidence().versionState().getVersionState()
          || versionIdentity.gameDesignVersionId()
              != policy.launchReadEvidence().launchDescriptor().getVersionId()
          || versionIdentity.versionStateEvidence().versionStateEpoch()
              != policy.launchReadEvidence().versionState().getVersionStateEpoch()
          || versionIdentity.versionStateEvidence().versionStateEpoch()
              != policy.launchReadEvidence().launchDescriptor().getVersionStateEpoch()
          || gameTemplateId != capability.gameTemplateId()
          || !identity.controlPlaneRequestId().equals(capability.operationId())
          || !"NEW_GAME_ROW".equals(sourceIntakeReceipt.source().provenanceKind())) {
        throw new IllegalArgumentException(
            "Run-owned fixture association differs from the exact owner allocation or World source/Version receipts");
      }
      requirePositive(worldInstanceId, "worldInstanceId");
    }
  }

  /**
   * Immutable canonical Game Session identity and owner scope, excluding transient read metadata.
   */
  public record CanonicalIdentity(
      UUID canonicalGameInstanceId,
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID playableStateNamespaceId,
      String playableStateScope,
      boolean publicProduction,
      UUID canonicalRealmId,
      long catalogRevision,
      String controlPlaneRequestId) {
    public CanonicalIdentity {
      requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("targetNamespace must be one canonical DNS label");
      }
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      Objects.requireNonNull(worldSlug, "worldSlug");
      AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
      requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
      requireNonNil(canonicalRealmId, "canonicalRealmId");
      requirePositive(catalogRevision, "catalogRevision");
      if (!"SHARED".equals(playableStateScope)) {
        throw new IllegalArgumentException(
            "This association slice accepts SHARED only; ISOLATED/playtest identity requires the missing lifecycle/generation carrier");
      }
      if (!publicProduction) {
        throw new IllegalArgumentException(
            "This association slice requires explicit public-production evidence; false or absent evidence is denied");
      }
      requireText(controlPlaneRequestId, "controlPlaneRequestId", 128);
    }
  }

  /**
   * Exact caller-owned selector and expected digests for Gameplay's owner-read RPC.
   *
   * <p>The read request UUID and expected digests are transient request metadata; the repository
   * validates the separate response echo and does not persist this request identity.
   */
  public record GameSessionReadRequest(
      UUID readRequestId,
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID canonicalGameInstanceId,
      String controlPlaneRequestId,
      String launchDescriptorId,
      String expectedDescriptorRequestDigest,
      String expectedDescriptorResultDigest,
      String expectedReleaseAttestationEvidenceDigest) {
    public GameSessionReadRequest {
      requireNonNil(readRequestId, "readRequestId");
      AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
      requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
      requireText(controlPlaneRequestId, "controlPlaneRequestId", 128);
      requireText(launchDescriptorId, "launchDescriptorId", 64);
      requireDigest(expectedDescriptorRequestDigest, "expectedDescriptorRequestDigest");
      requireDigest(expectedDescriptorResultDigest, "expectedDescriptorResultDigest");
      requireDigest(
          expectedReleaseAttestationEvidenceDigest, "expectedReleaseAttestationEvidenceDigest");
    }
  }

  /**
   * Typed copy of Gameplay's owner-read response, kept separate from its request selector.
   *
   * <p>The echoed fields are checked against {@link GameSessionReadRequest}. Current status and row
   * version are transient Game Session projection only; neither is frozen into the association or
   * treated as World lifecycle authority.
   */
  public record GameSessionReadEvidence(
      UUID readRequestId,
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID canonicalGameInstanceId,
      String controlPlaneRequestId,
      String launchDescriptorId,
      String descriptorRequestDigest,
      String descriptorResultDigest,
      String releaseAttestationEvidenceDigest,
      UUID playableStateNamespaceId,
      String playableStateScope,
      boolean publicProduction,
      UUID canonicalRealmId,
      long catalogRevision,
      String currentGameSessionStatus,
      long currentGameSessionRowVersion,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      AuthoredWorldReleaseAttestationEvidence releaseAttestation) {
    public GameSessionReadEvidence {
      requireNonNil(readRequestId, "readRequestId");
      AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
      requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
      requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
      requireNonNil(canonicalRealmId, "canonicalRealmId");
      requirePositive(catalogRevision, "catalogRevision");
      requireText(controlPlaneRequestId, "controlPlaneRequestId", 128);
      requireText(launchDescriptorId, "launchDescriptorId", 64);
      requireDigest(descriptorRequestDigest, "descriptorRequestDigest");
      requireDigest(descriptorResultDigest, "descriptorResultDigest");
      requireDigest(releaseAttestationEvidenceDigest, "releaseAttestationEvidenceDigest");
      if (!"SHARED".equals(playableStateScope)) {
        throw new IllegalArgumentException(
            "This association slice accepts SHARED only; ISOLATED/playtest identity requires the missing lifecycle/generation carrier");
      }
      if (!publicProduction) {
        throw new IllegalArgumentException(
            "This association slice requires explicit public-production evidence; false or absent evidence is denied");
      }
      requireText(currentGameSessionStatus, "currentGameSessionStatus", 32);
      requireNonNegative(currentGameSessionRowVersion, "currentGameSessionRowVersion");
      Objects.requireNonNull(descriptor, "descriptor");
      Objects.requireNonNull(releaseAttestation, "releaseAttestation");
      descriptor.requireValid();
      new CompleteLaunchBindingEvidence(descriptor, releaseAttestation);
      if (!targetNamespace.equals(descriptor.targetNamespace())
          || !canonicalTenantId.equals(descriptor.canonicalTenantId())
          || !worldSlug.equals(descriptor.worldSlug())
          || !controlPlaneRequestId.equals(descriptor.controlPlaneRequestId())
          || !launchDescriptorId.equals(descriptor.launchDescriptorId())
          || !descriptorRequestDigest.equals(descriptor.requestDigest())
          || !descriptorResultDigest.equals(descriptor.resultDigest())
          || !releaseAttestationEvidenceDigest.equals(releaseAttestation.evidenceDigest())) {
        throw new IllegalArgumentException(
            "Game Session launch-association response differs from its typed complete launch evidence");
      }
    }

    public CanonicalIdentity canonicalIdentity() {
      return new CanonicalIdentity(
          canonicalGameInstanceId,
          targetNamespace,
          canonicalTenantId,
          worldSlug,
          playableStateNamespaceId,
          playableStateScope,
          publicProduction,
          canonicalRealmId,
          catalogRevision,
          controlPlaneRequestId);
    }
  }

  /**
   * Non-authorizing persistence input. The transient Game Session read correlation/status fields
   * are not association identity; the exact complete binding and source/version receipts are.
   */
  public record Claim(
      GameSessionReadRequest gameSessionRequest,
      GameSessionReadEvidence gameSessionRead,
      long worldInstanceId,
      WorldCompleteLaunchBindingReceipt completeLaunchBinding,
      WorldAuthoredVersionIdentityReceipt versionIdentity) {
    public Claim {
      Objects.requireNonNull(gameSessionRequest, "gameSessionRequest");
      Objects.requireNonNull(gameSessionRead, "gameSessionRead");
      requirePositive(worldInstanceId, "worldInstanceId");
      Objects.requireNonNull(completeLaunchBinding, "completeLaunchBinding");
      Objects.requireNonNull(versionIdentity, "versionIdentity");
      requireExactReadEcho(gameSessionRequest, gameSessionRead);
      CanonicalIdentity identity = gameSessionRead.canonicalIdentity();
      requireExactLaunchEvidence(identity, completeLaunchBinding, versionIdentity);
      if (!gameSessionRead.descriptor().equals(completeLaunchBinding.evidence().descriptor())
          || !gameSessionRead
              .releaseAttestation()
              .equals(completeLaunchBinding.evidence().releaseAttestation())
          || !gameSessionRead
              .launchDescriptorId()
              .equals(completeLaunchBinding.descriptor().launchDescriptorId())
          || !gameSessionRead
              .descriptorRequestDigest()
              .equals(completeLaunchBinding.descriptor().requestDigest())
          || !gameSessionRead
              .descriptorResultDigest()
              .equals(completeLaunchBinding.descriptor().resultDigest())
          || !gameSessionRead
              .releaseAttestationEvidenceDigest()
              .equals(completeLaunchBinding.evidence().releaseAttestation().evidenceDigest())) {
        throw new IllegalArgumentException(
            "Game Session read projection differs from the original World V26 complete binding");
      }
    }

    public UUID canonicalGameInstanceId() {
      return gameSessionRead.canonicalGameInstanceId();
    }

    public CanonicalIdentity identity() {
      return gameSessionRead.canonicalIdentity();
    }

    public String targetNamespace() {
      return gameSessionRead.targetNamespace();
    }

    public UUID canonicalTenantId() {
      return gameSessionRead.canonicalTenantId();
    }

    public String worldSlug() {
      return gameSessionRead.worldSlug();
    }

    public UUID playableStateNamespaceId() {
      return gameSessionRead.playableStateNamespaceId();
    }

    public String playableStateScope() {
      return gameSessionRead.playableStateScope();
    }

    public String controlPlaneRequestId() {
      return gameSessionRead.controlPlaneRequestId();
    }

    public String launchDescriptorId() {
      return gameSessionRead.launchDescriptorId();
    }

    public String descriptorRequestDigest() {
      return gameSessionRead.descriptorRequestDigest();
    }

    public String descriptorResultDigest() {
      return gameSessionRead.descriptorResultDigest();
    }

    public String releaseAttestationDigest() {
      return gameSessionRead.releaseAttestationEvidenceDigest();
    }

    public UUID canonicalVersionId() {
      return completeLaunchBinding.evidence().releaseAttestation().canonicalVersionId();
    }

    public long localTenantKey() {
      return completeLaunchBinding.sourceIntakeReceipt().localTenantKey();
    }

    public long localVersionKey() {
      return versionIdentity.localVersionKey();
    }

    public UUID launchBindingOperationId() {
      return completeLaunchBinding.operationId();
    }

    public UUID versionIdentityOperationId() {
      return versionIdentity.operationId();
    }
  }

  /** Exact immutable subset of the legacy row created by World's existing prepare owner. */
  public record WorldPrepareFields(
      long worldInstanceId,
      long privateTenantKey,
      long privateGameInstanceKey,
      long gameTemplateId,
      String controlPlaneRequestId,
      String launchDescriptorId,
      long localVersionKey,
      String scriptPatchVersion,
      String runtimeFlagsJson,
      String generationConfigRevision,
      long releaseBundleId,
      String publishedReleaseBundleRef,
      long versionStateEpoch,
      String remapSetId) {
    public WorldPrepareFields {
      requirePositive(worldInstanceId, "worldInstanceId");
      requirePositive(privateTenantKey, "privateTenantKey");
      requirePositive(privateGameInstanceKey, "privateGameInstanceKey");
      requirePositive(gameTemplateId, "gameTemplateId");
      requireText(controlPlaneRequestId, "controlPlaneRequestId", 128);
      requireText(launchDescriptorId, "launchDescriptorId", 64);
      requirePositive(localVersionKey, "localVersionKey");
      requireText(runtimeFlagsJson, "runtimeFlagsJson", Integer.MAX_VALUE);
      requireText(generationConfigRevision, "generationConfigRevision", 128);
      requirePositive(releaseBundleId, "releaseBundleId");
      requireText(publishedReleaseBundleRef, "publishedReleaseBundleRef", 128);
      requirePositive(versionStateEpoch, "versionStateEpoch");
      requireOptionalText(scriptPatchVersion, "scriptPatchVersion", 100);
      requireOptionalText(remapSetId, "remapSetId", 64);
    }

    /**
     * Compares immutable prepare and release inputs only; lifecycle state, epoch, termination, and
     * timestamps are intentionally not part of this association.
     */
    public void requireMatches(
        CanonicalIdentity identity,
        WorldCompleteLaunchBindingReceipt completeLaunchBinding,
        WorldAuthoredVersionIdentityReceipt versionIdentity) {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(completeLaunchBinding, "completeLaunchBinding");
      Objects.requireNonNull(versionIdentity, "versionIdentity");
      var descriptor = completeLaunchBinding.descriptor();
      String expectedScriptPatch =
          descriptor.scriptPatchVersionPresent() ? descriptor.scriptPatchVersion() : null;
      String expectedRemapSet = descriptor.remapSetIdPresent() ? descriptor.remapSetId() : null;
      if (privateTenantKey != completeLaunchBinding.sourceIntakeReceipt().localTenantKey()
          || gameTemplateId != descriptor.gameTemplateId()
          || !controlPlaneRequestId.equals(identity.controlPlaneRequestId())
          || !controlPlaneRequestId.equals(descriptor.controlPlaneRequestId())
          || !launchDescriptorId.equals(descriptor.launchDescriptorId())
          || localVersionKey != versionIdentity.localVersionKey()
          || !Objects.equals(scriptPatchVersion, expectedScriptPatch)
          || !runtimeFlagsJson.equals(descriptor.runtimeFlagsJson())
          || !generationConfigRevision.equals(descriptor.generationConfigRevision())
          || releaseBundleId != descriptor.releaseBundleId()
          || !publishedReleaseBundleRef.equals(descriptor.publishedReleaseBundleRef())
          || versionStateEpoch != descriptor.versionStateEpoch()
          || !Objects.equals(remapSetId, expectedRemapSet)) {
        throw new IllegalArgumentException(
            "World instance row differs from the exact canonical launch prepare inputs");
      }
    }
  }

  private static void requireExactLaunchEvidence(
      CanonicalIdentity identity,
      WorldCompleteLaunchBindingReceipt binding,
      WorldAuthoredVersionIdentityReceipt versionIdentity) {
    var descriptor = binding.evidence().descriptor();
    var release = binding.evidence().releaseAttestation();
    var source = binding.sourceIntakeReceipt();
    if (!identity.targetNamespace().equals(binding.targetNamespace())
        || !identity.canonicalTenantId().equals(binding.canonicalTenantId())
        || !identity.worldSlug().equals(binding.worldSlug())
        || !identity.controlPlaneRequestId().equals(binding.controlPlaneRequestId())
        || !identity.targetNamespace().equals(descriptor.targetNamespace())
        || !identity.canonicalTenantId().equals(descriptor.canonicalTenantId())
        || !identity.worldSlug().equals(descriptor.worldSlug())
        || !identity.controlPlaneRequestId().equals(descriptor.controlPlaneRequestId())
        || !identity.targetNamespace().equals(release.targetNamespace())
        || !identity.canonicalTenantId().equals(release.canonicalTenantId())
        || !identity.worldSlug().equals(release.worldSlug())
        || !descriptor.authoredWorldSourceOperationId().equals(source.sourceOperationId())
        || !descriptor.authoredWorldSourceEvidenceDigest().equals(source.sourceEvidenceDigest())
        || !source.equals(versionIdentity.sourceIntakeReceipt())
        || !identity.targetNamespace().equals(versionIdentity.targetNamespace())
        || !identity.canonicalTenantId().equals(versionIdentity.canonicalTenantId())
        || !identity.worldSlug().equals(versionIdentity.worldSlug())
        || !release.canonicalVersionId().equals(versionIdentity.canonicalVersionId())
        || descriptor.versionId() != versionIdentity.gameDesignVersionId()
        || versionIdentity.sourceIntakeReceipt().localTenantKey() != source.localTenantKey()) {
      throw new IllegalArgumentException(
          "Canonical instance claim differs from its exact World launch/source/version evidence");
    }
  }

  private static void requireExactReadEcho(
      GameSessionReadRequest request, GameSessionReadEvidence response) {
    if (!request.readRequestId().equals(response.readRequestId())
        || !request.targetNamespace().equals(response.targetNamespace())
        || !request.canonicalTenantId().equals(response.canonicalTenantId())
        || !request.worldSlug().equals(response.worldSlug())
        || !request.canonicalGameInstanceId().equals(response.canonicalGameInstanceId())
        || !request.controlPlaneRequestId().equals(response.controlPlaneRequestId())
        || !request.launchDescriptorId().equals(response.launchDescriptorId())
        || !request.expectedDescriptorRequestDigest().equals(response.descriptorRequestDigest())
        || !request.expectedDescriptorResultDigest().equals(response.descriptorResultDigest())
        || !request
            .expectedReleaseAttestationEvidenceDigest()
            .equals(response.releaseAttestationEvidenceDigest())) {
      throw new IllegalArgumentException(
          "Game Session launch-association response does not echo the exact read request tuple and expected digests");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private static void requirePositive(long value, String label) {
    if (value <= 0L) {
      throw new IllegalArgumentException(label + " must be positive");
    }
  }

  private static void requireNonNegative(long value, String label) {
    if (value < 0L) {
      throw new IllegalArgumentException(label + " must not be negative");
    }
  }

  private static void requireText(String value, String label, int maxLength) {
    if (value == null || value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(label + " must be nonblank and within its storage bound");
    }
  }

  private static void requireDigest(String value, String label) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be a lowercase SHA-256 digest");
    }
  }

  private static void requireOptionalText(String value, String label, int maxLength) {
    if (value != null && (value.isBlank() || value.length() > maxLength)) {
      throw new IllegalArgumentException(
          label + " must be absent or nonblank within its storage bound");
    }
  }
}
