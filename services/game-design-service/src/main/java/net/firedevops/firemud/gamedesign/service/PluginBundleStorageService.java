package net.firedevops.firemud.gamedesign.service;

public interface PluginBundleStorageService {
  void storePluginBundle(
      String tenantId, String pluginId, String pluginVersionId, byte[] bundleBytes);

  byte[] loadPluginBundle(String tenantId, String pluginId, String pluginVersionId);

  PluginDistributionManifest exportPluginAssets(
      String tenantId, ParsedPluginBundle bundle, String signerKeyId, String bundleDigest);
}
