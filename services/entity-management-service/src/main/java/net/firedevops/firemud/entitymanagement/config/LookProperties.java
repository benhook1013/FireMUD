package net.firedevops.firemud.entitymanagement.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.ReloadHint;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "firemud.look")
public class LookProperties {
  private final Map<String, LookRoom> rooms = new LinkedHashMap<>();

  public Map<String, LookRoom> getRooms() {
    return rooms;
  }

  public static class LookRoom {
    private final List<LookEntity> entities = new ArrayList<>();

    public List<LookEntity> getEntities() {
      return entities;
    }
  }

  public static class LookEntity {
    private String entityId;
    private String displayName;
    private EntityType entityType = EntityType.ENTITY_TYPE_UNSPECIFIED;
    private String role;
    private List<String> stateFlags = new ArrayList<>();
    private Integer visionPriority = 0;
    private ReloadHint reloadHint = ReloadHint.STABLE;
    private boolean visible = true;

    public String getEntityId() {
      return entityId;
    }

    public void setEntityId(String entityId) {
      this.entityId = entityId;
    }

    public String getDisplayName() {
      return displayName;
    }

    public void setDisplayName(String displayName) {
      this.displayName = displayName;
    }

    public EntityType getEntityType() {
      return entityType;
    }

    public void setEntityType(EntityType entityType) {
      this.entityType = entityType;
    }

    public String getRole() {
      return role;
    }

    public void setRole(String role) {
      this.role = role;
    }

    public List<String> getStateFlags() {
      return stateFlags;
    }

    public void setStateFlags(List<String> stateFlags) {
      this.stateFlags = stateFlags;
    }

    public Integer getVisionPriority() {
      return visionPriority;
    }

    public void setVisionPriority(Integer visionPriority) {
      this.visionPriority = visionPriority;
    }

    public ReloadHint getReloadHint() {
      return reloadHint;
    }

    public void setReloadHint(ReloadHint reloadHint) {
      this.reloadHint = reloadHint;
    }

    public boolean isVisible() {
      return visible;
    }

    public void setVisible(boolean visible) {
      this.visible = visible;
    }
  }
}
