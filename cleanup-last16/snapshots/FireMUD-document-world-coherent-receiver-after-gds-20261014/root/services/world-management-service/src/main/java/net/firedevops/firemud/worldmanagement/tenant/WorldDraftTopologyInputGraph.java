package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.v1.EntityTemplateReferenceType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldInboundSourceFamily;

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
  public static final List<WorldInboundSourceFamily> INBOUND_SOURCE_FAMILY_ORDER =
      List.of(
          WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT,
          WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT,
          WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION,
          WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING,
          WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK,
          WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE,
          WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING);

  /** Validates the only digest-supported inbound subset against the source-authored declaration. */
  public static void requireEmptyInboundSourceClosure(InboundSourceClosureDeclaration declaration) {
    Objects.requireNonNull(declaration, "declaration");
    if (declaration.schemaVersion() != 1
        || declaration.familyCounts().size() != INBOUND_SOURCE_FAMILY_ORDER.size()) {
      throw new IllegalArgumentException(
          "World inbound source closure requires the complete supported version-1 family vector");
    }
    for (int index = 0; index < INBOUND_SOURCE_FAMILY_ORDER.size(); index++) {
      InboundSourceFamilyCount count = declaration.familyCounts().get(index);
      if (count.family() != INBOUND_SOURCE_FAMILY_ORDER.get(index) || count.count() != 0) {
        throw new IllegalArgumentException(
            "World inbound source closure is noncanonical or outside the supported empty-only subset");
      }
    }
  }

  /** One explicit member of the complete six-family declaration; zero is meaningful. */
  public record FamilyCount(WorldDesignAggregateType family, int count) {
    public FamilyCount {
      Objects.requireNonNull(family, "family");
      if (count < 0) {
        throw new IllegalArgumentException("Fresh World family count must be nonnegative");
      }
    }
  }

  /** One explicitly named selected-inbound source family; only zero counts are currently closed. */
  public record InboundSourceFamilyCount(WorldInboundSourceFamily family, int count) {
    public InboundSourceFamilyCount {
      Objects.requireNonNull(family, "family");
      if (count < 0) {
        throw new IllegalArgumentException("World inbound source count must be nonnegative");
      }
    }
  }

  /** Versioned family enumeration copied from the exact original World revision payload. */
  public record InboundSourceClosureDeclaration(
      int schemaVersion, List<InboundSourceFamilyCount> familyCounts) {
    public InboundSourceClosureDeclaration {
      if (schemaVersion <= 0) {
        throw new IllegalArgumentException("World inbound source schema version must be positive");
      }
      familyCounts = List.copyOf(Objects.requireNonNull(familyCounts, "familyCounts"));
    }
  }

  /** Typed start selector and source declarations from the original Account-bound input. */
  public record FreshGraphDeclaration(
      UUID tenantId,
      UUID versionId,
      RoomTemplateRef startLocation,
      List<FamilyCount> familyCounts,
      Optional<InboundSourceClosureDeclaration> inboundSourceClosure) {
    public FreshGraphDeclaration {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(versionId, "versionId");
      Objects.requireNonNull(startLocation, "startLocation");
      familyCounts = List.copyOf(familyCounts);
      inboundSourceClosure = Objects.requireNonNull(inboundSourceClosure, "inboundSourceClosure");
    }

    /**
     * Retains exact pre-closure source values without treating their omission as an empty value.
     */
    public FreshGraphDeclaration(
        UUID tenantId,
        UUID versionId,
        RoomTemplateRef startLocation,
        List<FamilyCount> familyCounts) {
      this(tenantId, versionId, startLocation, familyCounts, Optional.empty());
    }
  }

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
  private final FreshGraphDeclaration freshGraphDeclaration;

  WorldDraftTopologyInputGraph(
      UUID tenantId,
      UUID versionId,
      List<Node> nodes,
      FreshGraphDeclaration freshGraphDeclaration) {
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    this.versionId = Objects.requireNonNull(versionId, "versionId");
    this.nodes = List.copyOf(nodes);
    this.freshGraphDeclaration = freshGraphDeclaration;
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

  /** Absent only for immutable pre-declaration owner history; new APPLIED writes require it. */
  public java.util.Optional<FreshGraphDeclaration> freshGraphDeclaration() {
    return java.util.Optional.ofNullable(freshGraphDeclaration);
  }
}
