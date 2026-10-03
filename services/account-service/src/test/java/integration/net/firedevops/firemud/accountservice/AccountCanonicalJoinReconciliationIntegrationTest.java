package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.AccountServiceApplication;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCanonicalJoinReconciliationService;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL composition proof for the explicitly unwired canonical JOIN readback component.
 *
 * <p>These fixtures call Account owner repositories directly and establish storage/recovery proof
 * only. In particular, historical event/receipt/audit rows seeded beside already-expired scopes do
 * not exercise or claim authenticated entitlement, caller, or public JOIN authorization.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = AccountServiceApplication.class,
    properties = {
      GatewayTestProperties.SPRING_GRPC_SERVER_SSL_DISABLED,
      GatewayTestProperties.FIREMUD_GRPC_CERT_CHAIN_PATH,
      GatewayTestProperties.FIREMUD_GRPC_PRIVATE_KEY_PATH,
      GatewayTestProperties.FIREMUD_GRPC_CA_CERT_PATH,
      "firemud.grpc.workload-namespace=firemud-unit1b"
    })
class AccountCanonicalJoinReconciliationIntegrationTest {
  private static final String TEST_NAMESPACE = "firemud-unit1b";
  private static final int MAX_ATTEMPTS = 12;
  private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ISO_INSTANT;

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(registry, postgres, "account_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private AccountCanonicalJoinReconciliationService reconciliationService;
  @Autowired private AccountMembershipAuthorityEventProducer membershipEventProducer;
  @Autowired private AccountAuthorityGenerationRepository generations;
  @Autowired private AccountConnectScopeRepository connectScopes;
  @Autowired private AccountJoinOperationRepository joinOperations;
  @Autowired private AccountRepository accounts;
  @Autowired private AccountTenantMembershipRepository memberships;
  @Autowired private AccountTenantMembershipRoleSnapshotRepository roleSnapshots;
  @Autowired private AccountMembershipTransitionReceiptRepository receipts;
  @Autowired private AccountAuditOutboxRepository auditOutbox;
  @Autowired private FreshTenantIdentityAssociationRepository freshAssociations;

  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private LoggingAdminClient loggingAdminClient;
  @MockitoBean private JavaMailSender mailSender;

  @Test
  void completeHistoricalReadbackCommitsAfterExpiryAndLaterUnavailablePolicyAttempt() {
    JoinFixture fixture = fixture(true);
    persistEvidence(fixture, EvidenceShape.COMPLETE);
    recordLaterUnavailableAttempt(fixture);
    EvidenceCounts before = evidenceCounts(fixture);
    long fenceBefore = issuanceFence(fixture.account().accountUuid());
    Instant reconciliationAt = reconciliationTime(fixture);
    assertThat(expiresAt(fixture)).isBefore(reconciliationAt);

    reconciliationService.reconcileDueOperations(reconciliationAt);

    CanonicalJoinOperationEvidence committed = readOperation(fixture);
    assertThat(committed.status()).isEqualTo("COMMITTED");
    assertThat(committed.outcome()).isEqualTo("JOINED");
    assertThat(committed.membershipId()).isEqualTo(membershipId(fixture));
    assertThat(committed.membershipVersion()).isEqualTo(2L);
    assertThat(committed.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(committed.lastAttemptAuthorityAvailability()).isEqualTo("UNAVAILABLE");
    assertThat(committed.lastAttemptFailureCode()).isEqualTo("ENTITLEMENT_TIMEOUT");
    assertThat(committed.reconciliationAttemptCount()).isEqualTo(1);
    assertThat(evidenceCounts(fixture)).isEqualTo(before);
    assertThat(issuanceFence(fixture.account().accountUuid())).isEqualTo(fenceBefore);
  }

  @Test
  void eventReadbackRejectsStalePendingDtoAfterJournalHasTerminalized() {
    JoinFixture fixture = fixture(true);
    persistEvidence(fixture, EvidenceShape.COMPLETE);
    recordLaterUnavailableAttempt(fixture);
    CanonicalJoinOperationEvidence stalePending = readOperation(fixture);

    reconciliationService.reconcileDueOperations(reconciliationTime(fixture));

    assertThat(readOperation(fixture).status()).isEqualTo("COMMITTED");
    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> membershipEventProducer.requireCanonicalFirstJoinEvent(stalePending)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact persisted journal readback");
  }

  @Test
  void absentOrContradictoryOwnerEvidenceRemainsPendingWithoutReconciliationSideEffects() {
    List<Case> cases =
        List.of(
            new Case("policy not evaluated", null, EvidenceShape.NONE),
            new Case("policy denied", false, EvidenceShape.NONE),
            new Case("membership absent", true, EvidenceShape.NONE),
            new Case("roles absent", true, EvidenceShape.MEMBERSHIP_ONLY),
            new Case("role snapshot mismatch", true, EvidenceShape.MISMATCHED_ROLES),
            new Case("event and receipt absent", true, EvidenceShape.ROLES_ONLY),
            new Case("receipt absent", true, EvidenceShape.EVENT_ONLY),
            new Case("audit absent", true, EvidenceShape.RECEIPT_ONLY),
            new Case("audit payload mismatch", true, EvidenceShape.MISMATCHED_AUDIT));
    List<JoinFixture> fixtures =
        cases.stream()
            .map(
                candidate -> {
                  JoinFixture fixture = fixture(candidate.allowPublicJoin());
                  persistEvidence(fixture, candidate.evidenceShape());
                  recordLaterUnavailableAttempt(fixture);
                  return fixture;
                })
            .toList();
    Map<String, EvidenceCounts> before =
        fixtures.stream()
            .collect(
                java.util.stream.Collectors.toMap(JoinFixture::requestId, this::evidenceCounts));

    reconciliationService.reconcileDueOperations(Instant.now().plus(Duration.ofDays(2)));

    for (int index = 0; index < fixtures.size(); index++) {
      JoinFixture fixture = fixtures.get(index);
      String caseDescription = cases.get(index).description();
      CanonicalJoinOperationEvidence unresolved = readOperation(fixture);
      assertThat(unresolved.status()).as(caseDescription).isEqualTo("PENDING");
      assertThat(unresolved.outcome()).as(caseDescription).isNull();
      assertThat(unresolved.membershipId()).as(caseDescription).isNull();
      assertThat(unresolved.reconciliationAttemptCount()).as(caseDescription).isEqualTo(2);
      assertThat(unresolved.lastAttemptAuthorityAvailability())
          .as(caseDescription)
          .isEqualTo("UNAVAILABLE");
      assertThat(unresolved.lastAttemptFailureCode())
          .as(caseDescription)
          .isEqualTo("ENTITLEMENT_TIMEOUT");
      assertThat(evidenceCounts(fixture))
          .as(caseDescription)
          .isEqualTo(before.get(fixture.requestId()));
      if ("event and receipt absent".equals(caseDescription)) {
        assertThatThrownBy(
                () ->
                    inTransaction(
                        () ->
                            membershipEventProducer.requireCanonicalFirstJoinEvent(
                                joinOperations
                                    .findCanonicalEvidenceByRequestId(fixture.requestId())
                                    .orElseThrow())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no matching V33 event");
      }
    }
  }

  @Test
  void concurrentExactTerminalRetryAndReconcilerRetainOneHistoricalWriteSet() throws Exception {
    JoinFixture fixture = fixture(true);
    persistEvidence(fixture, EvidenceShape.COMPLETE);
    recordLaterUnavailableAttempt(fixture);
    EvidenceCounts before = evidenceCounts(fixture);
    Instant dueAt = reconciliationTime(fixture);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> reconcile =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                reconciliationService.reconcileDueOperations(dueAt);
              });
      Future<CanonicalJoinOperationEvidence> retry =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return exactTerminalRetry(fixture);
              });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      reconcile.get(30, TimeUnit.SECONDS);
      CanonicalJoinOperationEvidence retried = retry.get(30, TimeUnit.SECONDS);
      assertThat(retried.status()).isEqualTo("COMMITTED");
      assertThat(retried.outcome()).isEqualTo("JOINED");
      assertThat(exactTerminalRetry(fixture)).isEqualTo(retried);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    CanonicalJoinOperationEvidence committed = readOperation(fixture);
    assertThat(committed.status()).isEqualTo("COMMITTED");
    assertThat(committed.outcome()).isEqualTo("JOINED");
    assertThat(evidenceCounts(fixture)).isEqualTo(before);
  }

  private JoinFixture fixture(Boolean allowPublicJoin) {
    AccountFixture account = accountFixture();
    UUID tenantUuid = UUID.randomUUID();
    FreshTenantCreationEvidence source = freshTenantEvidence(tenantUuid);
    inTransaction(
        () -> {
          freshAssociations.importVerified(source);
          generations.initializeTenantIfAbsent(tenantUuid);
          generations.initializeIssuerIfAbsent(AccountServiceImpl.ACCOUNT_JWT_ISSUER);
          membershipEventProducer.readFreshNeverJoinedMembershipSnapshot(
              account.accountUuid(), tenantUuid);
          return null;
        });
    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            source.operationId(),
            source.evidenceDigest());
    Instant evaluatedAt = Instant.now().minus(Duration.ofHours(1));
    Instant expiresAt = Instant.now().minus(Duration.ofMinutes(30));
    CanonicalJoinScopeV2 scope =
        new CanonicalJoinScopeV2(
            "canonical-recovery-" + UUID.randomUUID(),
            account.accountUuid(),
            tenantUuid,
            UUID.randomUUID(),
            "recovery-tenant-" + shortUuid(),
            "recovery-world-" + shortUuid(),
            "production",
            UUID.randomUUID(),
            "SHARED",
            UUID.randomUUID(),
            3L,
            2L,
            RFC3339.format(evaluatedAt.atOffset(ZoneOffset.UTC)),
            RFC3339.format(expiresAt.atOffset(ZoneOffset.UTC)));
    String requestId = UUID.randomUUID().toString();
    String callerBinding = "synthetic-caller-" + UUID.randomUUID();
    inTransaction(
        () -> {
          connectScopes.insertCanonical(account.accountId(), scope, provenance);
          joinOperations.insertCanonicalIntent(requestId, scope, callerBinding);
          if (allowPublicJoin != null) {
            joinOperations.bindCanonicalPolicyEvidence(
                requestId, scope, callerBinding, allowPublicJoin, 11L);
          }
          return null;
        });
    return new JoinFixture(account, tenantUuid, provenance, scope, requestId, callerBinding);
  }

  private void persistEvidence(JoinFixture fixture, EvidenceShape shape) {
    inTransactionWithoutResult(
        () -> {
          if (shape.membership()) {
            Account account =
                accounts.findByAccountUuid(fixture.account().accountUuid()).orElseThrow();
            AccountTenantMembership membership = new AccountTenantMembership();
            membership.setAccount(account);
            membership.setTenantId(fixture.provenance().legacyTenantId());
            membership.setTenantUuid(fixture.tenantUuid());
            membership.setTenantProvenanceKind(fixture.provenance().kind().name());
            membership.setTenantSourceOperationId(fixture.provenance().sourceOperationId());
            membership.setTenantProvenanceDigest(fixture.provenance().digest());
            membership.setGameplayAdmissionAllowed(true);
            membership.setLifecycleState("ACTIVE");
            membership.setMembershipVersion(2L);
            membership.setMembershipAuthorityGeneration(1L);
            membership.setAuthorityProvenance("EXPLICIT_JOIN");
            memberships.saveCanonical(
                membership,
                fixture.account().accountUuid(),
                fixture.tenantUuid(),
                fixture.provenance());
            if (shape.roles()) {
              roleSnapshots.replaceCanonical(
                  membership,
                  fixture.account().accountUuid(),
                  fixture.tenantUuid(),
                  fixture.provenance(),
                  2L,
                  shape.mismatchedRoles() ? List.of("player", "moderator") : List.of("player"));
            }
          }
          if (shape.event()) {
            membershipEventProducer.publishCanonicalFirstJoinMembershipChange(
                fixture.scope(), fixture.requestId(), fixture.callerBinding());
          }
          if (shape.receipt()) {
            receipts.appendCanonicalTransition(
                fixture.account().accountUuid(),
                fixture.tenantUuid(),
                "MEMBERSHIP_JOINED",
                fixture.requestId());
          }
          if (shape.audit()) {
            UUID payloadAccountUuid =
                shape.mismatchedAudit() ? UUID.randomUUID() : fixture.account().accountUuid();
            String payload =
                AccountAuditOutboxRepository.canonicalJoinPayload(
                    payloadAccountUuid,
                    fixture.tenantUuid(),
                    fixture.scope().worldSlug(),
                    fixture.scope().realmSlug(),
                    Map.of(fixture.tenantUuid().toString(), "2"),
                    fixture.requestId());
            auditOutbox.appendCanonicalTenant(
                auditEventId(fixture.requestId()),
                fixture.tenantUuid().toString(),
                "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
                payload);
          }
        });
  }

  private void recordLaterUnavailableAttempt(JoinFixture fixture) {
    Instant diagnosticAt = Instant.now();
    inTransactionWithoutResult(
        () -> {
          joinOperations.recordCanonicalPolicyUnavailable(
              fixture.requestId(), fixture.scope(), fixture.callerBinding(), "ENTITLEMENT_TIMEOUT");
          joinOperations.recordCanonicalReconciliationAttempt(
              fixture.requestId(),
              0,
              MAX_ATTEMPTS,
              diagnosticAt,
              "CANONICAL_JOIN_READBACK_UNAVAILABLE",
              diagnosticAt);
        });
  }

  private CanonicalJoinOperationEvidence exactTerminalRetry(JoinFixture fixture) {
    return inTransaction(
        () ->
            joinOperations.finishCanonicalOperation(
                fixture.requestId(), "COMMITTED", "JOINED", membershipId(fixture), 2L, 1L));
  }

  private AccountFixture accountFixture() {
    String suffix = shortUuid();
    long accountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                    "canonical-reconcile-" + suffix,
                    "canonical-reconcile-" + suffix + "@example.test",
                    "synthetic-fixture-hash")
                .fetchOne(0, Long.class));
    UUID accountUuid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, UUID.class));
    dsl.execute(
        "INSERT INTO account_authority_generations "
            + "(scope_kind, account_uuid, generation, source_version) VALUES ('ACCOUNT', ?, 1, 1)",
        accountUuid);
    dsl.execute(
        "INSERT INTO account_authority_issuance_fences "
            + "(account_uuid, issuance_fence, source_version) VALUES (?, 1, 1)",
        accountUuid);
    inTransactionWithoutResult(
        () -> generations.initializeIssuerIfAbsent(AccountServiceImpl.ACCOUNT_JWT_ISSUER));
    return new AccountFixture(accountId, accountUuid);
  }

  private FreshTenantCreationEvidence freshTenantEvidence(UUID tenantUuid) {
    UUID creationRequestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String sourceTenantKey = "f-" + shortUuid();
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            TEST_NAMESPACE, creationRequestId, sourceTenantKey, "Canonical recovery fixture", null);
    long sourceGameRowId = positiveRandomLong();
    String provenanceKind = "NEW_GAME_ROW";
    return new FreshTenantCreationEvidence(
        1,
        TEST_NAMESPACE,
        creationRequestId,
        operationId,
        requestDigest,
        tenantUuid,
        sourceGameRowId,
        sourceTenantKey,
        provenanceKind,
        GameTenantCreationDigest.evidenceDigest(
            TEST_NAMESPACE,
            creationRequestId,
            operationId,
            requestDigest,
            tenantUuid,
            sourceGameRowId,
            sourceTenantKey,
            provenanceKind));
  }

  private CanonicalJoinOperationEvidence readOperation(JoinFixture fixture) {
    return inTransaction(
        () -> joinOperations.findCanonicalEvidenceByRequestId(fixture.requestId()).orElseThrow());
  }

  private long membershipId(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT id FROM account_tenant_membership "
                    + "WHERE account_id = ? AND tenant_uuid = ?",
                fixture.account().accountId(),
                fixture.tenantUuid())
            .fetchOne(0, Long.class));
  }

  private EvidenceCounts evidenceCounts(JoinFixture fixture) {
    return new EvidenceCounts(
        count(
            "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = ? AND tenant_uuid = ?",
            fixture.account().accountId(),
            fixture.tenantUuid()),
        count(
            "SELECT COUNT(*) FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
            eventStreamKey(fixture)),
        count(
            "SELECT COUNT(*) FROM account_membership_transition_receipts "
                + "WHERE account_uuid = ? AND tenant_uuid = ? AND receipt_version = 2",
            fixture.account().accountUuid(),
            fixture.tenantUuid()),
        count(
            "SELECT COUNT(*) FROM account_audit_outbox WHERE audit_event_id = ?",
            auditEventId(fixture.requestId())));
  }

  private long count(String sql, Object... bindings) {
    return Objects.requireNonNull(dsl.resultQuery(sql, bindings).fetchOne(0, Long.class));
  }

  private long issuanceFence(UUID accountUuid) {
    return count(
        "SELECT issuance_fence FROM account_authority_issuance_fences WHERE account_uuid = ?",
        accountUuid);
  }

  private Instant expiresAt(JoinFixture fixture) {
    return Instant.parse(fixture.scope().connectScopeExpiresAt());
  }

  private Instant reconciliationTime(JoinFixture fixture) {
    return expiresAt(fixture).plus(Duration.ofDays(1));
  }

  private String eventStreamKey(JoinFixture fixture) {
    return "account:auth-authority:v1:membership/"
        + fixture.account().accountUuid()
        + "/"
        + fixture.tenantUuid();
  }

  private static UUID auditEventId(String requestId) {
    return UUID.nameUUIDFromBytes(
        ("account-join-audit/v1:" + requestId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private <T> T inTransaction(java.util.function.Supplier<T> callback) {
    return new TransactionTemplate(transactionManager).execute(status -> callback.get());
  }

  private void inTransactionWithoutResult(Runnable callback) {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> callback.run());
  }

  private static String shortUuid() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private static long positiveRandomLong() {
    long candidate = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    return candidate == 0L ? 1L : candidate;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent reconciliation barrier timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent reconciliation was interrupted", exception);
    }
  }

  private record AccountFixture(long accountId, UUID accountUuid) {}

  private record JoinFixture(
      AccountFixture account,
      UUID tenantUuid,
      VerifiedTenantProvenance provenance,
      CanonicalJoinScopeV2 scope,
      String requestId,
      String callerBinding) {}

  private record EvidenceCounts(long memberships, long events, long receipts, long audits) {}

  private record Case(String description, Boolean allowPublicJoin, EvidenceShape evidenceShape) {}

  private enum EvidenceShape {
    NONE(false, false, false, false, false, false),
    MEMBERSHIP_ONLY(true, false, false, false, false, false),
    ROLES_ONLY(true, true, false, false, false, false),
    MISMATCHED_ROLES(true, true, false, false, false, false, true),
    EVENT_ONLY(true, true, true, false, false, false),
    RECEIPT_ONLY(true, true, true, true, false, false),
    MISMATCHED_AUDIT(true, true, true, true, true, true),
    COMPLETE(true, true, true, true, true, false);

    private final boolean membership;
    private final boolean roles;
    private final boolean event;
    private final boolean receipt;
    private final boolean audit;
    private final boolean mismatchedAudit;
    private final boolean mismatchedRoles;

    EvidenceShape(
        boolean membership,
        boolean roles,
        boolean event,
        boolean receipt,
        boolean audit,
        boolean mismatchedAudit) {
      this(membership, roles, event, receipt, audit, mismatchedAudit, false);
    }

    EvidenceShape(
        boolean membership,
        boolean roles,
        boolean event,
        boolean receipt,
        boolean audit,
        boolean mismatchedAudit,
        boolean mismatchedRoles) {
      this.membership = membership;
      this.roles = roles;
      this.event = event;
      this.receipt = receipt;
      this.audit = audit;
      this.mismatchedAudit = mismatchedAudit;
      this.mismatchedRoles = mismatchedRoles;
    }

    boolean membership() {
      return membership;
    }

    boolean roles() {
      return roles;
    }

    boolean event() {
      return event;
    }

    boolean receipt() {
      return receipt;
    }

    boolean audit() {
      return audit;
    }

    boolean mismatchedAudit() {
      return mismatchedAudit;
    }

    boolean mismatchedRoles() {
      return mismatchedRoles;
    }
  }
}
