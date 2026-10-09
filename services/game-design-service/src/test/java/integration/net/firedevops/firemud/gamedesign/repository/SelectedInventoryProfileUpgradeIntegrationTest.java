package net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/** Physical V69-to-V70 upgrade proof using isolated, explicitly synthetic upstream evidence. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class SelectedInventoryProfileUpgradeIntegrationTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void forwardUpgradePreservesRetainedV1OperationAndAdmitsOnlyPairedProfiles() throws Exception {
    String schema = "gd_inventory_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    dataSource.setSchema(schema);

    flyway(dataSource, schema, "69").migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var transactions = new DataSourceTransactionManager(dataSource);
    var transaction = new TransactionTemplate(transactions);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

    Version version =
        transaction.execute(
            status -> {
              Game game = new Game();
              game.setTenantId(UUID.randomUUID().toString());
              game.setName("synthetic historical inventory owner");
              new GameRepository(dsl).save(game);

              Version draft = new Version();
              draft.setTenantId(game.getTenantId());
              draft.setVersionNumber(1);
              draft.setVersionState(VersionLifecycleState.DRAFT);
              draft.setVersionStateEpoch(1L);
              draft.setNotes("synthetic V69 inventory upgrade fixture");
              return new VersionRepository(dsl).save(draft);
            });
    TargetProof target = targetProof(version);

    // This shared fixture creates the historical inventory/v1 + graph/2 + digest/3 profile.
    // The owner setup applies the required GD owner and writes real source, selection, attempt and
    // operation rows; Account and World source/receipt authority remains stipulated test evidence,
    // not a production claim.
    GameDesignPublicationOperation historicalV1 =
        transaction.execute(
            status -> {
              try {
                return IsolatedPublicationOwnerSetup.retainSourceBacked(
                    dsl, target, version.getVersionStateEpoch(), "synthetic V69 history");
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    assertThat(historicalV1.inventory().publicEvidence().schema())
        .isEqualTo("world-selected-publication-artifact-inventory/v1");
    assertThat(historicalV1.world().request().digestSchemaVersion()).isEqualTo(3);
    assertThat(historicalV1.inventory().publicEvidence().sourceModel().graphSchemaVersion())
        .isEqualTo(2);

    byte[] originalOperationBytes = historicalV1.canonicalBytes();
    assertThat(readOperationBytes(dsl, historicalV1.workflowId()))
        .containsExactly(originalOperationBytes);
    // The actual V56 validator is still installed at V69 and must accept the historical bytes.
    dsl.fetch("SELECT require_selected_inventory_operation_v2(?)", originalOperationBytes);

    flyway(dataSource, schema, null).migrate();

    assertThat(readOperationBytes(dsl, historicalV1.workflowId()))
        .containsExactly(originalOperationBytes);
    var repository = new GameDesignPublicationOperationRepository(dsl);
    assertThat(
            repository.read(historicalV1.workflowId()).orElseThrow().operation().canonicalBytes())
        .containsExactly(originalOperationBytes);

    transaction.executeWithoutResult(status -> repository.reserve(historicalV1));
    assertThat(readOperationBytes(dsl, historicalV1.workflowId()))
        .containsExactly(originalOperationBytes);

    GameDesignPublicationOperation closureV2 =
        IsolatedPublicationOperationFixtures.freshClosureQualifiedSelection(target);
    assertThat(closureV2.inventory().publicEvidence().schema())
        .isEqualTo("world-selected-publication-artifact-inventory/v2");
    assertThat(closureV2.world().request().digestSchemaVersion()).isEqualTo(4);
    dsl.fetch("SELECT require_selected_inventory_operation_v2(?)", closureV2.canonicalBytes());

    assertRejected(
        dsl,
        rebindInventory(
            closureV2,
            inventory -> {
              inventory.put("schema", "world-selected-publication-artifact-inventory/v1");
              inventory.put("schemaVersion", 1);
            }),
        "Incomplete or unknown public inventory members");
    assertRejected(
        dsl,
        rebindInventory(
            closureV2,
            inventory ->
                ((tools.jackson.databind.node.ObjectNode) inventory.get("checkpoint"))
                    .put("digestSchemaVersion", 3)),
        "Inventory differs from exact original selected Account, APPLIED graph or freeze");
    assertRejected(
        dsl,
        rebindInventory(
            closureV2,
            inventory ->
                ((tools.jackson.databind.node.ObjectNode) inventory.get("sourceModel"))
                    .put("unknownSourceFamily", true)),
        "Incomplete or unknown public inventory members");
    assertRejected(
        dsl,
        rebindInventory(
            closureV2,
            inventory ->
                ((tools.jackson.databind.node.ObjectNode)
                        inventory
                            .get("sourceModel")
                            .get("inboundSourceClosure")
                            .get("familyCounts")
                            .get(0))
                    .put("count", 1)),
        "Inventory differs from exact original selected Account, APPLIED graph or freeze");
  }

  private static Flyway flyway(DriverManagerDataSource dataSource, String schema, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .placeholders(Map.of("serviceSchema", schema))
            .locations(migrations());
    if (target != null) configuration.target(target);
    return configuration.load();
  }

  private static String migrations() {
    for (Path current = Path.of("").toAbsolutePath();
        current != null;
        current = current.getParent()) {
      Path target = current.resolve("services/game-design-service/src/main/resources/db/migration");
      if (Files.isDirectory(target)) return "filesystem:" + target;
    }
    throw new IllegalStateException("Game Design migration directory unavailable");
  }

  private static TargetProof targetProof(Version version) {
    return new TargetProof(
        version.getCanonicalTenantId(),
        version.getCanonicalVersionId(),
        version.getId(),
        version.getTenantId(),
        version.getIdentitySourceGameRowId(),
        version.getIdentitySourceGameTenantKey(),
        version.getIdentitySourceProvenanceKind());
  }

  private static byte[] readOperationBytes(DSLContext dsl, String workflowId) {
    var row =
        dsl.fetchOne(
            "SELECT request_bytes FROM game_design_publication_operation WHERE publish_workflow_id = ?",
            workflowId);
    if (row == null) throw new AssertionError("Retained publication operation is absent");
    return row.get("request_bytes", byte[].class);
  }

  private static byte[] rebindInventory(
      GameDesignPublicationOperation operation,
      java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> mutation)
      throws Exception {
    var inventory =
        (tools.jackson.databind.node.ObjectNode)
            JSON.readTree(operation.inventory().canonicalBytes());
    mutation.accept(inventory);
    byte[] changedInventory =
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(inventory));
    var bytes = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(bytes, GameDesignPublicationOperation.SCHEMA);
    DraftAuthorizationFenceBinding.frame(bytes, operation.account().canonicalBytes());
    DraftAuthorizationFenceBinding.frame(bytes, operation.world().canonicalBytes());
    DraftAuthorizationFenceBinding.frame(bytes, changedInventory);
    DraftAuthorizationFenceBinding.frame(bytes, sha256(changedInventory));
    return bytes.toByteArray();
  }

  private static String sha256(byte[] value) throws Exception {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static void assertRejected(DSLContext dsl, byte[] operationBytes, String message) {
    assertThatThrownBy(
            () -> dsl.fetch("SELECT require_selected_inventory_operation_v2(?)", operationBytes))
        .rootCause()
        .isInstanceOf(SQLException.class)
        .hasMessageContaining(message)
        .satisfies(
            failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514"));
  }
}
