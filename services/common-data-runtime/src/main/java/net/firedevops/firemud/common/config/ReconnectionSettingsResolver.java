package net.firedevops.firemud.common.config;

/** Resolves effective reconnection settings for a tenant/game binding. */
public interface ReconnectionSettingsResolver {
  FiremudReconnectionProperties resolve(long tenantId, long gameInstanceId);
}
