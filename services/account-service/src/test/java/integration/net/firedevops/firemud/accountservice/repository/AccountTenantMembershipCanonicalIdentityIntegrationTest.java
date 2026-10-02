package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proof of the shared numeric-history/canonical-UUID membership storage boundary.
 *
 * <p>Owner source rows are installed locally to isolate persistence behavior; this is not proof of
 * authenticated remote evidence, JOIN authorization, or membership activation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountTenantMembershipCanonicalIdentityIntegrationTest {
  private static final String TEST_NAMESPACE = "account-service";
  private static final long RETAINED_TENANT_ID = 700L;
  private static final String RETAINED_MANIFEST_DIGEST = "sha256:" + "b".repeat(64);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void migrationPreservesRetainedMembershipAndCanonicalRowsUseExactUuidAndSourceEvidence() {
    Fixture fixture = fixture();
    SeededRetainedMembership retained = seedRetainedMembership(fixture.setupDsl());
    assertPersistedRoleHistory(fixture.setupDsl(), retained.membershipId());
    String retainedDigest =
        new LegacyTenantSourceEvidence(fixture.setupDsl()).digest(RETAINED_TENANT_ID);
    UUID retainedTenantUuid = UUID.randomUUID();
    UUID retainedOperationId = UUID.randomUUID();
    insertApprovedRetainedAssociation(
        fixture.setupDsl(), retainedTenantUuid, retainedOperationId, retainedDigest);

    flyway(fixture.dataSource(), fixture.schema(), "45").migrate();
    RepositoryFixture repositories = repositories(fixture);
    assertPersistedRoleHistory(fixture.setupDsl(), retained.membershipId());
    String identityConstraint =
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conname = 'account_tenant_membership_tenant_identity_check'")
            .fetchOne(0, String.class);
    assertThat(identityConstraint)
        .contains(
            "tenant_id IS NOT NULL",
            "tenant_uuid IS NOT NULL",
            "tenant_provenance_digest IS NOT NULL");

    AccountTenantMembership beforeBinding =
        repositories
            .memberships()
            .findByAccountIdAndTenantId(retained.accountId(), RETAINED_TENANT_ID)
            .orElseThrow();
    assertThat(beforeBinding.getId()).isEqualTo(retained.membershipId());
    assertThat(beforeBinding.getTenantId()).isEqualTo(RETAINED_TENANT_ID);
    assertThat(beforeBinding.getTenantUuid()).isNull();
    assertThat(beforeBinding.getTenantProvenanceKind()).isEqualTo("UNBRIDGED_RETAINED");
    assertThat(beforeBinding.getLifecycleState()).isEqualTo("LEGACY_UNVERIFIED");
    assertThat(beforeBinding.getMembershipVersion()).isEqualTo(1L);
    assertThat(beforeBinding.getMembershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(beforeBinding.getAuthorityProvenance()).isEqualTo("LEGACY_UNVERIFIED");
    assertThat(beforeBinding.isGameplayAdmissionAllowed()).isFalse();
    var retainedNumericRoleHistory =
        fixture
            .transaction()
            .execute(
                status ->
                    repositories
                        .roles()
                        .findForUpdate(
                            retained.accountId(), RETAINED_TENANT_ID, retained.membershipId(), 1L))
            .orElseThrow();
    assertThat(retainedNumericRoleHistory.tenantId()).isEqualTo(RETAINED_TENANT_ID);
    assertThat(retainedNumericRoleHistory.tenantUuid()).isNull();
    assertThat(retainedNumericRoleHistory.roles()).containsExactly("legacy_observer");

    VerifiedTenantProvenance retainedProvenance =
        new VerifiedTenantProvenance(
            RETAINED_TENANT_ID,
            TenantProvenanceKind.APPROVED_RETAINED,
            retainedOperationId,
            RETAINED_MANIFEST_DIGEST);
    AccountTenantMembership retainedReadback =
        fixture
            .transaction()
            .execute(
                status ->
                    repositories
                        .memberships()
                        .saveCanonical(
                            beforeBinding,
                            retained.accountUuid(),
                            retainedTenantUuid,
                            retainedProvenance));
    assertPersistedRoleHistory(fixture.setupDsl(), retained.membershipId());
    assertThat(retainedReadback).isNotNull();
    assertThat(retainedReadback.getId()).isEqualTo(retained.membershipId());
    assertThat(retainedReadback.getTenantId()).isEqualTo(RETAINED_TENANT_ID);
    assertThat(retainedReadback.getTenantUuid()).isEqualTo(retainedTenantUuid);
    assertThat(retainedReadback.getTenantProvenanceKind()).isEqualTo("APPROVED_RETAINED");
    assertThat(retainedReadback.getTenantSourceOperationId()).isEqualTo(retainedOperationId);
    assertThat(retainedReadback.getTenantProvenanceDigest()).isEqualTo(RETAINED_MANIFEST_DIGEST);
    assertThat(retainedReadback.getLifecycleState()).isEqualTo("LEGACY_UNVERIFIED");
    assertThat(retainedReadback.getMembershipVersion()).isEqualTo(1L);
    assertThat(retainedReadback.getMembershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(retainedReadback.getAuthorityProvenance()).isEqualTo("LEGACY_UNVERIFIED");
    assertThat(retainedReadback.isGameplayAdmissionAllowed()).isFalse();

    var retainedCanonicalRoleHistory =
        fixture
            .transaction()
            .execute(
                status ->
                    repositories
                        .roles()
                        .findForCanonicalUpdate(
                            retained.accountUuid(),
                            retainedTenantUuid,
                            retainedProvenance,
                            retained.membershipId(),
                            1L))
            .orElseThrow();
    assertThat(retainedCanonicalRoleHistory.membershipId()).isEqualTo(retained.membershipId());
    assertThat(retainedCanonicalRoleHistory.snapshotVersion()).isEqualTo(1L);
    assertThat(retainedCanonicalRoleHistory.tenantUuid()).isEqualTo(retainedTenantUuid);
    assertThat(retainedCanonicalRoleHistory.tenantProvenance()).isEqualTo(retainedProvenance);
    assertThat(retainedCanonicalRoleHistory.roles()).containsExactly("legacy_observer");

    UUID freshTenantUuid = UUID.randomUUID();
    FreshTenantCreationEvidence freshEvidence = freshTenantEvidence(freshTenantUuid);
    fixture
        .transaction()
        .executeWithoutResult(
            status -> repositories.freshAssociations().importVerified(freshEvidence));
    VerifiedTenantProvenance freshProvenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            freshEvidence.operationId(),
            freshEvidence.evidenceDigest());
    AccountTenantMembership freshMembership = new AccountTenantMembership();
    Account account = new Account();
    account.setId(retained.accountId());
    freshMembership.setAccount(account);
    freshMembership.setGameplayAdmissionAllowed(false);
    freshMembership.setLifecycleState("LEGACY_UNVERIFIED");
    freshMembership.setMembershipVersion(1L);
    freshMembership.setMembershipAuthorityGeneration(1L);
    freshMembership.setAuthorityProvenance("LEGACY_UNVERIFIED");
    AccountTenantMembership freshReadback =
        fixture
            .transaction()
            .execute(
                status ->
                    repositories
                        .memberships()
                        .saveCanonical(
                            freshMembership,
                            retained.accountUuid(),
                            freshTenantUuid,
                            freshProvenance));

    assertThat(freshReadback).isNotNull();
    assertThat(freshReadback.getTenantId()).isNull();
    assertThat(freshReadback.getTenantUuid()).isEqualTo(freshTenantUuid);
    assertThat(freshReadback.getTenantProvenanceKind()).isEqualTo("FRESH_GAME_DESIGN");
    assertThat(freshReadback.getTenantSourceOperationId()).isEqualTo(freshEvidence.operationId());
    assertThat(freshReadback.getTenantProvenanceDigest()).isEqualTo(freshEvidence.evidenceDigest());

    var replacedRoles =
        fixture
            .transaction()
            .execute(
                status ->
                    repositories
                        .roles()
                        .replaceCanonical(
                            freshReadback,
                            retained.accountUuid(),
                            freshTenantUuid,
                            freshProvenance,
                            1L,
                            List.of("observer")));
    assertThat(replacedRoles).isNotNull();
    assertThat(replacedRoles.accountUuid()).isEqualTo(retained.accountUuid());
    assertThat(replacedRoles.tenantUuid()).isEqualTo(freshTenantUuid);
    assertThat(replacedRoles.tenantId()).isNull();
    assertThat(replacedRoles.tenantProvenance()).isEqualTo(freshProvenance);
    assertThat(replacedRoles.roles()).containsExactly("observer");
    Optional<RoleSnapshot> freshRoleReadback =
        fixture
            .transaction()
            .execute(
                status ->
                    repositories
                        .roles()
                        .findForCanonicalUpdate(
                            retained.accountUuid(),
                            freshTenantUuid,
                            freshProvenance,
                            freshReadback.getId(),
                            1L));
    assertThat(freshRoleReadback).contains(replacedRoles);

    assertThat(
            repositories
                .memberships()
                .findByAccountIdAndTenantId(retained.accountId(), freshEvidence.sourceGameRowId()))
        .isEmpty();
    assertThat(repositories.memberships().findByAccountId(retained.accountId()))
        .extracting(AccountTenantMembership::getId)
        .containsExactly(retained.membershipId());
    assertThatThrownBy(() -> repositories.memberships().save(freshReadback))
        .isInstanceOf(IllegalArgumentException.class);

    assertIdentityInsertRejected(
        fixture.setupDsl(),
        retained.accountId(),
        freshTenantUuid,
        freshEvidence.operationId(),
        freshEvidence.evidenceDigest(),
        "FRESH_GAME_DESIGN",
        702L);
    assertIdentityInsertRejected(
        fixture.setupDsl(),
        retained.accountId(),
        freshTenantUuid,
        freshEvidence.operationId(),
        null,
        "FRESH_GAME_DESIGN",
        null);
    assertIdentityInsertRejected(
        fixture.setupDsl(),
        retained.accountId(),
        null,
        freshEvidence.operationId(),
        freshEvidence.evidenceDigest(),
        "FRESH_GAME_DESIGN",
        null);
    assertIdentityInsertRejected(
        fixture.setupDsl(),
        retained.accountId(),
        new UUID(0L, 0L),
        freshEvidence.operationId(),
        freshEvidence.evidenceDigest(),
        "FRESH_GAME_DESIGN",
        null);
    assertIdentityInsertRejected(
        fixture.setupDsl(),
        retained.accountId(),
        retainedTenantUuid,
        retainedOperationId,
        RETAINED_MANIFEST_DIGEST,
        "APPROVED_RETAINED",
        null);
    assertIdentityInsertRejected(
        fixture.setupDsl(), retained.accountId(), null, null, null, "UNRECOGNIZED", 703L);
  }

  private static Fixture fixture() {
    String schema = "membership_identity_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    flyway(dataSource, schema, "44").migrate();

    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    return new Fixture(schema, dataSource, setupDsl, transactionDsl, transaction);
  }

  private static Flyway flyway(
      DriverManagerDataSource dataSource, String schema, String targetVersion) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .target(targetVersion)
        .load();
  }

  private static SeededRetainedMembership seedRetainedMembership(DSLContext dsl) {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    Record account =
        Objects.requireNonNull(
            dsl.fetchOne(
                "INSERT INTO accounts (username, email, password_hash, tenant_id) "
                    + "VALUES (?, ?, ?, ?) RETURNING id, account_uuid",
                "membership-identity-" + suffix,
                "membership-identity-" + suffix + "@example.test",
                "opaque-test-hash",
                RETAINED_TENANT_ID),
            "account insert must return its persisted identity row");
    long accountId = account.get("id", Long.class);
    UUID accountUuid = account.get("account_uuid", UUID.class);
    Long membershipId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO account_tenant_membership "
                        + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
                        + "membership_version, membership_authority_generation, authority_provenance) "
                        + "VALUES (?, ?, FALSE, 'LEGACY_UNVERIFIED', 1, 1, 'LEGACY_UNVERIFIED') "
                        + "RETURNING id",
                    accountId,
                    RETAINED_TENANT_ID)
                .fetchOne(0, Long.class),
            "membership insert must return its persisted id");
    dsl.execute(
        "INSERT INTO account_legacy_tenant_sources "
            + "(account_id, legacy_tenant_id, matching_membership_id, "
            + "matching_membership_admission_allowed, profile_tenant_count, "
            + "matching_profile_count, disposition) "
            + "VALUES (?, ?, ?, FALSE, 0, 0, 'UNVERIFIED')",
        accountId,
        RETAINED_TENANT_ID,
        membershipId);
    dsl.execute(
        "INSERT INTO account_legacy_membership_sources "
            + "(membership_id, account_id, tenant_id, original_gameplay_admission_allowed, "
            + "matches_account_legacy_tenant, disposition) "
            + "VALUES (?, ?, ?, FALSE, TRUE, 'UNVERIFIED')",
        membershipId,
        accountId,
        RETAINED_TENANT_ID);
    dsl.execute(
        "INSERT INTO account_tenant_membership_role_snapshots (membership_id, snapshot_version) "
            + "VALUES (?, 1)",
        membershipId);
    dsl.execute(
        "INSERT INTO account_tenant_membership_role_snapshot_roles "
            + "(membership_id, snapshot_version, role_identifier) VALUES (?, 1, 'legacy_observer')",
        membershipId);
    return new SeededRetainedMembership(accountId, accountUuid, membershipId);
  }

  private static void insertApprovedRetainedAssociation(
      DSLContext dsl, UUID canonicalTenantUuid, UUID operationId, String sourceDigest) {
    dsl.execute(
        "INSERT INTO account_approved_legacy_tenant_associations "
            + "(legacy_tenant_id, canonical_tenant_id, source_legacy_game_tenant_id, "
            + "source_game_row_id, account_evidence_digest, operation_id, manifest_digest, "
            + "manifest_signature, target_namespace, signer_key_id, approved_by, "
            + "approval_reference, signed_at, operation_entry_count, manifest_schema_version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1)",
        RETAINED_TENANT_ID,
        canonicalTenantUuid,
        "legacy-game-tenant-700",
        RETAINED_TENANT_ID,
        sourceDigest,
        operationId,
        RETAINED_MANIFEST_DIGEST,
        Base64.getEncoder().encodeToString(new byte[64]),
        TEST_NAMESPACE,
        "fixture-signing-key",
        "fixture-operator",
        "membership-identity-fixture",
        "2026-10-03T00:00:00Z");
  }

  private static RepositoryFixture repositories(Fixture fixture) {
    AccountRepository accounts = new AccountRepository(fixture.transactionDsl());
    LegacyTenantSourceEvidence sourceEvidence =
        new LegacyTenantSourceEvidence(fixture.transactionDsl());
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(fixture.transactionDsl());
    ApprovedLegacyTenantAssociationRepository approved =
        new ApprovedLegacyTenantAssociationRepository(
            fixture.transactionDsl(), sourceEvidence, TEST_NAMESPACE, generations);
    AccountTenantIdentityResolver resolver =
        new AccountTenantIdentityResolver(approved, sourceEvidence, TEST_NAMESPACE);
    FreshTenantIdentityAssociationRepository fresh =
        new FreshTenantIdentityAssociationRepository(fixture.transactionDsl(), TEST_NAMESPACE);
    return new RepositoryFixture(
        new AccountTenantMembershipRepository(fixture.transactionDsl(), accounts, resolver, fresh),
        new AccountTenantMembershipRoleSnapshotRepository(fixture.transactionDsl()),
        fresh);
  }

  private static FreshTenantCreationEvidence freshTenantEvidence(UUID canonicalTenantUuid) {
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String sourceTenantKey = "fresh-" + UUID.randomUUID().toString().replace("-", "");
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            TEST_NAMESPACE, requestId, sourceTenantKey, "Fixture tenant", null);
    long sourceGameRowId = 701L;
    String sourceKind = "NEW_GAME_ROW";
    return new FreshTenantCreationEvidence(
        1,
        TEST_NAMESPACE,
        requestId,
        operationId,
        requestDigest,
        canonicalTenantUuid,
        sourceGameRowId,
        sourceTenantKey,
        sourceKind,
        GameTenantCreationDigest.evidenceDigest(
            TEST_NAMESPACE,
            requestId,
            operationId,
            requestDigest,
            canonicalTenantUuid,
            sourceGameRowId,
            sourceTenantKey,
            sourceKind));
  }

  private static void assertIdentityInsertRejected(
      DSLContext dsl,
      long accountId,
      UUID tenantUuid,
      UUID operationId,
      String digest,
      String kind,
      Long tenantId) {
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO account_tenant_membership "
                        + "(account_id, tenant_id, tenant_uuid, tenant_provenance_kind, "
                        + "tenant_source_operation_id, tenant_provenance_digest, "
                        + "gameplay_admission_allowed, lifecycle_state, membership_version, "
                        + "membership_authority_generation, authority_provenance) "
                        + "VALUES (?, ?, ?, ?, ?, ?, FALSE, 'LEGACY_UNVERIFIED', 1, 1, "
                        + "'LEGACY_UNVERIFIED')",
                    accountId,
                    tenantId,
                    tenantUuid,
                    kind,
                    operationId,
                    digest))
        .isInstanceOf(DataAccessException.class);
  }

  private static void assertPersistedRoleHistory(DSLContext dsl, long membershipId) {
    Record header =
        dsl.fetchOne(
            "SELECT membership_id, snapshot_version "
                + "FROM account_tenant_membership_role_snapshots WHERE membership_id = ?",
            membershipId);
    assertThat(header).isNotNull();
    assertThat(header.get("membership_id", Long.class)).isEqualTo(membershipId);
    assertThat(header.get("snapshot_version", Long.class)).isEqualTo(1L);

    List<byte[]> roleBytes =
        dsl.fetch(
                "SELECT convert_to(role_identifier, 'UTF8') AS role_identifier_bytes "
                    + "FROM account_tenant_membership_role_snapshot_roles "
                    + "WHERE membership_id = ? AND snapshot_version = 1 "
                    + "ORDER BY convert_to(role_identifier, 'UTF8')",
                membershipId)
            .map(row -> row.get("role_identifier_bytes", byte[].class));
    assertThat(roleBytes).hasSize(1);
    assertThat(roleBytes.get(0))
        .containsExactly("legacy_observer".getBytes(StandardCharsets.UTF_8));
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext setupDsl,
      DSLContext transactionDsl,
      TransactionTemplate transaction) {}

  private record SeededRetainedMembership(long accountId, UUID accountUuid, long membershipId) {}

  private record RepositoryFixture(
      AccountTenantMembershipRepository memberships,
      AccountTenantMembershipRoleSnapshotRepository roles,
      FreshTenantIdentityAssociationRepository freshAssociations) {}
}
