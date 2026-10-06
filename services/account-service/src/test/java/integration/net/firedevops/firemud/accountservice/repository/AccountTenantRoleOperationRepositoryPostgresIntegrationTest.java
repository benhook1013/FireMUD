package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleMutationDigest.MemberResult;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository.Action;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository.AuditEvidence;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository.Claim;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository.OperationConflictException;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository.OperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository.Request;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AccountTenantRoleOperationRepositoryPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "tenant_role_operation_proof_";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void immutableJournalRetriesOriginalBytesAfterLaterRoleOperationAndRejectsChangedIntent() {
    TestContext context = newTestContext();
    UUID actor = insertAccount(context.setupDsl());
    UUID target = insertAccount(context.setupDsl());
    UUID tenant = UUID.randomUUID();
    Request original =
        request(UUID.randomUUID(), actor, tenant, target, Action.GRANT_DESIGNER, 4, 7);
    MemberResult originalMember =
        member(original, target, 8, 1, 1, List.of("designer", "player"), false);
    AuditEvidence originalAudit = audit(UUID.randomUUID(), "{\"request\":\"first\"}");

    OperationEvidence committed =
        inTransaction(
            context.transaction(),
            () -> {
              Claim claim = context.repository().claim(original);
              assertThat(claim.claimed()).isTrue();
              return context
                  .repository()
                  .complete(original, originalAudit, List.of(originalMember));
            });
    assertThat(committed.status()).isEqualTo("COMMITTED");
    assertThat(committed.members()).hasSize(1);
    MemberResult committedMember = committed.members().getFirst();
    assertThat(committedMember.accountUuid()).isEqualTo(originalMember.accountUuid());
    assertThat(committedMember.tenantUuid()).isEqualTo(originalMember.tenantUuid());
    assertThat(committedMember.membershipVersion()).isEqualTo(originalMember.membershipVersion());
    assertThat(committedMember.membershipAuthorityGeneration())
        .isEqualTo(originalMember.membershipAuthorityGeneration());
    assertThat(committedMember.eventSequence()).isEqualTo(originalMember.eventSequence());
    assertThat(committedMember.eventRequestId()).isEqualTo(originalMember.eventRequestId());
    assertThat(committedMember.eventId()).isEqualTo(originalMember.eventId());
    assertThat(committedMember.eventDigest()).isEqualTo(originalMember.eventDigest());
    assertThat(committedMember.callerBoundAuthorityInvalidated())
        .isEqualTo(originalMember.callerBoundAuthorityInvalidated());
    assertThat(committedMember.eventPayload()).containsExactly(originalMember.eventPayload());

    Request later = request(UUID.randomUUID(), actor, tenant, target, Action.REVOKE_DESIGNER, 4, 8);
    MemberResult laterMember = member(later, target, 9, 2, 2, List.of("player"), true);
    inTransaction(
        context.transaction(),
        () -> {
          assertThat(context.repository().claim(later).claimed()).isTrue();
          return context
              .repository()
              .complete(
                  later, audit(UUID.randomUUID(), "{\"request\":\"later\"}"), List.of(laterMember));
        });

    OperationEvidence retry =
        inTransaction(
            context.transaction(),
            () -> {
              Claim claim = context.repository().claim(original);
              assertThat(claim.claimed()).isFalse();
              return claim.replay().orElseThrow();
            });
    assertExactOperation(committed, retry);

    Request changedIntent =
        request(
            original.requestId(),
            actor,
            tenant,
            target,
            Action.REVOKE_DESIGNER,
            original.expectedActorMembershipVersion(),
            original.expectedTargetMembershipVersion());
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(), () -> context.repository().claim(changedIntent)))
        .isInstanceOf(OperationConflictException.class)
        .hasMessageContaining("changed immutable input");

    assertThatThrownBy(
            () ->
                context
                    .setupDsl()
                    .execute(
                        "UPDATE account_tenant_role_operations SET action = 'REVOKE_DESIGNER' "
                            + "WHERE request_id = ?",
                        original.requestId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .setupDsl()
                    .execute(
                        "DELETE FROM account_tenant_role_operations WHERE request_id = ?",
                        original.requestId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
  }

  @Test
  void forwardPendingMigrationPreservesExistingV56CommittedResultExactly() {
    TestContext context = newTestContext("56");
    UUID actor = insertAccount(context.setupDsl());
    UUID target = insertAccount(context.setupDsl());
    Request request =
        request(UUID.randomUUID(), actor, UUID.randomUUID(), target, Action.GRANT_DESIGNER, 4L, 7L);
    OperationEvidence original =
        inTransaction(
            context.transaction(),
            () -> {
              context.repository().claim(request);
              return context
                  .repository()
                  .complete(
                      request,
                      audit(UUID.randomUUID(), "{\"v56\":true}"),
                      List.of(
                          member(
                              request, target, 8L, 1L, 1L, List.of("designer", "player"), false)));
            });
    String schema =
        context.setupDsl().resultQuery("SELECT current_schema()").fetchOne(0, String.class);
    Flyway.configure()
        .dataSource(context.dataSource())
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    OperationEvidence replay =
        inTransaction(
            context.transaction(),
            () -> context.repository().claim(request).replay().orElseThrow());
    assertExactOperation(original, replay);
    assertThat(
            this.<java.util.Optional<SourceChange>>inTransaction(
                context.transaction(),
                () -> context.repository().findSourceChangeForUpdate(request)))
        .isEmpty();
  }

  @Test
  void onlyExactLinkedWaitingIntentMayPersistAndItsCaptureCannotBeChanged() {
    TestContext context = newTestContext();
    UUID actor = insertAccount(context.setupDsl());
    UUID target = insertAccount(context.setupDsl());
    Request request =
        request(UUID.randomUUID(), actor, UUID.randomUUID(), target, Action.GRANT_DESIGNER, 4L, 7L);
    assertThatThrownBy(
            () -> inTransaction(context.transaction(), () -> context.repository().claim(request)))
        .isInstanceOf(org.springframework.transaction.TransactionException.class);
    // Synthetic verified source prerequisites: storage/recovery proof, not authenticated capture.
    SourceChange original =
        new SourceChange(
            request.requestId(),
            List.of(
                new SourceEvidence(
                    SourceKind.MEMBERSHIP,
                    target + "/" + request.tenantUuid(),
                    "1",
                    "922337203685477580812345",
                    "synthetic-membership-stream",
                    "0",
                    new byte[] {1})),
            request.payload());
    var fences =
        new DraftAuthorizationFenceRepository(
            DSL.using(
                new TransactionAwareDataSourceProxy(context.dataSource()), SQLDialect.POSTGRES));
    inTransaction(
        context.transaction(),
        () -> {
          context.repository().claim(request);
          fences.requestSourceChange(original);
          context.repository().captureSourceChange(request, original);
          return null;
        });
    OperationEvidence pending =
        inTransaction(
            context.transaction(),
            () -> context.repository().claim(request).replay().orElseThrow());
    assertThat(pending.pending()).isTrue();
    assertThat(pending.members()).isEmpty();
    assertThat(pending.audit()).isNull();
    assertThat(
            this.<byte[]>inTransaction(
                context.transaction(),
                () ->
                    context
                        .repository()
                        .findSourceChangeForUpdate(request)
                        .orElseThrow()
                        .canonicalBytes()))
        .containsExactly(original.canonicalBytes());
    assertThatThrownBy(
            () ->
                context
                    .setupDsl()
                    .execute(
                        "UPDATE account_tenant_role_operations SET source_change_binding = ? WHERE request_id = ?",
                        new byte[] {2},
                        request.requestId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context.transaction(),
                    () -> {
                      fences.markSourceCommitted(original);
                      return null;
                    }))
        .isInstanceOf(org.springframework.transaction.TransactionException.class);
    assertThat(
            this.<String>inTransaction(
                context.transaction(), () -> fences.readSourceChange(original).status()))
        .isEqualTo("WAITING");
  }

  @Test
  void operationJournalAndMemberEvidenceRollBackTogether() {
    TestContext context = newTestContext();
    UUID actor = insertAccount(context.setupDsl());
    UUID target = insertAccount(context.setupDsl());
    UUID tenant = UUID.randomUUID();
    Request request =
        request(UUID.randomUUID(), actor, tenant, target, Action.GRANT_DESIGNER, 4, 7);
    MemberResult member = member(request, target, 8, 1, 1, List.of("designer", "player"), false);

    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(
                        status -> {
                          context.repository().claim(request);
                          context
                              .repository()
                              .complete(
                                  request,
                                  audit(UUID.randomUUID(), "{\"rollback\":true}"),
                                  List.of(member));
                          throw new IllegalStateException("injected after operation readback");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("injected after operation readback");
    assertThat(
            Objects.requireNonNull(
                Objects.requireNonNull(
                        context
                            .setupDsl()
                            .fetchOne(
                                "SELECT count(*) FROM account_tenant_role_operations WHERE request_id = ?",
                                request.requestId()),
                        "Expected tenant-role operation count row")
                    .get(0, Long.class),
                "Expected tenant-role operation count value"))
        .isZero();
    assertThat(
            Objects.requireNonNull(
                Objects.requireNonNull(
                        context
                            .setupDsl()
                            .fetchOne(
                                "SELECT count(*) FROM account_tenant_role_operation_members WHERE request_id = ?",
                                request.requestId()),
                        "Expected tenant-role member count row")
                    .get(0, Long.class),
                "Expected tenant-role member count value"))
        .isZero();
  }

  private TestContext newTestContext() {
    return newTestContext(null);
  }

  private TestContext newTestContext(String targetVersion) {
    String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    var migration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (targetVersion != null) {
      migration.target(targetVersion);
    }
    migration.load().migrate();
    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new TestContext(
        dataSource,
        setupDsl,
        new AccountTenantRoleOperationRepository(transactionDsl),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

  private UUID insertAccount(DSLContext dsl) {
    String unique = UUID.randomUUID().toString().replace("-", "");
    return dsl.resultQuery(
            "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) "
                + "RETURNING account_uuid",
            "tenant_role_" + unique.substring(0, 12),
            unique + "@example.test",
            "test-hash")
        .fetchOne(0, UUID.class);
  }

  private Request request(
      UUID request,
      UUID actor,
      UUID tenant,
      UUID target,
      Action action,
      long actorVersion,
      long targetVersion) {
    return new Request(request, actor, tenant, target, action, actorVersion, targetVersion);
  }

  private MemberResult member(
      Request request,
      UUID account,
      long membershipVersion,
      long generation,
      long sequence,
      List<String> roles,
      boolean invalidated) {
    String eventRequestId =
        AccountTenantRoleOperationRepository.eventRequestId(request.requestId(), account);
    String eventId =
        UUID.nameUUIDFromBytes(
                (MembershipAuthorityEventV1Codec.SCHEMA_VERSION + ":" + eventRequestId)
                    .getBytes(StandardCharsets.UTF_8))
            .toString();
    String streamKey =
        AccountTenantRoleOperationRepository.eventStreamKey(account, request.tenantUuid());
    Map<String, Object> tuple = new LinkedHashMap<>();
    tuple.put("issuerAuthGeneration", "1");
    tuple.put("accountAuthorityGeneration", "1");
    tuple.put("tenantAuthorityGeneration", Map.of(request.tenantUuid().toString(), "1"));
    tuple.put(
        "membershipAuthorityGeneration",
        Map.of(request.tenantUuid().toString(), Long.toString(generation)));
    tuple.put("privateRealmGrantVersions", List.of());
    Map<String, Object> preimage = new LinkedHashMap<>();
    preimage.put("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION);
    preimage.put("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE);
    preimage.put("eventId", eventId);
    preimage.put("requestId", eventRequestId);
    preimage.put("outboxStreamKey", streamKey);
    preimage.put("outboxSequence", Long.toString(sequence));
    preimage.put("sourceScope", "membership/" + account + "/" + request.tenantUuid());
    preimage.put("accountId", account.toString());
    preimage.put("tenantId", request.tenantUuid().toString());
    preimage.put("membershipExists", true);
    preimage.put("membershipLifecycleState", "ACTIVE");
    preimage.put(
        "membershipVersion",
        Map.of(request.tenantUuid().toString(), Long.toString(membershipVersion)));
    preimage.put("membershipAuthorityGeneration", Long.toString(generation));
    preimage.put("authorityTuple", tuple);
    preimage.put("issuanceFence", "1");
    preimage.put("roles", roles);
    preimage.put("gameplayAdmissionAllowed", true);
    preimage.put("callerBoundAuthorityInvalidated", invalidated);
    var event = MembershipAuthorityEventV1Codec.seal(preimage);
    return new MemberResult(
        account,
        request.tenantUuid(),
        membershipVersion,
        generation,
        sequence,
        eventRequestId,
        eventId,
        event.eventDigest(),
        invalidated,
        event.canonicalJsonUtf8());
  }

  private AuditEvidence audit(UUID eventId, String payload) {
    return new AuditEvidence(
        eventId,
        "ACCOUNT_TENANT_ROLE_CHANGED",
        java.time.Instant.parse("2026-10-05T01:02:03Z"),
        AccountAuditDigest.ofPayload(payload),
        payload.getBytes(StandardCharsets.UTF_8));
  }

  private void assertExactOperation(OperationEvidence expected, OperationEvidence actual) {
    assertThat(actual.request()).isEqualTo(expected.request());
    assertThat(actual.requestPayload()).containsExactly(expected.requestPayload());
    assertThat(actual.requestDigest()).isEqualTo(expected.requestDigest());
    assertThat(actual.status()).isEqualTo(expected.status());
    assertThat(actual.resultPayload()).containsExactly(expected.resultPayload());
    assertThat(actual.resultDigest()).isEqualTo(expected.resultDigest());
    assertThat(actual.audit()).isNotNull();
    assertThat(actual.audit().payload()).containsExactly(expected.audit().payload());
    assertThat(actual.members()).hasSize(expected.members().size());
    for (int index = 0; index < actual.members().size(); index++) {
      assertThat(actual.members().get(index).eventPayload())
          .containsExactly(expected.members().get(index).eventPayload());
    }
  }

  private <T> T inTransaction(
      TransactionTemplate transaction, java.util.function.Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private record TestContext(
      DataSource dataSource,
      DSLContext setupDsl,
      AccountTenantRoleOperationRepository repository,
      TransactionTemplate transaction) {}
}
