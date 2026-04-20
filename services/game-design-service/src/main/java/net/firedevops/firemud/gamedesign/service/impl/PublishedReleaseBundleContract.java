package net.firedevops.firemud.gamedesign.service.impl;

import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;

final class PublishedReleaseBundleContract {
  static final String SUPPORTED_ATTESTATION_SCHEMA_VERSION = "v1";
  static final String SCHEMA_VERSION_UNSUPPORTED = "SCHEMA_VERSION_UNSUPPORTED";
  static final String REPAIR_ATTESTATION_MISMATCH = "REPAIR_ATTESTATION_MISMATCH";
  static final String REPAIR_ATTESTED_ASSET_KEY_MISMATCH = "REPAIR_ATTESTED_ASSET_KEY_MISMATCH";

  private PublishedReleaseBundleContract() {}

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

  static void requireExactRepairMatch(
      PublishedReleaseBundleDto bundle, ExportedAssetManifest exportedManifest) {
    requireSupportedSchemaForRead(bundle);
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
