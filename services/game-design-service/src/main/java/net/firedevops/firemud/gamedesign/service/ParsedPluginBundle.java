package net.firedevops.firemud.gamedesign.service;

import java.util.List;
import java.util.Map;

public record ParsedPluginBundle(
    String pluginId,
    String pluginVersionId,
    long baseVersionId,
    String abilitySchemaDigest,
    String bundleDigest,
    int manifestSchemaVersion,
    String signerKeyId,
    List<PluginAssetRef> assetRefs,
    Map<String, byte[]> files) {
  public ParsedPluginBundle {
    assetRefs = assetRefs == null ? List.of() : List.copyOf(assetRefs);
    files =
        files == null
            ? Map.of()
            : files.entrySet().stream()
                .collect(
                    java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue() == null ? null : entry.getValue().clone()));
  }

  @Override
  public List<PluginAssetRef> assetRefs() {
    return assetRefs;
  }

  @Override
  public Map<String, byte[]> files() {
    return files.entrySet().stream()
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> entry.getValue() == null ? null : entry.getValue().clone()));
  }
}
