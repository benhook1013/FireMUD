package net.firedevops.firemud.automationscripting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationEmptySelectedSourceIntakeRepository;
import net.firedevops.firemud.common.automation.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL proof of Automation's bounded local repository persistence and census path. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AutomationEmptySelectedSourceIntakeRepositoryPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private String schema;
  private DSLContext dsl;
  private AutomationEmptySelectedSourceIntakeRepository repository;

  @BeforeEach
  void migrateFreshIsolatedSchema() {
    schema = "automation_empty_source_repo_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .target("3.2")
        .load()
        .migrate();
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .load()
        .migrate();
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    repository = new AutomationEmptySelectedSourceIntakeRepository(dsl);
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
  void retainsCanonicalEmptyCensusAndExactReadbackAcrossCorrelationIndependentRetry() {
    AutomationEmptySelectedSourceIntakePostgresFixture.Fixture fixture =
        AutomationEmptySelectedSourceIntakePostgresFixture.create("1");
    var inputs = fixture.inputs();
    String requestDigest = requestDigest(fixture);

    // This validates typed synthetic Common inputs; it does not authenticate either producer.
    assertThat(inputs.authorizationBinding().targetNamespace())
        .isEqualTo(AutomationEmptySelectedSourceIntakePostgresFixture.NAMESPACE);
    assertThat(inputs.authorizationBinding().tenantId())
        .isEqualTo(AutomationEmptySelectedSourceIntakePostgresFixture.TENANT);
    assertThat(inputs.authorizationBinding().versionId())
        .isEqualTo(AutomationEmptySelectedSourceIntakePostgresFixture.VERSION);
    assertThat(inputs.ownerSourceInventoryDeclaration().owner())
        .isEqualTo(
            net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner.AUTOMATION_SCRIPTING);
    assertThat(
            inputs
                .worldInventoryReadEvidence()
                .inventory()
                .publicEvidence()
                .sourceModel()
                .inboundSourceClosure()
                .familyCounts())
        .allSatisfy(count -> assertThat(count.count()).isZero());

    AutomationEmptySelectedSourceIntakeReceipt first =
        repository.retainFresh(inputs, requestDigest);

    assertThat(first.targetNamespace()).isEqualTo(inputs.authorizationBinding().targetNamespace());
    assertThat(first.canonicalTenantId()).isEqualTo(inputs.authorizationBinding().tenantId());
    assertThat(first.canonicalVersionId()).isEqualTo(inputs.authorizationBinding().versionId());
    assertThat(first.selectedCommitId())
        .isEqualTo(inputs.authorizationBinding().selected().commitId());
    assertThat(first.sourceRevisionId())
        .isEqualTo(inputs.ownerSourceInventoryDeclaration().revisionId());
    assertThat(first.sourceRevisionOrder())
        .isEqualTo(inputs.ownerSourceInventoryDeclaration().revisionOrder());
    assertThat(first.localTenantKey()).isPositive();
    assertThat(first.localVersionKey()).isPositive().isNotEqualTo(first.localTenantKey());
    assertThat(first.outcome()).isEqualTo("COMMITTED_EMPTY");
    assertThat(first.scriptsRowCount()).isZero();
    assertThat(first.eventBindingsRowCount()).isZero();
    assertThat(first.patchBaseBindingsRowCount()).isZero();
    assertThat(first.unqualifiedScriptsRowCount()).isZero();
    assertThat(first.unqualifiedEventBindingsRowCount()).isZero();
    assertThat(first.unqualifiedPatchBaseBindingsRowCount()).isZero();
    assertThat(first.emptyAssociatedScriptsRowCount()).isZero();
    assertThat(first.emptyAssociatedEventBindingsRowCount()).isZero();
    assertThat(first.emptyAssociatedPatchBaseBindingsRowCount()).isZero();
    assertThat(first.selectedScopeScriptsRowCount()).isZero();
    assertThat(first.selectedScopeEventBindingsRowCount()).isZero();
    assertThat(first.selectedScopePatchBaseBindingsRowCount()).isZero();
    assertThat(first.authorizationBindingBytes())
        .containsExactly(inputs.authorizationBinding().canonicalBytes());
    assertThat(first.worldInventoryBytes())
        .containsExactly(inputs.worldInventoryReadEvidence().inventory().canonicalBytes());
    assertThat(first.worldReadRequestId())
        .isEqualTo(inputs.worldInventoryReadEvidence().request().readRequestId());

    assertThat(rowCount("SELECT COUNT(*) FROM scripts")).isZero();
    assertThat(rowCount("SELECT COUNT(*) FROM script_event_bindings")).isZero();
    assertThat(rowCount("SELECT COUNT(*) FROM script_patch_base_bindings")).isZero();
    assertThat(
            rowCount(
                "SELECT COUNT(*) FROM automation_empty_source_numeric_key_reservation "
                    + "WHERE claim_kind = 'CANONICAL_EMPTY_SOURCE'"))
        .isEqualTo(2L);
    assertThat(rowCount("SELECT COUNT(*) FROM automation_empty_selected_source_association"))
        .isEqualTo(1L);
    assertThat(rowCount("SELECT COUNT(*) FROM automation_empty_selected_source_receipt"))
        .isEqualTo(1L);

    Record storedReceipt =
        dsl.fetchOne(
            "SELECT receipt_bytes, receipt_digest, request_digest FROM "
                + "automation_empty_selected_source_receipt "
                + "WHERE target_namespace = ? AND intake_request_id = ?",
            first.targetNamespace(),
            first.intakeRequestId());
    assertThat(storedReceipt).isNotNull();
    assertThat(storedReceipt.get(0, byte[].class)).containsExactly(first.canonicalBytes());
    assertThat(storedReceipt.get(1, String.class)).isEqualTo(first.receiptDigest());
    assertThat(storedReceipt.get(2, String.class)).isEqualTo(first.requestDigest());

    Record storedAssociation =
        dsl.fetchOne(
            "SELECT canonical_tenant_id, canonical_version_id, selected_commit_id, "
                + "source_revision_id, source_revision_order, local_tenant_key, local_version_key, "
                + "request_digest, authorization_binding_digest, receipt_digest "
                + "FROM automation_empty_selected_source_association "
                + "WHERE target_namespace = ? AND intake_request_id = ?",
            first.targetNamespace(),
            first.intakeRequestId());
    assertThat(storedAssociation).isNotNull();
    assertThat(storedAssociation.get(0, UUID.class)).isEqualTo(first.canonicalTenantId());
    assertThat(storedAssociation.get(1, UUID.class)).isEqualTo(first.canonicalVersionId());
    assertThat(storedAssociation.get(2, UUID.class)).isEqualTo(first.selectedCommitId());
    assertThat(storedAssociation.get(3, UUID.class)).isEqualTo(first.sourceRevisionId());
    assertThat(storedAssociation.get(4, String.class)).isEqualTo(first.sourceRevisionOrder());
    assertThat(storedAssociation.get(5, Long.class)).isEqualTo(first.localTenantKey());
    assertThat(storedAssociation.get(6, Long.class)).isEqualTo(first.localVersionKey());
    assertThat(storedAssociation.get(7, String.class)).isEqualTo(first.requestDigest());
    assertThat(storedAssociation.get(8, String.class))
        .isEqualTo(first.authorizationBindingDigest());
    assertThat(storedAssociation.get(9, String.class)).isEqualTo(first.receiptDigest());

    var readBack = repository.read(first.targetNamespace(), first.intakeRequestId()).orElseThrow();
    assertThat(readBack.canonicalBytes()).containsExactly(first.canonicalBytes());
    assertThat(readBack.receiptDigest()).isEqualTo(first.receiptDigest());
    var terminalRead = repository.readCommittedTerminal(fixture.authorization()).orElseThrow();
    assertThat(terminalRead.canonicalBytes()).containsExactly(first.canonicalBytes());
    assertThat(terminalRead.authorizationBindingBytes())
        .containsExactly(fixture.authorization().canonicalBytes());

    var retry =
        AutomationEmptySelectedSourceIntakePostgresFixture.withFreshWorldReadCorrelation(fixture);
    assertThat(retry.worldEvidence().request().readRequestId())
        .isNotEqualTo(fixture.worldEvidence().request().readRequestId());
    String retryDigest = requestDigest(retry);
    assertThat(retryDigest).isEqualTo(requestDigest);
    var retryReceipt = repository.retainFresh(retry.inputs(), retryDigest);
    assertThat(retryReceipt.canonicalBytes()).containsExactly(first.canonicalBytes());
    assertThat(retryReceipt.worldReadRequestId()).isEqualTo(first.worldReadRequestId());

    var altered = AutomationEmptySelectedSourceIntakePostgresFixture.create("2");
    assertThat(altered.authorization().intakeRequestId())
        .isEqualTo(fixture.authorization().intakeRequestId());
    assertThat(altered.authorization().canonicalBytes())
        .isNotEqualTo(fixture.authorization().canonicalBytes());
    assertThatThrownBy(() -> repository.readCommittedTerminal(altered.authorization()))
        .isInstanceOf(AutomationEmptySelectedSourceIntakeRepository.IntakeConflictException.class)
        .hasMessageContaining("complete original authorization");
    String alteredDigest = requestDigest(altered);
    assertThat(alteredDigest).isNotEqualTo(requestDigest);
    assertThatThrownBy(() -> repository.retainFresh(altered.inputs(), alteredDigest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already records different selected evidence");

    assertThat(
            rowCount(
                "SELECT COUNT(*) FROM automation_empty_source_numeric_key_reservation "
                    + "WHERE claim_kind = 'CANONICAL_EMPTY_SOURCE'"))
        .isEqualTo(2L);
    assertThat(rowCount("SELECT COUNT(*) FROM automation_empty_selected_source_association"))
        .isEqualTo(1L);
    assertThat(rowCount("SELECT COUNT(*) FROM automation_empty_selected_source_receipt"))
        .isEqualTo(1L);
    Record unchangedReceipt =
        dsl.fetchOne(
            "SELECT receipt_bytes FROM automation_empty_selected_source_receipt "
                + "WHERE target_namespace = ? AND intake_request_id = ?",
            first.targetNamespace(),
            first.intakeRequestId());
    assertThat(unchangedReceipt).isNotNull();
    assertThat(unchangedReceipt.get(0, byte[].class)).containsExactly(first.canonicalBytes());

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE automation_empty_selected_source_receipt SET receipt_bytes = ? "
                        + "WHERE target_namespace = ? AND intake_request_id = ?",
                    first.canonicalBytes(),
                    first.targetNamespace(),
                    first.intakeRequestId()))
        .rootCause()
        .hasMessageContaining("Automation empty-source intake records are immutable");

    byte[] corruptBytes = first.canonicalBytes();
    corruptBytes[0] ^= 1;
    // The isolated test owner bypasses only V5's immutable receipt-row trigger to model corruption.
    dsl.execute(
        "ALTER TABLE automation_empty_selected_source_receipt "
            + "DISABLE TRIGGER trg_automation_empty_source_receipt_immutable");
    try {
      assertThat(
              dsl.execute(
                  "UPDATE automation_empty_selected_source_receipt SET receipt_bytes = ? "
                      + "WHERE target_namespace = ? AND intake_request_id = ?",
                  corruptBytes,
                  first.targetNamespace(),
                  first.intakeRequestId()))
          .isEqualTo(1);
    } finally {
      dsl.execute(
          "ALTER TABLE automation_empty_selected_source_receipt "
              + "ENABLE TRIGGER trg_automation_empty_source_receipt_immutable");
    }
    assertThatThrownBy(() -> repository.readCommittedTerminal(fixture.authorization()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deniesUnqualifiedRowsInEachAuthoredFamilyWithoutCreatingOwnerRecords() {
    var fixture = AutomationEmptySelectedSourceIntakePostgresFixture.create("1");
    String requestDigest = requestDigest(fixture);

    dsl.execute(
        "INSERT INTO scripts (tenant_id, name, version, definition) VALUES (?, ?, ?, ?)",
        501L,
        "unqualified-script",
        "legacy-patch",
        "{}");
    assertUnqualifiedDenial(fixture, requestDigest);
    dsl.execute("DELETE FROM scripts WHERE name = ?", "unqualified-script");

    dsl.execute(
        "INSERT INTO script_event_bindings (tenant_id, script_patch_version, event_type, "
            + "event_schema_version, script_id, target_scope_type, target_scope_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
        502L,
        "unqualified-event-patch",
        "unqualified-event",
        "v1",
        "unqualified-script",
        "TENANT",
        "test");
    assertUnqualifiedDenial(fixture, requestDigest);
    dsl.execute(
        "DELETE FROM script_event_bindings WHERE script_patch_version = ?",
        "unqualified-event-patch");

    dsl.execute(
        "INSERT INTO script_patch_base_bindings (tenant_id, script_patch_version, base_version_id) "
            + "VALUES (?, ?, ?)",
        "unqualified-tenant-key",
        "unqualified-patch",
        503L);
    assertUnqualifiedDenial(fixture, requestDigest);
    dsl.execute(
        "DELETE FROM script_patch_base_bindings WHERE script_patch_version = ?",
        "unqualified-patch");

    assertThat(rowCount("SELECT COUNT(*) FROM scripts")).isZero();
    assertThat(rowCount("SELECT COUNT(*) FROM script_event_bindings")).isZero();
    assertThat(rowCount("SELECT COUNT(*) FROM script_patch_base_bindings")).isZero();
    assertThat(rowCount("SELECT COUNT(*) FROM automation_empty_source_numeric_key_reservation"))
        .isZero();
    assertThat(rowCount("SELECT COUNT(*) FROM automation_empty_selected_source_association"))
        .isZero();
    assertThat(rowCount("SELECT COUNT(*) FROM automation_empty_selected_source_receipt")).isZero();
  }

  private void assertUnqualifiedDenial(
      AutomationEmptySelectedSourceIntakePostgresFixture.Fixture fixture, String requestDigest) {
    assertThatThrownBy(() -> repository.retainFresh(fixture.inputs(), requestDigest))
        .isInstanceOf(
            AutomationEmptySelectedSourceIntakeRepository.UnqualifiedSourceRowsException.class);
    assertThat(rowCount("SELECT COUNT(*) FROM automation_empty_source_numeric_key_reservation"))
        .isZero();
    assertThat(rowCount("SELECT COUNT(*) FROM automation_empty_selected_source_association"))
        .isZero();
    assertThat(rowCount("SELECT COUNT(*) FROM automation_empty_selected_source_receipt")).isZero();
  }

  private static String requestDigest(
      AutomationEmptySelectedSourceIntakePostgresFixture.Fixture fixture) {
    return AutomationEmptySelectedSourceIntakeReceipt.requestDigest(
        AutomationEmptySelectedSourceIntakePostgresFixture.NAMESPACE,
        fixture.authorization(),
        fixture.freezeEvidence());
  }

  private long rowCount(String sql, Object... parameters) {
    Record record = dsl.fetchOne(sql, parameters);
    assertThat(record).isNotNull();
    Long value = record.get(0, Long.class);
    assertThat(value).isNotNull();
    return value;
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
