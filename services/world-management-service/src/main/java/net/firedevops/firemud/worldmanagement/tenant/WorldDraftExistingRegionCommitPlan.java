package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeMutationPolicy;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure input validation for one existing REGION property mutation. This is not a storage lookup,
 * permission, APPLIED result, or callable update route. World must resolve the full scoped UUID
 * against retained authored-Version/source lineage and protect the original COMMIT_ORDER at commit.
 * The fresh topology writer cannot consume this plan. Numeric selectors are never accepted.
 */
public final class WorldDraftExistingRegionCommitPlan {
  private static final JsonMapper STRICT_JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final DraftCommitBinding binding;
  private final OwnerBinding ownerBinding;
  private final UUID regionTemplateId;
  private final WorldDesignMutationRevision mutation;

  private WorldDraftExistingRegionCommitPlan(
      DraftCommitBinding binding,
      OwnerBinding ownerBinding,
      UUID regionTemplateId,
      WorldDesignMutationRevision mutation) {
    this.binding = binding;
    this.ownerBinding = ownerBinding;
    this.regionTemplateId = regionTemplateId;
    this.mutation = mutation;
  }

  public static WorldDraftExistingRegionCommitPlan create(
      DraftCommitBinding binding, OwnerBinding ownerBinding) {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(ownerBinding, "ownerBinding");
    var target = binding.target();
    require(
        target.canonicalTenantId().equals(ownerBinding.canonicalTenantId())
            && target.canonicalVersionId().equals(ownerBinding.canonicalVersionId())
            && target.gameDesignVersionRowId() == ownerBinding.gameDesignVersionId()
            && "NEW_GAME_ROW".equals(target.sourceProvenanceKind()),
        "Existing REGION input differs from its retained authored target/source");
    var revisions =
        binding.revisions().stream()
            .filter(revision -> revision.owner() == Owner.WORLD_MANAGEMENT)
            .toList();
    require(revisions.size() == 1, "Exactly one existing REGION revision is supported");
    var revision = revisions.getFirst();
    var mutation = parse(revision.payload());
    require(
        binding.commitId().toString().equals(mutation.getCommitId())
            && revision.revisionId().toString().equals(mutation.getLogicalRevisionId()),
        "REGION payload differs from its enclosing commit/revision identity");
    require(
        mutation.getAggregateType() == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION
            && mutation.getScopeType()
                == WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE
            && mutation.getOperation()
                == WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT
            && mutation.getScopeMutationPolicy()
                == WorldDesignScopeMutationPolicy.WORLD_DESIGN_SCOPE_MUTATION_POLICY_UNSPECIFIED
            && mutation.hasRegion()
            && !mutation.hasFreshGraphDeclaration(),
        "Only an existing REGION property UPSERT without fresh/replacement declaration is supported");
    UUID region = canonicalUuid(mutation.getAggregateId());
    require(
        region.equals(canonicalUuid(mutation.getScopeId())),
        "REGION_SUBTREE must select the same canonical REGION UUID");
    require(
        mutation.getExpectedDraftRevisionEpoch() >= 0
            && mutation.getExpectedDraftRevisionEpoch() < Long.MAX_VALUE
            && mutation.getExpectedDraftScopeRevisionEpoch() >= 0
            && mutation.getExpectedDraftScopeRevisionEpoch() < Long.MAX_VALUE,
        "REGION epochs must be nonnegative and incrementable");
    var regionInput = mutation.getRegion();
    require(
        !regionInput.getName().isBlank()
            && regionInput.getName().codePointCount(0, regionInput.getName().length()) <= 100
            && regionInput.getWeather().codePointCount(0, regionInput.getWeather().length()) <= 50
            && regionInput
                    .getGeneratorType()
                    .codePointCount(0, regionInput.getGeneratorType().length())
                <= 50
            && regionInput.getShardId() >= 0
            && Double.isFinite(regionInput.getSpacingMultiplier())
            && regionInput.getSpacingMultiplier() >= 0,
        "REGION properties exceed storage constraints");
    var expected =
        List.of(
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "REGION",
                region.toString(),
                "AGGREGATE",
                region.toString(),
                Long.toString(mutation.getExpectedDraftRevisionEpoch())),
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "REGION",
                region.toString(),
                "REGION_SUBTREE",
                region.toString(),
                Long.toString(mutation.getExpectedDraftScopeRevisionEpoch())));
    var actual = binding.affectedUnits(Owner.WORLD_MANAGEMENT);
    require(
        actual.size() == expected.size() && new HashSet<>(actual).equals(new HashSet<>(expected)),
        "Existing REGION input requires the exact complete aggregate/subtree epoch vector");
    return new WorldDraftExistingRegionCommitPlan(binding, ownerBinding, region, mutation);
  }

  public DraftCommitBinding binding() {
    return binding;
  }

  public OwnerBinding ownerBinding() {
    return ownerBinding;
  }

  public UUID regionTemplateId() {
    return regionTemplateId;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Built protobuf messages are immutable; mutable builders are never retained.")
  public WorldDesignMutationRevision mutation() {
    return mutation;
  }

  private static WorldDesignMutationRevision parse(String payload) {
    try {
      var tree = STRICT_JSON.readTree(payload);
      require(tree != null && tree.isObject(), "REGION payload must be a strict JSON object");
      var builder = WorldDesignMutationRevision.newBuilder();
      JsonFormat.parser().merge(payload, builder);
      return builder.build();
    } catch (InvalidProtocolBufferException | tools.jackson.core.JacksonException invalid) {
      throw new IllegalArgumentException("Invalid complete REGION payload", invalid);
    }
  }

  private static UUID canonicalUuid(String value) {
    UUID parsed = UUID.fromString(value);
    require(
        parsed.toString().equals(value) && !parsed.equals(new UUID(0, 0)),
        "Canonical nonzero UUID required; private numeric aliases are unsupported");
    return parsed;
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new IllegalArgumentException(message);
  }
}
