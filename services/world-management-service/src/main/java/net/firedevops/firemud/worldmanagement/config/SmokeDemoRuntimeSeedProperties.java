package net.firedevops.firemud.worldmanagement.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime-target seed list used by the smoke/demo seeder when explicitly enabled. */
@Data
@ConfigurationProperties(prefix = "firemud.smoke.seed-demo-runtime")
public class SmokeDemoRuntimeSeedProperties {
  private List<RuntimeTargetSeed> targets =
      new ArrayList<>(List.of(defaultTarget(1L, 1L), defaultTarget(1L, 2L)));

  private static RuntimeTargetSeed defaultTarget(long tenantId, long gameInstanceId) {
    RuntimeTargetSeed target = new RuntimeTargetSeed();
    target.setTenantId(tenantId);
    target.setGameInstanceId(gameInstanceId);
    return target;
  }

  @Data
  public static class RuntimeTargetSeed {
    private long tenantId;
    private long gameInstanceId;
  }
}
