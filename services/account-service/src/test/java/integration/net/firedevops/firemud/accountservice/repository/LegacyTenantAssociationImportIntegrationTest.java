package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
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

@Testcontainers(disabledWithoutDocker = true)
class LegacyTenantAssociationImportIntegrationTest {
  private static final String SCHEMA = "account_tenant_association_proof";
  private static final long LEGACY_TENANT_ID = 41L;
  private static final long ROLLBACK_LEGACY_TENANT_ID = 43L;
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID CONFLICTING_TENANT_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID ROLLBACK_TENANT_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void exactRetainedSourceAndApprovedOwnerReadAreRequiredForImmutableImport() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + SCHEMA);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .placeholders(Map.of("serviceSchema", SCHEMA))
        .locations("classpath:db/migration")
        .target("25")
        .load()
        .migrate();
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    long accountId = insertRetainedRows(dsl, LEGACY_TENANT_ID, "retained-tenant");
    insertRetainedRows(dsl, ROLLBACK_LEGACY_TENANT_ID, "retained-tenant-rollback");
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .placeholders(Map.of("serviceSchema", SCHEMA))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    LegacyTenantSourceEvidence sources = new LegacyTenantSourceEvidence(transactionDsl);
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(transactionDsl);
    ApprovedLegacyTenantAssociationRepository repository =
        new ApprovedLegacyTenantAssociationRepository(transactionDsl, sources, SCHEMA, generations);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    String exactDigest = sources.digest(LEGACY_TENANT_ID);
    ResolveLegacyAccountTenantAssociationResponse approved = ownerRead(exactDigest);

    assertThatThrownBy(
            () -> inTransaction(transaction, () -> repository.importApproved(42L, approved)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mismatched");
    assertThatThrownBy(
            () ->
                inTransaction(
                    transaction,
                    () ->
                        repository.importApproved(
                            LEGACY_TENANT_ID, ownerRead("sha256:" + "f".repeat(64)))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("differs");
    assertThat(repository.findByLegacyTenantId(LEGACY_TENANT_ID)).isEmpty();
    assertTenantGenerationAbsent(dsl, CANONICAL_TENANT_ID);

    var first =
        inTransaction(transaction, () -> repository.importApproved(LEGACY_TENANT_ID, approved));
    assertThat(first.canonicalTenantId()).isEqualTo(CANONICAL_TENANT_ID);
    assertThat(first.accountEvidenceDigest()).isEqualTo(exactDigest);
    ScopeState initialGeneration =
        inTransaction(
            transaction, () -> generations.read(AuthorityScope.tenant(CANONICAL_TENANT_ID)));
    assertThat(initialGeneration.generation()).isEqualTo(1L);
    assertThat(initialGeneration.sourceVersion()).isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_authority_generations "
                        + "WHERE scope_kind = 'MEMBERSHIP'")
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            inTransaction(transaction, () -> repository.importApproved(LEGACY_TENANT_ID, approved)))
        .isEqualTo(first);
    assertThat(repository.findByLegacyTenantId(LEGACY_TENANT_ID)).contains(first);

    ScopeState advancedGeneration =
        inTransaction(transaction, () -> generations.advance(initialGeneration, null));
    assertThat(advancedGeneration.generation()).isEqualTo(2L);
    assertThat(advancedGeneration.sourceVersion()).isEqualTo(2L);
    assertThat(
            inTransaction(transaction, () -> repository.importApproved(LEGACY_TENANT_ID, approved)))
        .isEqualTo(first);
    assertThat(
            inTransaction(
                transaction, () -> generations.read(AuthorityScope.tenant(CANONICAL_TENANT_ID))))
        .isEqualTo(advancedGeneration);

    ResolveLegacyAccountTenantAssociationResponse conflictingOwnerRead =
        approved.toBuilder().setCanonicalTenantId(CONFLICTING_TENANT_ID.toString()).build();
    assertThatThrownBy(
            () ->
                inTransaction(
                    transaction,
                    () -> repository.importApproved(LEGACY_TENANT_ID, conflictingOwnerRead)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");
    assertTenantGenerationAbsent(dsl, CONFLICTING_TENANT_ID);

    assertThatThrownBy(
            () ->
                inTransaction(
                    transaction,
                    () ->
                        repository.importApproved(
                            42L,
                            ownerRead(42L, CONFLICTING_TENANT_ID, "sha256:" + "a".repeat(64)))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source is absent");
    assertThat(repository.findByLegacyTenantId(42L)).isEmpty();
    assertTenantGenerationAbsent(dsl, CONFLICTING_TENANT_ID);

    proveImportAndGenerationRollback(
        dsl, transaction, repository, sources, ROLLBACK_LEGACY_TENANT_ID);

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE account_approved_legacy_tenant_associations "
                        + "SET canonical_tenant_id = ? WHERE legacy_tenant_id = ?",
                    UUID.randomUUID(),
                    LEGACY_TENANT_ID))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("Approved Account legacy tenant association is immutable");

    dsl.execute(
        "UPDATE account_legacy_tenant_sources SET disposition = 'CONFLICT' "
            + "WHERE account_id = ?",
        accountId);
    assertThatThrownBy(
            () ->
                inTransaction(
                    transaction, () -> repository.importApproved(LEGACY_TENANT_ID, approved)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicting");
    assertThat(repository.findByLegacyTenantId(LEGACY_TENANT_ID)).contains(first);
    assertThat(
            inTransaction(
                transaction, () -> generations.read(AuthorityScope.tenant(CANONICAL_TENANT_ID))))
        .isEqualTo(advancedGeneration);
  }

  private ResolveLegacyAccountTenantAssociationResponse ownerRead(String accountEvidenceDigest) {
    return ownerRead(LEGACY_TENANT_ID, CANONICAL_TENANT_ID, accountEvidenceDigest);
  }

  private ResolveLegacyAccountTenantAssociationResponse ownerRead(
      long legacyTenantId, UUID canonicalTenantId, String accountEvidenceDigest) {
    return ResolveLegacyAccountTenantAssociationResponse.newBuilder()
        .setLegacyAccountTenantId(legacyTenantId)
        .setCanonicalTenantId(canonicalTenantId.toString())
        .setSourceLegacyGameTenantId("legacy-game-7")
        .setSourceGameRowId(7L)
        .setAccountEvidenceDigest(accountEvidenceDigest)
        .setOperationId("11111111-1111-4111-8111-111111111111")
        .setManifestDigest("sha256:" + "b".repeat(64))
        .setManifestSignature(Base64.getEncoder().encodeToString(new byte[64]))
        .setTargetNamespace(SCHEMA)
        .setSignerKeyId("game-design-owner-2026")
        .setApprovedBy("owner@example.test")
        .setApprovalReference("reviewed-change-123")
        .setSignedAt("2026-09-26T00:00:00Z")
        .setOperationEntryCount(1)
        .setManifestSchemaVersion(1)
        .build();
  }

  private long insertRetainedRows(DSLContext dsl, long tenantId, String username) {
    long accountId =
        Objects.requireNonNull(
                (Number)
                    dsl.fetchValue(
                        "INSERT INTO accounts (username, email, password_hash, tenant_id) "
                            + "VALUES (?, ?, 'hash', ?) RETURNING id",
                        username,
                        username + "@example.test",
                        tenantId))
            .longValue();
    dsl.execute(
        "INSERT INTO account_tenant_membership "
            + "(account_id, tenant_id, gameplay_admission_allowed) VALUES (?, ?, TRUE)",
        accountId,
        tenantId);
    dsl.execute("INSERT INTO profiles (account_id, tenant_id) VALUES (?, ?)", accountId, tenantId);
    return accountId;
  }

  private void assertTenantGenerationAbsent(DSLContext dsl, UUID tenantId) {
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_authority_generations "
                        + "WHERE scope_kind = 'TENANT' AND tenant_uuid = ?",
                    tenantId)
                .fetchOne(0, Long.class))
        .isZero();
  }

  private void proveImportAndGenerationRollback(
      DSLContext dsl,
      TransactionTemplate transaction,
      ApprovedLegacyTenantAssociationRepository repository,
      LegacyTenantSourceEvidence sources,
      long legacyTenantId) {
    String evidenceDigest = sources.digest(legacyTenantId);
    dsl.execute(
        "CREATE FUNCTION reject_test_tenant_generation() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN IF NEW.scope_kind = 'TENANT' AND NEW.tenant_uuid = '"
            + ROLLBACK_TENANT_ID
            + "'::uuid THEN RAISE EXCEPTION 'forced tenant generation failure'; END IF; "
            + "RETURN NEW; END; $$");
    dsl.execute(
        "CREATE TRIGGER reject_test_tenant_generation BEFORE INSERT "
            + "ON account_authority_generations FOR EACH ROW "
            + "EXECUTE FUNCTION reject_test_tenant_generation()");
    try {
      assertThatThrownBy(
              () ->
                  inTransaction(
                      transaction,
                      () ->
                          repository.importApproved(
                              legacyTenantId,
                              ownerRead(legacyTenantId, ROLLBACK_TENANT_ID, evidenceDigest))))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("forced tenant generation failure");
      assertThat(repository.findByLegacyTenantId(legacyTenantId)).isEmpty();
      assertTenantGenerationAbsent(dsl, ROLLBACK_TENANT_ID);
    } finally {
      dsl.execute("DROP TRIGGER reject_test_tenant_generation ON account_authority_generations");
      dsl.execute("DROP FUNCTION reject_test_tenant_generation()");
    }
  }

  private <T> T inTransaction(TransactionTemplate transaction, Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }
}
