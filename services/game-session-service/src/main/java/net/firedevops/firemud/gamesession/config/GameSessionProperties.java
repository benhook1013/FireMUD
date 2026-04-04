package net.firedevops.firemud.gamesession.config;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configurable operating flags for the Game Session Service. */
@Data
@ConfigurationProperties(prefix = "game-session")
public class GameSessionProperties {
  /** Require authentication before processing gameplay commands. */
  private boolean requireAuthenticatedCommands = true;

  /** Tenant identifier used for initial account logins. */
  private String defaultTenantId = "demo";

  /** Public world selectors used by the current lobby flow. */
  private List<WorldOption> worlds =
      new ArrayList<>(
          List.of(
              new WorldOption("demo", "Demo World", 1L, false),
              new WorldOption("sandbox", "Builder Sandbox", 2L, true)));

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class WorldOption {
    private String slug;
    private String displayName;
    private long gameInstanceId;
    private boolean requiresCharacterSelection;
  }
}
