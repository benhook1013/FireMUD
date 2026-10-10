package net.firedevops.firemud.entitymanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.entitymanagement.sourceintake.EntityEmptySelectedSourceIntakeRepository;
import net.firedevops.firemud.entitymanagement.sourceintake.EntitySelectedEmptyPublicationDigestService;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Physical PostgreSQL proof for Entity's synthetic-input fresh EMPTY-only retention boundary. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class EntityEmptySelectedSourceIntakeRepositoryPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final List<String> EMPTY_ONLY_TABLES =
      List.of(
          "entity_empty_source_actor_body_layout_assignments",
          "entity_empty_source_archetype_assignments",
          "entity_empty_source_archetype_constraints",
          "entity_empty_source_archetype_roots",
          "entity_empty_source_balance_curve_attachments",
          "entity_empty_source_balance_curve_roots",
          "entity_empty_source_equipment_attachment_rules",
          "entity_empty_source_equipment_capabilities",
          "entity_empty_source_equipment_compatibility_rules",
          "entity_empty_source_equipment_occupancy_rules",
          "entity_empty_source_inbound_loot_bindings",
          "entity_empty_source_loot_item_mappings",
          "entity_empty_source_loot_table_roots",
          "entity_empty_source_other_actor_template_roots");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private String schema;
  private DSLContext dsl;
  private EntityEmptySelectedSourceIntakeRepository repository;

  @BeforeEach
  void migrateFreshIsolatedSchema() {
    schema = "entity_empty_source_repo_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .load()
        .migrate();
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    repository = new EntityEmptySelectedSourceIntakeRepository(dsl);
  }

  @AfterEach
  void dropIsolatedSchema() {
    if (schema != null) {
      String checkedSchema = schema.replaceAll("[^a-zA-Z0-9_]", "");
      DSL.using(dataSource(null), SQLDialect.POSTGRES)
          .execute("DROP SCHEMA IF EXISTS \"" + checkedSchema + "\" CASCADE");
      schema = null;
    }
  }

  @Test
  void persistsAllTwentyThreeExplicitEmptyStatesAndReplaysOriginalReceiptBytes() {
    var fixture = EntityEmptySelectedSourceIntakePostgresFixture.create("1");
    var inputs = fixture.inputs();
    assertThat(inputs.ownerSourceInventoryDeclaration().owner()).isEqualTo(Owner.ENTITY_MANAGEMENT);
    assertThat(
            inputs
                .worldInventoryReadEvidence()
                .inventory()
                .publicEvidence()
                .sourceModel()
                .inboundSourceClosure()
                .familyCounts())
        .allSatisfy(count -> assertThat(count.count()).isZero());
    String requestDigest = requestDigest(fixture);

    EntityEmptySelectedSourceIntakeReceipt first = repository.retainFresh(inputs, requestDigest);
    assertThat(first.familyStates())
        .hasSize(23)
        .allSatisfy(
            state -> {
              assertThat(state.state())
                  .isEqualTo(EntityEmptySelectedSourceIntakeReceipt.FamilyState.EMPTY);
              assertThat(state.rowCount()).isZero();
              assertThat(state.referenceCount()).isZero();
            });
    assertThat(first.familyStates())
        .filteredOn(
            state ->
                state.evidenceKind()
                    == EntityEmptySelectedSourceIntakeReceipt.FamilyEvidenceKind
                        .EMPTY_ONLY_OWNER_PROVIDER)
        .hasSize(14);
    assertThat(first.familyStates())
        .filteredOn(
            state ->
                state.evidenceKind()
                    == EntityEmptySelectedSourceIntakeReceipt.FamilyEvidenceKind.V1_SOURCE_CENSUS)
        .hasSize(9);
    assertThat(first.localTenantKey()).isPositive();
    assertThat(first.localVersionKey()).isPositive().isNotEqualTo(first.localTenantKey());
    assertThat(first.authorizationBindingBytes())
        .containsExactly(inputs.authorizationBinding().canonicalBytes());
    assertThat(first.selectedSourceBytes())
        .containsExactly(inputs.sourceContent().canonicalBytes());
    assertThat(first.worldClosureBytes())
        .containsExactly(inputs.worldInventoryReadEvidence().inventory().canonicalBytes());

    assertThat(rowCount("SELECT COUNT(*) FROM entity_empty_selected_source_association"))
        .isEqualTo(1L);
    assertThat(rowCount("SELECT COUNT(*) FROM entity_empty_selected_source_family_state"))
        .isEqualTo(23L);
    assertThat(rowCount("SELECT COUNT(*) FROM entity_empty_selected_source_receipt")).isEqualTo(1L);
    for (String table : EMPTY_ONLY_TABLES) {
      assertThat(rowCount("SELECT COUNT(*) FROM " + table)).as(table).isEqualTo(1L);
    }
    assertThat(
            repository
                .read(first.targetNamespace(), first.intakeRequestId())
                .orElseThrow()
                .canonicalBytes())
        .containsExactly(first.canonicalBytes());

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO items (name, tenant_id, version_id) VALUES ('blocked', ?, ?)",
                    first.localTenantKey(),
                    first.localVersionKey()))
        .isInstanceOf(DataAccessException.class);
    dsl.execute(
        "INSERT INTO items (name, tenant_id, version_id) VALUES ('unassociated', 81001, 81002)");
    assertThat(rowCount("SELECT COUNT(*) FROM items WHERE tenant_id = 81001")).isEqualTo(1L);

    var retry =
        EntityEmptySelectedSourceIntakePostgresFixture.withFreshWorldReadCorrelation(fixture);
    assertThat(retry.worldEvidence().request().readRequestId())
        .isNotEqualTo(fixture.worldEvidence().request().readRequestId());
    EntityEmptySelectedSourceIntakeReceipt replay =
        repository.retainFresh(retry.inputs(), requestDigest(retry));
    assertThat(replay.canonicalBytes()).containsExactly(first.canonicalBytes());
    assertThat(replay.worldReadRequestId()).isEqualTo(first.worldReadRequestId());

    var changed = EntityEmptySelectedSourceIntakePostgresFixture.create("2");
    assertThatThrownBy(() -> repository.retainFresh(changed.inputs(), requestDigest(changed)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("different selected evidence");
    assertThat(
            repository
                .read(first.targetNamespace(), first.intakeRequestId())
                .orElseThrow()
                .canonicalBytes())
        .containsExactly(first.canonicalBytes());
  }

  @Test
  void existingGlobalEntityRowsAndReferencesDenyFreshGenesisAndRollBackOwnerRecords() {
    var fixture = EntityEmptySelectedSourceIntakePostgresFixture.create("1");
    dsl.execute("INSERT INTO items (name, tenant_id, version_id) VALUES ('legacy', 72001, 72002)");

    assertThatThrownBy(() -> repository.retainFresh(fixture.inputs(), requestDigest(fixture)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not freshly empty");
    assertNoCanonicalOwnerRecords();
  }

  @Test
  void selectedEmptyPublicationReaderResolvesOnlyTheValidatedReceiptForTenantAndGameDesignRow() {
    var fixture = EntityEmptySelectedSourceIntakePostgresFixture.create("1");
    EntityEmptySelectedSourceIntakeReceipt receipt =
        repository.retainFresh(fixture.inputs(), requestDigest(fixture));
    long gameDesignVersionRowId =
        fixture.authorization().selected().target().gameDesignVersionRowId();

    assertThat(
            repository
                .readPublicationScope(
                    EntityEmptySelectedSourceIntakePostgresFixture.NAMESPACE,
                    EntityEmptySelectedSourceIntakePostgresFixture.TENANT,
                    gameDesignVersionRowId)
                .canonicalBytes())
        .containsExactly(receipt.canonicalBytes());
    assertThatThrownBy(
            () ->
                repository.readPublicationScope(
                    EntityEmptySelectedSourceIntakePostgresFixture.NAMESPACE,
                    EntityEmptySelectedSourceIntakePostgresFixture.TENANT,
                    gameDesignVersionRowId + 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing or ambiguous");

    var digest =
        new EntitySelectedEmptyPublicationDigestService(
                EntityEmptySelectedSourceIntakePostgresFixture.NAMESPACE, repository)
            .getDraftDesignDigest(
                PublicationDigestRequestBinding.full(
                    EntityEmptySelectedSourceIntakePostgresFixture.TENANT.toString(),
                    Long.toString(gameDesignVersionRowId),
                    "publish-request-1"));
    assertThat(digest.tenantId())
        .isEqualTo(EntityEmptySelectedSourceIntakePostgresFixture.TENANT.toString());
    assertThat(digest.scopeValue()).isEqualTo(Long.toString(gameDesignVersionRowId));
    assertThat(digest.appliedCommitId()).isEqualTo(receipt.selectedCommitId().toString());
    String canonicalInventoryJson =
        fixture.inputs().ownerSourceInventoryDeclaration().entityInventory().canonicalJson();
    assertThat(digest.contentDigest())
        .isEqualTo(
            expectedSelectedEmptyDigest(
                "entity-selected-empty-publication-digest/v1",
                "3",
                EntityEmptySelectedSourceIntakePostgresFixture.TENANT,
                gameDesignVersionRowId,
                receipt.canonicalVersionId(),
                canonicalInventoryJson));
    assertThat(digest.digestSchemaVersion())
        .isEqualTo(EntitySelectedEmptyPublicationDigestService.DIGEST_SCHEMA_VERSION);

    var differentPublicationRequest =
        new EntitySelectedEmptyPublicationDigestService(
                EntityEmptySelectedSourceIntakePostgresFixture.NAMESPACE, repository)
            .getDraftDesignDigest(
                PublicationDigestRequestBinding.full(
                    EntityEmptySelectedSourceIntakePostgresFixture.TENANT.toString(),
                    Long.toString(gameDesignVersionRowId),
                    "publish-request-2"));
    assertThat(differentPublicationRequest.contentDigest()).isEqualTo(digest.contentDigest());
  }

  @Test
  void publicationScopeScanAndReceiptReadbackUseOneRepeatableReadSnapshot() {
    var fixture = EntityEmptySelectedSourceIntakePostgresFixture.create("1");
    EntityEmptySelectedSourceIntakeReceipt receipt =
        repository.retainFresh(fixture.inputs(), requestDigest(fixture));
    byte[] concurrentlyCorruptedBytes = receipt.canonicalBytes();
    concurrentlyCorruptedBytes[0] ^= 1;
    AtomicBoolean concurrentMutationApplied = new AtomicBoolean();
    dsl.configuration()
        .set(
            new DefaultExecuteListenerProvider(
                new DefaultExecuteListener() {
                  @Override
                  public void executeEnd(ExecuteContext context) {
                    String sql = context.sql();
                    if (sql == null
                        || !sql.toLowerCase(Locale.ROOT)
                            .contains("from entity_empty_selected_source_association")
                        || !concurrentMutationApplied.compareAndSet(false, true)) {
                      return;
                    }
                    DSLContext concurrentDsl = DSL.using(dataSource(schema), SQLDialect.POSTGRES);
                    concurrentDsl.execute(
                        "ALTER TABLE entity_empty_selected_source_receipt "
                            + "DISABLE TRIGGER trg_entity_empty_source_receipt_immutable");
                    try {
                      assertThat(
                              concurrentDsl.execute(
                                  "UPDATE entity_empty_selected_source_receipt "
                                      + "SET receipt_bytes = ? WHERE target_namespace = ? "
                                      + "AND intake_request_id = ?",
                                  concurrentlyCorruptedBytes,
                                  receipt.targetNamespace(),
                                  receipt.intakeRequestId()))
                          .isEqualTo(1);
                    } finally {
                      concurrentDsl.execute(
                          "ALTER TABLE entity_empty_selected_source_receipt "
                              + "ENABLE TRIGGER trg_entity_empty_source_receipt_immutable");
                    }
                  }
                }));

    EntityEmptySelectedSourceIntakeReceipt read =
        repository.readPublicationScope(
            receipt.targetNamespace(),
            receipt.canonicalTenantId(),
            fixture.authorization().selected().target().gameDesignVersionRowId());

    assertThat(read.canonicalBytes()).containsExactly(receipt.canonicalBytes());
    assertThat(concurrentMutationApplied).isTrue();
    var storedReceipt =
        dsl.fetchOne(
            "SELECT receipt_bytes FROM entity_empty_selected_source_receipt "
                + "WHERE target_namespace = ? AND intake_request_id = ?",
            receipt.targetNamespace(),
            receipt.intakeRequestId());
    assertThat(storedReceipt).isNotNull();
    assertThat(storedReceipt.get(0, byte[].class)).containsExactly(concurrentlyCorruptedBytes);
  }

  @Test
  void retainedAuditReferenceWithoutAnItemDeniesFreshGenesis() {
    var fixture = EntityEmptySelectedSourceIntakePostgresFixture.create("1");
    dsl.execute(
        "INSERT INTO item_transfer_audits (tenant_id, item_id, quantity, verb, correlation_key, "
            + "source_holder_kind, destination_holder_kind) "
            + "VALUES (73001, 999999, 1, 'MOVE', 'retained-audit', 'UNKNOWN', 'UNKNOWN')");

    assertThatThrownBy(() -> repository.retainFresh(fixture.inputs(), requestDigest(fixture)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not freshly empty");
    assertNoCanonicalOwnerRecords();
  }

  @Test
  void providerWriteFailureRollsBackAssociationKeysAndAllTypedStates() {
    var fixture = EntityEmptySelectedSourceIntakePostgresFixture.create("1");
    dsl.execute(
        "ALTER TABLE entity_empty_source_archetype_roots "
            + "ADD CONSTRAINT test_force_empty_provider_failure CHECK (FALSE)");

    assertThatThrownBy(() -> repository.retainFresh(fixture.inputs(), requestDigest(fixture)))
        .isInstanceOf(DataAccessException.class);
    assertNoCanonicalOwnerRecords();
  }

  @Test
  void absentEmptyOnlyProviderFailsClosedBeforeAnyAssociationIsCommitted() {
    var fixture = EntityEmptySelectedSourceIntakePostgresFixture.create("1");
    dsl.execute("DROP TABLE entity_empty_source_archetype_roots");

    assertThatThrownBy(() -> repository.retainFresh(fixture.inputs(), requestDigest(fixture)))
        .isInstanceOf(DataAccessException.class);
    assertNoCanonicalOwnerRecords("entity_empty_source_archetype_roots");
  }

  @Test
  void providerConstraintsAndImmutableReadbackRejectUnknownNonemptyOrChangedState() {
    var fixture = EntityEmptySelectedSourceIntakePostgresFixture.create("1");
    EntityEmptySelectedSourceIntakeReceipt receipt =
        repository.retainFresh(fixture.inputs(), requestDigest(fixture));
    String table = "entity_empty_source_archetype_roots";

    dsl.execute("ALTER TABLE " + table + " DISABLE TRIGGER USER");
    try {
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE " + table + " SET row_count = 1 WHERE target_namespace = ?",
                      receipt.targetNamespace()))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE " + table + " SET owner_state = 'UNKNOWN' WHERE target_namespace = ?",
                      receipt.targetNamespace()))
          .isInstanceOf(DataAccessException.class);
      dsl.execute(
          "DELETE FROM " + table + " WHERE target_namespace = ? AND intake_request_id = ?",
          receipt.targetNamespace(),
          receipt.intakeRequestId());
    } finally {
      dsl.execute("ALTER TABLE " + table + " ENABLE TRIGGER USER");
    }
    assertThatThrownBy(() -> repository.read(receipt.targetNamespace(), receipt.intakeRequestId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("provider readback is missing or differs");
    assertThatThrownBy(
            () ->
                repository.readPublicationScope(
                    receipt.targetNamespace(),
                    receipt.canonicalTenantId(),
                    fixture.authorization().selected().target().gameDesignVersionRowId()))
        .isInstanceOf(IllegalStateException.class);
  }

  private static String expectedSelectedEmptyDigest(
      String domain,
      String schemaVersion,
      UUID canonicalTenantId,
      long gameDesignVersionRowId,
      UUID canonicalVersionId,
      String canonicalInventoryJson) {
    ByteArrayOutputStream preimage = new ByteArrayOutputStream();
    appendDigestSegment(preimage, domain);
    appendDigestSegment(preimage, schemaVersion);
    appendDigestSegment(preimage, canonicalTenantId.toString());
    appendDigestSegment(preimage, Long.toString(gameDesignVersionRowId));
    appendDigestSegment(preimage, canonicalVersionId.toString());
    appendDigestSegment(preimage, canonicalInventoryJson);
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray()));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void appendDigestSegment(ByteArrayOutputStream output, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(bytes);
  }

  private String requestDigest(EntityEmptySelectedSourceIntakePostgresFixture.Fixture fixture) {
    return EntityEmptySelectedSourceIntakeReceipt.requestDigest(
        fixture.authorization().targetNamespace(),
        fixture.authorization(),
        fixture.freezeEvidence());
  }

  private long rowCount(String sql) {
    var row = Objects.requireNonNull(dsl.fetchOne(sql), "count query returned no row");
    return Objects.requireNonNull(row.get(0, Long.class), "count query returned no value");
  }

  private void assertNoCanonicalOwnerRecords(String... unavailableProviderTables) {
    assertThat(rowCount("SELECT COUNT(*) FROM entity_empty_selected_source_association")).isZero();
    assertThat(rowCount("SELECT COUNT(*) FROM entity_empty_selected_source_family_state")).isZero();
    assertThat(rowCount("SELECT COUNT(*) FROM entity_empty_selected_source_receipt")).isZero();
    assertThat(
            rowCount(
                "SELECT COUNT(*) FROM entity_empty_source_numeric_key_reservation "
                    + "WHERE claim_kind = 'CANONICAL_EMPTY_SOURCE'"))
        .isZero();
    for (String table : EMPTY_ONLY_TABLES) {
      if (List.of(unavailableProviderTables).contains(table)) continue;
      assertThat(rowCount("SELECT COUNT(*) FROM " + table)).as(table).isZero();
    }
  }

  private DriverManagerDataSource dataSource(String schemaName) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    String baseUrl = postgres.getJdbcUrl();
    dataSource.setUrl(
        schemaName == null
            ? baseUrl
            : baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schemaName);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }
}
