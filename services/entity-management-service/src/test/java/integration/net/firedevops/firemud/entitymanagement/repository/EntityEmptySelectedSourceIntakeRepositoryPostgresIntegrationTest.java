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
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.entitymanagement.sourceintake.EntityEmptySelectedSourceIntakeRepository;
import net.firedevops.firemud.entitymanagement.sourceintake.EntitySelectedEmptyPublicationDigestService;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
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
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
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
    var flywayConfiguration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATION_LOCATION, "classpath:db/migration/saga")
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema));
    flywayConfiguration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    flywayConfiguration.load().migrate();
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
  @ResourceLock(Resources.TIME_ZONE)
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

    TimeZone originalTimeZone = TimeZone.getDefault();
    EntityEmptySelectedSourceIntakeReceipt first;
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
      first = repository.retainFresh(inputs, requestDigest);
    } finally {
      TimeZone.setDefault(originalTimeZone);
    }
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
    EntityEmptySelectedSourceIntakeReceipt readBack;
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
      readBack = repository.read(first.targetNamespace(), first.intakeRequestId()).orElseThrow();
    } finally {
      TimeZone.setDefault(originalTimeZone);
    }
    assertThat(readBack.canonicalBytes()).containsExactly(first.canonicalBytes());
    assertThat(readBack.receiptDigest()).isEqualTo(first.receiptDigest());
    assertThat(readBack.retainedAt().toInstant()).isEqualTo(first.retainedAt().toInstant());

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
    EntityEmptySelectedSourceIntakeReceipt replay;
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
      replay = repository.retainFresh(retry.inputs(), requestDigest(retry));
    } finally {
      TimeZone.setDefault(originalTimeZone);
    }
    assertThat(replay.canonicalBytes()).containsExactly(first.canonicalBytes());
    assertThat(replay.receiptDigest()).isEqualTo(first.receiptDigest());
    assertThat(replay.retainedAt().toInstant()).isEqualTo(first.retainedAt().toInstant());
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
  void multiplePriorScopeOrOperationMatchesProduceTheOwnerConflict() {
    var fixture = EntityEmptySelectedSourceIntakePostgresFixture.create("1");
    var binding = fixture.authorization();
    seedAssociation(
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"),
        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1"),
        UUID.fromString("cccccccc-cccc-4ccc-8ccc-ccccccccccc1"),
        UUID.fromString("dddddddd-dddd-4ddd-8ddd-ddddddddddd1"),
        EntityEmptySelectedSourceIntakePostgresFixture.TENANT,
        EntityEmptySelectedSourceIntakePostgresFixture.VERSION,
        91001L,
        91002L);
    seedAssociation(
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2"),
        binding.operationId(),
        UUID.fromString("cccccccc-cccc-4ccc-8ccc-ccccccccccc2"),
        UUID.fromString("dddddddd-dddd-4ddd-8ddd-ddddddddddd2"),
        UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
        UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"),
        91003L,
        91004L);

    assertThatThrownBy(() -> repository.retainFresh(fixture.inputs(), requestDigest(fixture)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Entity selected scope or owner operation is already associated");
    assertThat(rowCount("SELECT COUNT(*) FROM entity_empty_selected_source_association"))
        .isEqualTo(2L);
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
                    fixture.freezeEvidence().request().publicationRequestId()));
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

    String rowBeforeMismatchedPublicationRead =
        dsl.fetchSingle(
                "SELECT encode(receipt_bytes, 'hex') || ':' || xmin::text "
                    + "FROM entity_empty_selected_source_receipt "
                    + "WHERE target_namespace = ? AND intake_request_id = ?",
                receipt.targetNamespace(),
                receipt.intakeRequestId())
            .get(0, String.class);
    assertThatThrownBy(
            () ->
                new EntitySelectedEmptyPublicationDigestService(
                        EntityEmptySelectedSourceIntakePostgresFixture.NAMESPACE, repository)
                    .getDraftDesignDigest(
                        PublicationDigestRequestBinding.full(
                            EntityEmptySelectedSourceIntakePostgresFixture.TENANT.toString(),
                            Long.toString(gameDesignVersionRowId),
                            "publish-request-2")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("original retained World freeze");
    String rowAfterMismatchedPublicationRead =
        dsl.fetchSingle(
                "SELECT encode(receipt_bytes, 'hex') || ':' || xmin::text "
                    + "FROM entity_empty_selected_source_receipt "
                    + "WHERE target_namespace = ? AND intake_request_id = ?",
                receipt.targetNamespace(),
                receipt.intakeRequestId())
            .get(0, String.class);
    assertThat(rowAfterMismatchedPublicationRead).isEqualTo(rowBeforeMismatchedPublicationRead);
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

  private void seedAssociation(
      UUID genesisId,
      UUID operationId,
      UUID fenceId,
      UUID intakeRequestId,
      UUID tenantId,
      UUID versionId,
      long localTenantKey,
      long localVersionKey) {
    dsl.execute(
        "INSERT INTO entity_empty_source_numeric_key_reservation "
            + "(key_kind, numeric_key, claim_kind) VALUES "
            + "('TENANT', ?, 'CANONICAL_EMPTY_SOURCE'), "
            + "('VERSION', ?, 'CANONICAL_EMPTY_SOURCE')",
        localTenantKey,
        localVersionKey);
    String digest = "sha256:" + "a".repeat(64);
    dsl.execute(
        "INSERT INTO entity_empty_selected_source_association "
            + "(target_namespace, genesis_id, operation_id, fence_id, intake_request_id, "
            + "canonical_tenant_id, canonical_version_id, selected_commit_id, "
            + "source_revision_id, source_revision_order, local_tenant_key, local_version_key, "
            + "request_digest, schema_digest, authorization_binding_digest, "
            + "selected_source_digest, world_read_request_id, world_read_request_digest, "
            + "world_closure_digest, receipt_digest, associated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '0', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
            + "CURRENT_TIMESTAMP)",
        EntityEmptySelectedSourceIntakePostgresFixture.NAMESPACE,
        genesisId,
        operationId,
        fenceId,
        intakeRequestId,
        tenantId,
        versionId,
        UUID.fromString("12121212-1212-4212-8212-121212121212"),
        UUID.fromString("13131313-1313-4313-8313-131313131313"),
        localTenantKey,
        localVersionKey,
        digest,
        digest,
        digest,
        digest,
        UUID.fromString("14141414-1414-4414-8414-141414141414"),
        digest,
        digest,
        digest);
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
