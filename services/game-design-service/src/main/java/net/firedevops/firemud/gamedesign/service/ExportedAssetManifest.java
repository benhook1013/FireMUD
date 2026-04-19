package net.firedevops.firemud.gamedesign.service;

import java.util.List;

public record ExportedAssetManifest(String manifestHash, List<String> requiredManifestAssetKeys) {}
