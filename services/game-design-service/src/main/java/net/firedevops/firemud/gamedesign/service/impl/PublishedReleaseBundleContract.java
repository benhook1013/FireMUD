package net.firedevops.firemud.gamedesign.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;

final class PublishedReleaseBundleContract {
  static final String SUPPORTED_ATTESTATION_SCHEMA_VERSION = "v1";
  static final String SELECTOR_ATTESTATION_SCHEMA_VERSION = "v2";
  static final String SELECTED_FULL_ATTESTATION_SCHEMA_VERSION = "v4";
  static final int SUPPORTED_MANIFEST_SCHEMA_VERSION = 1;
  static final int SUPPORTED_ARTIFACT_SCHEMA_VERSION = 1;
  static final String SCHEMA_VERSION_UNSUPPORTED = "SCHEMA_VERSION_UNSUPPORTED";
  static final String REPAIR_ATTESTATION_MISMATCH = "REPAIR_ATTESTATION_MISMATCH";
  static final String REPAIR_ATTESTED_ASSET_KEY_MISMATCH = "REPAIR_ATTESTED_ASSET_KEY_MISMATCH";

  private PublishedReleaseBundleContract() {}

  static void requireSelectorBinding(PublishedReleaseBundleDto bundle) {
    requireSelectedBinding(
        bundle,
        SELECTOR_ATTESTATION_SCHEMA_VERSION,
        AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION,
        "World selector");
  }

  static void requireSelectedFullBinding(PublishedReleaseBundleDto bundle) {
    requireSelectedBinding(
        bundle,
        SELECTED_FULL_ATTESTATION_SCHEMA_VERSION,
        AuthoredWorldReleaseAttestationEvidence.SELECTED_FULL_SCHEMA_VERSION,
        "Selected-full release");
  }

  private static void requireSelectedBinding(
      PublishedReleaseBundleDto bundle,
      String expectedBundleSchema,
      int evidenceSchema,
      String description) {
    var evidence =
        Objects.requireNonNull(bundle.worldPublishedStartLocationEvidence(), "World evidence");
    var request = evidence.request();
    if (!expectedBundleSchema.equals(bundle.attestationSchemaVersion())
        || bundle.scriptOnly()
        || bundle.scriptPatchVersion() != null
        || !request.canonicalTenantId().equals(bundle.canonicalTenantId())
        || !request.canonicalVersionId().equals(bundle.canonicalVersionId())
        || !request.publishWorkflowId().equals(bundle.publishWorkflowId())
        || bundle.manifestSchemaVersion() == null
        || bundle.manifestSchemaVersion() != SUPPORTED_MANIFEST_SCHEMA_VERSION
        || bundle.artifactDigests() == null
        || request.appliedCommitId() == null
        || request.appliedCommitId().isBlank()) {
      throw new IllegalArgumentException(
          description + " differs from complete immutable release binding");
    }
    new ExportedAssetManifest(
        bundle.manifestHash(),
        bundle.manifestSchemaVersion(),
        bundle.requiredManifestAssetKeys(),
        bundle.artifactDigests());
    if (bundle.artifactDigests().stream()
        .anyMatch(
            artifact -> artifact.artifactSchemaVersion() != SUPPORTED_ARTIFACT_SCHEMA_VERSION)) {
      throw new IllegalArgumentException("World selector contains an unsupported artifact schema");
    }
    var required = AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder();
    if (bundle.participantDigests().size() != required.size()) {
      throw new IllegalArgumentException(description + " release lacks required participants");
    }
    for (int index = 0; index < required.size(); index++) {
      var participant = bundle.participantDigests().get(index);
      String expectedParticipantKey = required.get(index);
      if (!expectedParticipantKey.equals(participant.participantKey())
          || !participant.succeeded()
          || (participant.errorMessage() != null && !participant.errorMessage().isBlank())
          || participant.baseVersionId() != null
          || !Long.toString(bundle.versionId()).equals(participant.scopeValue())
          || !request.appliedCommitId().equals(participant.appliedCommitId())
          || participant.contentDigest() == null
          || !participant.contentDigest().matches("[0-9a-f]{64}")
          || !Objects.equals(
              participant.digestSchemaVersion(),
              AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                  participant.participantKey(), evidenceSchema))) {
        throw new IllegalArgumentException(
            description + " requires all exact successful ordered release participants");
      }
      if ("WORLD_MANAGEMENT".equals(participant.participantKey())
          && (!request.contentDigest().equals(participant.contentDigest())
              || request.digestSchemaVersion() != participant.digestSchemaVersion())) {
        throw new IllegalArgumentException(
            description + " differs from World participant digest/schema");
      }
      if ("GAME_LOGIC".equals(participant.participantKey())
          && (participant.abilitySchemaDigest() == null
              || !participant.abilitySchemaDigest().matches("sha256:[0-9a-f]{64}"))) {
        throw new IllegalArgumentException(
            description + " release lacks Game Logic ability schema proof");
      }
      if (!"GAME_LOGIC".equals(participant.participantKey())
          && participant.abilitySchemaDigest() != null) {
        throw new IllegalArgumentException(
            description + " ability schema proof must be owned by Game Logic");
      }
    }
  }

  static void requireFreshSelectedFullParticipantVector(
      long versionId, String appliedCommitId, List<PublishParticipantDigestDto> participants) {
    if (versionId <= 0 || appliedCommitId == null || appliedCommitId.isBlank()) {
      throw new IllegalArgumentException("Selected-full participant scope is invalid");
    }
    var required = AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder();
    if (participants == null || participants.size() != required.size()) {
      throw new IllegalArgumentException(
          "Selected-full release requires all five exact participants");
    }
    String expectedScope = Long.toString(versionId);
    for (int index = 0; index < required.size(); index++) {
      var participant = participants.get(index);
      String participantKey = required.get(index);
      boolean gameLogic = "GAME_LOGIC".equals(participantKey);
      if (participant == null
          || !participantKey.equals(participant.participantKey())
          || !expectedScope.equals(participant.scopeValue())
          || participant.baseVersionId() != null
          || !appliedCommitId.equals(participant.appliedCommitId())
          || !participant.succeeded()
          || (participant.errorMessage() != null && !participant.errorMessage().isBlank())
          || participant.contentDigest() == null
          || !participant.contentDigest().matches("[0-9a-f]{64}")
          || participant.digestSchemaVersion() == null
          || participant.digestSchemaVersion()
              != AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                  participantKey,
                  AuthoredWorldReleaseAttestationEvidence.SELECTED_FULL_SCHEMA_VERSION)
          || (gameLogic
              ? participant.abilitySchemaDigest() == null
                  || !participant.abilitySchemaDigest().matches("sha256:[0-9a-f]{64}")
              : participant.abilitySchemaDigest() != null)) {
        throw new IllegalArgumentException(
            "Selected-full release requires the exact 4/3/1/6/2 successful participant vector");
      }
    }
  }

  static void requireExactSelectorRetry(
      PublishedReleaseBundleDto stored,
      VersionDto version,
      String workflow,
      ExportedAssetManifest manifest,
      String generationRevision,
      List<PublishParticipantDigestDto> participants,
      WorldPublishedStartLocationEvidence evidence) {
    if (SELECTOR_ATTESTATION_SCHEMA_VERSION.equals(stored.attestationSchemaVersion())) {
      requireSelectorBinding(stored);
    } else if (SELECTED_FULL_ATTESTATION_SCHEMA_VERSION.equals(stored.attestationSchemaVersion())) {
      requireSelectedFullBinding(stored);
    } else {
      throw new IllegalStateException(
          "IDEMPOTENCY_CONFLICT: historical release is selector-ineligible");
    }
    if (stored.versionNumber() != version.versionNumber()
        || version.scriptOnly()
        || version.scriptPatchVersion() != null
        || !Objects.equals(stored.publishWorkflowId(), workflow)
        || !Objects.equals(stored.manifestHash(), manifest.manifestHash())
        || !Objects.equals(stored.manifestSchemaVersion(), manifest.manifestSchemaVersion())
        || !Objects.equals(stored.artifactDigests(), manifest.artifactDigests())
        || !stored.requiredManifestAssetKeys().equals(manifest.requiredManifestAssetKeys())
        || !Objects.equals(stored.generationConfigRevision(), generationRevision)
        || !stored.participantDigests().equals(participants)
        || !Arrays.equals(
            stored.worldPublishedStartLocationEvidence().canonicalBytes(),
            evidence.canonicalBytes())) {
      throw new IllegalStateException(
          "IDEMPOTENCY_CONFLICT: changed immutable selector release input");
    }
  }

  static void requireSupportedSchemaForRead(PublishedReleaseBundleDto bundle) {
    if (SELECTOR_ATTESTATION_SCHEMA_VERSION.equals(bundle.attestationSchemaVersion())) {
      requireSelectorBinding(bundle);
      return;
    }
    if (SELECTED_FULL_ATTESTATION_SCHEMA_VERSION.equals(bundle.attestationSchemaVersion())) {
      requireSelectedFullBinding(bundle);
      return;
    }
    if (!SUPPORTED_ATTESTATION_SCHEMA_VERSION.equals(bundle.attestationSchemaVersion())) {
      throw new IllegalStateException(
          SCHEMA_VERSION_UNSUPPORTED
              + ": unsupported published release bundle attestation schema "
              + bundle.attestationSchemaVersion());
    }
  }

  /**
   * Publication reconciliation only; does not widen launch or repair authorization/schema support.
   */
  static void requireSupportedSchemaForPublicationRead(PublishedReleaseBundleDto bundle) {
    if (SELECTOR_ATTESTATION_SCHEMA_VERSION.equals(bundle.attestationSchemaVersion())) {
      requireSelectorBinding(bundle);
      return;
    }
    if (SELECTED_FULL_ATTESTATION_SCHEMA_VERSION.equals(bundle.attestationSchemaVersion())) {
      requireSelectedFullBinding(bundle);
      return;
    }
    requireSupportedSchemaForRead(bundle);
  }

  static void requireSupportedSchemaForLaunch(PublishedReleaseBundleDto bundle) {
    if (SELECTOR_ATTESTATION_SCHEMA_VERSION.equals(bundle.attestationSchemaVersion())) {
      requireSelectorBinding(bundle);
      return;
    }
    if (SELECTED_FULL_ATTESTATION_SCHEMA_VERSION.equals(bundle.attestationSchemaVersion())) {
      requireSelectedFullBinding(bundle);
      return;
    }
    if (!SUPPORTED_ATTESTATION_SCHEMA_VERSION.equals(bundle.attestationSchemaVersion())) {
      throw new IllegalArgumentException(
          SCHEMA_VERSION_UNSUPPORTED
              + ": unsupported published release bundle attestation schema "
              + bundle.attestationSchemaVersion());
    }
  }

  /**
   * Descriptor resolution only; descriptor/v1 remains separate from the complete release carrier.
   */
  static void requireSupportedSchemaForLaunchDescriptor(PublishedReleaseBundleDto bundle) {
    requireSupportedSchemaForLaunch(bundle);
  }

  static void requireExactRepairMatch(
      PublishedReleaseBundleDto bundle, ExportedAssetManifest exportedManifest) {
    if (PublishedReleaseBundleDto.isSelectedAttestationSchema(bundle.attestationSchemaVersion())) {
      requireSupportedSchemaForPublicationRead(bundle);
    } else {
      requireSupportedSchemaForRead(bundle);
    }
    if (!Objects.equals(bundle.manifestSchemaVersion(), exportedManifest.manifestSchemaVersion())
        || !Objects.equals(bundle.artifactDigests(), exportedManifest.artifactDigests())) {
      throw new IllegalStateException(
          REPAIR_ATTESTATION_MISMATCH
              + ": repair could not reproduce the attested artifact/schema proof");
    }
    if (!Objects.equals(bundle.manifestHash(), exportedManifest.manifestHash())) {
      throw new IllegalStateException(
          REPAIR_ATTESTATION_MISMATCH + ": repair could not reproduce the attested manifest hash");
    }
    if (!List.copyOf(bundle.requiredManifestAssetKeys())
        .equals(List.copyOf(exportedManifest.requiredManifestAssetKeys()))) {
      throw new IllegalStateException(
          REPAIR_ATTESTED_ASSET_KEY_MISMATCH
              + ": repair could not reproduce the attested manifest asset key set");
    }
  }
}
