package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantEntitlementOutboxRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.DemoTenantEntitlementException;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** Physical PostgreSQL proof for exact-source demo entitlement commit and revalidation fencing. */
class AccountDemoTenantEntitlementPersistenceIntegrationTest {
  private static final String TEST_NAMESPACE = "account-service";
  private static AccountPostgresIntegrationFixture postgresFixture;
  private static DriverManagerDataSource rootDataSource;
  private final Set<String> runOwnedSchemas = new java.util.LinkedHashSet<>();

  @BeforeAll
  static void setUpPostgresFixture() {
    postgresFixture = new AccountPostgresIntegrationFixture();
    postgresFixture.start();
    rootDataSource = postgresFixture.dataSource();
  }

  @AfterEach
  void dropOnlyRunOwnedSchemas() {
    JdbcTemplate rootJdbc = new JdbcTemplate(rootDataSource);
    for (String schema : runOwnedSchemas) {
      rootJdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
    runOwnedSchemas.clear();
  }

  @AfterAll
  static void stopPostgresFixture() {
    if (postgresFixture != null) {
      postgresFixture.stop();
    }
  }

  @Test
  void sequenceZeroTenantAuthorityHeadCannotCommit() {
    TestContext context = newTestContext();
    FreshTenantIdentityAssociationRepository identityRepository =
        new FreshTenantIdentityAssociationRepository(context.dsl(), TEST_NAMESPACE);
    AccountAuthorityGenerationRepository generationRepository =
        new AccountAuthorityGenerationRepository(context.dsl());
    AccountTenantEntitlementOutboxRepository billingOutbox =
        new AccountTenantEntitlementOutboxRepository(context.dsl());
    AccountTenantAuthorityEventRepository tenantAuthorityEvents =
        new AccountTenantAuthorityEventRepository(
            context.dsl(),
            new AccountAuthorityOutboxRepository(context.dsl()),
            billingOutbox,
            identityRepository,
            generationRepository);
    UUID tenantId = UUID.fromString("88888888-8888-4888-8888-888888888888");
    UUID requestId = UUID.fromString("88888888-8888-4888-8888-888888888889");
    FreshTenantCreationEvidence source = evidence(tenantId, 805L);
    inTransaction(
        context.transaction(),
        () -> {
          identityRepository.importVerified(source);
          generationRepository.initializeTenantIfAbsent(tenantId);
          return null;
        });

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> {
                      tenantAuthorityEvents.establishFirstEventSource(
                          tenantId,
                          requestId,
                          source,
                          generationRepository.read(
                              AccountAuthorityGenerationRepository.AuthorityScope.tenant(
                                  tenantId)));
                      context
                          .dsl()
                          .execute(
                              "SET CONSTRAINTS account_tenant_authority_stream_consistency IMMEDIATE");
                      return null;
                    }))
        .rootCause()
        .hasMessageContaining(
            "Tenant authority outbox head lacks an Account-owned source checkpoint");
  }

  @Test
  void exactFreshUuidReplayConflictQuotasAndGenerationChangeAreTransactionallyFenced() {
    TestContext context = newTestContext();
    FreshTenantIdentityAssociationRepository identityRepository =
        new FreshTenantIdentityAssociationRepository(context.dsl(), TEST_NAMESPACE);
    AccountAuthorityGenerationRepository generationRepository =
        new AccountAuthorityGenerationRepository(context.dsl());
    AccountTenantEntitlementOutboxRepository outboxRepository =
        new AccountTenantEntitlementOutboxRepository(context.dsl());
    AccountAuthorityOutboxRepository authorityOutbox =
        new AccountAuthorityOutboxRepository(context.dsl());
    AccountTenantAuthorityEventRepository tenantAuthorityEvents =
        new AccountTenantAuthorityEventRepository(
            context.dsl(),
            authorityOutbox,
            outboxRepository,
            identityRepository,
            generationRepository);
    AccountDemoTenantEntitlementRepository entitlements =
        new AccountDemoTenantEntitlementRepository(
            context.dsl(),
            identityRepository,
            generationRepository,
            outboxRepository,
            tenantAuthorityEvents);

    UUID tenantId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    FreshTenantCreationEvidence source = evidence(tenantId, 801L);
    inTransaction(
        context.transaction(),
        () -> {
          identityRepository.importVerified(source);
          generationRepository.initializeTenantIfAbsent(tenantId);
          return null;
        });

    DemoTenantEntitlementRequest create =
        request(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            tenantId,
            source,
            null,
            null,
            null,
            true);
    DemoTenantEntitlementSnapshot first =
        inTransaction(context.transaction(), () -> entitlements.provision(create, source));
    assertThat(first.canonicalTenantId()).isEqualTo(tenantId);
    assertThat(first.sourceEvidence()).isEqualTo(source);
    assertThat(first.entitlementKind()).isEqualTo("NON_PAID_DEMO");
    assertThat(first.status()).isEqualTo("ACTIVE");
    assertThat(first.subscriptionStatus()).isNull();
    assertThat(first.paid()).isFalse();
    assertThat(first.gameplayAvailable()).isTrue();
    assertThat(first.allowPublicJoin()).isFalse();
    assertThat(first.allowNewGameplayBindings()).isFalse();
    assertThat(first.allowNewInstanceStarts()).isTrue();
    assertThat(first.quotas()).isEqualTo(new DemoTenantEntitlementRequest.Quotas(3L, 2L, 4096L));
    assertThat(first.entitlementVersion()).isEqualTo(1L);
    assertThat(first.tenantAuthorityGeneration()).isEqualTo(2L);
    assertThat(first.tenantAuthoritySourceVersion()).isEqualTo(2L);
    assertThat(first.tenantBillingSequence()).isEqualTo(1L);
    assertThat(first.outboxStreamKey())
        .isEqualTo(AccountTenantEntitlementOutboxRepository.streamKey(tenantId));
    assertThat(first.tenantAuthorityOutboxStreamKey())
        .isEqualTo("account:auth-authority:v1:tenant/" + tenantId);
    assertThat(first.tenantAuthorityOutboxSequence()).isEqualTo(1L);
    assertThat(first.tenantAuthorityEventId()).isNotNull();
    assertThat(first.tenantAuthorityEventDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(first.tenantAuthorityEventId()).isNotEqualTo(first.eventId());

    assertThat(inTransaction(context.transaction(), () -> entitlements.provision(create, source)))
        .isEqualTo(first);
    DemoTenantEntitlementSnapshot exactReadback =
        inTransaction(context.transaction(), () -> entitlements.readCurrent(tenantId));
    assertThat(exactReadback).isEqualTo(first);
    var authorityReadback =
        inTransaction(
            context.transaction(), () -> tenantAuthorityEvents.readCurrentByTenant(tenantId));
    assertThat(authorityReadback.eventId()).isEqualTo(first.tenantAuthorityEventId());
    assertThat(authorityReadback.eventDigest()).isEqualTo(first.tenantAuthorityEventDigest());
    assertThat(authorityReadback.outboxSequence()).isEqualTo(first.tenantAuthorityOutboxSequence());

    DemoTenantEntitlementRequest conflictingReplay =
        request(create.requestId(), tenantId, source, null, null, null, false);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(), () -> entitlements.provision(conflictingReplay, source)))
        .isInstanceOf(DemoTenantEntitlementException.class)
        .hasMessageContaining("reused for a different tenant or payload");

    FreshTenantCreationEvidence alteredProvenance = alteredProvenance(source);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(), () -> entitlements.provision(create, alteredProvenance)))
        .isInstanceOf(DemoTenantEntitlementException.class)
        .hasMessageContaining("differs from exact Account UUID readback");

    UUID otherTenantId = UUID.fromString("55555555-5555-4555-8555-555555555555");
    FreshTenantCreationEvidence otherSource = evidence(otherTenantId, 802L);
    inTransaction(
        context.transaction(),
        () -> {
          identityRepository.importVerified(otherSource);
          generationRepository.initializeTenantIfAbsent(otherTenantId);
          return null;
        });
    DemoTenantEntitlementRequest crossTenantReplay =
        request(create.requestId(), otherTenantId, otherSource, null, null, null, true);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> entitlements.provision(crossTenantReplay, otherSource)))
        .isInstanceOf(DemoTenantEntitlementException.class)
        .hasMessageContaining("reused for a different tenant or payload");

    DemoTenantEntitlementRequest wrongSourceTenant =
        request(
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            otherTenantId,
            source,
            null,
            null,
            null,
            true);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(), () -> entitlements.provision(wrongSourceTenant, source)))
        .isInstanceOf(DemoTenantEntitlementException.class)
        .hasMessageContaining(
            "Only exact authenticated fresh Game Design tenant creation evidence");

    DemoTenantEntitlementRequest update =
        request(
            UUID.fromString("88888888-8888-4888-8888-888888888888"),
            tenantId,
            source,
            first.entitlementVersion(),
            first.tenantAuthorityGeneration(),
            first.tenantAuthoritySourceVersion(),
            false);
    DemoTenantEntitlementSnapshot updated =
        inTransaction(context.transaction(), () -> entitlements.provision(update, source));
    assertThat(updated.entitlementVersion()).isEqualTo(2L);
    assertThat(updated.tenantAuthorityGeneration()).isEqualTo(3L);
    assertThat(updated.tenantAuthoritySourceVersion()).isEqualTo(3L);
    assertThat(updated.tenantBillingSequence()).isEqualTo(2L);
    assertThat(updated.tenantAuthorityOutboxSequence()).isEqualTo(2L);
    assertThat(inTransaction(context.transaction(), () -> entitlements.provision(update, source)))
        .isEqualTo(updated);
    assertThatThrownBy(
            () -> inTransaction(context.transaction(), () -> entitlements.revalidate(first)))
        .isInstanceOf(DemoTenantEntitlementException.class)
        .hasMessageContaining("changed after its complete evaluation snapshot");

    Long legacyIdentityColumnCount =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? "
                            + "AND table_name = 'account_demo_tenant_entitlements' "
                            + "AND column_name IN ('tenant_id', 'legacy_tenant_id')",
                        context.schema()),
                "Expected information_schema aggregate row")
            .get(0, Long.class);
    assertThat(legacyIdentityColumnCount).isZero();
    Long outboxEventCount =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "SELECT COUNT(*) FROM account_tenant_entitlement_outbox_events WHERE tenant_uuid = ?",
                        tenantId),
                "Expected tenant entitlement outbox aggregate row")
            .get(0, Long.class);
    assertThat(outboxEventCount).isEqualTo(2L);
    Long authorityEventCount =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "SELECT COUNT(*) FROM account_authority_outbox_events "
                            + "WHERE outbox_stream_key = ?",
                        "account:auth-authority:v1:tenant/" + tenantId),
                "Expected tenant authority outbox aggregate row")
            .get(0, Long.class);
    assertThat(authorityEventCount).isEqualTo(2L);
    Long operationCount =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "SELECT COUNT(*) FROM account_demo_tenant_entitlement_operations WHERE tenant_uuid = ?",
                        tenantId),
                "Expected tenant entitlement operation aggregate row")
            .get(0, Long.class);
    assertThat(operationCount).isEqualTo(2L);

    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> {
                      context
                          .dsl()
                          .execute(
                              "UPDATE account_authority_generations SET generation = generation + 1, "
                                  + "source_version = source_version + 1 WHERE scope_kind = 'TENANT' "
                                  + "AND tenant_uuid = ?",
                              tenantId);
                      return null;
                    }))
        .isInstanceOf(RuntimeException.class);
    assertThat(inTransaction(context.transaction(), () -> entitlements.readCurrent(tenantId)))
        .isEqualTo(updated);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> {
                      context
                          .dsl()
                          .execute(
                              "UPDATE account_demo_tenant_entitlements SET gameplay_available = NOT gameplay_available "
                                  + "WHERE tenant_uuid = ?",
                              tenantId);
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Demo entitlement identity is immutable and version advances by one");
    assertThat(inTransaction(context.transaction(), () -> entitlements.readCurrent(tenantId)))
        .isEqualTo(updated);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> tenantAuthorityEvents.readCurrentByTenant(tenantId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("differs from its exact committed billing event");
  }

  @Test
  void failedOwnerTransactionAndUnmappedGenerationCannotCreateSyntheticAuthorityHistory() {
    TestContext context = newTestContext();
    FreshTenantIdentityAssociationRepository identityRepository =
        new FreshTenantIdentityAssociationRepository(context.dsl(), TEST_NAMESPACE);
    AccountAuthorityGenerationRepository generationRepository =
        new AccountAuthorityGenerationRepository(context.dsl());
    AccountTenantEntitlementOutboxRepository outboxRepository =
        new AccountTenantEntitlementOutboxRepository(context.dsl());
    AccountTenantAuthorityEventRepository tenantAuthorityEvents =
        new AccountTenantAuthorityEventRepository(
            context.dsl(),
            new AccountAuthorityOutboxRepository(context.dsl()),
            outboxRepository,
            identityRepository,
            generationRepository);
    AccountDemoTenantEntitlementRepository entitlements =
        new AccountDemoTenantEntitlementRepository(
            context.dsl(),
            identityRepository,
            generationRepository,
            outboxRepository,
            tenantAuthorityEvents);

    UUID rollbackTenant = UUID.fromString("66666666-6666-4666-8666-666666666666");
    FreshTenantCreationEvidence rollbackSource = evidence(rollbackTenant, 803L);
    inTransaction(
        context.transaction(),
        () -> {
          identityRepository.importVerified(rollbackSource);
          generationRepository.initializeTenantIfAbsent(rollbackTenant);
          return null;
        });
    DemoTenantEntitlementRequest rollbackRequest =
        request(
            UUID.fromString("66666666-6666-4666-8666-666666666667"),
            rollbackTenant,
            rollbackSource,
            null,
            null,
            null,
            true);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> {
                      entitlements.provision(rollbackRequest, rollbackSource);
                      throw new IllegalStateException("force owner transaction rollback");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("force owner transaction rollback");
    assertTenantHasOnlyGenerationOne(context, rollbackTenant);
    assertThat(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT COUNT(*) FROM account_authority_outbox_events "
                                + "WHERE outbox_stream_key = ?",
                            "account:auth-authority:v1:tenant/" + rollbackTenant),
                    "Expected rolled-back tenant authority outbox aggregate row")
                .get(0, Long.class))
        .isZero();
    assertThat(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT COUNT(*) FROM account_tenant_entitlement_outbox_events WHERE tenant_uuid = ?",
                            rollbackTenant),
                    "Expected rolled-back tenant billing outbox aggregate row")
                .get(0, Long.class))
        .isZero();

    UUID retainedTenant = UUID.fromString("77777777-7777-4777-8777-777777777777");
    FreshTenantCreationEvidence retainedSource = evidence(retainedTenant, 804L);
    inTransaction(
        context.transaction(),
        () -> {
          identityRepository.importVerified(retainedSource);
          return null;
        });
    inTransaction(
        context.transaction(),
        () -> {
          context
              .dsl()
              .execute(
                  "INSERT INTO account_authority_generations "
                      + "(scope_kind, issuer_id, account_uuid, tenant_uuid, generation, source_version) "
                      + "VALUES ('TENANT', NULL, NULL, ?, 2, 2)",
                  retainedTenant);
          return null;
        });
    DemoTenantEntitlementRequest retainedRequest =
        request(
            UUID.fromString("77777777-7777-4777-8777-777777777778"),
            retainedTenant,
            retainedSource,
            null,
            null,
            null,
            true);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> entitlements.provision(retainedRequest, retainedSource)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("advanced without canonical source-event evidence");
    assertThat(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT generation FROM account_authority_generations "
                                + "WHERE scope_kind = 'TENANT' AND tenant_uuid = ?",
                            retainedTenant),
                    "Expected retained tenant authority generation row")
                .get(0, Long.class))
        .isEqualTo(2L);
    assertThat(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT COUNT(*) FROM account_fresh_tenant_identity_associations "
                                + "WHERE canonical_tenant_id = ?",
                            retainedTenant),
                    "Expected retained tenant identity association aggregate row")
                .get(0, Long.class))
        .isEqualTo(1L);
  }

  private static void assertTenantHasOnlyGenerationOne(TestContext context, UUID tenantId) {
    assertThat(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT generation FROM account_authority_generations "
                                + "WHERE scope_kind = 'TENANT' AND tenant_uuid = ?",
                            tenantId),
                    "Expected tenant authority generation row")
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT COUNT(*) FROM account_demo_tenant_entitlement_operations "
                                + "WHERE tenant_uuid = ?",
                            tenantId),
                    "Expected tenant entitlement operation aggregate row")
                .get(0, Long.class))
        .isZero();
  }

  private TestContext newTestContext() {
    String schema = "demo_entitlement_" + UUID.randomUUID().toString().replace("-", "");
    runOwnedSchemas.add(schema);
    org.jooq
        .impl
        .DSL
        .using(rootDataSource, SQLDialect.POSTGRES)
        .execute("CREATE SCHEMA \"" + schema + "\"");
    DriverManagerDataSource dataSource = postgresFixture.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new TestContext(
        dsl, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), schema);
  }

  private static DemoTenantEntitlementRequest request(
      UUID requestId,
      UUID tenantId,
      FreshTenantCreationEvidence source,
      Long entitlementVersion,
      Long authorityGeneration,
      Long authoritySourceVersion,
      boolean gameplayAvailable) {
    return new DemoTenantEntitlementRequest(
        requestId,
        tenantId,
        source.creationRequestId(),
        source.requestDigest(),
        entitlementVersion,
        authorityGeneration,
        authoritySourceVersion,
        gameplayAvailable,
        false,
        false,
        true,
        new DemoTenantEntitlementRequest.Quotas(3L, 2L, 4096L));
  }

  private static FreshTenantCreationEvidence evidence(UUID tenantId, long sourceRowId) {
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String digest = "sha256:" + Long.toHexString(sourceRowId % 16L).repeat(64);
    String sourceKey = "demo-source-" + sourceRowId;
    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            TEST_NAMESPACE,
            requestId,
            operationId,
            digest,
            tenantId,
            sourceRowId,
            sourceKey,
            "NEW_GAME_ROW");
    return new FreshTenantCreationEvidence(
        1,
        TEST_NAMESPACE,
        requestId,
        operationId,
        digest,
        tenantId,
        sourceRowId,
        sourceKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static FreshTenantCreationEvidence alteredProvenance(FreshTenantCreationEvidence source) {
    long sourceRowId = source.sourceGameRowId() + 1000L;
    UUID operationId = UUID.randomUUID();
    String sourceKey = "different-source-" + sourceRowId;
    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            source.targetNamespace(),
            source.creationRequestId(),
            operationId,
            source.requestDigest(),
            source.canonicalTenantId(),
            sourceRowId,
            sourceKey,
            source.provenanceKind());
    return new FreshTenantCreationEvidence(
        source.schemaVersion(),
        source.targetNamespace(),
        source.creationRequestId(),
        operationId,
        source.requestDigest(),
        source.canonicalTenantId(),
        sourceRowId,
        sourceKey,
        source.provenanceKind(),
        evidenceDigest);
  }

  private static <T> T inTransaction(
      TransactionTemplate transaction, java.util.function.Supplier<T> action) {
    return transaction.execute(status -> action.get());
  }

  private record TestContext(DSLContext dsl, TransactionTemplate transaction, String schema) {}
}
