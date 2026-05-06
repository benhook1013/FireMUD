package net.firedevops.firemud.gamedesign.service;

import java.util.List;

public record ExportedAssetManifest(String manifestHash, List<String> requiredManifestAssetKeys) {
  public ExportedAssetManifest {
    requiredManifestAssetKeys =
        requiredManifestAssetKeys == null ? List.of() : List.copyOf(requiredManifestAssetKeys);
  }

  @Override
  public List<String> requiredManifestAssetKeys() {
    return requiredManifestAssetKeys;
  }
}
