package net.firedevops.firemud.gamesession.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Topology-facing settings seed for later scope-sensitive communication and world behavior. */
@ConfigurationProperties(prefix = "firemud.world-topology")
public record WorldTopologyProperties(ScopeModel scopeModel, boolean regionsEnabled) {
  public WorldTopologyProperties {
    scopeModel = scopeModel == null ? ScopeModel.MAP_ONLY : scopeModel;
    if (regionsEnabled && scopeModel != ScopeModel.REGION_AREA_AND_MAP) {
      scopeModel = ScopeModel.REGION_AREA_AND_MAP;
    }
    if (scopeModel == ScopeModel.REGION_AREA_AND_MAP) {
      regionsEnabled = true;
    }
  }

  public WorldTopologyProperties() {
    this(ScopeModel.MAP_ONLY, false);
  }

  public boolean areasEnabled() {
    return scopeModel != ScopeModel.MAP_ONLY;
  }

  public boolean mapEnabled() {
    return true;
  }

  public enum ScopeModel {
    MAP_ONLY,
    AREA_AND_MAP,
    REGION_AREA_AND_MAP
  }
}
