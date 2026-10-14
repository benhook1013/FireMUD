package net.firedevops.firemud.worldmanagement.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;

/**
 * World-local verified projection of one run-owned fixture handoff.
 *
 * <p>The package-private constructor restricts creation to the fixed-server evidence provider. The
 * public wire records themselves remain untrusted data and are never accepted as this carrier.
 */
public final class RunOwnedWorldLaunchEvidence {
  private static final String BINDING_DOMAIN = "firemud-world-run-owned-launch-binding/v1";

  private final RunOwnedInitialAdmissionFixtureWireCodec.Capability capability;
  private final RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt sourceReceipt;
  private final AuthoredWorldLaunchDescriptorEvidence launchDescriptor;
  private final CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult
      gameSessionAssociation;
  private final byte[] capabilityBytes;
  private final byte[] sourceReceiptBytes;
  private final String gameSessionLeafSha256;
  private final String worldManagementUriSan;
  private final String worldManagementLeafSha256;
  private final String caCertificateSha256;

  RunOwnedWorldLaunchEvidence(
      RunOwnedInitialAdmissionFixtureWireCodec.Capability capability,
      RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt sourceReceipt,
      AuthoredWorldLaunchDescriptorEvidence launchDescriptor,
      byte[] capabilityBytes,
      byte[] sourceReceiptBytes,
      String gameSessionLeafSha256,
      String worldManagementUriSan,
      String worldManagementLeafSha256,
      String caCertificateSha256,
      CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult gameSessionAssociation) {
    this.capability = Objects.requireNonNull(capability, "capability");
    this.sourceReceipt = Objects.requireNonNull(sourceReceipt, "sourceReceipt");
    this.launchDescriptor = Objects.requireNonNull(launchDescriptor, "launchDescriptor");
    this.capabilityBytes = Objects.requireNonNull(capabilityBytes, "capabilityBytes").clone();
    this.sourceReceiptBytes =
        Objects.requireNonNull(sourceReceiptBytes, "sourceReceiptBytes").clone();
    this.gameSessionLeafSha256 =
        Objects.requireNonNull(gameSessionLeafSha256, "gameSessionLeafSha256");
    this.worldManagementUriSan =
        Objects.requireNonNull(worldManagementUriSan, "worldManagementUriSan");
    this.worldManagementLeafSha256 =
        Objects.requireNonNull(worldManagementLeafSha256, "worldManagementLeafSha256");
    this.caCertificateSha256 = Objects.requireNonNull(caCertificateSha256, "caCertificateSha256");
    this.gameSessionAssociation =
        Objects.requireNonNull(gameSessionAssociation, "gameSessionAssociation");
  }

  public RunOwnedInitialAdmissionFixtureWireCodec.Capability capability() {
    return capability;
  }

  public RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt sourceReceipt() {
    return sourceReceipt;
  }

  public AuthoredWorldLaunchDescriptorEvidence launchDescriptor() {
    return launchDescriptor;
  }

  /** Exact authenticated allocation read from the existing Game Session launch owner. */
  public CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult gameSessionAssociation() {
    return gameSessionAssociation;
  }

  /** The readback identifies the private Game Session keys; it grants no World write authority. */
  public void requireGameSessionAllocation(long tenantId, long gameInstanceId) {
    if (tenantId != gameSessionAssociation.gameSessionTenantId()
        || gameInstanceId != gameSessionAssociation.gameInstanceId()) {
      throw new IllegalArgumentException(
          "RUN_OWNED_WORLD_GAME_SESSION_ALLOCATION_MISMATCH: request differs from authenticated Game Session owner read");
    }
  }

  public String gameSessionLeafSha256() {
    return gameSessionLeafSha256;
  }

  public String worldManagementUriSan() {
    return worldManagementUriSan;
  }

  public String worldManagementLeafSha256() {
    return worldManagementLeafSha256;
  }

  public String caCertificateSha256() {
    return caCertificateSha256;
  }

  /** Verifies the complete request tuple against the descriptor and committed owner snapshots. */
  public void requireMatches(PreparedWorldInstanceRequest request) {
    Objects.requireNonNull(request, "request");
    var bundle = sourceReceipt.launchReadEvidence().publishedReleaseBundle();
    var artifact = sourceReceipt.launchReadEvidence().versionAssetArtifactState();
    var version = sourceReceipt.launchReadEvidence().versionState();
    var source = sourceReceipt.sourceEvidence();
    boolean publishedOrActive =
        version.getVersionState()
                == net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                    .VERSION_LIFECYCLE_STATE_PUBLISHED
            || version.getVersionState()
                == net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                    .VERSION_LIFECYCLE_STATE_ACTIVE;
    if (request.tenantId() <= 0L
        || request.gameInstanceId() <= 0L
        || request.gameTemplateId() != capability.gameTemplateId()
        || !capability.operationId().equals(request.controlPlaneRequestId())
        || !sourceReceipt.fixtureOperationId().equals(request.controlPlaneRequestId())
        || !launchDescriptor.launchDescriptorId().equals(request.launchDescriptorId())
        || launchDescriptor.gameTemplateId() != request.gameTemplateId()
        || launchDescriptor.versionId() != request.versionId()
        || !Objects.equals(
            launchDescriptor.scriptPatchVersionPresent()
                ? launchDescriptor.scriptPatchVersion()
                : null,
            normalizeBlank(request.scriptPatchVersion()))
        || !effectiveRuntimeFlags(request.runtimeFlagsJson())
            .equals(launchDescriptor.runtimeFlagsJson())
        || !launchDescriptor.generationConfigRevision().equals(request.generationConfigRevision())
        || launchDescriptor.releaseBundleId() != request.releaseBundleId()
        || !launchDescriptor.publishedReleaseBundleRef().equals(request.publishedReleaseBundleRef())
        || launchDescriptor.versionStateEpoch() != request.versionStateEpoch()
        || !Objects.equals(
            launchDescriptor.remapSetIdPresent() ? launchDescriptor.remapSetId() : null,
            normalizeBlank(request.remapSetId()))
        || bundle.getId() != request.releaseBundleId()
        || bundle.getVersionId() != request.versionId()
        || !bundle.getPublishedReleaseBundleRef().equals(request.publishedReleaseBundleRef())
        || !bundle.getGenerationConfigRevision().equals(request.generationConfigRevision())
        || artifact.getVersionId() != request.versionId()
        || artifact.getArtifactState()
            != net.firedevops.firemud.gamedesign.v1.ArtifactState.ARTIFACT_STATE_PUBLISHED
        || !artifact.getManifestHash().equals(bundle.getManifestHash())
        || version.getVersionId() != request.versionId()
        || !publishedOrActive
        || version.getVersionStateEpoch() != request.versionStateEpoch()
        || !source.targetNamespace().equals("dev")
        || !Long.toString(capability.tenantId()).equals(source.sourceGameTenantKey())
        || !launchDescriptor.canonicalTenantId().equals(source.canonicalTenantId())
        || !launchDescriptor.worldSlug().equals(source.worldSlug())
        || !launchDescriptor.authoredWorldSourceOperationId().equals(source.operationId())
        || !launchDescriptor.authoredWorldSourceEvidenceDigest().equals(source.evidenceDigest())
        || launchDescriptor.requestedScriptPatchVersionPresent()
        || launchDescriptor.sourceVersionIdPresent()
        || launchDescriptor.targetVersionIdPresent()
        || launchDescriptor.requestedRuntimeFlagsJsonPresent()
        || launchDescriptor.remapSetIdPresent()
        || !request.controlPlaneRequestId().equals(launchDescriptor.controlPlaneRequestId())) {
      throw new IllegalArgumentException(
          "RUN_OWNED_WORLD_LAUNCH_EVIDENCE_MISMATCH: prepared request differs from committed fixture evidence");
    }
    requireGameSessionAllocation(request.tenantId(), request.gameInstanceId());
    requireExactGameSessionFixtureAssociation();
  }

  private void requireExactGameSessionFixtureAssociation() {
    var expectedPolicy =
        new RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicyRequest(
            capability,
            sourceReceipt.creationEvidence(),
            sourceReceipt.sourceEvidence(),
            sourceReceipt.launchReadEvidence());
    byte[] expectedPolicyBytes =
        RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestBytes(
            capability,
            sourceReceipt.creationEvidence(),
            sourceReceipt.sourceEvidence(),
            sourceReceipt.launchReadEvidence());
    if (!gameSessionAssociation
            .request()
            .fixtureOperationId()
            .toString()
            .equals(capability.operationId())
        || !gameSessionAssociation
            .request()
            .targetNamespace()
            .equals(sourceReceipt.sourceEvidence().targetNamespace())
        || !gameSessionAssociation
            .request()
            .expectedFixturePolicyEvidenceDigest()
            .equals(sourceReceipt.fixturePolicySeal().evidenceDigest())
        || !gameSessionAssociation.fixturePolicySeal().equals(sourceReceipt.fixturePolicySeal())
        || !Objects.equals(gameSessionAssociation.fixturePolicyRequest(), expectedPolicy)
        || !Arrays.equals(gameSessionAssociation.fixturePolicyRequestBytes(), expectedPolicyBytes)
        || !gameSessionAssociation
            .canonicalTenantId()
            .equals(sourceReceipt.sourceEvidence().canonicalTenantId())
        || !gameSessionAssociation
            .canonicalVersionId()
            .toString()
            .equals(
                sourceReceipt
                    .launchReadEvidence()
                    .publishedReleaseBundle()
                    .getCanonicalVersionId())) {
      throw new IllegalArgumentException(
          "RUN_OWNED_WORLD_GAME_SESSION_FIXTURE_ASSOCIATION_MISMATCH: owner read differs from immutable fixture receipt");
    }
  }

  /**
   * Returns the durable digest for the immutable evidence, exact request, target instance, and
   * original PREPARING epoch using the repository's length-framed digest convention.
   */
  public String bindingDigest(PreparedWorldInstanceRequest request, long preparingEpoch) {
    requireMatches(request);
    if (preparingEpoch <= 0L) {
      throw new IllegalArgumentException("RUN_OWNED_WORLD_LAUNCH_EPOCH_INVALID");
    }
    StringBuilder preimage = new StringBuilder();
    appendDigestField(preimage, "domain", BINDING_DOMAIN);
    appendDigestField(preimage, "capabilityBytesSha256", sha256Hex(capabilityBytes));
    appendDigestField(preimage, "sourceReceiptBytesSha256", sha256Hex(sourceReceiptBytes));
    appendDigestField(preimage, "runId", capability.runId());
    appendDigestField(preimage, "composeProjectName", capability.composeProjectName());
    appendDigestField(preimage, "fixtureOperationId", capability.operationId());
    appendDigestField(preimage, "gameSessionUriSan", capability.gameSessionUriSan());
    appendDigestField(preimage, "gameSessionLeafSha256", gameSessionLeafSha256);
    appendDigestField(preimage, "worldManagementUriSan", worldManagementUriSan);
    appendDigestField(preimage, "worldManagementLeafSha256", worldManagementLeafSha256);
    appendDigestField(preimage, "caCertificateSha256", caCertificateSha256);
    appendDigestField(
        preimage, "sourceEvidenceDigest", sourceReceipt.sourceEvidence().evidenceDigest());
    appendDigestField(preimage, "descriptorRequestDigest", launchDescriptor.requestDigest());
    appendDigestField(preimage, "descriptorResultDigest", launchDescriptor.resultDigest());
    appendDigestField(preimage, "tenantId", Long.toString(request.tenantId()));
    appendDigestField(preimage, "gameInstanceId", Long.toString(request.gameInstanceId()));
    appendDigestField(preimage, "gameTemplateId", Long.toString(request.gameTemplateId()));
    appendDigestField(preimage, "controlPlaneRequestId", request.controlPlaneRequestId());
    appendDigestField(preimage, "launchDescriptorId", request.launchDescriptorId());
    appendDigestField(preimage, "versionId", Long.toString(request.versionId()));
    appendDigestField(
        preimage, "scriptPatchVersion", nullToEmpty(normalizeBlank(request.scriptPatchVersion())));
    appendDigestField(
        preimage, "runtimeFlagsJson", effectiveRuntimeFlags(request.runtimeFlagsJson()));
    appendDigestField(preimage, "generationConfigRevision", request.generationConfigRevision());
    appendDigestField(preimage, "releaseBundleId", Long.toString(request.releaseBundleId()));
    appendDigestField(preimage, "publishedReleaseBundleRef", request.publishedReleaseBundleRef());
    appendDigestField(preimage, "versionStateEpoch", Long.toString(request.versionStateEpoch()));
    appendDigestField(preimage, "remapSetId", nullToEmpty(normalizeBlank(request.remapSetId())));
    appendDigestField(preimage, "preparingLifecycleEpoch", Long.toString(preparingEpoch));
    return sha256Hex(preimage.toString().getBytes(StandardCharsets.UTF_8));
  }

  byte[] capabilityBytes() {
    return capabilityBytes.clone();
  }

  byte[] sourceReceiptBytes() {
    return sourceReceiptBytes.clone();
  }

  private static String effectiveRuntimeFlags(String value) {
    String normalized = normalizeBlank(value);
    return normalized == null ? "{}" : value;
  }

  private static String normalizeBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static void appendDigestField(StringBuilder preimage, String name, String value) {
    String normalized = nullToEmpty(value);
    int nameByteLength = name.getBytes(StandardCharsets.UTF_8).length;
    int valueByteLength = normalized.getBytes(StandardCharsets.UTF_8).length;
    preimage
        .append(nameByteLength)
        .append(':')
        .append(name)
        .append(valueByteLength)
        .append(':')
        .append(normalized);
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
