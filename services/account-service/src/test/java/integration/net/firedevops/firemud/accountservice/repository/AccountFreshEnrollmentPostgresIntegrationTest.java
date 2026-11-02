package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountRepository.FreshAccountInsert;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Actual producer/storage proof using explicitly installed controlled owner publications. The
 * fixture installation below stipulates deployment-owned publication delivery; it never claims
 * production mount custody, Logging & Admin delivery, PKI, registration or activation proof. All
 * V1018 and existing source/category guards stay enabled.
 */
class AccountFreshEnrollmentPostgresIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void actualNewInsertCommitsBothExplicitOwnerDecisionsCurrentHeadsAndOriginalEvents()
      throws Exception {
    Context context = context(null);
    controlledPolicies(context);
    var request = request();
    var result = context.transaction.execute(status -> producer(context).enroll(request));
    assertThat(result).isNotNull();
    assertThat(result.accountSecurityLock().receipt().orElseThrow().sourceState().generation())
        .isEqualTo(2);
    assertThat(result.platformAccessBan().receipt().orElseThrow().sourceState().generation())
        .isEqualTo(3);
    assertThat(context.dsl.fetchCount(DSL.table("account_fresh_enrollment_decisions")))
        .isEqualTo(2);
    assertThat(context.dsl.fetchCount(DSL.table("account_platform_restriction_revisions")))
        .isEqualTo(2);
    assertThat(
            context.dsl.fetchCount(DSL.table("account_platform_restriction_current_projections")))
        .isEqualTo(2);
    assertThat(context.dsl.fetchCount(DSL.table("account_authority_outbox_events"))).isEqualTo(2);
    context.transaction.executeWithoutResult(
        status -> {
          var roleSource =
              context.dsl.fetchSingle(
                  "SELECT a.id AS account_id, a.role, a.xmin::text AS account_xmin, "
                      + "a.account_uuid_source_numeric_id AS account_source_numeric_id, "
                      + "a.account_uuid_provenance AS account_provenance, s.account_uuid, "
                      + "s.account_uuid_source_numeric_id, s.account_uuid_provenance, "
                      + "s.global_roles, s.global_role_source_version, s.xmin::text AS source_xmin "
                      + "FROM accounts a JOIN account_global_role_sources s "
                      + "ON s.account_uuid = a.account_uuid WHERE a.account_uuid = ?",
                  result.accountUuid());
          assertThat(roleSource.get("role", String.class)).isNull();
          assertThat(roleSource.get("account_id", Long.class)).isEqualTo(result.accountId());
          assertThat(roleSource.get("account_uuid", UUID.class)).isEqualTo(result.accountUuid());
          assertThat(roleSource.get("account_uuid_source_numeric_id", Long.class))
              .isEqualTo(roleSource.get("account_source_numeric_id", Long.class))
              .isEqualTo(result.accountId());
          assertThat(roleSource.get("account_uuid_provenance", String.class))
              .isEqualTo(roleSource.get("account_provenance", String.class))
              .isEqualTo("ACCOUNT_REPOSITORY_INSERT");
          assertThat(roleSource.get("global_roles", String[].class)).isEmpty();
          assertThat(roleSource.get("global_role_source_version", Long.class)).isEqualTo(1L);
          assertThat(roleSource.get("source_xmin", String.class))
              .isEqualTo(roleSource.get("account_xmin", String.class));
          var outbox = new AccountAuthorityOutboxRepository(context.dsl);
          for (var operation :
              java.util.List.of(result.accountSecurityLock(), result.platformAccessBan())) {
            var receipt = operation.receipt().orElseThrow();
            var event = receipt.event();
            assertThat(outbox.findEvent(event.outboxStreamKey(), event.outboxSequence()))
                .contains(event);
            assertThat(event.outboxSequence()).isEqualTo(receipt.sourceState().generation() - 1);
            assertThat(
                    context
                        .dsl
                        .fetchSingle(
                            "SELECT expected_generation FROM account_fresh_enrollment_decisions WHERE request_id = ?",
                            operation.request().requestId())
                        .get("expected_generation", Long.class))
                .isEqualTo(receipt.sourceState().generation() - 1);
          }
          var generations = new AccountAuthorityGenerationRepository(context.dsl);
          var state = generations.read(AuthorityScope.account(result.accountUuid()));
          assertThat(state.generation()).isEqualTo(3);
          assertThat(state.sourceVersion()).isEqualTo(3);
          assertThat(state.issuanceFence().value()).isEqualTo(3);
          assertThat(state.issuanceFence().sourceVersion()).isEqualTo(3);
          var source = source(context.dsl).readCurrentSource(state.scope(), state);
          assertThat(source.checkpoint().sequence()).isEqualTo(2);
          assertThat(source.checkpoint().sourceEventId().orElseThrow())
              .isEqualTo(result.platformAccessBan().receipt().orElseThrow().event().eventId());
          assertThat(source.checkpoint().sourceEventDigest().orElseThrow())
              .isEqualTo(result.platformAccessBan().receipt().orElseThrow().event().eventDigest());
        });
    assertThat(
            context
                .dsl
                .fetchSingle(
                    "SELECT account_uuid_provenance, lifecycle_state, account_repository_insert_transaction_id "
                        + "FROM accounts WHERE account_uuid = ?",
                    result.accountUuid())
                .get("lifecycle_state", String.class))
        .isEqualTo("active");
  }

  @Test
  void sourceGuardsStillRefuseGenerationOrFenceAdvanceWithoutExactSourceEvidenceAtCommit()
      throws Exception {
    Context context = context(null);
    controlledPolicies(context);
    var result = context.transaction.execute(status -> producer(context).enroll(request()));
    var scope = AuthorityScope.account(result.accountUuid());
    var generations = new AccountAuthorityGenerationRepository(context.dsl);
    var before = context.transaction.execute(status -> generations.read(scope));
    var originalSource =
        context.transaction.execute(status -> source(context.dsl).readCurrentSource(scope, before));
    for (var mutation :
        Map.of(
                "UPDATE account_authority_generations SET generation = generation + 1, source_version = source_version + 1 WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                "account_authority_source_generation_required",
                "UPDATE account_authority_issuance_fences SET issuance_fence = issuance_fence + 1, source_version = source_version + 1 WHERE account_uuid = ?",
                "account_authority_source_fence_required")
            .entrySet()) {
      assertThatThrownBy(
              () ->
                  context.transaction.executeWithoutResult(
                      status -> context.dsl.execute(mutation.getKey(), result.accountUuid())))
          .rootCause()
          .isInstanceOf(org.postgresql.util.PSQLException.class)
          .satisfies(
              cause -> {
                var failure = (org.postgresql.util.PSQLException) cause;
                assertThat(failure.getSQLState()).isEqualTo("23514");
                assertThat(Objects.requireNonNull(failure.getServerErrorMessage()).getConstraint())
                    .isEqualTo(mutation.getValue());
              });
      context.transaction.executeWithoutResult(
          status -> {
            assertThat(generations.read(scope)).isEqualTo(before);
            assertThat(source(context.dsl).readCurrentSource(scope, before))
                .isEqualTo(originalSource);
          });
    }
  }

  @Test
  void lostResponseRetryReturnsExactOriginalAccountAndResultsWithoutRevisionDuplication()
      throws Exception {
    Context context = context(null);
    controlledPolicies(context);
    var request = request();
    var first = context.transaction.execute(status -> producer(context).enroll(request));
    var replay = context.transaction.execute(status -> producer(context).enroll(request));
    assertThat(replay).isEqualTo(first);
    assertThat(context.dsl.fetchCount(DSL.table("accounts"))).isEqualTo(1);
    assertThat(context.dsl.fetchCount(DSL.table("account_platform_restriction_revisions")))
        .isEqualTo(2);
    var changed =
        new AccountFreshEnrollmentRequest(
            request.enrollmentId(),
            "changed",
            "changed@example.test",
            "changed-hash",
            false,
            "PASSWORD");
    assertThatThrownBy(
            () -> context.transaction.execute(status -> producer(context).enroll(changed)))
        .isInstanceOf(AccountAuthorityOutboxRepository.IdempotencyConflictException.class);
    // New policy publications do not rewrite or re-certify original retained success receipts.
    context.transaction.executeWithoutResult(
        status ->
            controlledPublication(
                context, Category.ACCOUNT_SECURITY_LOCK, 2, "DENY_NEW_ACCOUNT", "CLOSED"));
    var replayAfterPolicyAdvance =
        context.transaction.execute(status -> producer(context).enroll(request));
    assertThat(replayAfterPolicyAdvance).isEqualTo(first);
  }

  @Test
  void missingPolicyUnsafePolicyAndSecondCategoryClosedPolicyNeverLeaveAnAccountOrFirstCategory()
      throws Exception {
    Context missing = context(null);
    assertThatThrownBy(
            () -> missing.transaction.execute(status -> producer(missing).enroll(request())))
        .isInstanceOf(IllegalStateException.class);
    assertNoEnrollmentState(missing);
    for (String[] rules :
        new String[][] {
          {"DENY_NEW_ACCOUNT", "EXACT_FRESH_INSERT_ONLY"}, {"CERTIFY_NEW_ACTIVE_ACCOUNT", "CLOSED"}
        }) {
      Context context = context(null);
      context.transaction.executeWithoutResult(
          status -> {
            controlledPublication(
                context,
                Category.ACCOUNT_SECURITY_LOCK,
                1,
                "CERTIFY_NEW_ACTIVE_ACCOUNT",
                "EXACT_FRESH_INSERT_ONLY");
            controlledPublication(context, Category.PLATFORM_ACCESS_BAN, 1, rules[0], rules[1]);
          });
      assertThatThrownBy(
              () -> context.transaction.execute(status -> producer(context).enroll(request())))
          .isInstanceOf(IllegalStateException.class);
      assertNoEnrollmentState(context);
    }
  }

  @Test
  void ordinarySaveRemainsBirthOnlyAndRetainedAccountCannotAcquireInsertionOrEnrollmentAuthority()
      throws Exception {
    Context context = context(null);
    controlledPolicies(context);
    Account retained = request().newAccount();
    context.transaction.executeWithoutResult(
        status -> new AccountRepository(context.dsl).save(retained));
    assertThat(
            context.dsl.fetchCount(DSL.table("account_platform_restriction_current_projections")))
        .isZero();
    assertThatThrownBy(
            () ->
                context.transaction.execute(
                    status ->
                        new AccountRepository(context.dsl)
                            .insertFreshAndApply(retained, proof -> proof)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                context.transaction.executeWithoutResult(
                    status ->
                        context.dsl.execute(
                            "INSERT INTO account_fresh_enrollments (enrollment_id, request_digest, account_uuid, account_id, insertion_xid) "
                                + "SELECT ?, ?, account_uuid, id, txid_current() FROM accounts WHERE account_uuid = ?",
                            UUID.randomUUID(),
                            "sha256:" + "0".repeat(64),
                            retained.getAccountUuid())))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(context.dsl.fetchCount(DSL.table("account_fresh_enrollments"))).isZero();
  }

  @Test
  void escapedCapabilityAndDifferentTransactionCannotCertifyAnInsert() throws Exception {
    Context context = context(null);
    controlledPolicies(context);
    AtomicReference<FreshAccountInsert> escaped = new AtomicReference<>();
    context.transaction.executeWithoutResult(
        status ->
            new AccountRepository(context.dsl)
                .insertFreshAndApply(
                    request().newAccount(),
                    proof -> {
                      escaped.set(proof);
                      var independent =
                          new TransactionTemplate(
                              new DataSourceTransactionManager(context.dataSource));
                      independent.setPropagationBehavior(
                          TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                      assertThatThrownBy(
                              () ->
                                  independent.execute(
                                      inner ->
                                          new AccountFreshEnrollmentPolicyRepository(context.dsl)
                                              .evaluate(
                                                  proof,
                                                  UUID.randomUUID(),
                                                  Category.ACCOUNT_SECURITY_LOCK)))
                          .isInstanceOf(IllegalStateException.class);
                      return proof.accountUuid();
                    }));
    assertThatThrownBy(
            () ->
                context.transaction.execute(
                    status ->
                        new AccountFreshEnrollmentPolicyRepository(context.dsl)
                            .evaluate(
                                escaped.get(), UUID.randomUUID(), Category.ACCOUNT_SECURITY_LOCK)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(context.dsl.fetchCount(DSL.table("account_fresh_enrollment_decisions"))).isZero();
  }

  @Test
  void missingSecondCertificationFailsAtPhysicalCommitAndRollsBackTheCompleteFirstCategory()
      throws Exception {
    Context context = context(null);
    controlledPolicies(context);
    assertThatThrownBy(
            () ->
                context.transaction.executeWithoutResult(
                    status -> {
                      var policies = new AccountFreshEnrollmentPolicyRepository(context.dsl);
                      policies.lockCurrentPolicies();
                      new AccountRepository(context.dsl)
                          .insertFreshAndApply(
                              request().newAccount(),
                              proof -> {
                                checkCompletedSourceBoundary(context);
                                UUID id = UUID.randomUUID();
                                insertEnrollment(context, proof, id);
                                restrictions(context.dsl)
                                    .certifyFreshEnrollment(
                                        proof,
                                        policies.evaluate(
                                            proof, id, Category.ACCOUNT_SECURITY_LOCK));
                                checkCompletedSourceBoundary(context);
                                return proof.accountUuid();
                              });
                    }))
        .rootCause()
        .isInstanceOf(org.postgresql.util.PSQLException.class)
        .hasMessageContaining(
            "Both explicit category certifications and exact enrollment result must commit atomically");
    assertNoEnrollmentState(context);
  }

  @Test
  void currentOwnerPolicyWriterWaitsThroughBothCertificationsAndResultCompletion()
      throws Exception {
    Context context = context(null);
    controlledPolicies(context);
    CountDownLatch shared = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch writerStarted = new CountDownLatch(1);
    AtomicReference<Integer> readerPid = new AtomicReference<>();
    AtomicReference<Integer> writerPid = new AtomicReference<>();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var reader =
          executor.submit(
              () ->
                  context.transaction.execute(
                      status -> {
                        int backendPid =
                            Objects.requireNonNull(
                                    context.dsl.fetchOne("SELECT pg_backend_pid() AS pid"))
                                .get("pid", Integer.class);
                        readerPid.set(backendPid);
                        new AccountFreshEnrollmentPolicyRepository(context.dsl)
                            .lockCurrentPolicies();
                        shared.countDown();
                        await(release);
                        return producer(context).enroll(request());
                      }));
      assertThat(shared.await(10, TimeUnit.SECONDS)).isTrue();
      var writer =
          executor.submit(
              () ->
                  context.transaction.executeWithoutResult(
                      status -> {
                        int backendPid =
                            Objects.requireNonNull(
                                    context.dsl.fetchOne("SELECT pg_backend_pid() AS pid"))
                                .get("pid", Integer.class);
                        writerPid.set(backendPid);
                        writerStarted.countDown();
                        controlledPublication(
                            context,
                            Category.ACCOUNT_SECURITY_LOCK,
                            2,
                            "DENY_NEW_ACCOUNT",
                            "CLOSED");
                      }));
      assertThat(writerStarted.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(readerPid.get()).isNotNull();
      assertThat(writerPid.get()).isNotNull();
      assertThat(
              awaitPostgresBlocking(
                  context, writerPid.get(), readerPid.get(), 10, TimeUnit.SECONDS))
          .isTrue();
      assertThat(writer.isDone()).isFalse();
      release.countDown();
      assertThat(reader.get(10, TimeUnit.SECONDS)).isNotNull();
      writer.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
    }
    assertThat(context.dsl.fetchCount(DSL.table("account_fresh_enrollment_results"))).isEqualTo(1);
    assertThatThrownBy(
            () -> context.transaction.execute(status -> producer(context).enroll(request())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void directSqlCannotOmitClosureForgeDecisionMutateReceiptsRegressPolicyOrBreakReciprocalResult()
      throws Exception {
    Context context = context(null);
    controlledPolicies(context);
    var result = context.transaction.execute(status -> producer(context).enroll(request()));
    for (String sql :
        new String[] {
          "UPDATE account_fresh_enrollment_decisions SET decision_digest = 'sha256:' || repeat('0',64)",
          "DELETE FROM account_fresh_enrollments",
          "TRUNCATE account_fresh_enrollment_results",
          "UPDATE account_fresh_enrollment_policy_current SET revision = revision - 1",
          "UPDATE account_fresh_enrollment_policy_publications SET closure_rule = 'CLOSED'",
          "UPDATE account_fresh_enrollment_results SET security_request_id = ban_request_id",
          "INSERT INTO account_fresh_enrollment_decisions SELECT * FROM account_fresh_enrollment_decisions LIMIT 1"
        }) {
      assertThatThrownBy(
              () -> context.transaction.executeWithoutResult(status -> context.dsl.execute(sql)))
          .isInstanceOf(RuntimeException.class);
    }
    context.transaction.executeWithoutResult(
        status ->
            context.dsl.fetch(
                "SELECT account_fresh_enrollment_require_complete(?)", result.enrollmentId()));
    assertThat(context.dsl.fetchCount(DSL.table("account_fresh_enrollment_results"))).isEqualTo(1);
  }

  @Test
  void swappedReciprocalCategoryResultsAreRefusedEvenWithTwoGenuineFreshDecisions()
      throws Exception {
    Context context = context(null);
    controlledPolicies(context);
    assertThatThrownBy(
            () ->
                context.transaction.executeWithoutResult(
                    status -> {
                      var policies = new AccountFreshEnrollmentPolicyRepository(context.dsl);
                      policies.lockCurrentPolicies();
                      new AccountRepository(context.dsl)
                          .insertFreshAndApply(
                              request().newAccount(),
                              proof -> {
                                checkCompletedSourceBoundary(context);
                                UUID id = UUID.randomUUID();
                                insertEnrollment(context, proof, id);
                                var lockDecision =
                                    policies.evaluate(proof, id, Category.ACCOUNT_SECURITY_LOCK);
                                // Even genuine fresh-owner correlation cannot authorize the
                                // ordinary request command.
                                var request = lockDecision.requireCurrent(context.dsl, proof);
                                assertThatThrownBy(() -> restrictions(context.dsl).commit(request))
                                    .isInstanceOf(
                                        AccountPlatformRestrictionOperationRepository
                                            .OwnerAuthorizationUnresolvedException.class);
                                var lock =
                                    restrictions(context.dsl)
                                        .certifyFreshEnrollment(proof, lockDecision);
                                checkCompletedSourceBoundary(context);
                                var ban =
                                    restrictions(context.dsl)
                                        .certifyFreshEnrollment(
                                            proof,
                                            policies.evaluate(
                                                proof, id, Category.PLATFORM_ACCESS_BAN));
                                context.dsl.execute(
                                    "INSERT INTO account_fresh_enrollment_results (enrollment_id, security_request_id, ban_request_id) VALUES (?, ?, ?)",
                                    id,
                                    ban.request().requestId(),
                                    lock.request().requestId());
                                return id;
                              });
                    }))
        .rootCause()
        .isInstanceOf(org.postgresql.util.PSQLException.class)
        .hasMessageContaining(
            "Enrollment result does not reciprocate its exact two owner decisions");
    assertNoEnrollmentState(context);
  }

  @Test
  void malformedPublicationWithoutClosureAndForgedDecisionForRetainedAccountFailClosed()
      throws Exception {
    Context context = context(null);
    controlledPolicies(context);
    var result = context.transaction.execute(status -> producer(context).enroll(request()));
    assertThatThrownBy(
            () ->
                context.transaction.executeWithoutResult(
                    status ->
                        context.dsl.execute(
                            "INSERT INTO account_fresh_enrollment_policy_publications SELECT gen_random_uuid(), category, 2, policy_id, closure_id, "
                                + "policy_rule, closure_rule, source_identity, source_root, 'sha256:' || encode(sha256(convert_to('{}','UTF8')),'hex'), "
                                + "convert_to('{}','UTF8') FROM account_fresh_enrollment_policy_publications LIMIT 1")))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThatThrownBy(
            () ->
                context.transaction.executeWithoutResult(
                    status ->
                        context.dsl.execute(
                            "INSERT INTO account_fresh_enrollment_decisions SELECT gen_random_uuid(), enrollment_id, account_uuid, account_id, txid_current(), "
                                + "category, publication_id, publication_digest, policy_id, closure_id, gen_random_uuid(), expected_generation, expected_source_version, "
                                + "expected_fence, expected_fence_source_version, expected_revision, expected_epoch, expected_result_id, decision_digest "
                                + "FROM account_fresh_enrollment_decisions WHERE enrollment_id = ? LIMIT 1",
                            result.enrollmentId())))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
  }

  @Test
  void upgradePreservesRetainedAccountBirthAndSourceRowsWithoutCertifyingThem() throws Exception {
    Context context = context("1017");
    Account retained = request().newAccount();
    context.transaction.executeWithoutResult(
        status -> new AccountRepository(context.dsl).save(retained));
    Map<String, java.util.List<Map<String, Object>>> before = new java.util.LinkedHashMap<>();
    for (String table :
        new String[] {
          "accounts",
          "account_platform_restriction_births",
          "account_platform_restriction_projections",
          "account_platform_restriction_birth_outbox",
          "account_authority_source_records",
          "account_authority_generations",
          "account_authority_issuance_fences"
        }) {
      before.put(
          table,
          context
              .dsl
              .fetch(
                  "SELECT to_jsonb(t)::text AS retained, xmin::text AS xmin FROM "
                      + table
                      + " t ORDER BY retained")
              .intoMaps());
    }
    migrate(context, null);
    for (var entry : before.entrySet()) {
      assertThat(
              context
                  .dsl
                  .fetch(
                      "SELECT to_jsonb(t)::text AS retained, xmin::text AS xmin FROM "
                          + entry.getKey()
                          + " t ORDER BY retained")
                  .intoMaps())
          .as(entry.getKey())
          .isEqualTo(entry.getValue());
    }
    assertThat(context.dsl.fetchCount(DSL.table("account_fresh_enrollments"))).isZero();
    assertThat(
            context.dsl.fetchCount(DSL.table("account_platform_restriction_current_projections")))
        .isZero();
    controlledPolicies(context);
    assertThatThrownBy(
            () ->
                context.transaction.executeWithoutResult(
                    status ->
                        context.dsl.execute(
                            "INSERT INTO account_fresh_enrollments (enrollment_id, request_digest, account_uuid, account_id, insertion_xid) "
                                + "SELECT ?, ?, account_uuid, id, txid_current() FROM accounts WHERE account_uuid = ?",
                            UUID.randomUUID(),
                            "sha256:" + "0".repeat(64),
                            retained.getAccountUuid())))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
  }

  private static void assertNoEnrollmentState(Context context) {
    for (String table :
        new String[] {
          "accounts",
          "account_global_role_sources",
          "account_fresh_enrollments",
          "account_fresh_enrollment_decisions",
          "account_fresh_enrollment_results",
          "account_platform_restriction_operations",
          "account_platform_restriction_revisions",
          "account_platform_restriction_current_projections",
          "account_authority_outbox_events"
        }) {
      assertThat(context.dsl.fetchCount(DSL.table(table))).as(table).isZero();
    }
  }

  private static void insertEnrollment(Context context, FreshAccountInsert proof, UUID id) {
    context.dsl.execute(
        "INSERT INTO account_fresh_enrollments (enrollment_id, request_digest, account_uuid, account_id, insertion_xid) VALUES (?, ?, ?, ?, ?)",
        id,
        "sha256:" + "a".repeat(64),
        proof.accountUuid(),
        proof.accountId(),
        proof.insertionTransactionId());
  }

  private static void checkCompletedSourceBoundary(Context context) {
    // Deliberately incomplete native compositions still check each complete source boundary,
    // so their final refusal must come from the intended enrollment-completeness constraint.
    String constraints =
        "account_authority_source_generation_consistent, "
            + "account_authority_source_fence_consistent, account_authority_source_record_consistent";
    context.dsl.execute("SET CONSTRAINTS " + constraints + " IMMEDIATE");
    context.dsl.execute("SET CONSTRAINTS " + constraints + " DEFERRED");
  }

  private static void controlledPolicies(Context context) {
    context.transaction.executeWithoutResult(
        status -> {
          for (Category category : Category.values())
            controlledPublication(
                context, category, 1, "CERTIFY_NEW_ACTIVE_ACCOUNT", "EXACT_FRESH_INSERT_ONLY");
        });
  }

  /**
   * Controlled test publication, not a genuine protected mount or authenticated owner publisher.
   */
  private static void controlledPublication(
      Context context, Category category, long revision, String policy, String closure) {
    try {
      UUID publication = UUID.randomUUID();
      UUID policyId = UUID.randomUUID();
      UUID closureId = UUID.randomUUID();
      String owner = AccountFreshEnrollmentPolicySource.sourceIdentity(category);
      byte[] bytes =
          Rfc8785CanonicalJson.canonicalizeUtf8(
              JsonMapper.builder()
                  .build()
                  .writeValueAsString(
                      Map.of(
                          "schemaVersion",
                          "account-fresh-enrollment-policy/v1",
                          "category",
                          category.storageValue(),
                          "sourceIdentity",
                          owner,
                          "publicationId",
                          publication.toString(),
                          "revision",
                          Long.toString(revision),
                          "policyId",
                          policyId.toString(),
                          "policyRule",
                          policy,
                          "closureId",
                          closureId.toString(),
                          "closureRule",
                          closure)));
      context.dsl.execute(
          "INSERT INTO account_fresh_enrollment_policy_publications "
              + "(publication_id, category, revision, policy_id, closure_id, policy_rule, closure_rule, source_identity, source_root, publication_digest, publication_bytes) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          publication,
          category.storageValue(),
          revision,
          policyId,
          closureId,
          policy,
          closure,
          owner,
          "/controlled-test/owner-protected-mount",
          AccountFreshEnrollmentPolicySource.digest(bytes),
          bytes);
      context.dsl.execute(
          "INSERT INTO account_fresh_enrollment_policy_current (category, publication_id, revision) VALUES (?, ?, ?) "
              + "ON CONFLICT (category) DO UPDATE SET publication_id = EXCLUDED.publication_id, revision = EXCLUDED.revision",
          category.storageValue(),
          publication,
          revision);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS))
        throw new IllegalStateException("Fixture latch timeout");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private static boolean awaitPostgresBlocking(
      Context context, int waiterPid, int blockerPid, long timeout, TimeUnit unit) {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    do {
      Boolean blocked =
          Objects.requireNonNull(
                  context.dsl.fetchOne(
                      "SELECT ? = ANY(pg_blocking_pids(?)) AS blocked", blockerPid, waiterPid))
              .get("blocked", Boolean.class);
      if (Boolean.TRUE.equals(blocked)) return true;
      Thread.yield();
    } while (System.nanoTime() < deadline);
    return false;
  }

  private static AccountFreshEnrollmentRequest request() {
    String suffix = UUID.randomUUID().toString();
    return new AccountFreshEnrollmentRequest(
        UUID.randomUUID(),
        suffix,
        suffix + "@example.test",
        "controlled-password-verifier",
        false,
        "PASSWORD,EMAIL_OTP");
  }

  private static AccountFreshEnrollmentRepository producer(Context context) {
    return new AccountFreshEnrollmentRepository(
        context.dsl,
        new AccountRepository(context.dsl),
        new AccountFreshEnrollmentPolicyRepository(context.dsl),
        restrictions(context.dsl));
  }

  private static AccountPlatformRestrictionOperationRepository restrictions(DSLContext dsl) {
    return new AccountPlatformRestrictionOperationRepository(
        dsl,
        new AccountAuthorityGenerationRepository(dsl),
        new AccountAuthorityOutboxRepository(dsl),
        source(dsl),
        new AccountPlatformRestrictionBirthRepository(dsl));
  }

  private static AccountAuthoritySourceEvidenceRepository source(DSLContext dsl) {
    return new AccountAuthoritySourceEvidenceRepository(
        dsl,
        new AccountAuthorityGenerationRepository(dsl),
        new AccountAuthorityOutboxRepository(dsl));
  }

  private static Context context(String target) {
    String schema = "fresh_enrollment_" + UUID.randomUUID().toString().replace("-", "");
    var ds = POSTGRES.dataSource(schema);
    Context context =
        new Context(
            schema,
            ds,
            DSL.using(new TransactionAwareDataSourceProxy(ds), SQLDialect.POSTGRES),
            new TransactionTemplate(new DataSourceTransactionManager(ds)));
    migrate(context, target);
    return context;
  }

  private static void migrate(Context context, String target) {
    var config =
        Flyway.configure()
            .dataSource(context.dataSource)
            .schemas(context.schema)
            .defaultSchema(context.schema)
            .locations("classpath:db/migration")
            .placeholders(Map.of("serviceSchema", context.schema));
    if (target != null) config.target(target);
    config.load().migrate();
  }

  private record Context(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      TransactionTemplate transaction) {}
}
