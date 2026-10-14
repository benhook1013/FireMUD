package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Family;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import org.junit.jupiter.api.Test;

class WorldCanonicalAuthoredGraphReaderTest {
  private static final UUID TENANT = new UUID(0, 1);
  private static final UUID VERSION = new UUID(0, 2);
  private static final UUID COMMIT = new UUID(0, 3);
  private static final UUID REGION = new UUID(0, 4);
  private static final UUID REVISION = new UUID(0, 5);

  @Test
  void consumesClosedActualStorageViewWithPrivateKeysAndEffectiveDefaults() throws Exception {
    var graph = new WorldCanonicalAuthoredGraphReader().read(plan(), bytes(graphJson()));
    assertThat(graph.tenantId()).isEqualTo(TENANT);
    assertThat(graph.localTenantKey()).isEqualTo(9007199254740993L);
    assertThat(graph.family(Family.REGION)).hasSize(1);
    var row = graph.rows().getFirst();
    assertThat(row.template().templateId()).isEqualTo(REGION);
    assertThat(row.scope()).isEqualTo(row.template());
    assertThat(row.authored().mutation().getRegion().getSpacingMultiplier()).isZero();
    assertThat(row.content().getRegion().getSpacingMultiplier()).isEqualTo(1.0);
    assertThatThrownBy(() -> graph.rows().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsUnknownMissingDuplicateTrailingAndWrongRepresentations() throws Exception {
    String good = graphJson();
    for (String bad :
        List.of(
            good.replace("\"schemaVersion\":\"2\"", "\"schemaVersion\":\"1\""),
            good.replace("\"schemaVersion\":\"2\"", "\"schemaVersion\":2"),
            good.replace("\"schemaVersion\":\"2\"", "\"schemaVersion\":\"2\",\"extra\":true"),
            good.replace(
                "\"schemaVersion\":\"2\"", "\"schemaVersion\":\"2\",\"schemaVersion\":\"2\""),
            good + " {}",
            good.replace("\"family\":\"REGION\"", "\"family\":\"ROOM\""),
            good.replace("\"private_row_key\":11", "\"private_row_key\":\"11\""),
            good.replace("\"private_row_key\":11", "\"private_row_key\":0"),
            good.replace("\"private_row_key\":11", "\"private_row_key\":9223372036854775808"),
            good.replace("\"weather\":\"rain\"", "\"weather\":\"tampered\""),
            good.replace("\"version_id\":31,\"version\":0", "\"version_id\":32,\"version\":0"),
            good.replace(
                "\"canonicalVersionId\":\"" + VERSION, "\"canonicalVersionId\":\"" + REGION),
            good.replace("\"name\":\"region\"", "\"name\":\"region\",\"extra\":null"))) {
      assertThatThrownBy(() -> new WorldCanonicalAuthoredGraphReader().read(plan(), bytes(bad)))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void graphThreeRetainsTheExactSevenInboundDeclarationAndTypedEntityReference() throws Exception {
    var selected = WorldDraftTopologyCommitPlanTest.selectedPlanWithAllFamilies();
    var graph =
        new WorldCanonicalAuthoredGraphReader().read(selected, selectedGraphBytes(selected));
    assertThat(graph.inboundSourceClosure())
        .contains(
            selected
                .graph()
                .freshGraphDeclaration()
                .orElseThrow()
                .inboundSourceClosure()
                .orElseThrow());
    assertThat(graph.inboundSourceClosure().orElseThrow().familyCounts()).hasSize(7);
    assertThat(
            graph.family(Family.WORLD_ENTITY_SPAWN_BINDING).getFirst().authored().entityReference())
        .isEqualTo(
            selected
                .graph()
                .family(
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING)
                .getFirst()
                .entityReference());
  }

  @Test
  void graphThreeRejectsMissingChangedUnsupportedExtraAndUnselectedClosure() throws Exception {
    var selected = WorldDraftTopologyCommitPlanTest.selectedPlanWithAllFamilies();
    var json = tools.jackson.databind.json.JsonMapper.builder().build();
    var original = json.readTree(selectedGraphBytes(selected));
    var missing = original.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) missing).remove("inboundSourceClosure");
    var changed = original.deepCopy();
    ((tools.jackson.databind.node.ObjectNode)
            changed.get("inboundSourceClosure").get("familyCounts").get(0))
        .put("count", 1);
    var unsupported = original.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) unsupported.get("inboundSourceClosure"))
        .put("schemaVersion", 2);
    var extra = original.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) extra.get("inboundSourceClosure"))
        .put("unknown", true);
    var wrongSchema = original.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) wrongSchema).put("schemaVersion", "2");
    for (var malformed : List.of(missing, changed, unsupported, extra, wrongSchema)) {
      assertThatThrownBy(
              () ->
                  new WorldCanonicalAuthoredGraphReader()
                      .read(selected, json.writeValueAsBytes(malformed)))
          .isInstanceOf(RuntimeException.class);
    }
    assertThatThrownBy(
            () ->
                new WorldCanonicalAuthoredGraphReader()
                    .read(plan(), json.writeValueAsBytes(original)))
        .isInstanceOf(RuntimeException.class);
    assertThat(
            new WorldCanonicalAuthoredGraphReader()
                .read(plan(), bytes(graphJson()))
                .inboundSourceClosure())
        .isEmpty();
  }

  // Decoder-only structural storage bytes; no physical owner storage or APPLIED proof is claimed.
  private static byte[] selectedGraphBytes(WorldDraftTopologyCommitPlan plan) {
    var keys = new java.util.LinkedHashMap<String, Long>();
    long key = 100;
    for (var node : plan.graph().nodes()) {
      var family =
          node.mutation().getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      keys.put(family + ":" + node.templateId(), key++);
    }
    var rows = new java.util.ArrayList<java.util.Map<String, Object>>();
    long mappingId = 1;
    for (var node : plan.graph().nodes()) {
      var family =
          node.mutation().getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      var mapping = new java.util.LinkedHashMap<String, Object>();
      mapping.put("id", mappingId++);
      mapping.put("target_namespace", plan.ownerBinding().targetNamespace());
      mapping.put("canonical_tenant_id", plan.graph().tenantId().toString());
      mapping.put("canonical_version_id", plan.graph().versionId().toString());
      mapping.put("family", family);
      mapping.put("template_id", node.templateId().toString());
      mapping.put("private_row_key", keys.get(family + ":" + node.templateId()));
      mapping.put("tenant_id", 11);
      mapping.put("version_id", 31);
      mapping.put(
          "version_identity_operation_id",
          plan.ownerBinding().versionIdentityOperationId().toString());
      mapping.put("request_id", plan.binding().requestId().toString());
      mapping.put("commit_id", plan.binding().commitId().toString());
      mapping.put("revision_id", node.revisionId().toString());
      mapping.put("revision_order", node.revisionOrder());
      rows.add(
          java.util.Map.of(
              "mapping",
              mapping,
              "content",
              WorldDraftTopologyCommitRepository.expectedContent(node, keys, 11, 31)));
    }
    var graph = new java.util.LinkedHashMap<String, Object>();
    graph.put("schemaVersion", "3");
    graph.put("canonicalTenantId", plan.graph().tenantId().toString());
    graph.put("canonicalVersionId", plan.graph().versionId().toString());
    graph.put(
        "inboundSourceClosure",
        WorldDraftTopologyCommitRepository.inboundSourceClosureContent(
            plan.graph()
                .freshGraphDeclaration()
                .orElseThrow()
                .inboundSourceClosure()
                .orElseThrow()));
    graph.put("rows", rows);
    return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(graph);
  }

  static WorldDraftTopologyCommitPlan plan() throws Exception {
    var owner =
        new OwnerBinding(
            "firemud",
            TENANT,
            VERSION,
            new UUID(0, 6),
            41,
            new UUID(0, 7),
            new UUID(0, 8),
            "sha256:" + "a".repeat(64),
            new UUID(0, 9),
            "sha256:" + "b".repeat(64),
            "sha256:" + "c".repeat(64));
    var mutation =
        WorldDesignMutationRevision.newBuilder()
            .setCommitId(COMMIT.toString())
            .setLogicalRevisionId(REVISION.toString())
            .setAggregateId(REGION.toString())
            .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
            .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
            .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
            .setScopeId(REGION.toString())
            .setRegion(RegionDesignMutation.newBuilder().setName("region").setWeather("rain"))
            .build();
    var binding =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                TENANT, VERSION, 41, "gd-source", 51, "gd-source", "NEW_GAME_ROW"),
            new UUID(0, 10),
            COMMIT,
            "original-base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    REVISION,
                    Owner.WORLD_MANAGEMENT,
                    JsonFormat.printer().omittingInsignificantWhitespace().print(mutation))),
            List.of(
                new AffectedUnit(
                    Owner.WORLD_MANAGEMENT,
                    "REGION",
                    REGION.toString(),
                    "AGGREGATE",
                    REGION.toString(),
                    "0"),
                new AffectedUnit(
                    Owner.WORLD_MANAGEMENT,
                    "REGION",
                    REGION.toString(),
                    "REGION_SUBTREE",
                    REGION.toString(),
                    "0")));
    return WorldDraftTopologyCommitPlan.create(binding, owner);
  }

  // Synthetic bytes are deliberate decoder fixtures; real V30 storage is covered by PostgreSQL.
  private String graphJson() {
    return """
        {"schemaVersion":"2","canonicalTenantId":"%s","canonicalVersionId":"%s","rows":[
        {"mapping":{"id":1,"target_namespace":"firemud","canonical_tenant_id":"%s",
        "canonical_version_id":"%s","family":"REGION","template_id":"%s","private_row_key":11,
        "tenant_id":9007199254740993,"version_id":31,"version_identity_operation_id":"%s",
        "request_id":"%s","commit_id":"%s","revision_id":"%s","revision_order":"0"},
        "content":{"id":11,"tenant_id":9007199254740993,"version_id":31,"version":0,"name":"region",
        "shard_id":0,"weather":"rain","generation_seed":0,"generator_type":"","generator_params":"",
        "spacing_multiplier":1.0}}]}
        """
        .replace("\n", "")
        .formatted(
            TENANT,
            VERSION,
            TENANT,
            VERSION,
            REGION,
            new UUID(0, 6),
            new UUID(0, 10),
            COMMIT,
            REVISION);
  }

  private byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
