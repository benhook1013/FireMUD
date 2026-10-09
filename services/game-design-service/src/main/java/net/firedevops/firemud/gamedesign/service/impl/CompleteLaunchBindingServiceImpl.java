package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.dto.CompleteLaunchBindingDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository.AuthoredWorldVersionStateSnapshot;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.CompleteLaunchBindingService;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads the already committed immutable launch descriptor and complete release evidence. */
@Service
public class CompleteLaunchBindingServiceImpl implements CompleteLaunchBindingService {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final int SUPPORTED_MANIFEST_SCHEMA_VERSION = 1;
  private static final int SUPPORTED_ARTIFACT_SCHEMA_VERSION = 1;

  private final LaunchDescriptorService launchDescriptorService;
  private final GameAuthoredWorldSourceRepository authoredWorldSourceRepository;
  private final PublishedReleaseBundleService publishedReleaseBundleService;
  private final VersionRepository versionRepository;

  @Value("${firemud.grpc.workload-namespace:}")
  private String workloadNamespace;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Injected Spring collaborators are internal; construction acquires no resources and this service has no finalizer.")
  public CompleteLaunchBindingServiceImpl(
      LaunchDescriptorService launchDescriptorService,
      GameAuthoredWorldSourceRepository authoredWorldSourceRepository,
      PublishedReleaseBundleService publishedReleaseBundleService,
      VersionRepository versionRepository) {
    this.launchDescriptorService =
        Objects.requireNonNull(launchDescriptorService, "launchDescriptorService");
    this.authoredWorldSourceRepository =
        Objects.requireNonNull(authoredWorldSourceRepository, "authoredWorldSourceRepository");
    this.publishedReleaseBundleService =
        Objects.requireNonNull(publishedReleaseBundleService, "publishedReleaseBundleService");
    this.versionRepository = Objects.requireNonNull(versionRepository, "versionRepository");
  }

  @Override
  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      readOnly = true,
      isolation = Isolation.REPEATABLE_READ)
  @Timed(value = "gamedesign.completeLaunchBinding.read")
  public CompleteLaunchBindingDto getCompleteLaunchBinding(
      UUID readRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      String controlPlaneRequestId,
      String expectedRequestDigest,
      String expectedResultDigest) {
    ResolvedLaunchDescriptorDto resolved =
        launchDescriptorService.getLaunchDescriptorInOwnerSnapshot(
            readRequestId,
            canonicalTenantId,
            worldSlug,
            controlPlaneRequestId,
            expectedRequestDigest,
            expectedResultDigest);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        requireDescriptorMatchesSelector(
            resolved,
            readRequestId,
            canonicalTenantId,
            worldSlug,
            controlPlaneRequestId,
            expectedRequestDigest,
            expectedResultDigest);

    AuthoredWorldVersionStateSnapshot sourceSnapshot =
        authoredWorldSourceRepository
            .readVersionStateSnapshot(
                workloadNamespace,
                readRequestId,
                canonicalTenantId,
                worldSlug,
                descriptor.authoredWorldSourceOperationId(),
                descriptor.authoredWorldSourceEvidenceDigest(),
                descriptor.versionId())
            .orElseThrow(
                () ->
                    deny(
                        "AUTHORED_WORLD_SOURCE_NOT_FOUND",
                        "exact committed source or descriptor version is missing"));
    AuthoredWorldSourceEvidence source = sourceSnapshot.sourceEvidence();
    requireSourceMatchesDescriptor(source, descriptor, canonicalTenantId, worldSlug);
    if (sourceSnapshot.versionState() == null
        || sourceSnapshot.versionStateEpoch() <= 0
        || sourceSnapshot.versionStateEpoch() < descriptor.versionStateEpoch()) {
      throw deny("VERSION_STATE_INVALID", "same-snapshot Version state evidence is incomplete");
    }
    // The current snapshot state is provenance/current-state context. The immutable descriptor
    // epoch below remains the historical release binding and is not an activation decision.

    PublishedReleaseBundleDto bundle;
    try {
      bundle =
          publishedReleaseBundleService.getPublishedReleaseBundle(
              source.sourceGameTenantKey(), descriptor.versionId());
    } catch (PublishedReleaseBundleNotFoundException missing) {
      throw deny(
          "RELEASE_BUNDLE_NOT_FOUND", "the descriptor's committed release is missing", missing);
    }
    requireExactBundleBinding(bundle, source, descriptor, sourceSnapshot.canonicalVersionId());
    Version canonicalVersion = requireCanonicalVersion(bundle, source, descriptor, sourceSnapshot);
    requireFullVersion(canonicalVersion, bundle);

    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        requireParticipantEvidence(bundle, descriptor.versionId());
    String commitId = participants.getFirst().appliedCommitId();
    List<AuthoredWorldReleaseAttestationEvidence.Artifact> artifacts =
        requireManifestAndArtifactEvidence(bundle);

    AuthoredWorldReleaseAttestationEvidence releaseAttestation;
    if ("v2".equals(bundle.attestationSchemaVersion())) {
      releaseAttestation =
          AuthoredWorldReleaseAttestationEvidence.create(
              descriptor.targetNamespace(),
              descriptor.resultDigest(),
              source.canonicalTenantId(),
              canonicalVersion.getCanonicalVersionId(),
              source.worldSlug(),
              source.operationId(),
              source.evidenceDigest(),
              descriptor.launchDescriptorId(),
              descriptor.publishedReleaseBundleRef(),
              descriptor.versionStateEpoch(),
              bundle.publishWorkflowId(),
              commitId,
              participants,
              bundle.manifestHash(),
              bundle.manifestSchemaVersion(),
              bundle.requiredManifestAssetKeys(),
              artifacts,
              bundle.commandDefinitions(),
              bundle.generationConfigRevision(),
              bundle.worldPublishedStartLocationEvidence());
    } else {
      releaseAttestation =
          AuthoredWorldReleaseAttestationEvidence.create(
              descriptor.targetNamespace(),
              descriptor.resultDigest(),
              source.canonicalTenantId(),
              canonicalVersion.getCanonicalVersionId(),
              source.worldSlug(),
              source.operationId(),
              source.evidenceDigest(),
              descriptor.launchDescriptorId(),
              descriptor.publishedReleaseBundleRef(),
              descriptor.versionStateEpoch(),
              bundle.publishWorkflowId(),
              commitId,
              participants,
              bundle.manifestHash(),
              bundle.manifestSchemaVersion(),
              bundle.requiredManifestAssetKeys(),
              artifacts,
              bundle.commandDefinitions(),
              bundle.generationConfigRevision());
    }
    releaseAttestation.requireValid(descriptor);
    return new CompleteLaunchBindingDto(descriptor, releaseAttestation);
  }

  private AuthoredWorldLaunchDescriptorEvidence requireDescriptorMatchesSelector(
      ResolvedLaunchDescriptorDto resolved,
      UUID readRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      String controlPlaneRequestId,
      String expectedRequestDigest,
      String expectedResultDigest) {
    if (readRequestId == null || NIL_UUID.equals(readRequestId)) {
      throw deny("INVALID_READ_REQUEST", "readRequestId must be a canonical non-nil UUID");
    }
    if (resolved == null || resolved.authoredWorldBinding() == null) {
      throw deny("LAUNCH_DESCRIPTOR_NOT_FOUND", "the exact committed descriptor is missing");
    }
    AuthoredWorldLaunchDescriptorEvidence descriptor = resolved.authoredWorldBinding();
    try {
      descriptor.requireValid();
    } catch (RuntimeException invalid) {
      throw deny("LAUNCH_DESCRIPTOR_CONFLICT", "the exact descriptor evidence is invalid", invalid);
    }
    if (!Objects.equals(workloadNamespace, descriptor.targetNamespace())
        || !Objects.equals(canonicalTenantId, descriptor.canonicalTenantId())
        || !Objects.equals(worldSlug, descriptor.worldSlug())
        || !Objects.equals(controlPlaneRequestId, descriptor.controlPlaneRequestId())
        || !Objects.equals(expectedRequestDigest, descriptor.requestDigest())
        || !Objects.equals(expectedResultDigest, descriptor.resultDigest())
        || !Objects.equals(resolved.launchDescriptorId(), descriptor.launchDescriptorId())
        || !Objects.equals(resolved.canonicalTenantId(), descriptor.canonicalTenantId().toString())
        || !Objects.equals(resolved.controlPlaneRequestId(), descriptor.controlPlaneRequestId())
        || resolved.gameTemplateId() != descriptor.gameTemplateId()
        || resolved.versionId() != descriptor.versionId()
        || !Objects.equals(resolved.scriptPatchVersion(), descriptor.scriptPatchVersion())
        || !Objects.equals(resolved.runtimeFlagsJson(), descriptor.runtimeFlagsJson())
        || !Objects.equals(
            resolved.generationConfigRevision(), descriptor.generationConfigRevision())
        || resolved.versionStateEpoch() != descriptor.versionStateEpoch()
        || resolved.releaseBundleId() != descriptor.releaseBundleId()
        || !Objects.equals(
            resolved.publishedReleaseBundleRef(), descriptor.publishedReleaseBundleRef())
        || !Objects.equals(resolved.remapSetId(), descriptor.remapSetId())) {
      throw deny(
          "LAUNCH_DESCRIPTOR_CONFLICT",
          "the resolved descriptor does not match the exact read selector and evidence");
    }
    return descriptor;
  }

  private void requireSourceMatchesDescriptor(
      AuthoredWorldSourceEvidence source,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      UUID canonicalTenantId,
      String worldSlug) {
    if (source == null
        || !Objects.equals(workloadNamespace, source.targetNamespace())
        || !Objects.equals(canonicalTenantId, source.canonicalTenantId())
        || !Objects.equals(worldSlug, source.worldSlug())
        || !Objects.equals(descriptor.canonicalTenantId(), source.canonicalTenantId())
        || !Objects.equals(descriptor.worldSlug(), source.worldSlug())
        || !Objects.equals(descriptor.authoredWorldSourceOperationId(), source.operationId())
        || !Objects.equals(descriptor.authoredWorldSourceEvidenceDigest(), source.evidenceDigest())
        || source.sourceGameRowId() <= 0
        || source.sourceGameTenantKey() == null
        || source.sourceGameTenantKey().isBlank()
        || source.provenanceKind() == null
        || source.provenanceKind().isBlank()) {
      throw deny("AUTHORED_WORLD_SOURCE_CHANGED", "source evidence does not match the descriptor");
    }
  }

  private void requireExactBundleBinding(
      PublishedReleaseBundleDto bundle,
      AuthoredWorldSourceEvidence source,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      UUID snapshotCanonicalVersionId) {
    if (bundle == null
        || bundle.id() == null
        || bundle.id() <= 0
        || bundle.versionId() == null
        || bundle.versionId() <= 0
        || bundle.versionNumber() <= 0
        || bundle.canonicalTenantId() == null
        || bundle.canonicalVersionId() == null
        || NIL_UUID.equals(bundle.canonicalTenantId())
        || NIL_UUID.equals(bundle.canonicalVersionId())
        || snapshotCanonicalVersionId == null
        || NIL_UUID.equals(snapshotCanonicalVersionId)
        || !Objects.equals(snapshotCanonicalVersionId, bundle.canonicalVersionId())
        || !("v1".equals(bundle.attestationSchemaVersion())
                && bundle.worldPublishedStartLocationEvidence() == null
            || "v2".equals(bundle.attestationSchemaVersion())
                && bundle.worldPublishedStartLocationEvidence() != null)
        || !Objects.equals(bundle.id(), descriptor.releaseBundleId())
        || !Objects.equals(bundle.versionId(), descriptor.versionId())
        || !Objects.equals(bundle.tenantId(), source.sourceGameTenantKey())
        || !Objects.equals(bundle.canonicalTenantId(), source.canonicalTenantId())
        || !Objects.equals(bundle.generationConfigRevision(), descriptor.generationConfigRevision())
        || !Objects.equals(
            bundle.publishedReleaseBundleRef(), descriptor.publishedReleaseBundleRef())
        || bundle.publishWorkflowId() == null
        || bundle.publishWorkflowId().isBlank()) {
      throw deny(
          "RELEASE_ATTESTATION_MISMATCH",
          "release bundle identity does not match the exact descriptor and source");
    }
  }

  private Version requireCanonicalVersion(
      PublishedReleaseBundleDto bundle,
      AuthoredWorldSourceEvidence source,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      AuthoredWorldVersionStateSnapshot sourceSnapshot) {
    Version version =
        versionRepository
            .findByCanonicalTenantIdAndCanonicalVersionId(
                bundle.canonicalTenantId(), bundle.canonicalVersionId())
            .orElseThrow(
                () ->
                    deny(
                        "CANONICAL_VERSION_NOT_FOUND",
                        "release canonical Version identity has no exact persisted owner source"));
    if (!Objects.equals(version.getId(), descriptor.versionId())
        || !Objects.equals(version.getTenantId(), source.sourceGameTenantKey())
        || !Objects.equals(version.getCanonicalTenantId(), source.canonicalTenantId())
        || !Objects.equals(version.getCanonicalVersionId(), bundle.canonicalVersionId())
        || !Objects.equals(version.getCanonicalVersionId(), sourceSnapshot.canonicalVersionId())
        || !Objects.equals(version.getIdentitySourceGameRowId(), source.sourceGameRowId())
        || !Objects.equals(version.getIdentitySourceGameTenantKey(), source.sourceGameTenantKey())
        || !Objects.equals(version.getIdentitySourceProvenanceKind(), source.provenanceKind())
        || !Objects.equals(version.getVersionNumber(), bundle.versionNumber())
        || version.getVersionState() != sourceSnapshot.versionState()
        || !Objects.equals(version.getVersionStateEpoch(), sourceSnapshot.versionStateEpoch())) {
      throw deny(
          "CANONICAL_VERSION_MISMATCH",
          "persisted Version UUID and Game source provenance do not match this release");
    }
    return version;
  }

  private void requireFullVersion(Version version, PublishedReleaseBundleDto bundle) {
    if (version.isScriptOnly()
        || version.getBaseVersionId() != null
        || version.getScriptPatchVersion() != null
        || bundle.scriptOnly()
        || bundle.scriptPatchVersion() != null) {
      throw deny(
          "RELEASE_ATTESTATION_MISMATCH",
          "a script-only publication cannot attest a full-Version launch descriptor");
    }
  }

  private List<AuthoredWorldReleaseAttestationEvidence.Participant> requireParticipantEvidence(
      PublishedReleaseBundleDto bundle, long versionId) {
    int attestationSchemaVersion =
        switch (bundle.attestationSchemaVersion()) {
          case "v1" -> AuthoredWorldReleaseAttestationEvidence.SCHEMA_VERSION;
          case "v2" -> AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION;
          case null, default ->
              throw deny(
                  "PARTICIPANT_EVIDENCE_INVALID",
                  "supported release attestation schema is required");
        };
    List<PublishParticipantDigestDto> observed = bundle.participantDigests();
    List<String> requiredParticipantOrder =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder();
    if (observed == null || observed.size() != requiredParticipantOrder.size()) {
      throw deny(
          "PARTICIPANT_EVIDENCE_INCOMPLETE", "exactly five full-Version owners are required");
    }
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        new ArrayList<>(requiredParticipantOrder.size());
    String expectedScope = Long.toString(versionId);
    String appliedCommitId = null;
    for (int index = 0; index < requiredParticipantOrder.size(); index++) {
      String expectedParticipantKey = requiredParticipantOrder.get(index);
      int supportedDigestSchemaVersion =
          AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
              expectedParticipantKey, attestationSchemaVersion);
      PublishParticipantDigestDto digest = observed.get(index);
      if (digest == null
          || !expectedParticipantKey.equals(digest.participantKey())
          || !expectedScope.equals(digest.scopeValue())
          || digest.baseVersionId() != null
          || !digest.succeeded()
          || (digest.errorMessage() != null && !digest.errorMessage().isBlank())
          || !isParticipantContentDigest(digest.contentDigest())
          || digest.digestSchemaVersion() == null
          || digest.digestSchemaVersion() != supportedDigestSchemaVersion
          || digest.appliedCommitId() == null
          || digest.appliedCommitId().isBlank()) {
        throw deny(
            "PARTICIPANT_EVIDENCE_INVALID",
            "full-Version participant evidence is missing, failed, unsupported, or out of order");
      }
      if (appliedCommitId == null) {
        appliedCommitId = digest.appliedCommitId();
      } else if (!appliedCommitId.equals(digest.appliedCommitId())) {
        throw deny("PARTICIPANT_COMMIT_MISMATCH", "full-Version owners attest different commits");
      }
      boolean abilityPresent = digest.abilitySchemaDigest() != null;
      if ("GAME_LOGIC".equals(expectedParticipantKey)) {
        if (!abilityPresent || !isDigest(digest.abilitySchemaDigest())) {
          throw deny(
              "ABILITY_SCHEMA_EVIDENCE_MISSING",
              "Game Logic must provide its dedicated ability-schema digest");
        }
      } else if (abilityPresent) {
        throw deny(
            "ABILITY_SCHEMA_EVIDENCE_OWNER_MISMATCH",
            "only Game Logic may provide the ability-schema digest");
      }
      participants.add(
          new AuthoredWorldReleaseAttestationEvidence.Participant(
              digest.participantKey(),
              digest.scopeValue(),
              false,
              null,
              digest.appliedCommitId(),
              digest.contentDigest(),
              digest.digestSchemaVersion(),
              abilityPresent,
              digest.abilitySchemaDigest()));
    }
    if (appliedCommitId == null || appliedCommitId.isBlank()) {
      throw deny("PARTICIPANT_COMMIT_MISSING", "full-Version commit identity is missing");
    }
    return List.copyOf(participants);
  }

  private List<AuthoredWorldReleaseAttestationEvidence.Artifact> requireManifestAndArtifactEvidence(
      PublishedReleaseBundleDto bundle) {
    if (!isDigest(bundle.manifestHash())
        || bundle.manifestSchemaVersion() == null
        || bundle.manifestSchemaVersion() != SUPPORTED_MANIFEST_SCHEMA_VERSION
        || bundle.requiredManifestAssetKeys() == null
        || bundle.artifactDigests() == null
        || bundle.commandDefinitions() == null
        || bundle.commandDefinitions().stream().anyMatch(value -> value == null || value.isBlank())
        || bundle.generationConfigRevision() == null
        || bundle.generationConfigRevision().isBlank()) {
      throw deny(
          "MANIFEST_ARTIFACT_EVIDENCE_INCOMPLETE",
          "manifest, required-key, actual-artifact, command, or generation proof is missing");
    }
    List<String> requiredKeys = bundle.requiredManifestAssetKeys();
    for (int index = 0; index < requiredKeys.size(); index++) {
      String key = requiredKeys.get(index);
      if (key == null
          || key.isBlank()
          || (index > 0
              && PublishedArtifactDigest.compareUsageKeysUtf8(requiredKeys.get(index - 1), key)
                  >= 0)) {
        throw deny(
            "MANIFEST_ARTIFACT_EVIDENCE_INVALID",
            "required manifest asset keys must be unique and in unsigned UTF-8 order");
      }
    }
    List<PublishedArtifactDigest> observedArtifacts = bundle.artifactDigests();
    List<AuthoredWorldReleaseAttestationEvidence.Artifact> artifacts =
        new ArrayList<>(observedArtifacts.size());
    String previousUsageKey = null;
    for (PublishedArtifactDigest artifact : observedArtifacts) {
      if (artifact == null
          || artifact.artifactSchemaVersion() != SUPPORTED_ARTIFACT_SCHEMA_VERSION
          || (previousUsageKey != null
              && PublishedArtifactDigest.compareUsageKeysUtf8(previousUsageKey, artifact.usageKey())
                  >= 0)) {
        throw deny(
            "MANIFEST_ARTIFACT_EVIDENCE_INVALID",
            "artifact entries must be unique and in unsigned UTF-8 usage-key order");
      }
      previousUsageKey = artifact.usageKey();
      artifacts.add(
          new AuthoredWorldReleaseAttestationEvidence.Artifact(
              artifact.usageKey(),
              artifact.artifactKind(),
              artifact.immutableObjectKey(),
              artifact.contentDigest(),
              artifact.contentType(),
              artifact.artifactSchemaVersion()));
    }
    for (String requiredKey : requiredKeys) {
      long matches =
          observedArtifacts.stream()
              .filter(artifact -> requiredKey.equals(artifact.usageKey()))
              .count();
      if (matches != 1) {
        throw deny(
            "MANIFEST_ARTIFACT_EVIDENCE_MISMATCH",
            "each required manifest key must select exactly one actual-byte artifact proof");
      }
    }
    return List.copyOf(artifacts);
  }

  private boolean isDigest(String value) {
    return value != null && value.matches("sha256:[0-9a-f]{64}");
  }

  private boolean isParticipantContentDigest(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  private IllegalArgumentException deny(String code, String detail) {
    return new IllegalArgumentException("COMPLETE_LAUNCH_BINDING_" + code + ": " + detail);
  }

  private IllegalArgumentException deny(String code, String detail, Throwable cause) {
    return new IllegalArgumentException("COMPLETE_LAUNCH_BINDING_" + code + ": " + detail, cause);
  }
}
