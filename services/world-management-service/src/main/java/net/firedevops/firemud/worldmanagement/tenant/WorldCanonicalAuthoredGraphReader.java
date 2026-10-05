package net.firedevops.firemud.worldmanagement.tenant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Family;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Row;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Template;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Closed graph/2 decoder. Retained numeric graph/1 remains a separate explicit reader. */
public final class WorldCanonicalAuthoredGraphReader {
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final Set<String> MAPPING_FIELDS =
      Set.of(
          "id",
          "target_namespace",
          "canonical_tenant_id",
          "canonical_version_id",
          "family",
          "template_id",
          "private_row_key",
          "tenant_id",
          "version_id",
          "version_identity_operation_id",
          "request_id",
          "commit_id",
          "revision_id",
          "revision_order");

  /** Complete original input supplies typed hierarchy/scope/Entity closure, never authority. */
  public WorldCanonicalAuthoredGraph read(WorldDraftTopologyCommitPlan plan, byte[] bytes) {
    JsonNode root = JSON.readTree(bytes);
    fields(root, Set.of("schemaVersion", "canonicalTenantId", "canonicalVersionId", "rows"));
    text(root, "schemaVersion", "2");
    text(root, "canonicalTenantId", plan.graph().tenantId().toString());
    text(root, "canonicalVersionId", plan.graph().versionId().toString());
    JsonNode rows = root.get("rows");
    if (!rows.isArray() || rows.size() != plan.graph().nodes().size()) {
      throw invalid("Missing or extra canonical graph rows");
    }
    Map<String, Long> keys = new HashMap<>();
    Set<String> privateKeys = new HashSet<>();
    Set<Long> mappingKeys = new HashSet<>();
    List<Row> decoded = new ArrayList<>();
    long tenant = 0;
    long version = 0;
    for (int i = 0; i < rows.size(); i++) {
      JsonNode item = rows.get(i);
      fields(item, Set.of("mapping", "content"));
      JsonNode mapping = item.get("mapping");
      fields(mapping, MAPPING_FIELDS);
      var node = plan.graph().nodes().get(i);
      String family = WorldDraftTopologyCommitRepository.family(node);
      var owner = plan.ownerBinding();
      text(mapping, "target_namespace", owner.targetNamespace());
      text(mapping, "canonical_tenant_id", plan.graph().tenantId().toString());
      text(mapping, "canonical_version_id", plan.graph().versionId().toString());
      text(mapping, "family", family);
      text(mapping, "template_id", node.templateId().toString());
      text(mapping, "version_identity_operation_id", owner.versionIdentityOperationId().toString());
      text(mapping, "request_id", plan.binding().requestId().toString());
      text(mapping, "commit_id", plan.binding().commitId().toString());
      text(mapping, "revision_id", node.revisionId().toString());
      text(mapping, "revision_order", node.revisionOrder());
      long localTenant = positive(mapping, "tenant_id");
      long localVersion = positive(mapping, "version_id");
      if (i == 0) {
        tenant = localTenant;
        version = localVersion;
      }
      if (tenant != localTenant || version != localVersion) {
        throw invalid("Canonical graph crosses private tenant or Version scope");
      }
      long key = positive(mapping, "private_row_key");
      long mappingKey = positive(mapping, "id");
      if (keys.put(family + ":" + node.templateId(), key) != null
          || !privateKeys.add(family + ":" + key)
          || !mappingKeys.add(mappingKey)) {
        throw invalid("Canonical graph mapping is not one-to-one within its typed family");
      }
      decoded.add(
          new Row(
              new Template(Family.valueOf(family), node.templateId()),
              mappingKey,
              key,
              node,
              node.mutation()));
    }
    for (int i = 0; i < rows.size(); i++) {
      JsonNode actual = rows.get(i).get("content");
      JsonNode expected =
          JSON.valueToTree(
              WorldDraftTopologyCommitRepository.expectedContent(
                  plan.graph().nodes().get(i), keys, tenant, version));
      if (!actual.isObject() || !WorldDraftTopologyCommitRepository.sameContent(actual, expected)) {
        throw invalid("Canonical graph content differs from exact typed original payload");
      }
      if (expected.properties().stream()
          .anyMatch(
              entry ->
                  entry.getValue().isIntegralNumber()
                      && !actual.get(entry.getKey()).isIntegralNumber())) {
        throw invalid(
            "Canonical graph private selectors and integer content require integer representation");
      }
      Row original = decoded.get(i);
      var content = original.authored().mutation().toBuilder();
      if (content.hasRegion()) {
        content.setRegion(
            content.getRegion().toBuilder()
                .setSpacingMultiplier(actual.get("spacing_multiplier").doubleValue()));
      } else if (content.hasRoomExit()) {
        content.setRoomExit(
            content.getRoomExit().toBuilder().setCost(actual.get("cost").intValue()));
      } else if (content.hasWorldEntitySpawnBinding()) {
        content.setWorldEntitySpawnBinding(
            content.getWorldEntitySpawnBinding().toBuilder()
                .setSpawnCount(actual.get("spawn_count").intValue()));
      }
      decoded.set(
          i,
          new Row(
              original.template(),
              original.mappingKey(),
              original.privateRowKey(),
              original.authored(),
              content.build()));
    }
    return new WorldCanonicalAuthoredGraph(
        plan.graph().tenantId(), plan.graph().versionId(), tenant, version, decoded);
  }

  private static void fields(JsonNode node, Set<String> expected) {
    if (node == null
        || !node.isObject()
        || node.size() != expected.size()
        || node.properties().stream().anyMatch(entry -> !expected.contains(entry.getKey()))) {
      throw invalid("Canonical graph has unsupported or missing fields");
    }
  }

  private static void text(JsonNode node, String field, String expected) {
    JsonNode actual = node.get(field);
    if (actual == null || !actual.isString() || !actual.asText().equals(expected)) {
      throw invalid("Canonical graph differs at " + field);
    }
  }

  private static long positive(JsonNode node, String field) {
    JsonNode actual = node.get(field);
    if (actual == null
        || !actual.isIntegralNumber()
        || !actual.canConvertToLong()
        || actual.longValue() <= 0) {
      throw invalid("Canonical graph requires a positive private integer at " + field);
    }
    return actual.longValue();
  }

  private static ConflictException invalid(String message) {
    return new ConflictException(message);
  }
}
