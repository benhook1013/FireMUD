package net.firedevops.firemud.gamesession.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Startup-only pointer seed data used when the persisted authority store is empty. */
@Data
@ConfigurationProperties(prefix = "firemud.gameplay.pointer-bootstrap")
public class GameplayAdmissionPointerBootstrapProperties {
  private List<PointerSeed> pointers =
      new ArrayList<>(
          List.of(
              defaultPointerSeed("demo", "Demo World", 1L, 1L, false),
              defaultPointerSeed("sandbox", "Builder Sandbox", 1L, 2L, true)));

  private static PointerSeed defaultPointerSeed(
      String worldSlug,
      String worldDisplayName,
      long tenantId,
      long gameInstanceId,
      boolean requiresCharacterSelection) {
    PointerSeed pointer = new PointerSeed();
    pointer.setWorldSlug(worldSlug);
    pointer.setWorldDisplayName(worldDisplayName);
    pointer.setRealmSlug("production");
    pointer.setRealmDisplayName("Live Realm");
    pointer.setTenantId(tenantId);
    pointer.setGameInstanceId(gameInstanceId);
    pointer.setVisible(true);
    pointer.setPublicProductionRealm(true);
    pointer.setRequiresCharacterSelection(requiresCharacterSelection);
    pointer.setStateScope(StateScope.SHARED);
    pointer.setCharacterCreationPolicy(CharacterCreationPolicy.ALLOW_NEW);
    return pointer;
  }

  public enum StateScope {
    SHARED,
    ISOLATED
  }

  public enum CharacterCreationPolicy {
    ALLOW_NEW,
    COPIED_ONLY,
    DISALLOWED
  }

  @Data
  public static class PointerSeed {
    private String worldSlug;
    private String worldDisplayName;
    private String realmSlug;
    private String realmDisplayName;
    private long tenantId;
    private long gameInstanceId;
    private boolean visible = true;
    private boolean publicProductionRealm = true;
    private boolean requiresCharacterSelection;
    private StateScope stateScope = StateScope.SHARED;
    private CharacterCreationPolicy characterCreationPolicy = CharacterCreationPolicy.ALLOW_NEW;
  }
}
