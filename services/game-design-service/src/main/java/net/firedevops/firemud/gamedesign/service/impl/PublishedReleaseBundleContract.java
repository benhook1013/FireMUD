package net.firedevops.firemud.gamedesign.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;

final class PublishedReleaseBundleContract {
  static final String SUPPORTED_ATTESTATION_SCHEMA_VERSION = "v1";
  static final String SELECTOR_ATTESTATION_SCHEMA_VERSION = "v2";
  static final String SCHEMA_VERSION_UNSUPPORTED = "SCHEMA_VERSION_UNSUPPORTED";
  static final String REPAIR_ATTESTATION_MISMATCH = "REPAIR_ATTESTATION_MISMATCH";
  static final String REPAIR_ATTESTED_ASSET_KEY_MISMATCH = "REPAIR_ATTESTED_ASSET_KEY_MISMATCH";

  private PublishedReleaseBundleContract() {}

  static void requireSelectorBinding(PublishedReleaseBundleDto bundle) {
    new ExportedAssetManifest(
        bundle.manifestHash(),
        Objects.requireNonNull(bundle.manifestSchemaVersion(), "manifestSchemaVersion"),
        bundle.requiredManifestAssetKeys(),
        Objects.requireNonNull(bundle.artifactDigests(), "artifactDigests"));
    var evidence =
        Objects.requireNonNull(bundle.worldPublishedStartLocationEvidence(), "World evidence");
    var request = evidence.request();
    if (!SELECTOR_ATTESTATION_SCHEMA_VERSION.equals(bundle.attestationSchemaVersion())
        || bundle.scriptOnly()
        || bundle.scriptPatchVersion() != null
        || !request.canonicalTenantId().equals(bundle.canonicalTenantId())
        || !request.canonicalVersionId().equals(bundle.canonicalVersionId())
        || !request.publishWorkflowId().equals(bundle.publishWorkflowId())
        || bundle.manifestSchemaVersion() == null
        || bundle.artifactDigests() == null) {
      throw new IllegalArgumentException(
          "World selector differs from complete immutable release binding");
    }
    var required = AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder();
    var seen = new HashSet<String>();
    for (var participant : bundle.participantDigests()) {
      if (!required.contains(participant.participantKey())
          || !seen.add(participant.participantKey())
          || !participant.succeeded()
          || participant.baseVersionId() != null
          || !Long.toString(bundle.versionId()).equals(participant.scopeValue())
          || !request.appliedCommitId().equals(participant.appliedCommitId())
          || participant.contentDigest() == null
          || !participant.contentDigest().matches("[0-9a-f]{64}")
          || !Objects.equals(
              participant.digestSchemaVersion(),
              AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                  participant.participantKey()))) {
        throw new IllegalArgumentException(
            "World selector requires all exact successful release participants");
      }
      if ("WORLD_MANAGEMENT".equals(participant.participantKey())
          && (!request.contentDigest().equals(participant.contentDigest())
              || request.digestSchemaVersion() != participant.digestSchemaVersion())) {
        throw new IllegalArgumentException(
            "World selector differs from World participant digest/schema");
      }
      if ("GAME_LOGIC".equals(participant.participantKey())
          && (participant.abilitySchemaDigest() == null
              || !participant.abilitySchemaDigest().matches("sha256:[0-9a-f]{64}"))) {
        throw new IllegalArgumentException(
            "World selector release lacks Game Logic ability schema proof");
      }
    }
    if (seen.size() != required.size()) {
      throw new IllegalArgumentException("World selector release lacks required participants");
    }
  }

  static void requireSupportedSchemaForRead(PublishedReleaseBundleDto bundle) {
    if (!SUPPORTED_ATTESTATION_SCHEMA_VERSION.equals(bundle.attestationSchemaVersion())) {
      throw new IllegalStateException(
          SCHEMA_VERSION_UNSUPPORTED
              + ": unsupported published release bundle attestation schema "
              + bundle.attestationSchemaVersion());
    }
  }

  static void requireSupportedSchemaForLaunch(PublishedReleaseBundleDto bundle) {
    if (!SUPPORTED_ATTESTATION_SCHEMA_VERSION.equals(bundle.attestationSchemaVersion())) {
      throw new IllegalArgumentException(
          SCHEMA_VERSION_UNSUPPORTED
              + ": unsupported published release bundle attestation schema "
              + bundle.attestationSchemaVersion());
    }
  }

  static void requireSupportedSchemaForLaunchDescriptor(PublishedReleaseBundleDto bundle) {
    if (SELECTOR_ATTESTATION_SCHEMA_VERSION.equals(bundle.attestationSchemaVersion())) {
      requireSelectorBinding(bundle);
      return;
    }
    requireSupportedSchemaForLaunch(bundle);
  }

  static void requireExactRepairMatch(
      PublishedReleaseBundleDto bundle, ExportedAssetManifest exportedManifest) {
    requireSupportedSchemaForRead(bundle);
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
