package net.firedevops.firemud.worldmanagement.tenant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftRegionCommitPlan.RegionRevision;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;

/** Pure staging transform for an already supplied and validated World REGION commit plan. */
public final class WorldDraftRegionGraphStager {
  private static final Set<String> CAPTURED_REGION_FIELDS =
      Set.of(
          "id",
          "shardId",
          "name",
          "weather",
          "generationSeed",
          "generatorType",
          "generatorParams",
          "spacingMultiplier");

  /**
   * Immutable in-memory content only; this is not APPLIED proof, commit authorization, synchronized
   * Draft visibility, or release-eligible content.
   */
  public record UnverifiedStagedContent(
      DraftCommitBinding binding, OwnerBinding ownerBinding, WorldAuthoredGraph graph) {
    public UnverifiedStagedContent {
      Objects.requireNonNull(binding, "binding");
      Objects.requireNonNull(ownerBinding, "ownerBinding");
      Objects.requireNonNull(graph, "graph");
    }
  }

  /** Applies all supported plan revisions to a copy of the supplied graph or returns no result. */
  public UnverifiedStagedContent stage(
      WorldDraftRegionCommitPlan plan, WorldAuthoredGraph priorGraph) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(priorGraph, "priorGraph");

    List<Map<String, Object>> regions = new ArrayList<>(priorGraph.regions());
    Map<Long, Integer> regionIndexes = indexRegions(regions);
    Set<Long> updatedRegionIds = new HashSet<>();

    for (RegionRevision revision : plan.regionRevisions()) {
      WorldDesignMutationRevision mutation = revision.mutation();
      long regionId = parseRegionId(mutation.getAggregateId());
      if (!updatedRegionIds.add(regionId)) {
        throw invalid("World region plan contains a duplicate staging target");
      }
      Integer index = regionIndexes.get(regionId);
      if (index == null) {
        throw invalid("World region staging target does not exist in the supplied graph");
      }

      Map<String, Object> priorRegion = regions.get(index);
      validateExistingRegion(priorRegion, regionId);
      RegionDesignMutation payload = mutation.getRegion();
      validatePayload(payload);
      LinkedHashMap<String, Object> updatedRegion = new LinkedHashMap<>(priorRegion);
      updatedRegion.put("shardId", payload.getShardId());
      updatedRegion.put("name", payload.getName());
      updatedRegion.put("weather", blankToNull(payload.getWeather()));
      updatedRegion.put("generationSeed", payload.getGenerationSeed());
      updatedRegion.put("generatorType", blankToNull(payload.getGeneratorType()));
      updatedRegion.put("generatorParams", blankToNull(payload.getGeneratorParams()));
      updatedRegion.put(
          "spacingMultiplier",
          payload.getSpacingMultiplier() == 0.0d ? 1.0d : payload.getSpacingMultiplier());
      regions.set(index, updatedRegion);
    }

    WorldAuthoredGraph stagedGraph = priorGraph.withRegions(regions);
    return new UnverifiedStagedContent(plan.binding(), plan.ownerBinding(), stagedGraph);
  }

  private static Map<Long, Integer> indexRegions(List<Map<String, Object>> regions) {
    Map<Long, Integer> indexes = new HashMap<>();
    for (int index = 0; index < regions.size(); index++) {
      Object rawId = regions.get(index).get("id");
      if (!(rawId instanceof Long id) || id <= 0L) {
        throw invalid("Supplied World graph contains a missing or inconsistent region ID");
      }
      if (indexes.putIfAbsent(id, index) != null) {
        throw invalid("Supplied World graph contains duplicate region IDs");
      }
    }
    return indexes;
  }

  private static void validateExistingRegion(Map<String, Object> region, long expectedId) {
    if (!region.keySet().equals(CAPTURED_REGION_FIELDS)
        || !Objects.equals(region.get("id"), expectedId)
        || !(region.get("shardId") instanceof Integer shardId)
        || shardId < 0
        || !(region.get("name") instanceof String name)
        || name.isBlank()
        || !nullableString(region.get("weather"))
        || !(region.get("generationSeed") instanceof Long)
        || !nullableString(region.get("generatorType"))
        || !nullableString(region.get("generatorParams"))
        || !(region.get("spacingMultiplier") instanceof Double spacingMultiplier)
        || !Double.isFinite(spacingMultiplier)
        || spacingMultiplier <= 0.0d) {
      throw invalid("Supplied World graph contains an inconsistent existing region target");
    }
  }

  private static void validatePayload(RegionDesignMutation payload) {
    if (payload.getName().isBlank()) {
      throw invalid("World region staging requires a nonempty region name");
    }
    if (payload.getShardId() < 0) {
      throw invalid("World region staging does not support a negative shard ID");
    }
    double spacingMultiplier = payload.getSpacingMultiplier();
    if (!Double.isFinite(spacingMultiplier) || spacingMultiplier < 0.0d) {
      throw invalid("World region staging requires finite nonnegative spacing");
    }
  }

  private static long parseRegionId(String value) {
    try {
      long id = Long.parseLong(value);
      if (id <= 0L || !Long.toString(id).equals(value)) {
        throw new NumberFormatException("not a canonical positive region ID");
      }
      return id;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("World region staging target ID is invalid", exception);
    }
  }

  private static boolean nullableString(Object value) {
    return value == null || value instanceof String;
  }

  private static String blankToNull(String value) {
    return value.isBlank() ? null : value;
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
