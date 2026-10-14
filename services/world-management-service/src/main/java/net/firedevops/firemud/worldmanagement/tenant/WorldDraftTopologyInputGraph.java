package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.v1.EntityTemplateReferenceType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;

/**
 * Immutable PURE_UNVERIFIED logical input, never a stored or released World graph.
 *
 * <p>No numeric allocation, source authentication, Account authorization, APPLIED result,
 * synchronized visibility, public handler, lifecycle or runtime readiness follows from this value.
 * Its protobuf values preserve every supplied payload field without normalizing defaults.
 * Preserving free-text and localized JSON fields does not validate their semantics.
 */
public final class WorldDraftTopologyInputGraph {
  public static final String STATUS = "PURE_UNVERIFIED";

  /** A typed canonical Entity reference; existence and owner authority remain unverified. */
  public record EntityTemplateReference(
      EntityTemplateReferenceType kind, UUID tenantId, UUID versionId, UUID templateId) {
    public EntityTemplateReference {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(versionId, "versionId");
      Objects.requireNonNull(templateId, "templateId");
    }
  }

  /** One complete immutable logical mutation in its original enclosing revision position. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Generated protobuf messages are immutable value messages.")
  public record Node(
      String revisionOrder,
      UUID revisionId,
      UUID templateId,
      UUID scopeId,
      WorldDesignMutationRevision mutation,
      EntityTemplateReference entityReference) {
    public Node {
      Objects.requireNonNull(revisionOrder, "revisionOrder");
      Objects.requireNonNull(revisionId, "revisionId");
      Objects.requireNonNull(templateId, "templateId");
      Objects.requireNonNull(scopeId, "scopeId");
      Objects.requireNonNull(mutation, "mutation");
    }
  }

  private final UUID tenantId;
  private final UUID versionId;
  private final List<Node> nodes;

  WorldDraftTopologyInputGraph(UUID tenantId, UUID versionId, List<Node> nodes) {
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    this.versionId = Objects.requireNonNull(versionId, "versionId");
    this.nodes = List.copyOf(nodes);
  }

  public UUID tenantId() {
    return tenantId;
  }

  public UUID versionId() {
    return versionId;
  }

  /** All six families in original canonical revision order, including interleaved families. */
  public List<Node> nodes() {
    return nodes;
  }

  /** An immutable family view retaining original order; an empty family is not readiness proof. */
  public List<Node> family(WorldDesignAggregateType kind) {
    Objects.requireNonNull(kind, "kind");
    return nodes.stream().filter(node -> node.mutation().getAggregateType() == kind).toList();
  }
}
