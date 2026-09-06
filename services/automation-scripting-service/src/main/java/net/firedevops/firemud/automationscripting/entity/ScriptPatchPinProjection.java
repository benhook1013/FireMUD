package net.firedevops.firemud.automationscripting.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class ScriptPatchPinProjection {
  private Long id;
  private String tenantId;
  private String gameInstanceId;
  private String observedPinnedScriptPatchVersion = "";
  private String playableStateScope = "";
  private String worldSlug = "";
  private String realmSlug = "";
  private String pointerVersion = "";

  /** Null is the canonical absent/unpinned projection value; zero is legacy-only. */
  private Long scriptPinEpoch;

  private String runtimeRegionId = "";
  private long runtimeRegionEpoch;
  private String lastObservedControlPlaneRequestId = "";
  private Instant observedAt = Instant.EPOCH;
  private Instant projectionRefreshedAt = Instant.EPOCH;
  private int rowVersion;
}
