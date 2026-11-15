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
