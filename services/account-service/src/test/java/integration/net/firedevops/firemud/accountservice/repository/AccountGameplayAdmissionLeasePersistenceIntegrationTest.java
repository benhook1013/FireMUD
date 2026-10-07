package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real PostgreSQL definitions for non-admitting storage only. Carriers are fabricated shape
 * fixtures, never authenticated source snapshots or evidence that a binding may be admitted.
 */
class AccountGameplayAdmissionLeasePersistenceIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();
  private static final String TENANT = "22222222-2222-4222-8222-222222222222";
  private static final String OTHER = "33333333-3333-4333-8333-333333333333";

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void lostResponseRecoveryUsesOriginalRequestWithoutReconstructingServerLeaseIdentity() {
    var context = context(null);
    UUID account = account(context);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    UUID request = UUID.randomUUID();
    UUID lease = UUID.randomUUID();
    var allocation = tx(context, () -> repository.allocate(account, request, lease));
    assertThat(tx(context, () -> repository.findAllocation(account, request))).contains(allocation);
    assertThat(
            tx(
                context,
                () ->
                    repository.findByRequest(
                        account, request, "spiffe://firemud/ns/test/sa/game-session-service")))
        .isEmpty();
    var original = evidence(allocation);
    var pending = tx(context, () -> repository.beginPending(original));
    assertThat(
            tx(
                context,
                () ->
                    repository.findByRequest(
                        account, request, "spiffe://firemud/ns/test/sa/game-session-service")))
        .contains(pending);
    UUID decision = UUID.randomUUID();
    var committed = tx(context, () -> repository.recordCommitted(original, decision));
    assertThat(
            tx(
                context,
                () ->
                    repository.findByRequest(
                        account, request, "spiffe://firemud/ns/test/sa/game-session-service")))
        .contains(committed);
    assertThat(tx(context, () -> repository.findAllocation(account, request))).contains(allocation);
    UUID wrongAccount = account(context);
    assertThatThrownBy(() -> tx(context, () -> repository.findAllocation(wrongAccount, request)))
        .hasMessageContaining("identity conflict");
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        repository.findByRequest(
                            wrongAccount,
                            request,
                            "spiffe://firemud/ns/test/sa/game-session-service")))
        .hasMessageContaining("identity conflict");
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        repository.findByRequest(
                            account, request, "spiffe://firemud/ns/other/sa/game-session-service")))
        .hasMessageContaining("identity conflict");
    UUID missingRequest = UUID.randomUUID();
    assertThat(tx(context, () -> repository.findAllocation(account, missingRequest))).isEmpty();
    assertThat(
            tx(
                context,
                () ->
                    repository.findByRequest(
                        account,
                        missingRequest,
                        "spiffe://firemud/ns/test/sa/game-session-service")))
        .isEmpty();
    assertThat(context.dsl().fetchCount(DSL.table("account_gameplay_admission_lease_allocations")))
        .isEqualTo(1);
  }

  @Test
  void freshMigrationStoresExactReplayAndRejectsChangedBaselineDigestOrAllocation() {
    var context = context(null);
    UUID account = account(context);
    var evidence = pending(context, account);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    assertThat(tx(context, () -> repository.beginPending(evidence)).evidence()).isEqualTo(evidence);
    var changed = new LinkedHashMap<>(evidence.carrier());
    changed.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState",
            "INACTIVE",
            "membershipVersion",
            Map.of(TENANT, "1"),
            "membershipAuthorityGeneration",
            "1"));
    var changedEvidence = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changed);
    assertThatThrownBy(() -> tx(context, () -> repository.beginPending(changedEvidence)))
        .hasMessageContaining("identity conflict");
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_gameplay_admission_lease_operations SET evidence_sha256 = ? WHERE request_id = ?",
                                "b".repeat(64),
                                UUID.fromString((String) evidence.carrier().get("requestId")))))
        .isInstanceOf(RuntimeException.class);
    changed.put("requestId", UUID.randomUUID().toString());
    changed.put("leaseId", UUID.randomUUID().toString());
    var unallocated = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changed);
    assertThatThrownBy(() -> tx(context, () -> repository.beginPending(unallocated)))
        .hasMessageContaining("identity conflict");
    var otherAllocation =
        tx(context, () -> repository.allocate(account, UUID.randomUUID(), UUID.randomUUID()));
    var substituted = new LinkedHashMap<>(evidence.carrier());
    substituted.put("leaseFence", Long.toString(otherAllocation.leaseFence()));
    var substitutedEvidence = AccountGameplayAdmissionLeaseEvidence.fromCarrier(substituted);
    assertThatThrownBy(() -> tx(context, () -> repository.beginPending(substitutedEvidence)))
        .hasMessageContaining("identity conflict");
    var original = evidence(otherAllocation);
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "INSERT INTO account_gameplay_admission_lease_operations "
                                    + "(account_uuid, request_id, lease_id, lease_fence, caller_workload, evidence_json, "
                                    + "evidence_sha256, evaluated_at_ms, expires_at_ms, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')",
                                account,
                                otherAllocation.requestId(),
                                otherAllocation.leaseId(),
                                otherAllocation.leaseFence(),
                                original.carrier().get("callerWorkload"),
                                original.canonicalJson(),
                                "b".repeat(64),
                                Long.parseLong((String) original.carrier().get("evaluatedAt")),
                                Long.parseLong((String) original.carrier().get("expiresAt")))))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void retainedAccountMigrationPreservesAccountAndAddsIndependentFenceDomain() {
    var context = context("84.4");
    UUID account =
        tx(
            context,
            () ->
                PreRestrictionBirthAccountFixture.create(
                        context.dsl(),
                        "lease-" + UUID.randomUUID(),
                        UUID.randomUUID() + "@example.test",
                        "storage-fixture-only",
                        "PASSWORD")
                    .getAccountUuid());
    migrate(context, null);
    var first = pending(context, account);
    var second = pending(context, account);
    assertThat(first.leaseFence().longValueExact()).isEqualTo(1);
    assertThat(second.leaseFence().longValueExact()).isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("accounts"))).isEqualTo(1);
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_gameplay_admission_lease_fences SET last_fence = 1 WHERE account_uuid = ?",
                                account)))
        .isInstanceOf(RuntimeException.class);
    for (String table :
        List.of(
            "account_gameplay_admission_lease_fences",
            "account_gameplay_admission_lease_allocations",
            "account_gameplay_admission_lease_operations")) {
      assertThatThrownBy(() -> tx(context, () -> context.dsl().execute("DELETE FROM " + table)))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> tx(context, () -> context.dsl().execute("TRUNCATE " + table)))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void commitsExactDecisionAndAtomicallyRetainsAbortedOrphanIntentWithTerminalConflict() {
    var context = context(null);
    UUID account = account(context);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    var committed = pending(context, account);
    UUID decision = UUID.randomUUID();
    var result = tx(context, () -> repository.recordCommitted(committed, decision));
    assertThat(result.state()).isEqualTo(State.COMMITTED);
    assertThat(tx(context, () -> repository.recordCommitted(committed, decision)))
        .isEqualTo(result);
    assertThatThrownBy(
            () -> tx(context, () -> repository.recordCommitted(committed, UUID.randomUUID())))
        .hasMessageContaining("identity conflict");
    assertThatThrownBy(
            () ->
                tx(context, () -> repository.recordAborted(committed, decision, UUID.randomUUID())))
        .hasMessageContaining("identity conflict");
    var aborted = pending(context, account);
    UUID cleanup = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () -> {
                      repository.recordAborted(aborted, decision, cleanup);
                      throw new IllegalStateException("simulate transaction rollback");
                    }))
        .hasMessageContaining("rollback");
    assertThat(tx(context, () -> repository.readExact(aborted).orElseThrow()).state())
        .isEqualTo(State.PENDING);
    var orphan = tx(context, () -> repository.recordAborted(aborted, decision, cleanup));
    assertThat(orphan.hasPendingOrphanCleanup()).isTrue();
    assertThat(orphan.evidence()).isEqualTo(aborted);
    assertThat(orphan.orphanCleanupId()).isEqualTo(cleanup);
    assertThat(tx(context, () -> repository.recordAborted(aborted, decision, cleanup)))
        .isEqualTo(orphan);
    assertThatThrownBy(
            () -> tx(context, () -> repository.recordAborted(aborted, decision, UUID.randomUUID())))
        .hasMessageContaining("identity conflict");
  }

  @Test
  void forwardMigrationPreservesRetainedReceiptsAndAllowsUnknownDecisionAbort() {
    var context = context("88");
    UUID account = account(context);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    var retainedPending = pending(context, account);
    var retainedKnown = pending(context, account);
    UUID decision = UUID.randomUUID();
    UUID knownCleanup = UUID.randomUUID();
    var known = tx(context, () -> repository.recordAborted(retainedKnown, decision, knownCleanup));
    var retainedCommitted = pending(context, account);
    var committed = tx(context, () -> repository.recordCommitted(retainedCommitted, decision));

    migrate(context, null);

    assertThat(tx(context, () -> repository.readExact(retainedKnown))).contains(known);
    assertThat(tx(context, () -> repository.readExact(retainedCommitted))).contains(committed);
    UUID cleanup = UUID.randomUUID();
    var unknown = tx(context, () -> repository.recordAborted(retainedPending, null, cleanup));
    assertThat(unknown.state()).isEqualTo(State.ABORTED);
    assertThat(unknown.bindingDecisionId()).isNull();
    assertThat(unknown.orphanCleanupId()).isEqualTo(cleanup);
    assertThat(unknown.hasPendingOrphanCleanup()).isTrue();
    assertThat(unknown.evidence()).isEqualTo(retainedPending);
    assertThat(tx(context, () -> repository.recordAborted(retainedPending, null, cleanup)))
        .isEqualTo(unknown);
    assertThat(tx(context, () -> repository.readExact(retainedPending))).contains(unknown);
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () -> repository.recordAborted(retainedPending, UUID.randomUUID(), cleanup)))
        .hasMessageContaining("identity conflict");
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () -> repository.recordAborted(retainedPending, null, UUID.randomUUID())))
        .hasMessageContaining("identity conflict");
    // The unchanged V87 trigger prevents a direct rewrite of the unknown terminal observation.
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_gameplay_admission_lease_operations SET binding_decision_id = ? WHERE request_id = ?",
                                UUID.randomUUID(),
                                UUID.fromString(
                                    (String) retainedPending.carrier().get("requestId")))))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void relaxedAbortShapeStillRejectsMissingCleanupAndCommittedWithoutDecision() {
    var context = context(null);
    UUID account = account(context);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    var original = pending(context, account);
    UUID request = UUID.fromString((String) original.carrier().get("requestId"));
    assertThatThrownBy(() -> tx(context, () -> repository.recordAborted(original, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tx(context, () -> repository.recordCommitted(original, null)))
        .isInstanceOf(IllegalArgumentException.class);
    for (String state : List.of("ABORTED", "COMMITTED")) {
      assertThatThrownBy(
              () ->
                  tx(
                      context,
                      () ->
                          context
                              .dsl()
                              .execute(
                                  "UPDATE account_gameplay_admission_lease_operations SET status = ? WHERE request_id = ?",
                                  state,
                                  request)))
          .isInstanceOf(RuntimeException.class);
    }
    assertThat(tx(context, () -> repository.readExact(original).orElseThrow()).state())
        .isEqualTo(State.PENDING);
  }

  @Test
  void expiredPendingCannotCommitOrRenewButKeepsExactReadbackAndCanAbort() {
    var context = context(null);
    UUID account = account(context);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    var evidence = pending(context, account);
    tx(context, () -> context.dsl().fetchOne("SELECT pg_sleep(16)"));
    assertThat(tx(context, () -> repository.beginPending(evidence)).evidence()).isEqualTo(evidence);
    assertThatThrownBy(
            () -> tx(context, () -> repository.recordCommitted(evidence, UUID.randomUUID())))
        .hasMessageContaining("deadline expired");
    var changed = new LinkedHashMap<>(evidence.carrier());
    long now = Instant.now().toEpochMilli();
    changed.put("evaluatedAt", Long.toString(now));
    changed.put("expiresAt", Long.toString(now + 15000));
    var renewed = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changed);
    assertThatThrownBy(() -> tx(context, () -> repository.beginPending(renewed)))
        .hasMessageContaining("identity conflict");
    assertThat(
            tx(context, () -> repository.recordAborted(evidence, null, UUID.randomUUID())).state())
        .isEqualTo(State.ABORTED);
  }

  @Test
  void concurrentDuplicatesReplayOneAllocationAndDistinctRequestsAdvanceFence() throws Exception {
    var context = context(null);
    UUID account = account(context);
    UUID request = UUID.randomUUID();
    UUID lease = UUID.randomUUID();
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    var start = new CountDownLatch(1);
    Callable<AccountGameplayAdmissionLeaseRepository.Allocation> duplicate =
        () -> {
          if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
          return retrySerialization(
              () -> tx(context, () -> repository.allocate(account, request, lease)));
        };
    try (var executor = Executors.newFixedThreadPool(2)) {
      var left = executor.submit(duplicate);
      var right = executor.submit(duplicate);
      start.countDown();
      assertThat(left.get(30, TimeUnit.SECONDS)).isEqualTo(right.get(30, TimeUnit.SECONDS));
      var a =
          executor.submit(
              () ->
                  retrySerialization(
                      () ->
                          tx(
                              context,
                              () ->
                                  repository.allocate(
                                      account, UUID.randomUUID(), UUID.randomUUID()))));
      var b =
          executor.submit(
              () ->
                  retrySerialization(
                      () ->
                          tx(
                              context,
                              () ->
                                  repository.allocate(
                                      account, UUID.randomUUID(), UUID.randomUUID()))));
      assertThat(
              List.of(
                  a.get(30, TimeUnit.SECONDS).leaseFence(),
                  b.get(30, TimeUnit.SECONDS).leaseFence()))
          .containsExactlyInAnyOrder(2L, 3L);
    }
  }

  private static <T> T retrySerialization(Supplier<T> action) {
    for (int attempt = 0; ; attempt++) {
      try {
        return action.get();
      } catch (RuntimeException failure) {
        Throwable cause = failure;
        boolean serialization = false;
        while (cause != null) {
          if (cause instanceof java.sql.SQLException sql && "40001".equals(sql.getSQLState()))
            serialization = true;
          cause = cause.getCause();
        }
        if (!serialization || attempt >= 4) throw failure;
      }
    }
  }

  private static AccountGameplayAdmissionLeaseEvidence pending(Context context, UUID account) {
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    return tx(
        context,
        () -> {
          var allocation = repository.allocate(account, UUID.randomUUID(), UUID.randomUUID());
          var evidence = evidence(allocation);
          repository.beginPending(evidence);
          return evidence;
        });
  }

  private static AccountGameplayAdmissionLeaseEvidence evidence(
      AccountGameplayAdmissionLeaseRepository.Allocation allocation) {
    String account = allocation.accountId().toString();
    long now = Instant.now().toEpochMilli();
    Map<String, Object> carrier = new LinkedHashMap<>();
    carrier.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    carrier.put("schemaVersion", "1");
    carrier.put("mode", "PUBLIC_PRODUCTION");
    carrier.put("targetNamespace", "test");
    carrier.put("callerWorkload", "spiffe://firemud/ns/test/sa/game-session-service");
    carrier.put("requestId", allocation.requestId().toString());
    carrier.put("leaseId", allocation.leaseId().toString());
    carrier.put("leaseFence", Long.toString(allocation.leaseFence()));
    carrier.put("leaseKind", "NEW_BINDING");
    Map<String, Object> scope = new LinkedHashMap<>();
    for (String key :
        List.of(
            "realmId",
            "playableStateNamespaceId",
            "gameInstanceId",
            "characterId",
            "sessionId",
            "regionId")) scope.put(key, OTHER);
    scope.put("accountId", account);
    scope.put("tenantId", TENANT);
    scope.put("worldSlug", "world");
    scope.put("realmSlug", "realm");
    scope.put("playableStateScope", "SHARED");
    for (String key :
        List.of("bindingGeneration", "catalogRevision", "pointerVersion", "regionEpoch"))
      scope.put(key, "1");
    carrier.put("bindingScope", scope);
    carrier.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration",
            "1",
            "accountAuthorityGeneration",
            "1",
            "tenantAuthorityGeneration",
            Map.of(TENANT, "1"),
            "membershipAuthorityGeneration",
            Map.of(TENANT, "1"),
            "privateRealmGrantVersions",
            List.of()));
    carrier.put("issuanceFence", "7");
    carrier.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState",
            "ACTIVE",
            "membershipVersion",
            Map.of(TENANT, "1"),
            "membershipAuthorityGeneration",
            "1"));
    carrier.put(
        "outboxCheckpoints",
        List.of(
                "account/" + account,
                "issuer/firemud-account-service",
                "membership/" + account + "/" + TENANT,
                "tenant/" + TENANT)
            .stream()
            .map(
                suffix ->
                    Map.of(
                        "outboxStreamKey",
                        "account:auth-authority:v1:" + suffix,
                        "outboxSequence",
                        "1"))
            .toList());
    Map<String, Object> token = new LinkedHashMap<>();
    token.put("accountId", account);
    for (String key : List.of("operationId", "issuanceRequestId", "tokenJti"))
      token.put(key, OTHER);
    token.put("tokenSHA256", "a".repeat(64));
    token.put("tokenProfile", "game-session-account-delegation");
    token.put("tokenGeneration", "1");
    token.put("issuanceFence", "7");
    token.put("tokenIdentityFence", "9");
    token.put("issuedAt", Long.toString(now / 1000 - 1));
    token.put("notBefore", Long.toString(now / 1000 - 1));
    token.put("expiresAt", Long.toString(now / 1000 + 300));
    carrier.put("tokenIdentityEvidence", token);
    carrier.put("evaluatedAt", Long.toString(now));
    carrier.put("expiresAt", Long.toString(now + 15000));
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
  }

  private static UUID account(Context context) {
    var authorities = new AccountAuthorityGenerationRepository(context.dsl());
    var sources =
        new AccountAuthoritySourceEvidenceRepository(
            context.dsl(), authorities, new AccountAuthorityOutboxRepository(context.dsl()));
    return tx(
        context,
        () -> {
          sources.initializeIssuerIfAbsent("firemud-account-service");
          var account = new Account();
          account.setUsername("lease-" + UUID.randomUUID());
          account.setEmail(UUID.randomUUID() + "@example.test");
          account.setPasswordHash("storage-fixture-only");
          account.setLoginAuthModes("PASSWORD");
          return new AccountRepository(context.dsl(), sources).save(account).getAccountUuid();
        });
  }

  private static Context context(String target) {
    String schema = "admission_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource = POSTGRES.dataSource(schema);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    var context =
        new Context(
            DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES),
            transaction,
            dataSource,
            schema);
    migrate(context, target);
    return context;
  }

  private static void migrate(Context context, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(context.dataSource())
            .schemas(context.schema())
            .defaultSchema(context.schema())
            .placeholders(Map.of("serviceSchema", context.schema()))
            .locations("classpath:db/migration");
    if (target != null) configuration.target(target);
    configuration.load().migrate();
  }

  private static <T> T tx(Context context, Supplier<T> action) {
    return context.transaction().execute(status -> action.get());
  }

  private record Context(
      DSLContext dsl,
      TransactionTemplate transaction,
      DriverManagerDataSource dataSource,
      String schema) {}
}
