package net.firedevops.firemud.worldmanagement.tenant;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Family;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftReviewedBaseStaging.StagedDelta;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Retained component-storage bytes only, never APPLIED, synchronized truth or Account permission.
 * The snapshot is one exact current-row delta, not a replacement historical graph.
 */
final class WorldDraftExistingRegionStoredEvidence {
  static final String STATUS = "STORED_PERMISSION_UNVERIFIED";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> PROPERTIES =
      List.of(
          "name",
          "shard_id",
          "weather",
          "generation_seed",
          "generator_type",
          "generator_params",
          "spacing_multiplier");
  private final byte[] graphBytes;
  private final byte[] resultBytes;

  private WorldDraftExistingRegionStoredEvidence(StagedDelta staged, String snapshot) {
    requireInput(staged);
    var operation = staged.originalOperation();
    JsonNode graph = JSON.readTree(snapshot);
    validateSnapshot(staged, graph);
    try {
      // Preserve exact PostgreSQL BIGINT values, including values beyond IEEE-754 precision.
      // The compact retained snapshot has deterministic parser field order; RFC 8785 is used
      // only for the enclosing string/base64 result, never to round physical row integers.
      graphBytes = JSON.writeValueAsBytes(graph);
      var companion = staged.companion();
      var visibility = companion.baseVisibility();
      var fence = visibility.fence();
      var review =
          Map.of(
              "reviewedIncomingBinding", operation.binding().canonicalJson(),
              "baseReference", companion.reviewedBase().canonicalValue(),
              "retainedReviewEvidenceDigest", companion.retainedReviewEvidenceDigest(),
              "targetNamespace", visibility.request().targetNamespace(),
              "baseBinding", visibility.binding().canonicalJson(),
              "fenceRequestId", fence.requestId().toString(),
              "fenceCommitId", fence.commitId().toString(),
              "fenceInputDigest", fence.inputDigest(),
              "fenceResultVector", fence.resultVectorJson(),
              "fenceCreatedAt", fence.createdAt().toString());
      resultBytes =
          Rfc8785CanonicalJson.canonicalizeUtf8(
              JSON.writeValueAsString(
                  Map.of(
                      "schema",
                      "world-draft-existing-region-storage/v1",
                      "status",
                      STATUS,
                      "operationBytesBase64",
                      Base64.getEncoder().encodeToString(operation.canonicalBytes()),
                      "reviewedBase",
                      review,
                      "snapshotBytesBase64",
                      Base64.getEncoder().encodeToString(graphBytes),
                      "snapshotDigest",
                      WorldDraftGraphAppliedResult.digest(graphBytes))));
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException("Cannot encode exact existing REGION storage", invalid);
    }
  }

  /** Pure staging correlation is checked again, including direct package-local constructions. */
  static void requireInput(StagedDelta staged) {
    Objects.requireNonNull(staged, "staged");
    var operation = staged.originalOperation();
    var plan =
        WorldDraftExistingRegionCommitPlan.create(operation.binding(), operation.ownerBinding());
    var base = staged.historicalBase();
    require(
        plan.binding().equals(staged.delta().binding())
            && plan.ownerBinding().equals(staged.delta().ownerBinding())
            && plan.binding().equals(staged.companion().reviewedIncomingBinding())
            && operation
                .ownerBinding()
                .targetNamespace()
                .equals(staged.companion().baseVisibility().request().targetNamespace())
            && operation.binding().target().canonicalTenantId().equals(base.tenantId())
            && operation.binding().target().canonicalVersionId().equals(base.versionId())
            && base.localTenantKey() > 0
            && base.localVersionKey() > 0,
        "Existing REGION storage differs from original reviewed operation");
    staged.companion().baseVisibility().requireValid();
    var selected =
        base.family(Family.REGION).stream()
            .filter(row -> row.template().templateId().equals(plan.regionTemplateId()))
            .toList();
    require(
        selected.size() == 1 && selected.getFirst().scope().equals(selected.getFirst().template()),
        "Reviewed base lacks the exact scoped REGION template");
  }

  static WorldDraftExistingRegionStoredEvidence create(StagedDelta staged, String snapshot) {
    return new WorldDraftExistingRegionStoredEvidence(staged, snapshot);
  }

  static WorldDraftExistingRegionStoredEvidence fromStored(
      StagedDelta staged, byte[] graph, byte[] result) {
    var exact = create(staged, new String(graph, StandardCharsets.UTF_8));
    require(
        Arrays.equals(graph, exact.graphBytes) && Arrays.equals(result, exact.resultBytes),
        "Retained existing REGION bytes differ from the complete original input");
    return exact;
  }

  byte[] graphBytes() {
    return graphBytes.clone();
  }

  byte[] resultBytes() {
    return resultBytes.clone();
  }

  String status() {
    return STATUS;
  }

  private static void validateSnapshot(StagedDelta staged, JsonNode graph) {
    var plan = staged.delta();
    var operation = staged.originalOperation();
    var binding = operation.binding();
    if (graph == null || !graph.isObject() || graph.size() != 5) {
      throw new ConflictException("Complete existing REGION snapshot required");
    }
    var mapping = graph.path("mapping");
    var before = graph.path("oldRegion");
    var after = graph.path("newRegion");
    var aggregate = graph.path("aggregate");
    var scope = graph.path("scope");
    var selected =
        staged.historicalBase().family(Family.REGION).stream()
            .filter(row -> row.template().templateId().equals(plan.regionTemplateId()))
            .toList();
    require(selected.size() == 1, "Original reviewed REGION mapping unavailable");
    var original = selected.getFirst();
    long rowKey = original.privateRowKey();
    long tenant = staged.historicalBase().localTenantKey();
    long version = staged.historicalBase().localVersionKey();
    require(
        mapping.path("id").asLong() == original.mappingKey()
            && mapping.path("private_row_key").asLong() == rowKey
            && mapping.path("family").asText().equals("REGION")
            && mapping.path("template_id").asText().equals(plan.regionTemplateId().toString())
            && mapping
                .path("target_namespace")
                .asText()
                .equals(operation.ownerBinding().targetNamespace())
            && mapping
                .path("canonical_tenant_id")
                .asText()
                .equals(binding.target().canonicalTenantId().toString())
            && mapping
                .path("canonical_version_id")
                .asText()
                .equals(binding.target().canonicalVersionId().toString())
            && mapping
                .path("version_identity_operation_id")
                .asText()
                .equals(operation.ownerBinding().versionIdentityOperationId().toString())
            && (mapping.path("preparation_authority_kind").isNull()
                || mapping.path("preparation_authority_kind").asText().equals("HUMAN")),
        "Stored REGION differs from its original UUID creation mapping");
    for (var row : List.of(mapping, before, after, aggregate, scope)) {
      require(
          row.path("tenant_id").asLong() == tenant && row.path("version_id").asLong() == version,
          "Stored REGION snapshot differs from retained private owner scope");
    }
    require(
        before.isObject()
            && after.isObject()
            && before.size() == after.size()
            && before.path("id").asLong() == rowKey
            && after.path("id").asLong() == rowKey,
        "Complete exact old/new REGION rows required");
    for (var field : before.properties()) {
      if (!PROPERTIES.contains(field.getKey())) {
        require(
            field.getValue().equals(after.get(field.getKey())),
            "REGION delta changed unrelated row identity or content");
      }
    }
    var input = plan.mutation().getRegion();
    require(
        after.path("name").asText().equals(input.getName())
            && after.path("shard_id").asInt() == input.getShardId()
            && after.path("weather").asText().equals(input.getWeather())
            && after.path("generation_seed").asLong() == input.getGenerationSeed()
            && after.path("generator_type").asText().equals(input.getGeneratorType())
            && after.path("generator_params").asText().equals(input.getGeneratorParams())
            && Double.compare(
                    after.path("spacing_multiplier").asDouble(),
                    input.getSpacingMultiplier() == 0 ? 1 : input.getSpacingMultiplier())
                == 0,
        "Actual stored REGION properties differ from original typed payload");
    require(
        aggregate.path("aggregate_type").asText().equals("REGION")
            && aggregate.path("aggregate_id").asLong() == rowKey
            && aggregate.path("draft_revision_epoch").asLong()
                == plan.mutation().getExpectedDraftRevisionEpoch() + 1
            && scope.path("scope_type").asText().equals("REGION_SUBTREE")
            && scope.path("scope_id").asText().equals(plan.regionTemplateId().toString())
            && scope.path("draft_scope_revision_epoch").asLong()
                == plan.mutation().getExpectedDraftScopeRevisionEpoch() + 1,
        "Actual stored REGION result lacks complete resulting UUID epochs");
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new ConflictException(message);
  }
}
