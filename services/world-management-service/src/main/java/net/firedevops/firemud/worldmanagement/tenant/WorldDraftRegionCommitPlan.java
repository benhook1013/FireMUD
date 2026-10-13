package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeMutationPolicy;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;

/**
 * Pure, typed validation of the World-owned region-upsert subset of a complete Draft commit.
 *
 * <p>This validates only the supplied owner binding and World revision subset. It does not
 * authenticate the source evidence, authorize a write, consult storage, or represent an applied
 * owner result.
 */
public final class WorldDraftRegionCommitPlan {
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
  private static final JsonFormat.Parser PAYLOAD_PARSER = JsonFormat.parser();

  /**
   * One decoded World revision, retaining its position in the enclosing canonical revision list.
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Generated protobuf messages are immutable value messages.")
  public record RegionRevision(
      String revisionOrder, UUID revisionId, WorldDesignMutationRevision mutation) {
    public RegionRevision {
      Objects.requireNonNull(revisionOrder, "revisionOrder");
      Objects.requireNonNull(revisionId, "revisionId");
      Objects.requireNonNull(mutation, "mutation");
    }
  }

  private final DraftCommitBinding binding;
  private final WorldDesignPublicationFenceEvidence.OwnerBinding ownerBinding;
  private final List<RegionRevision> regionRevisions;

  private WorldDraftRegionCommitPlan(
      DraftCommitBinding binding,
      WorldDesignPublicationFenceEvidence.OwnerBinding ownerBinding,
      List<RegionRevision> regionRevisions) {
    this.binding = binding;
    this.ownerBinding = ownerBinding;
    this.regionRevisions = List.copyOf(regionRevisions);
  }

  /**
   * Validates the World-owned ordered revision subset against the exact supplied owner scope.
   *
   * <p>The {@code ownerBinding} carries caller-supplied source evidence; this method checks that
   * its canonical tenant, Version, and Game Design Version selector match the shared binding, but
   * does not authenticate that evidence.
   */
  public static WorldDraftRegionCommitPlan create(
      DraftCommitBinding binding, WorldDesignPublicationFenceEvidence.OwnerBinding ownerBinding) {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(ownerBinding, "ownerBinding");
    validateTarget(binding.target(), ownerBinding);

    List<RegionRevision> decoded = new ArrayList<>();
    List<AffectedUnit> expectedWorldUnits = new ArrayList<>();
    Set<UUID> worldRevisionIds = new HashSet<>();
    Set<String> regionIds = new HashSet<>();

    for (DraftCommitBinding.RevisionPayload revision : binding.revisions()) {
      if (revision.owner() != Owner.WORLD_MANAGEMENT) {
        continue;
      }
      if (!worldRevisionIds.add(revision.revisionId())) {
        throw invalid("World subset contains a duplicate revision identity");
      }

      WorldDesignMutationRevision mutation = parseRevision(revision.payload());
      validateRevisionIdentity(binding.commitId(), revision, mutation);
      validateSupportedRegionUpsert(mutation);

      String regionId = mutation.getAggregateId();
      parsePositiveId(regionId, "aggregate_id");
      if (!regionId.equals(mutation.getScopeId())) {
        throw invalid("REGION_SUBTREE scope_id must exactly match aggregate_id");
      }
      if (!regionIds.add(regionId)) {
        throw invalid("World subset contains duplicate region mutations");
      }

      long aggregateEpoch = validateEpoch(mutation.getExpectedDraftRevisionEpoch(), "aggregate");
      long scopeEpoch = validateEpoch(mutation.getExpectedDraftScopeRevisionEpoch(), "scope");
      expectedWorldUnits.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              "REGION",
              regionId,
              "AGGREGATE",
              regionId,
              Long.toString(aggregateEpoch)));
      expectedWorldUnits.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              "REGION",
              regionId,
              "REGION_SUBTREE",
              regionId,
              Long.toString(scopeEpoch)));
      decoded.add(new RegionRevision(revision.revisionOrder(), revision.revisionId(), mutation));
    }

    if (decoded.isEmpty()) {
      throw invalid("Draft commit has no World-owned region revisions");
    }
    requireExactWorldUnits(binding.affectedUnits(Owner.WORLD_MANAGEMENT), expectedWorldUnits);
    return new WorldDraftRegionCommitPlan(binding, ownerBinding, decoded);
  }

  /** Returns the exact immutable enclosing commit binding, including other owners' declarations. */
  public DraftCommitBinding binding() {
    return binding;
  }

  /** Returns the exact source-qualified World owner scope validated for this plan. */
  public WorldDesignPublicationFenceEvidence.OwnerBinding ownerBinding() {
    return ownerBinding;
  }

  /** Returns the decoded World region revisions in their original enclosing revision order. */
  public List<RegionRevision> regionRevisions() {
    return List.copyOf(regionRevisions);
  }

  private static void validateTarget(
      DraftCommitBinding.TargetProof target,
      WorldDesignPublicationFenceEvidence.OwnerBinding ownerBinding) {
    // Compare only the canonical identity and Game Design Version selector present in both
    // contracts. The World-local numeric key is not part of either input and must not be inferred
    // from the Game Design selector. Remaining source-qualified evidence is retained on the plan
    // and must be checked against World's retained identity and intake at its owner boundary.
    if (!target.canonicalTenantId().equals(ownerBinding.canonicalTenantId())
        || !target.canonicalVersionId().equals(ownerBinding.canonicalVersionId())
        || target.gameDesignVersionRowId() != ownerBinding.gameDesignVersionId()) {
      throw invalid("Draft commit target does not match the supplied World owner binding");
    }
  }

  private static WorldDesignMutationRevision parseRevision(String payload) {
    WorldDesignMutationRevision.Builder builder = WorldDesignMutationRevision.newBuilder();
    try {
      // Unknown JSON fields are rejected by the default parser; accepting them would permit
      // unvalidated input to disappear from the owner-specific typed interpretation.
      PAYLOAD_PARSER.merge(payload, builder);
    } catch (InvalidProtocolBufferException exception) {
      throw new IllegalArgumentException(
          "World revision payload is not valid typed JSON", exception);
    }
    return builder.build();
  }

  private static void validateRevisionIdentity(
      UUID commitId,
      DraftCommitBinding.RevisionPayload revision,
      WorldDesignMutationRevision mutation) {
    if (!commitId.toString().equals(mutation.getCommitId())) {
      throw invalid("World payload commit_id does not match the enclosing commit");
    }
    if (!revision.revisionId().toString().equals(mutation.getLogicalRevisionId())) {
      throw invalid("World payload logical_revision_id does not match the enclosing revision");
    }
  }

  private static void validateSupportedRegionUpsert(WorldDesignMutationRevision mutation) {
    if (mutation.getOperation()
            != WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT
        || mutation.getAggregateType()
            != WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION
        || mutation.getScopeType() != WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE
        || mutation.getScopeMutationPolicy()
            != WorldDesignScopeMutationPolicy.WORLD_DESIGN_SCOPE_MUTATION_POLICY_UNSPECIFIED
        || mutation.getPayloadCase() != WorldDesignMutationRevision.PayloadCase.REGION) {
      throw invalid(
          "World commit plan supports only existing REGION UPSERT with REGION_SUBTREE scope");
    }
    if (mutation.getRegion().getName().isBlank()) {
      throw invalid("REGION UPSERT requires a nonempty region name");
    }
    double spacing = mutation.getRegion().getSpacingMultiplier();
    // The existing World mutation contract treats zero as the default multiplier (1.0).
    if (!Double.isFinite(spacing) || spacing < 0.0d) {
      throw invalid("REGION UPSERT spacing_multiplier must be finite and nonnegative");
    }
  }

  private static long parsePositiveId(String value, String label) {
    if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
      throw invalid(label + " must be a canonical positive decimal ID");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(label + " is outside the supported ID range", exception);
    }
  }

  private static long validateEpoch(long epoch, String label) {
    if (epoch < 0L || epoch == Long.MAX_VALUE) {
      throw invalid("Expected Draft " + label + " epoch must be nonnegative and advanceable");
    }
    return epoch;
  }

  private static void requireExactWorldUnits(
      List<AffectedUnit> declaredUnits, List<AffectedUnit> expectedUnits) {
    if (declaredUnits.size() != expectedUnits.size()
        || !new HashSet<>(declaredUnits).equals(new HashSet<>(expectedUnits))) {
      throw invalid(
          "World affected units must exactly match each REGION aggregate and REGION_SUBTREE fence");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
