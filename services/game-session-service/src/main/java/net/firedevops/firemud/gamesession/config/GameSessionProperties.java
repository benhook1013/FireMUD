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
  public static class WorldOption {
    private String slug;
    private String displayName;
    private List<RealmOption> realms = new ArrayList<>();

    public WorldOption(
        String slug, String displayName, long gameInstanceId, boolean requiresCharacterSelection) {
      this.slug = slug;
      this.displayName = displayName;
      this.realms =
          new ArrayList<>(
              List.of(
                  new RealmOption(
                      "production",
                      "Live Realm",
                      gameInstanceId,
                      true,
                      requiresCharacterSelection)));
    }

    public WorldOption(String slug, String displayName, List<RealmOption> realms) {
      this.slug = slug;
      this.displayName = displayName;
      this.realms = new ArrayList<>(realms == null ? List.of() : realms);
    }

    public long getGameInstanceId() {
      return defaultRealm().getGameInstanceId();
    }

    public boolean isRequiresCharacterSelection() {
      return defaultRealm().isRequiresCharacterSelection();
    }

    public RealmOption defaultRealm() {
      if (realms == null || realms.isEmpty()) {
        return new RealmOption("production", "Live Realm", 0L, true, false);
      }
      return realms.stream()
          .filter(realm -> realm != null && "production".equalsIgnoreCase(realm.getSlug()))
          .findFirst()
          .orElse(realms.get(0));
    }
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class RealmOption {
    private String slug;
    private String displayName;
    private long gameInstanceId;
    private boolean visible = true;
    private boolean requiresCharacterSelection;
  }
}
