package net.firedevops.firemud.common.gameplay;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "firemud.gameplay.catalog")
public class GameplayCatalogProperties {
  private List<World> worlds =
      new ArrayList<>(
          List.of(
              defaultWorld("demo", "Demo World", 1L, 1L, false),
              defaultWorld("sandbox", "Builder Sandbox", 1L, 2L, true)));

  private static World defaultWorld(
      String slug,
      String displayName,
      long tenantId,
      long gameInstanceId,
      boolean requiresCharacterSelection) {
    World world = new World();
    world.setSlug(slug);
    world.setDisplayName(displayName);
    world.setRealms(
        new ArrayList<>(
            List.of(
                defaultRealm(
                    "production",
                    "Live Realm",
                    tenantId,
                    gameInstanceId,
                    requiresCharacterSelection))));
    return world;
  }

  private static Realm defaultRealm(
      String slug,
      String displayName,
      long tenantId,
      long gameInstanceId,
      boolean requiresCharacterSelection) {
    Realm realm = new Realm();
    realm.setSlug(slug);
    realm.setDisplayName(displayName);
    realm.setTenantId(tenantId);
    realm.setGameInstanceId(gameInstanceId);
    realm.setPointerVersion(1L);
    realm.setVisible(true);
    realm.setRequiresCharacterSelection(requiresCharacterSelection);
    realm.setStateScope(RealmStateScope.SHARED);
    realm.setCharacterCreationPolicy(CharacterCreationPolicy.ALLOW_NEW);
    return realm;
  }

  public enum RealmStateScope {
    SHARED,
    ISOLATED
  }

  public enum CharacterCreationPolicy {
    ALLOW_NEW,
    COPIED_ONLY,
    DISALLOWED
  }

  @Data
  public static class World {
    private String slug;
    private String displayName;
    private List<Realm> realms = new ArrayList<>();
  }

  @Data
  public static class Realm {
    private String slug;
    private String displayName;
    private long tenantId;
    private long gameInstanceId;
    private long pointerVersion = 1L;
    private boolean visible = true;
    private boolean requiresCharacterSelection;
    private RealmStateScope stateScope = RealmStateScope.SHARED;
    private CharacterCreationPolicy characterCreationPolicy = CharacterCreationPolicy.ALLOW_NEW;
  }
}
