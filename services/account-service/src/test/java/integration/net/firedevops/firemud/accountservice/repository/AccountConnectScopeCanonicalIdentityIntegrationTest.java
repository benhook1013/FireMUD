package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.VerifiedJoinScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository.CanonicalConnectScopeEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof of the shared V1/V2 scope evidence storage boundary. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountConnectScopeCanonicalIdentityIntegrationTest {
  private static final String TEST_NAMESPACE = "account-service";
  private static final long RETAINED_TENANT_ID = 700L;
  private static final String RETAINED_MANIFEST_DIGEST = "sha256:" + "b".repeat(64);
  private static final String V1_TOKEN = "retained-numeric-scope-token";
  private static final String V1_SCOPE_SELECT =
      "SELECT scope_token_hash, account_id, target_class, tenant_id, realm_id, world_slug, "
          + "realm_slug, playable_state_namespace_id, playable_state_scope, game_instance_id, "
          + "catalog_revision, pointer_version, evaluated_at, connect_scope_expires_at, "
          + "playtest_lifecycle_id, playtest_state_generation, snapshot_digest, created_at "
          + "FROM account_connect_scope_records WHERE scope_token_hash = ?";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void v2ScopesPreserveV1AndRequireExactUuidSourcesWithoutBearerOrNumericAliases() {
    Fixture fixture = fixture();
    SeededRetainedIdentity retained = seedRetainedIdentity(fixture.setupDsl());
    AccountConnectScopeRepository scopes = scopeRepository(fixture).scopes();
    VerifiedJoinScope v1Scope = retainedV1Scope(retained.accountId());
    scopes.insert(v1Scope);
    Record v1Before =
        fixture.setupDsl().fetchOne(V1_SCOPE_SELECT, AccountJoinDigest.tokenHash(V1_TOKEN));
    assertThat(v1Before).isNotNull();

    String retainedSourceDigest =
        new LegacyTenantSourceEvidence(fixture.setupDsl()).digest(RETAINED_TENANT_ID);
    UUID retainedTenantUuid = UUID.randomUUID();
    UUID retainedOperationId = UUID.randomUUID();
    insertApprovedRetainedAssociation(
        fixture.setupDsl(), retainedTenantUuid, retainedOperationId, retainedSourceDigest);

    UUID freshTenantUuid = UUID.randomUUID();
    FreshTenantCreationEvidence freshAssociation = freshTenantEvidence(freshTenantUuid);
    fixture
        .transaction()
        .executeWithoutResult(
            status ->
                scopeRepository(fixture).freshTenantIdentities().importVerified(freshAssociation));

    flyway(fixture.dataSource(), fixture.schema(), "46").migrate();

    Record v1After =
        fixture.setupDsl().fetchOne(V1_SCOPE_SELECT, AccountJoinDigest.tokenHash(V1_TOKEN));
    assertThat(v1After.intoMap()).containsExactlyEntriesOf(v1Before.intoMap());
    Integer v1ScopeDigestVersion =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .resultQuery(
                    "SELECT scope_digest_version FROM account_connect_scope_records "
                        + "WHERE scope_token_hash = ?",
                    AccountJoinDigest.tokenHash(V1_TOKEN))
                .fetchOne(0, Integer.class),
            "Retained scope digest version must be persisted");
    assertThat(v1ScopeDigestVersion).isEqualTo(1);

    CanonicalConnectScopeRepositoryFixture canonical = scopeRepository(fixture);
    VerifiedTenantProvenance retainedProvenance =
        new VerifiedTenantProvenance(
            RETAINED_TENANT_ID,
            TenantProvenanceKind.APPROVED_RETAINED,
            retainedOperationId,
            RETAINED_MANIFEST_DIGEST);
    CanonicalJoinScopeV2 retainedScope =
        v2Scope(
            "retained-uuid-scope-token",
            retained.accountUuid(),
            retainedTenantUuid,
            "retained-tenant");
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              canonical
                  .scopes()
                  .insertCanonical(retained.accountId(), retainedScope, retainedProvenance);
              canonical
                  .scopes()
                  .insertCanonical(retained.accountId(), retainedScope, retainedProvenance);
            });

    CanonicalConnectScopeEvidence retainedEvidence =
        fixture
            .transaction()
            .execute(
                status ->
                    canonical
                        .scopes()
                        .findCanonicalEvidenceByTokenHash(
                            AccountJoinDigest.tokenHash(retainedScope.connectScopeId()))
                        .orElseThrow());
    Optional<CanonicalJoinScopeV2> retainedBearerReadback =
        fixture
            .transaction()
            .execute(status -> canonical.scopes().findCanonical(retainedScope.connectScopeId()));
    assertThat(retainedBearerReadback).contains(retainedScope);
    assertThat(retainedEvidence.scopeTokenHash())
        .isEqualTo(AccountJoinDigest.tokenHash(retainedScope.connectScopeId()));
    assertThat(retainedEvidence.privateAccountId()).isEqualTo(retained.accountId());
    assertThat(retainedEvidence.accountUuid()).isEqualTo(retained.accountUuid());
    assertThat(retainedEvidence.tenantUuid()).isEqualTo(retainedTenantUuid);
    assertThat(retainedEvidence.tenantProvenance()).isEqualTo(retainedProvenance);
    assertThat(retainedEvidence.scopeDigestVersion()).isEqualTo(2);
    assertThat(retainedEvidence.scopeDigest()).isEqualTo(AccountJoinDigest.scopeV2(retainedScope));
    assertThat(retainedEvidence.evaluatedAt()).isEqualTo(retainedScope.evaluatedAt());
    assertThat(retainedEvidence.connectScopeExpiresAt())
        .isEqualTo(retainedScope.connectScopeExpiresAt());
    assertThat(retainedEvidence.scopeDigest()).contains("sha256:");
    Record retainedNumericAliases =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT tenant_id, game_instance_id, playable_state_namespace_id "
                        + "FROM account_connect_scope_records WHERE scope_token_hash = ?",
                    retainedEvidence.scopeTokenHash()),
            "Canonical scope row must be persisted for alias inspection");
    assertThat(retainedNumericAliases.intoMap())
        .containsEntry("tenant_id", null)
        .containsEntry("game_instance_id", null)
        .containsEntry("playable_state_namespace_id", null);
    Long plaintextBearerColumnCount =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .resultQuery(
                    "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'account_connect_scope_records' "
                        + "AND column_name = 'connect_scope_id'",
                    fixture.schema())
                .fetchOne(0, Long.class),
            "Information-schema column count must be returned");
    assertThat(plaintextBearerColumnCount).isZero();

    VerifiedTenantProvenance freshProvenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            freshAssociation.operationId(),
            freshAssociation.evidenceDigest());
    CanonicalJoinScopeV2 freshScope =
        v2Scope("fresh-uuid-scope-token", retained.accountUuid(), freshTenantUuid, "fresh-tenant");
    fixture
        .transaction()
        .executeWithoutResult(
            status ->
                canonical
                    .scopes()
                    .insertCanonical(retained.accountId(), freshScope, freshProvenance));
    Optional<CanonicalJoinScopeV2> freshReadback =
        fixture
            .transaction()
            .execute(status -> canonical.scopes().findCanonical(freshScope.connectScopeId()));
    assertThat(freshReadback).contains(freshScope);

    CanonicalJoinScopeV2 conflictingRetry =
        new CanonicalJoinScopeV2(
            retainedScope.connectScopeId(),
            retainedScope.accountId(),
            retainedScope.tenantId(),
            retainedScope.realmId(),
            retainedScope.tenantSlug(),
            "changed-world",
            retainedScope.realmSlug(),
            retainedScope.playableStateNamespaceId(),
            retainedScope.playableStateScope(),
            retainedScope.gameInstanceId(),
            retainedScope.catalogRevision(),
            retainedScope.pointerVersion(),
            retainedScope.evaluatedAt(),
            retainedScope.connectScopeExpiresAt());
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            canonical
                                .scopes()
                                .insertCanonical(
                                    retained.accountId(), conflictingRetry, retainedProvenance)))
        .isInstanceOf(AccountConnectScopeRepository.CanonicalScopeConflictException.class);

    UUID otherAccountUuid = insertAccount(fixture.setupDsl(), "scope-other");
    long otherAccountId =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .resultQuery("SELECT id FROM accounts WHERE account_uuid = ?", otherAccountUuid)
                .fetchOne(0, Long.class),
            "Secondary Account insert must retain its private row key");
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            canonical
                                .scopes()
                                .insertCanonical(
                                    otherAccountId, retainedScope, retainedProvenance)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Canonical JOIN scope does not match the locked persisted Account identity");

    CanonicalJoinScopeV2 wrongTenant =
        v2Scope(
            "wrong-tenant-scope-token", retained.accountUuid(), UUID.randomUUID(), "wrong-tenant");
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            canonical
                                .scopes()
                                .insertCanonical(
                                    retained.accountId(), wrongTenant, retainedProvenance)))
        .isInstanceOf(IllegalStateException.class);

    VerifiedTenantProvenance wrongSource =
        new VerifiedTenantProvenance(
            RETAINED_TENANT_ID,
            TenantProvenanceKind.APPROVED_RETAINED,
            UUID.randomUUID(),
            RETAINED_MANIFEST_DIGEST);
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            canonical
                                .scopes()
                                .insertCanonical(retained.accountId(), retainedScope, wrongSource)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Canonical JOIN scope retained tenant provenance differs from owner readback");

    CanonicalJoinScopeV2 badOrdering =
        new CanonicalJoinScopeV2(
            "bad-ordering-scope-token",
            retained.accountUuid(),
            retainedTenantUuid,
            retainedScope.realmId(),
            retainedScope.tenantSlug(),
            retainedScope.worldSlug(),
            retainedScope.realmSlug(),
            retainedScope.playableStateNamespaceId(),
            retainedScope.playableStateScope(),
            retainedScope.gameInstanceId(),
            retainedScope.catalogRevision(),
            retainedScope.pointerVersion(),
            "2026-10-03T00:00:00.1234567891Z",
            "2026-10-03T00:00:00.1234567890Z");
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            canonical
                                .scopes()
                                .insertCanonical(
                                    retained.accountId(), badOrdering, retainedProvenance)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Canonical JOIN scope expiry must be after its exact evaluation time");

    CanonicalJoinScopeV2 badDigestScope =
        v2Scope(
            "wrong-digest-scope-token",
            retained.accountUuid(),
            retainedTenantUuid,
            "retained-tenant");
    String incorrectDigest = "sha256:" + "0".repeat(64);
    fixture
        .transaction()
        .executeWithoutResult(
            status ->
                insertRawCanonicalScope(
                    fixture.transactionDsl(),
                    retained.accountId(),
                    badDigestScope,
                    retainedProvenance,
                    incorrectDigest));
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status ->
                            canonical.scopes().findCanonical(badDigestScope.connectScopeId())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Canonical JOIN scope digest mismatch");

    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .transactionDsl()
                                .execute(
                                    "UPDATE account_connect_scope_records SET snapshot_digest = ? "
                                        + "WHERE scope_token_hash = ?",
                                    "sha256:" + "c".repeat(64),
                                    retainedEvidence.scopeTokenHash())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .transactionDsl()
                                .execute(
                                    "UPDATE account_connect_scope_records SET scope_digest_version = 1 "
                                        + "WHERE scope_token_hash = ?",
                                    retainedEvidence.scopeTokenHash())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .transactionDsl()
                                .execute(
                                    "DELETE FROM account_connect_scope_records "
                                        + "WHERE scope_token_hash = ?",
                                    retainedEvidence.scopeTokenHash())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .transactionDsl()
                                .execute("TRUNCATE account_connect_scope_records")))
        .isInstanceOf(DataAccessException.class);

    assertAliasInsertRejected(
        fixture.setupDsl(),
        retainedEvidence.scopeTokenHash(),
        AccountJoinDigest.tokenHash("numeric-tenant-alias-candidate"),
        RETAINED_TENANT_ID);
    assertThatThrownBy(
            () ->
                fixture.transaction().execute(status -> canonical.scopes().findCanonical(V1_TOKEN)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("JOIN scope is not canonical digest version 2");
    assertThatThrownBy(() -> canonical.scopes().find(V2_TOKEN_FOR_RETAINED_SCOPE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("JOIN scope is not retained digest version 1");
    assertThatThrownBy(
            () -> canonical.scopes().findEvidenceByTokenHash(retainedEvidence.scopeTokenHash()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("JOIN scope is not retained digest version 1");

    Optional<CanonicalJoinScopeV2> originalRetainedReadback =
        fixture
            .transaction()
            .execute(status -> canonical.scopes().findCanonical(retainedScope.connectScopeId()));
    assertThat(originalRetainedReadback).contains(retainedScope);
    Long retainedScopeRowCount =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .resultQuery(
                    "SELECT COUNT(*) FROM account_connect_scope_records "
                        + "WHERE scope_token_hash = ?",
                    retainedEvidence.scopeTokenHash())
                .fetchOne(0, Long.class),
            "Retained scope row count must be returned");
    assertThat(retainedScopeRowCount).isEqualTo(1L);
  }

  @Test
  void canonicalJoinJournalSharesRequestIdsAndRetainsPendingHashOnlyIntentEvidence() {
    Fixture fixture = fixture();
    SeededRetainedIdentity account = seedRetainedIdentity(fixture.setupDsl());
    UUID tenantUuid = UUID.randomUUID();
    FreshTenantCreationEvidence tenantEvidence = freshTenantEvidence(tenantUuid);
    fixture
        .transaction()
        .executeWithoutResult(
            status ->
                scopeRepository(fixture)
                    .freshTenantIdentities()
                    .importVerified(tenantEvidence));
    flyway(fixture.dataSource(), fixture.schema(), "46").migrate();
    flyway(fixture.dataSource(), fixture.schema(), "47").migrate();

    AccountConnectScopeRepository scopes = scopeRepository(fixture).scopes();
    VerifiedTenantProvenance tenantProvenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            tenantEvidence.operationId(),
            tenantEvidence.evidenceDigest());
    CanonicalJoinScopeV2 scope =
        v2Scope("canonical-join-scope-token", account.accountUuid(), tenantUuid, "join-tenant");
    fixture
        .transaction()
        .executeWithoutResult(
            status -> scopes.insertCanonical(account.accountId(), scope, tenantProvenance));
    AccountJoinOperationRepository operations =
        new AccountJoinOperationRepository(fixture.transactionDsl(), scopes);

    String requestId = "canonical-join-lost-ack";
    String callerBinding = "verified-bootstrap-caller";
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          operations.insertCanonicalIntent(requestId, scope, callerBinding);
                          TransactionSynchronizationManager.registerSynchronization(
                              new TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                  throw new IllegalStateException(
                                      "simulated lost canonical JOIN intent acknowledgement");
                                }
                              });
                        }))
        .hasMessage("simulated lost canonical JOIN intent acknowledgement");
    assertThat(
            fixture
                .transaction()
                .execute(status -> operations.insertCanonicalIntent(requestId, scope, callerBinding)))
        .isFalse();

    CanonicalJoinOperationEvidence pending =
        fixture
            .transaction()
            .execute(status -> operations.findCanonicalEvidenceByRequestId(requestId))
            .orElseThrow();
    assertThat(pending.status()).isEqualTo("PENDING");
    assertThat(pending.operationRepresentationVersion()).isEqualTo(2);
    assertThat(pending.scopeDigestVersion()).isEqualTo(2);
    assertThat(pending.intentDigestVersion()).isEqualTo(2);
    assertThat(pending.intentDigest())
        .isEqualTo(AccountJoinDigest.intentV2(requestId, scope, callerBinding));
    assertThat(pending.entitlementAuthorityAvailability()).isEqualTo("NOT_EVALUATED");
    assertThat(pending.allowPublicJoin()).isNull();
    assertThat(pending.entitlementVersion()).isNull();
    assertThat(pending.requestDigestVersion()).isNull();
    assertThat(pending.requestDigest()).isNull();
    assertThat(pending.toString()).doesNotContain(scope.connectScopeId());
    Record aliases =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT tenant_id, game_instance_id, playable_state_namespace_id, "
                        + "tenant_uuid, game_instance_uuid, playable_state_namespace_uuid "
                        + "FROM account_join_operations WHERE request_id = ?",
                    requestId),
            "Canonical JOIN operation must be persisted for alias inspection");
    assertThat(aliases.get("tenant_id", Long.class)).isNull();
    assertThat(aliases.get("game_instance_id", Long.class)).isNull();
    assertThat(aliases.get("playable_state_namespace_id", String.class)).isNull();
    assertThat(aliases.get("tenant_uuid", UUID.class)).isEqualTo(tenantUuid);
    assertThat(aliases.get("game_instance_uuid", UUID.class))
        .isEqualTo(scope.gameInstanceId());
    assertThat(aliases.get("playable_state_namespace_uuid", UUID.class))
        .isEqualTo(scope.playableStateNamespaceId());

    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(status -> operations.insertCanonicalIntent(requestId, scope, "other-caller")))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class);
    CanonicalJoinScopeV2 changedTarget =
        new CanonicalJoinScopeV2(
            "canonical-join-other-scope-token",
            scope.accountId(),
            scope.tenantId(),
            scope.realmId(),
            scope.tenantSlug(),
            "other-world",
            scope.realmSlug(),
            scope.playableStateNamespaceId(),
            scope.playableStateScope(),
            scope.gameInstanceId(),
            scope.catalogRevision(),
            scope.pointerVersion(),
            scope.evaluatedAt(),
            scope.connectScopeExpiresAt());
    fixture
        .transaction()
        .executeWithoutResult(
            status -> scopes.insertCanonical(account.accountId(), changedTarget, tenantProvenance));
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status ->
                            operations.insertCanonicalIntent(
                                requestId, changedTarget, callerBinding)))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class);

    String rollbackRequestId = "canonical-join-rollback";
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          operations.insertCanonicalIntent(rollbackRequestId, scope, callerBinding);
                          throw new IllegalStateException("simulated canonical JOIN rollback");
                        }))
        .hasMessage("simulated canonical JOIN rollback");
    assertThat(
            fixture
                .transaction()
                .execute(status -> operations.findCanonicalEvidenceByRequestId(rollbackRequestId)))
        .isEmpty();
    assertThat(
            fixture
                .transaction()
                .execute(
                    status -> operations.insertCanonicalIntent(rollbackRequestId, scope, callerBinding)))
        .isTrue();

    CanonicalJoinOperationEvidence policyBound =
        fixture
            .transaction()
            .execute(
                status -> operations.bindCanonicalPolicyEvidence(requestId, scope, callerBinding, true, 9L));
    String expectedPolicyDigest =
        AccountJoinDigest.requestV2(
            scope,
            callerBinding,
            AccountJoinDigest.EntitlementAvailabilityV2.AVAILABLE,
            true,
            9L);
    assertThat(policyBound.entitlementAuthorityAvailability()).isEqualTo("AVAILABLE");
    assertThat(policyBound.allowPublicJoin()).isTrue();
    assertThat(policyBound.entitlementVersion()).isEqualTo(9L);
    assertThat(policyBound.requestDigestVersion()).isEqualTo(2);
    assertThat(policyBound.requestDigest()).isEqualTo(expectedPolicyDigest);
    assertThat(
            fixture
                .transaction()
                .execute(
                    status ->
                        operations.bindCanonicalPolicyEvidence(
                            requestId, scope, callerBinding, true, 9L)))
        .isEqualTo(policyBound);
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status ->
                            operations.bindCanonicalPolicyEvidence(
                                requestId, scope, callerBinding, false, 9L)))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status ->
                            operations.bindCanonicalPolicyEvidence(
                                requestId, scope, callerBinding, true, 10L)))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status ->
                            operations.recordCanonicalPolicyUnavailable(
                                requestId, scope, callerBinding, "ENTITLEMENT_UNAVAILABLE")))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class);

    String unavailableRequestId = "canonical-join-policy-unavailable";
    fixture
        .transaction()
        .executeWithoutResult(
            status -> operations.insertCanonicalIntent(unavailableRequestId, scope, callerBinding));
    CanonicalJoinOperationEvidence unavailable =
        fixture
            .transaction()
            .execute(
                status ->
                    operations.recordCanonicalPolicyUnavailable(
                        unavailableRequestId, scope, callerBinding, "ENTITLEMENT_UNAVAILABLE"));
    assertThat(unavailable.status()).isEqualTo("PENDING");
    assertThat(unavailable.entitlementAuthorityAvailability()).isEqualTo("UNAVAILABLE");
    assertThat(unavailable.lastAttemptAuthorityAvailability()).isEqualTo("UNAVAILABLE");
    assertThat(unavailable.lastAttemptFailureCode()).isEqualTo("ENTITLEMENT_UNAVAILABLE");
    assertThat(unavailable.allowPublicJoin()).isNull();
    assertThat(unavailable.entitlementVersion()).isNull();
    assertThat(unavailable.requestDigestVersion()).isNull();
    assertThat(unavailable.requestDigest()).isNull();

    String policyRollbackRequestId = "canonical-join-policy-rollback";
    fixture
        .transaction()
        .executeWithoutResult(
            status -> operations.insertCanonicalIntent(policyRollbackRequestId, scope, callerBinding));
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          operations.bindCanonicalPolicyEvidence(
                              policyRollbackRequestId, scope, callerBinding, true, 11L);
                          throw new IllegalStateException("simulated policy binding rollback");
                        }))
        .hasMessage("simulated policy binding rollback");
    CanonicalJoinOperationEvidence afterPolicyRollback =
        fixture
            .transaction()
            .execute(
                status ->
                    operations.findCanonicalEvidenceByRequestId(policyRollbackRequestId))
            .orElseThrow();
    assertThat(afterPolicyRollback.entitlementAuthorityAvailability()).isEqualTo("NOT_EVALUATED");
    assertThat(afterPolicyRollback.entitlementVersion()).isNull();
    assertThat(afterPolicyRollback.requestDigest()).isNull();

    String rawGuardSourceId = "canonical-join-raw-guard-source";
    fixture
        .transaction()
        .executeWithoutResult(
            status -> operations.insertCanonicalIntent(rawGuardSourceId, scope, callerBinding));
    assertRawCanonicalInsertRejected(
        fixture.setupDsl(), rawGuardSourceId, "canonical-join-raw-invalidated", true, "PENDING", null);
    assertRawCanonicalInsertRejected(
        fixture.setupDsl(),
        rawGuardSourceId,
        "canonical-join-raw-terminal",
        false,
        "FAILED",
        "DENIED");
    assertRawCanonicalRequiredSlotRejected(
        fixture.setupDsl(), rawGuardSourceId, "canonical-join-raw-missing-target", "target_class");
    assertRawCanonicalRequiredSlotRejected(
        fixture.setupDsl(), rawGuardSourceId, "canonical-join-raw-missing-account", "account_uuid");
    assertRawCanonicalRequiredSlotRejected(
        fixture.setupDsl(), rawGuardSourceId, "canonical-join-raw-missing-tenant", "tenant_uuid");
    assertRawCanonicalRequiredSlotRejected(
        fixture.setupDsl(), rawGuardSourceId, "canonical-join-raw-missing-tenant-slug", "tenant_slug");
    assertRawCanonicalRequiredSlotRejected(
        fixture.setupDsl(),
        rawGuardSourceId,
        "canonical-join-raw-missing-namespace",
        "playable_state_namespace_uuid");
    assertRawCanonicalRequiredSlotRejected(
        fixture.setupDsl(), rawGuardSourceId, "canonical-join-raw-missing-instance", "game_instance_uuid");
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_join_operations SET operation_representation_version = 3 "
                            + "WHERE request_id = ?",
                        requestId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> fixture.setupDsl().execute("DELETE FROM account_join_operations WHERE request_id = ?", requestId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> fixture.setupDsl().execute("TRUNCATE TABLE account_join_operations"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_join_operations SET verified_caller_binding = ? "
                            + "WHERE request_id = ?",
                        "raw-changed-caller",
                        requestId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_join_operations SET allow_public_join = FALSE "
                            + "WHERE request_id = ?",
                        requestId))
        .isInstanceOf(DataAccessException.class);

    VerifiedJoinScope v1Scope = retainedV1Scope(account.accountId());
    scopes.insert(v1Scope);
    String v1CollisionRequestId = "shared-request-id-v1-first";
    fixture
        .transaction()
        .executeWithoutResult(
            status ->
                operations.insertIntent(
                    v1CollisionRequestId,
                    v1Scope,
                    "legacy-caller",
                    AccountJoinDigest.intent(
                        v1CollisionRequestId, v1Scope, "legacy-caller")));
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status ->
                            operations.insertCanonicalIntent(
                                v1CollisionRequestId, scope, callerBinding)))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class);
    String v2CollisionRequestId = "shared-request-id-v2-first";
    fixture
        .transaction()
        .executeWithoutResult(
            status -> operations.insertCanonicalIntent(v2CollisionRequestId, scope, callerBinding));
    assertThat(
            fixture
                .transaction()
                .execute(
                    status ->
                        operations.insertIntent(
                            v2CollisionRequestId,
                            v1Scope,
                            "legacy-caller",
                            AccountJoinDigest.intent(
                                v2CollisionRequestId, v1Scope, "legacy-caller"))))
        .isFalse();
    assertThat(operations.find(v2CollisionRequestId)).isEmpty();
    assertThat(
            fixture
                .transaction()
                .execute(status -> operations.findCanonicalEvidenceByRequestId(v2CollisionRequestId)))
        .isPresent();
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status ->
                            operations.findCanonicalEvidenceByRequestId(v1CollisionRequestId)))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_join_operations SET operation_representation_version = 2 "
                            + "WHERE request_id = ?",
                        v1CollisionRequestId))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            operations.bindPolicyEvidence(
                                requestId, "sha256:" + "a".repeat(64), 1L, true)))
        .hasMessage("JOIN operation changed before policy binding");
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            operations.recordAttemptFailure(
                                requestId, "UNAVAILABLE", "LEGACY_FAILURE")))
        .hasMessage("JOIN operation changed while recording attempt failure");
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            operations.finish(requestId, "COMMITTED", "JOINED", 1L, 1L, 1L)))
        .hasMessage("JOIN operation changed concurrently");
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status -> operations.recordCallerBoundAuthorityInvalidation(requestId)))
        .hasMessage("JOIN operation changed concurrently");
    Instant attemptedAt = Instant.now();
    assertThat(
            operations.recordReconciliationAttempt(
                requestId,
                0,
                1,
                attemptedAt,
                "legacy selector guard",
                attemptedAt.plusSeconds(1)))
        .isFalse();
    assertThat(operations.find(requestId)).isEmpty();
    assertThat(operations.findForUpdate(requestId)).isEmpty();
    assertThat(
            operations.findDuePendingReconciliation(attemptedAt.plusSeconds(1), 100, 1))
        .noneMatch(operation -> operation.requestId().equals(requestId));
  }

  @Test
  void concurrentCanonicalIntentAndPolicyRetriesRecoverOneImmutableRequestRow() throws Exception {
    Fixture fixture = fixture();
    SeededRetainedIdentity account = seedRetainedIdentity(fixture.setupDsl());
    UUID tenantUuid = UUID.randomUUID();
    FreshTenantCreationEvidence tenantEvidence = freshTenantEvidence(tenantUuid);
    fixture
        .transaction()
        .executeWithoutResult(
            status ->
                scopeRepository(fixture)
                    .freshTenantIdentities()
                    .importVerified(tenantEvidence));
    flyway(fixture.dataSource(), fixture.schema(), "46").migrate();
    flyway(fixture.dataSource(), fixture.schema(), "47").migrate();

    AccountConnectScopeRepository scopes = scopeRepository(fixture).scopes();
    VerifiedTenantProvenance tenantProvenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            tenantEvidence.operationId(),
            tenantEvidence.evidenceDigest());
    CanonicalJoinScopeV2 scope =
        v2Scope("concurrent-canonical-join-scope", account.accountUuid(), tenantUuid, "join-tenant");
    fixture
        .transaction()
        .executeWithoutResult(
            status -> scopes.insertCanonical(account.accountId(), scope, tenantProvenance));
    AccountJoinOperationRepository operations =
        new AccountJoinOperationRepository(fixture.transactionDsl(), scopes);

    String requestId = "canonical-join-concurrent-exact-retry";
    String callerBinding = "verified-bootstrap-concurrent-caller";
    long membershipsBefore =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .resultQuery(
                    "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = ?",
                    account.accountId())
                .fetchOne(0, Long.class));
    long outboxBefore =
        Objects.requireNonNull(
            fixture.setupDsl().resultQuery("SELECT COUNT(*) FROM account_audit_outbox").fetchOne(0, Long.class));
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch intentReady = new CountDownLatch(2);
      CountDownLatch releaseIntents = new CountDownLatch(1);
      Future<Boolean> firstInsert =
          submitAtBarrier(
              executor,
              intentReady,
              releaseIntents,
              () ->
                  fixture
                      .transaction()
                      .execute(
                          status ->
                              operations.insertCanonicalIntent(
                                  requestId, scope, callerBinding)));
      Future<Boolean> secondInsert =
          submitAtBarrier(
              executor,
              intentReady,
              releaseIntents,
              () ->
                  fixture
                      .transaction()
                      .execute(
                          status ->
                              operations.insertCanonicalIntent(
                                  requestId, scope, callerBinding)));
      assertThat(intentReady.await(15, TimeUnit.SECONDS)).isTrue();
      releaseIntents.countDown();
      boolean firstWasInserted = firstInsert.get(30, TimeUnit.SECONDS);
      boolean secondWasInserted = secondInsert.get(30, TimeUnit.SECONDS);
      assertThat(firstWasInserted).isNotEqualTo(secondWasInserted);

      CountDownLatch policyReady = new CountDownLatch(2);
      CountDownLatch releasePolicy = new CountDownLatch(1);
      Future<CanonicalJoinOperationEvidence> firstPolicy =
          submitAtBarrier(
              executor,
              policyReady,
              releasePolicy,
              () ->
                  fixture
                      .transaction()
                      .execute(
                          status ->
                              operations.bindCanonicalPolicyEvidence(
                                  requestId, scope, callerBinding, true, 17L)));
      Future<CanonicalJoinOperationEvidence> secondPolicy =
          submitAtBarrier(
              executor,
              policyReady,
              releasePolicy,
              () ->
                  fixture
                      .transaction()
                      .execute(
                          status ->
                              operations.bindCanonicalPolicyEvidence(
                                  requestId, scope, callerBinding, true, 17L)));
      assertThat(policyReady.await(15, TimeUnit.SECONDS)).isTrue();
      releasePolicy.countDown();
      CanonicalJoinOperationEvidence firstPolicyResult = firstPolicy.get(30, TimeUnit.SECONDS);
      CanonicalJoinOperationEvidence secondPolicyResult = secondPolicy.get(30, TimeUnit.SECONDS);
      assertThat(secondPolicyResult).isEqualTo(firstPolicyResult);
      assertThat(firstPolicyResult.requestId()).isEqualTo(requestId);
      assertThat(firstPolicyResult.status()).isEqualTo("PENDING");
      assertThat(firstPolicyResult.intentDigest())
          .isEqualTo(AccountJoinDigest.intentV2(requestId, scope, callerBinding));
      assertThat(firstPolicyResult.requestDigest())
          .isEqualTo(
              AccountJoinDigest.requestV2(
                  scope,
                  callerBinding,
                  AccountJoinDigest.EntitlementAvailabilityV2.AVAILABLE,
                  true,
                  17L));
      assertThat(firstPolicyResult.entitlementVersion()).isEqualTo(17L);
      assertThat(firstPolicyResult.allowPublicJoin()).isTrue();
      assertThat(
              fixture
                  .setupDsl()
                  .resultQuery(
                      "SELECT COUNT(*) FROM account_join_operations WHERE request_id = ?",
                      requestId)
                  .fetchOne(0, Long.class))
          .isEqualTo(1L);
      assertThat(
              fixture
                  .setupDsl()
                  .resultQuery(
                      "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = ?",
                      account.accountId())
                  .fetchOne(0, Long.class))
          .isEqualTo(membershipsBefore);
      assertThat(
              fixture
                  .setupDsl()
                  .resultQuery("SELECT COUNT(*) FROM account_audit_outbox")
                  .fetchOne(0, Long.class))
          .isEqualTo(outboxBefore);
    } finally {
      executor.shutdownNow();
    }
  }

  private static <T> Future<T> submitAtBarrier(
      ExecutorService executor,
      CountDownLatch ready,
      CountDownLatch release,
      Callable<T> action) {
    return executor.submit(
        () -> {
          ready.countDown();
          if (!release.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent PostgreSQL fixture barrier timed out");
          }
          return action.call();
        });
  }

  private static void assertRawCanonicalInsertRejected(
      DSLContext dsl,
      String sourceRequestId,
      String rejectedRequestId,
      boolean callerAuthorityInvalidated,
      String status,
      String outcome) {
    assertRawCanonicalInsertRejected(
        dsl,
        sourceRequestId,
        rejectedRequestId,
        callerAuthorityInvalidated,
        status,
        outcome,
        null);
  }

  private static void assertRawCanonicalRequiredSlotRejected(
      DSLContext dsl, String sourceRequestId, String rejectedRequestId, String missingSlot) {
    assertRawCanonicalInsertRejected(
        dsl, sourceRequestId, rejectedRequestId, false, "PENDING", null, missingSlot);
  }

  private static void assertRawCanonicalInsertRejected(
      DSLContext dsl,
      String sourceRequestId,
      String rejectedRequestId,
      boolean callerAuthorityInvalidated,
      String status,
      String outcome,
      String missingSlot) {
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO account_join_operations "
                        + "(request_id, account_id, tenant_id, verified_caller_binding, "
                        + "scope_token_hash, connect_scope_digest, world_slug, realm_slug, realm_id, "
                        + "playable_state_namespace_id, playable_state_scope, game_instance_id, "
                        + "catalog_revision, pointer_version, intent_digest_version, intent_digest, "
                        + "entitlement_authority_availability, caller_bound_authority_invalidated, "
                        + "last_attempt_authority_availability, status, outcome, "
                        + "operation_representation_version, scope_digest_version, target_class, "
                        + "account_uuid, tenant_uuid, tenant_slug, playable_state_namespace_uuid, "
                        + "game_instance_uuid) "
                        + "SELECT ?, account_id, tenant_id, verified_caller_binding, scope_token_hash, "
                        + "connect_scope_digest, world_slug, realm_slug, realm_id, "
                        + "playable_state_namespace_id, playable_state_scope, game_instance_id, "
                        + "catalog_revision, pointer_version, intent_digest_version, intent_digest, "
                        + "entitlement_authority_availability, ?, last_attempt_authority_availability, "
                        + "?, ?, operation_representation_version, scope_digest_version, "
                        + selectedSlot("target_class", missingSlot) + ", "
                        + selectedSlot("account_uuid", missingSlot) + ", "
                        + selectedSlot("tenant_uuid", missingSlot) + ", "
                        + selectedSlot("tenant_slug", missingSlot) + ", "
                        + selectedSlot("playable_state_namespace_uuid", missingSlot) + ", "
                        + selectedSlot("game_instance_uuid", missingSlot) + " "
                        + "FROM account_join_operations WHERE request_id = ?",
                    rejectedRequestId,
                    callerAuthorityInvalidated,
                    status,
                    outcome,
                    sourceRequestId))
        .isInstanceOf(DataAccessException.class);
  }

  private static String selectedSlot(String column, String missingSlot) {
    if (!column.equals("target_class")
        && !column.equals("account_uuid")
        && !column.equals("tenant_uuid")
        && !column.equals("tenant_slug")
        && !column.equals("playable_state_namespace_uuid")
        && !column.equals("game_instance_uuid")) {
      throw new IllegalArgumentException("Unexpected canonical JOIN slot");
    }
    return column.equals(missingSlot) ? "NULL" : column;
  }

  private static Fixture fixture() {
    String schema = "connect_scope_v2_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    flyway(dataSource, schema, "45").migrate();

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

  private static CanonicalConnectScopeRepositoryFixture scopeRepository(Fixture fixture) {
    DSLContext transactionDsl = fixture.transactionDsl();
    AccountRepository accounts = new AccountRepository(transactionDsl);
    LegacyTenantSourceEvidence sourceEvidence = new LegacyTenantSourceEvidence(transactionDsl);
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(transactionDsl);
    ApprovedLegacyTenantAssociationRepository approved =
        new ApprovedLegacyTenantAssociationRepository(
            transactionDsl, sourceEvidence, TEST_NAMESPACE, generations);
    AccountTenantIdentityResolver retained =
        new AccountTenantIdentityResolver(approved, sourceEvidence, TEST_NAMESPACE);
    FreshTenantIdentityAssociationRepository fresh =
        new FreshTenantIdentityAssociationRepository(transactionDsl, TEST_NAMESPACE);
    return new CanonicalConnectScopeRepositoryFixture(
        new AccountConnectScopeRepository(transactionDsl, accounts, retained, fresh), fresh);
  }

  private static SeededRetainedIdentity seedRetainedIdentity(DSLContext dsl) {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    Record account =
        Objects.requireNonNull(
            dsl.fetchOne(
                "INSERT INTO accounts (username, email, password_hash, tenant_id) "
                    + "VALUES (?, ?, ?, ?) RETURNING id, account_uuid",
                "connect-scope-v2-" + suffix,
                "connect-scope-v2-" + suffix + "@example.test",
                "opaque-test-hash",
                RETAINED_TENANT_ID),
            "Account insert must return exact persisted identity");
    long accountId =
        Objects.requireNonNull(
            account.get("id", Long.class), "Persisted Account row key is required");
    UUID accountUuid =
        Objects.requireNonNull(
            account.get("account_uuid", UUID.class), "Persisted Account UUID is required");
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
            "Retained membership insert must return its persisted id");
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
    return new SeededRetainedIdentity(accountId, accountUuid);
  }

  private static void insertApprovedRetainedAssociation(
      DSLContext dsl, UUID tenantUuid, UUID operationId, String sourceDigest) {
    dsl.execute(
        "INSERT INTO account_approved_legacy_tenant_associations "
            + "(legacy_tenant_id, canonical_tenant_id, source_legacy_game_tenant_id, "
            + "source_game_row_id, account_evidence_digest, operation_id, manifest_digest, "
            + "manifest_signature, target_namespace, signer_key_id, approved_by, "
            + "approval_reference, signed_at, operation_entry_count, manifest_schema_version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1)",
        RETAINED_TENANT_ID,
        tenantUuid,
        "legacy-game-tenant-700",
        RETAINED_TENANT_ID,
        sourceDigest,
        operationId,
        RETAINED_MANIFEST_DIGEST,
        Base64.getEncoder().encodeToString(new byte[64]),
        TEST_NAMESPACE,
        "fixture-signing-key",
        "fixture-operator",
        "connect-scope-v2-fixture",
        "2026-10-03T00:00:00Z");
  }

  private static UUID insertAccount(DSLContext dsl, String prefix) {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    return Objects.requireNonNull(
        dsl.resultQuery(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) "
                    + "RETURNING account_uuid",
                prefix + "-" + suffix,
                prefix + "-" + suffix + "@example.test",
                "opaque-test-hash")
            .fetchOne(0, UUID.class),
        "Secondary Account insert must return its canonical UUID");
  }

  private static VerifiedJoinScope retainedV1Scope(long accountId) {
    VerifiedJoinScope base =
        new VerifiedJoinScope(
            V1_TOKEN,
            accountId,
            RETAINED_TENANT_ID,
            UUID.fromString("7ac47b2d-fbe5-45eb-a16c-3914cf70c71a"),
            "retained-world",
            "production",
            "legacy-namespace-44",
            "SHARED",
            44L,
            23L,
            17L,
            "2026-10-03T00:00:00Z",
            "2026-10-03T00:02:00Z",
            "unused");
    return new VerifiedJoinScope(
        base.connectScopeId(),
        base.accountId(),
        base.tenantId(),
        base.realmId(),
        base.worldSlug(),
        base.realmSlug(),
        base.playableStateNamespaceId(),
        base.playableStateScope(),
        base.gameInstanceId(),
        base.catalogRevision(),
        base.pointerVersion(),
        base.evaluatedAt(),
        base.connectScopeExpiresAt(),
        AccountJoinDigest.scope(base));
  }

  private static CanonicalJoinScopeV2 v2Scope(
      String token, UUID accountUuid, UUID tenantUuid, String tenantSlug) {
    return new CanonicalJoinScopeV2(
        token,
        accountUuid,
        tenantUuid,
        UUID.fromString("e2b33891-a9a6-4b6f-9c2a-a71f078570dc"),
        tenantSlug,
        "world-" + tenantSlug,
        "production",
        UUID.fromString("156fc510-d536-4974-a350-79fbd4d6a1bc"),
        "SHARED",
        UUID.fromString("7701a6e2-d178-4a3f-97c8-72130c685f4b"),
        8L,
        5L,
        "2026-10-03T00:00:00.1234567890Z",
        "2026-10-03T00:00:00.1234567891Z");
  }

  private static FreshTenantCreationEvidence freshTenantEvidence(UUID tenantUuid) {
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String sourceTenantKey = "f-" + UUID.randomUUID().toString().replace("-", "");
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            TEST_NAMESPACE, requestId, sourceTenantKey, "Scope fixture tenant", null);
    long sourceGameRowId = 701L;
    String sourceKind = "NEW_GAME_ROW";
    return new FreshTenantCreationEvidence(
        1,
        TEST_NAMESPACE,
        requestId,
        operationId,
        requestDigest,
        tenantUuid,
        sourceGameRowId,
        sourceTenantKey,
        sourceKind,
        GameTenantCreationDigest.evidenceDigest(
            TEST_NAMESPACE,
            requestId,
            operationId,
            requestDigest,
            tenantUuid,
            sourceGameRowId,
            sourceTenantKey,
            sourceKind));
  }

  private static void insertRawCanonicalScope(
      DSLContext dsl,
      long accountId,
      CanonicalJoinScopeV2 scope,
      VerifiedTenantProvenance provenance,
      String storedDigest) {
    dsl.execute(
        "INSERT INTO account_connect_scope_records "
            + "(scope_token_hash, account_id, target_class, tenant_id, realm_id, world_slug, "
            + "realm_slug, playable_state_namespace_id, playable_state_scope, game_instance_id, "
            + "catalog_revision, pointer_version, evaluated_at, connect_scope_expires_at, "
            + "snapshot_digest, scope_digest_version, account_uuid, tenant_uuid, tenant_slug, "
            + "playable_state_namespace_uuid, game_instance_uuid, tenant_provenance_kind, "
            + "tenant_provenance_legacy_tenant_id, tenant_source_operation_id, "
            + "tenant_provenance_digest) "
            + "VALUES (?, ?, 'PUBLIC_PRODUCTION', NULL, ?, ?, ?, NULL, ?, NULL, ?, ?, ?, ?, ?, "
            + "2, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        AccountJoinDigest.tokenHash(scope.connectScopeId()),
        accountId,
        scope.realmId(),
        scope.worldSlug(),
        scope.realmSlug(),
        scope.playableStateScope(),
        scope.catalogRevision(),
        scope.pointerVersion(),
        scope.evaluatedAt(),
        scope.connectScopeExpiresAt(),
        storedDigest,
        scope.accountId(),
        scope.tenantId(),
        scope.tenantSlug(),
        scope.playableStateNamespaceId(),
        scope.gameInstanceId(),
        provenance.kind().name(),
        provenance.legacyTenantId(),
        provenance.sourceOperationId(),
        provenance.digest());
  }

  private static void assertAliasInsertRejected(
      DSLContext dsl, String sourceHash, String uniqueHash, long numericAlias) {
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO account_connect_scope_records "
                        + "(scope_token_hash, account_id, target_class, tenant_id, realm_id, "
                        + "world_slug, realm_slug, playable_state_namespace_id, playable_state_scope, "
                        + "game_instance_id, catalog_revision, pointer_version, evaluated_at, "
                        + "connect_scope_expires_at, playtest_lifecycle_id, playtest_state_generation, "
                        + "snapshot_digest, scope_digest_version, account_uuid, tenant_uuid, "
                        + "tenant_slug, playable_state_namespace_uuid, game_instance_uuid, "
                        + "tenant_provenance_kind, tenant_provenance_legacy_tenant_id, "
                        + "tenant_source_operation_id, tenant_provenance_digest) "
                        + "SELECT ?, account_id, target_class, ?, realm_id, world_slug, realm_slug, "
                        + "playable_state_namespace_id, playable_state_scope, game_instance_id, "
                        + "catalog_revision, pointer_version, evaluated_at, connect_scope_expires_at, "
                        + "playtest_lifecycle_id, playtest_state_generation, snapshot_digest, "
                        + "scope_digest_version, account_uuid, tenant_uuid, tenant_slug, "
                        + "playable_state_namespace_uuid, game_instance_uuid, tenant_provenance_kind, "
                        + "tenant_provenance_legacy_tenant_id, tenant_source_operation_id, "
                        + "tenant_provenance_digest FROM account_connect_scope_records "
                        + "WHERE scope_token_hash = ?",
                    uniqueHash,
                    numericAlias,
                    sourceHash))
        .isInstanceOf(DataAccessException.class);
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext setupDsl,
      DSLContext transactionDsl,
      TransactionTemplate transaction) {}

  private record SeededRetainedIdentity(long accountId, UUID accountUuid) {}

  private record CanonicalConnectScopeRepositoryFixture(
      AccountConnectScopeRepository scopes,
      FreshTenantIdentityAssociationRepository freshTenantIdentities) {}

  private static final String V2_TOKEN_FOR_RETAINED_SCOPE = "retained-uuid-scope-token";
}
