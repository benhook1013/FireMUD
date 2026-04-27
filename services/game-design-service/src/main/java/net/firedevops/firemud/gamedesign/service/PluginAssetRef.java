package net.firedevops.firemud.gamedesign.service;

public record PluginAssetRef(
    String assetId, String path, String contentHash, String contentType, Long sizeBytes) {}
