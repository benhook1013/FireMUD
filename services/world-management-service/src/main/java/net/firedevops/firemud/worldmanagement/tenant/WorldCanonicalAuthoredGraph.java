package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph.EntityTemplateReference;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph.Node;

/** Owner-private, validated graph/2 storage view, never synchronized or released content. */
public final class WorldCanonicalAuthoredGraph {
  public enum Family {
    REGION,
    ZONE,
    ROOM,
    ROOM_EXIT,
    GENERATION_RULE,
    WORLD_ENTITY_SPAWN_BINDING
  }

  /** Family is part of identity: equal UUIDs in different families are distinct templates. */
  public record Template(Family family, UUID templateId) {
    public Template {
      Objects.requireNonNull(family, "family");
      Objects.requireNonNull(templateId, "templateId");
      if (templateId.equals(new UUID(0, 0))) {
        throw new IllegalArgumentException("Canonical template ID must be non-nil");
      }
    }
  }

  /** Private World selectors never become Entity selectors or public template identifiers. */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Protobuf messages are immutable.")
  public record Row(
      Template template,
      long mappingKey,
      long privateRowKey,
      Node authored,
      WorldDesignMutationRevision content) {
    public Row {
      Objects.requireNonNull(template, "template");
      Objects.requireNonNull(authored, "authored");
      Objects.requireNonNull(content, "content");
      if (mappingKey <= 0 || privateRowKey <= 0) {
        throw new IllegalArgumentException("World private keys must be positive");
      }
    }

    public Template scope() {
      boolean region = authored.mutation().getScopeType().name().endsWith("REGION_SUBTREE");
      return new Template(region ? Family.REGION : Family.ZONE, authored.scopeId());
    }

    public EntityTemplateReference entityReference() {
      return authored.entityReference();
    }
  }

  private final UUID tenantId;
  private final UUID versionId;
  private final long localTenantKey;
  private final long localVersionKey;
  private final List<Row> rows;

  WorldCanonicalAuthoredGraph(
      UUID tenantId, UUID versionId, long localTenantKey, long localVersionKey, List<Row> rows) {
    this.tenantId = tenantId;
    this.versionId = versionId;
    this.localTenantKey = localTenantKey;
    this.localVersionKey = localVersionKey;
    this.rows = List.copyOf(rows);
  }

  public UUID tenantId() {
    return tenantId;
  }

  public UUID versionId() {
    return versionId;
  }

  public long localTenantKey() {
    return localTenantKey;
  }

  public long localVersionKey() {
    return localVersionKey;
  }

  public List<Row> rows() {
    return rows;
  }

  public List<Row> family(Family family) {
    return rows.stream().filter(row -> row.template().family() == family).toList();
  }
}
