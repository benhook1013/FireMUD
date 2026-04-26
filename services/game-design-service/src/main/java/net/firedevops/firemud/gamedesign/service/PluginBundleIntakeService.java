package net.firedevops.firemud.gamedesign.service;

public interface PluginBundleIntakeService {
  ParsedPluginBundle parseAndVerify(byte[] bundleBytes);
}
