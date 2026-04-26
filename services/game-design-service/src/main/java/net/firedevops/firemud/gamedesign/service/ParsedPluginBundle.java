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
    Map<String, byte[]> files) {}
