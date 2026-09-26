package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class LegacyTenantAssociationImportIntegrationTest {
  private static final String SCHEMA = "account_tenant_association_proof";
  private static final long LEGACY_TENANT_ID = 41L;
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");

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
    long accountId =
        Objects.requireNonNull(
                (Number)
                    dsl.fetchValue(
                        "INSERT INTO accounts (username, email, password_hash, tenant_id) "
                            + "VALUES ('retained-tenant', 'retained@example.test', 'hash', ?) "
                            + "RETURNING id",
                        LEGACY_TENANT_ID))
            .longValue();
    dsl.execute(
        "INSERT INTO account_tenant_membership "
            + "(account_id, tenant_id, gameplay_admission_allowed) VALUES (?, ?, TRUE)",
        accountId,
        LEGACY_TENANT_ID);
    dsl.execute(
        "INSERT INTO profiles (account_id, tenant_id) VALUES (?, ?)", accountId, LEGACY_TENANT_ID);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .placeholders(Map.of("serviceSchema", SCHEMA))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    LegacyTenantSourceEvidence sources = new LegacyTenantSourceEvidence(dsl);
    ApprovedLegacyTenantAssociationRepository repository =
        new ApprovedLegacyTenantAssociationRepository(dsl, sources, SCHEMA);
    String exactDigest = sources.digest(LEGACY_TENANT_ID);
    ResolveLegacyAccountTenantAssociationResponse approved = ownerRead(exactDigest);

    assertThatThrownBy(() -> repository.importApproved(42L, approved))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mismatched");
    assertThatThrownBy(
            () ->
                repository.importApproved(LEGACY_TENANT_ID, ownerRead("sha256:" + "f".repeat(64))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("differs");
    assertThat(repository.findByLegacyTenantId(LEGACY_TENANT_ID)).isEmpty();

    var first = repository.importApproved(LEGACY_TENANT_ID, approved);
    assertThat(first.canonicalTenantId()).isEqualTo(CANONICAL_TENANT_ID);
    assertThat(first.accountEvidenceDigest()).isEqualTo(exactDigest);
    assertThat(repository.importApproved(LEGACY_TENANT_ID, approved)).isEqualTo(first);
    assertThat(repository.findByLegacyTenantId(LEGACY_TENANT_ID)).contains(first);
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
    assertThatThrownBy(() -> repository.importApproved(LEGACY_TENANT_ID, approved))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicting");
    assertThat(repository.findByLegacyTenantId(LEGACY_TENANT_ID)).contains(first);
  }

  private ResolveLegacyAccountTenantAssociationResponse ownerRead(String accountEvidenceDigest) {
    return ResolveLegacyAccountTenantAssociationResponse.newBuilder()
        .setLegacyAccountTenantId(LEGACY_TENANT_ID)
        .setCanonicalTenantId(CANONICAL_TENANT_ID.toString())
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
}
