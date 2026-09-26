package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinScope;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionResult;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceiptDigest;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.v1.GameplayRealm;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = AccountServiceApplication.class,
    properties = {
      GatewayTestProperties.SPRING_GRPC_SERVER_SSL_DISABLED,
      GatewayTestProperties.FIREMUD_GRPC_CERT_CHAIN_PATH,
      GatewayTestProperties.FIREMUD_GRPC_PRIVATE_KEY_PATH,
      GatewayTestProperties.FIREMUD_GRPC_CA_CERT_PATH
    })
class AccountJoinPostgresIntegrationTest {
  private static final UUID REALM_ID = UUID.fromString("4c4b57d8-e3a2-48fe-9977-e7df0fdce901");
  private static final String WORLD_SLUG = "join-proof-world";
  private static final String REALM_SLUG = "production";
  private static final String NAMESPACE_ID = "join-proof-namespace";
  private static final long GAME_INSTANCE_ID = 44L;
  private static final long CATALOG_REVISION = 23L;
  private static final long POINTER_VERSION = 17L;

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
  @Autowired private AccountService accountService;

  @Autowired
  private AccountMembershipTransitionReceiptRepository membershipTransitionReceiptRepository;

  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private LoggingAdminClient loggingAdminClient;
  @MockitoBean private JavaMailSender mailSender;
  @MockitoSpyBean private AccountJoinOperationRepository joinOperationRepository;
  private final Map<Long, GameplayRealm> fixtureRealms = new LinkedHashMap<>();

  @Test
  void terminalCommitWithLostAcknowledgementReadsBackExactStoredJoinResult() {
    JoinFixture fixture = fixture("active");
    AtomicBoolean loseNextTerminalAcknowledgement = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              if ("COMMITTED".equals(invocation.getArgument(1))
                  && loseNextTerminalAcknowledgement.compareAndSet(true, false)) {
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                      @Override
                      public void afterCommit() {
                        throw new IllegalStateException(
                            "simulated lost terminal commit acknowledgement");
                      }
                    });
              }
              return null;
            })
        .when(joinOperationRepository)
        .finish(anyString(), anyString(), anyString(), any(), any(), any());

    JoinPublicProductionResult result = join(fixture);

    assertThat(result)
        .isEqualTo(
            new JoinPublicProductionResult(
                true,
                "JOINED",
                fixture.accountId(),
                fixture.tenantId(),
                result.membershipId(),
                1L,
                1L,
                true));
    assertThat(result.membershipId()).isPositive();
    assertThat(
            dsl.fetchOne(
                "SELECT status, outcome, request_digest, request_digest_version, entitlement_version, allow_public_join, membership_id, outcome_membership_version, outcome_membership_authority_generation "
                    + "FROM account_join_operations WHERE request_id = ?",
                fixture.requestId()))
        .satisfies(
            row -> {
              assertThat(row.get("status", String.class)).isEqualTo("COMMITTED");
              assertThat(row.get("outcome", String.class)).isEqualTo("JOINED");
              assertThat(row.get("request_digest", String.class)).startsWith("sha256:");
              assertThat(row.get("request_digest_version", Integer.class)).isEqualTo(1);
              assertThat(row.get("entitlement_version", Long.class)).isEqualTo(1L);
              assertThat(row.get("allow_public_join", Boolean.class)).isTrue();
              assertThat(row.get("membership_id", Long.class)).isEqualTo(result.membershipId());
              assertThat(row.get("outcome_membership_version", Long.class)).isEqualTo(1L);
              assertThat(row.get("outcome_membership_authority_generation", Long.class))
                  .isEqualTo(1L);
            });
    assertTransitionAndAuditOutboxOnce(fixture, result.membershipId());
  }

  @Test
  void rolledBackTerminalAttemptStaysPendingAndSameRequestCommitsOnceOnRetry() {
    JoinFixture fixture = fixture("active");
    AtomicBoolean failNextTerminalAttempt = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              if ("COMMITTED".equals(invocation.getArgument(1))
                  && failNextTerminalAttempt.compareAndSet(true, false)) {
                throw new IllegalStateException(
                    "simulated failure before terminal transaction commit");
              }
              return null;
            })
        .when(joinOperationRepository)
        .finish(anyString(), anyString(), anyString(), any(), any(), any());

    AuthenticationException uncertain =
        assertThrows(AuthenticationException.class, () -> join(fixture));

    assertThat(uncertain.getCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(
            dsl.fetchOne(
                "SELECT status, outcome, request_digest, membership_id, last_attempt_failure_code "
                    + "FROM account_join_operations WHERE request_id = ?",
                fixture.requestId()))
        .satisfies(
            row -> {
              assertThat(row.get("status", String.class)).isEqualTo("PENDING");
              assertThat(row.get("outcome", String.class)).isNull();
              assertThat(row.get("request_digest", String.class)).isNull();
              assertThat(row.get("membership_id", Long.class)).isNull();
              assertThat(row.get("last_attempt_failure_code", String.class))
                  .isEqualTo("AUTH_UNAVAILABLE");
            });
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countJoinOutbox(fixture)).isZero();
    assertThat(countMembershipTransitionReceipts(fixture)).isZero();

    JoinPublicProductionResult recovered = join(fixture);

    assertThat(recovered.success()).isTrue();
    assertThat(recovered.outcomeCode()).isEqualTo("JOINED");
    assertThat(recovered.replayed()).isFalse();
    assertThat(countMemberships(fixture)).isEqualTo(1L);
    assertTransitionAndAuditOutboxOnce(fixture, recovered.membershipId());
  }

  @Test
  void failedJoinReplaysStoredOutcomeAndRejectsChangedPolicyDigest() {
    JoinFixture fixture = fixture("grace");

    JoinPublicProductionResult failed = join(fixture);
    JoinPublicProductionResult replayed = join(fixture);

    assertThat(failed.success()).isFalse();
    assertThat(failed.outcomeCode()).isEqualTo("PUBLIC_PRODUCTION_ADMISSION_DENIED");
    assertThat(failed.replayed()).isFalse();
    assertThat(replayed)
        .isEqualTo(
            new JoinPublicProductionResult(
                failed.success(),
                failed.outcomeCode(),
                failed.accountId(),
                failed.tenantId(),
                failed.membershipId(),
                failed.membershipVersion(),
                failed.membershipAuthorityGeneration(),
                true));
    String originalDigest =
        dsl.resultQuery(
                "SELECT request_digest FROM account_join_operations WHERE request_id = ?",
                fixture.requestId())
            .fetchOne(0, String.class);
    assertThat(originalDigest).startsWith("sha256:");
    assertThat(
            dsl.resultQuery(
                    "SELECT status || ':' || outcome FROM account_join_operations WHERE request_id = ?",
                    fixture.requestId())
                .fetchOne(0, String.class))
        .isEqualTo("FAILED:PUBLIC_PRODUCTION_ADMISSION_DENIED");
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countJoinOutbox(fixture)).isZero();

    dsl.execute(
        "UPDATE subscription SET status = 'active', entitlement_version = 2 WHERE tenant_id = ?",
        fixture.tenantId());
    AuthenticationException conflict =
        assertThrows(AuthenticationException.class, () -> join(fixture));

    assertThat(conflict.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(
            dsl.resultQuery(
                    "SELECT request_digest FROM account_join_operations WHERE request_id = ?",
                    fixture.requestId())
                .fetchOne(0, String.class))
        .isEqualTo(originalDigest);
    assertThat(
            dsl.resultQuery(
                    "SELECT status || ':' || outcome FROM account_join_operations WHERE request_id = ?",
                    fixture.requestId())
                .fetchOne(0, String.class))
        .isEqualTo("FAILED:PUBLIC_PRODUCTION_ADMISSION_DENIED");
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countJoinOutbox(fixture)).isZero();
    assertThat(countMembershipTransitionReceipts(fixture)).isZero();
  }

  @Test
  void exactJoinRetryDoesNotAppendAnotherMembershipTransitionReceipt() {
    JoinFixture fixture = fixture("active");

    JoinPublicProductionResult joined = join(fixture);
    JoinPublicProductionResult replayed = join(fixture);

    assertThat(joined.success()).isTrue();
    assertThat(joined.outcomeCode()).isEqualTo("JOINED");
    assertThat(replayed.replayed()).isTrue();
    assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(1L);
    assertMembershipTransitionReceipt(fixture, "MEMBERSHIP_JOINED", 1L);
  }

  @Test
  void newRequestForRetainedActiveMembershipIsEventFreeAndRequiresPositiveHistory() {
    JoinFixture initialJoin = fixture("active");
    assertThat(join(initialJoin).outcomeCode()).isEqualTo("JOINED");
    JoinFixture alreadyActive = fixtureForMembership(initialJoin);

    JoinPublicProductionResult result = join(alreadyActive);

    assertThat(result.success()).isTrue();
    assertThat(result.outcomeCode()).isEqualTo("ALREADY_ACTIVE");
    assertThat(countMembershipTransitionReceipts(alreadyActive)).isEqualTo(1L);
    assertMembershipTransitionReceipt(initialJoin, "MEMBERSHIP_JOINED", 1L);
    assertThat(countTenantJoinOutboxEvents(initialJoin)).isEqualTo(1L);
    assertThat(countTenantJoinOutboxEvents(alreadyActive)).isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_join_operations "
                        + "WHERE request_id = ? AND status = 'COMMITTED' AND outcome = 'ALREADY_ACTIVE'",
                    alreadyActive.requestId())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void membershipTransitionReceiptSequenceIsIndependentForEachAccountTenantStream() {
    JoinFixture first = fixture("active");
    JoinFixture second = fixture("active", first.accountId());

    assertThat(join(first).success()).isTrue();
    assertThat(join(second).success()).isTrue();

    assertMembershipTransitionReceipt(first, "MEMBERSHIP_JOINED", 1L);
    assertMembershipTransitionReceipt(second, "MEMBERSHIP_JOINED", 1L);
    assertThat(receiptStreamKey(first)).isNotEqualTo(receiptStreamKey(second));
  }

  @Test
  void reactivationAppendsTheNextPositiveMembershipTransitionReceiptSequence() {
    JoinFixture initialJoin = fixture("active");
    assertThat(join(initialJoin).success()).isTrue();
    assertMembershipTransitionReceipt(initialJoin, "MEMBERSHIP_JOINED", 1L);

    dsl.execute(
        "UPDATE account_tenant_membership SET lifecycle_state = 'INACTIVE', "
            + "gameplay_admission_allowed = FALSE WHERE account_id = ? AND tenant_id = ?",
        initialJoin.accountId(),
        initialJoin.tenantId());
    JoinFixture reactivation = fixtureForMembership(initialJoin);

    JoinPublicProductionResult result = join(reactivation);

    assertThat(result.success()).isTrue();
    assertThat(result.outcomeCode()).isEqualTo("JOINED");
    assertMembershipTransitionReceipt(reactivation, "MEMBERSHIP_REACTIVATED", 2L);
    assertThat(countMembershipTransitionReceipts(reactivation)).isEqualTo(2L);
    assertThat(
            dsl.resultQuery(
                    "SELECT membership_version, membership_authority_generation "
                        + "FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
                    reactivation.accountId(),
                    reactivation.tenantId())
                .fetchOne())
        .satisfies(
            row -> {
              assertThat(row.get("membership_version", Long.class)).isEqualTo(2L);
              assertThat(row.get("membership_authority_generation", Long.class)).isEqualTo(2L);
            });
  }

  @Test
  void committedMembershipTransitionReceiptHistorySurvivesAbsenceOfCurrentMembershipRow() {
    JoinFixture fixture = fixture("active");
    assertThat(join(fixture).success()).isTrue();
    assertMembershipTransitionReceipt(fixture, "MEMBERSHIP_JOINED", 1L);

    dsl.execute("DELETE FROM account_join_operations WHERE request_id = ?", fixture.requestId());

    dsl.execute(
        "DELETE FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
        fixture.accountId(),
        fixture.tenantId());

    assertThat(countMemberships(fixture)).isZero();
    assertMembershipTransitionReceipt(fixture, "MEMBERSHIP_JOINED", 1L);
  }

  @Test
  void absentMembershipWithPriorAuditHistoryCannotRestartAtSequenceOne() {
    JoinFixture initialJoin = fixture("active");
    assertThat(join(initialJoin).success()).isTrue();
    dsl.execute(
        "DELETE FROM account_membership_transition_receipts WHERE account_id = ? AND tenant_id = ?",
        initialJoin.accountId(),
        initialJoin.tenantId());
    dsl.execute(
        "DELETE FROM account_membership_transition_receipt_stream_heads "
            + "WHERE account_id = ? AND tenant_id = ?",
        initialJoin.accountId(),
        initialJoin.tenantId());
    dsl.execute(
        "DELETE FROM account_join_operations WHERE request_id = ?", initialJoin.requestId());
    dsl.execute(
        "DELETE FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
        initialJoin.accountId(),
        initialJoin.tenantId());
    JoinFixture retry = fixtureForMembership(initialJoin);

    AuthenticationException blocked =
        assertThrows(AuthenticationException.class, () -> join(retry));

    assertThat(blocked.getCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(countMemberships(retry)).isZero();
    assertThat(countMembershipTransitionReceipts(retry)).isZero();
    assertThat(countTenantJoinOutboxEvents(retry)).isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT status FROM account_join_operations WHERE request_id = ?",
                    retry.requestId())
                .fetchOne(0, String.class))
        .isEqualTo("PENDING");
  }

  @Test
  void absentMembershipWithRetainedReceiptFailsClosedWithoutResettingSequence() {
    JoinFixture initialJoin = fixture("active");
    assertThat(join(initialJoin).success()).isTrue();
    dsl.execute(
        "DELETE FROM account_join_operations WHERE request_id = ?", initialJoin.requestId());
    dsl.execute(
        "DELETE FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
        initialJoin.accountId(),
        initialJoin.tenantId());
    JoinFixture retry = fixtureForMembership(initialJoin);

    AuthenticationException blocked =
        assertThrows(AuthenticationException.class, () -> join(retry));

    assertThat(blocked.getCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(countMemberships(retry)).isZero();
    assertThat(countMembershipTransitionReceipts(retry)).isEqualTo(1L);
    assertMembershipTransitionReceipt(initialJoin, "MEMBERSHIP_JOINED", 1L);
    assertThat(countTenantJoinOutboxEvents(retry)).isEqualTo(1L);
  }

  @Test
  void retainedActiveOrInactiveMembershipWithoutTransitionReceiptFailsClosed() {
    JoinFixture active = fixture("active");
    JoinFixture inactive = fixture("active");
    dsl.execute(
        "INSERT INTO account_tenant_membership "
            + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
            + "membership_version, membership_authority_generation, authority_provenance) "
            + "VALUES (?, ?, TRUE, 'ACTIVE', 1, 1, 'EXPLICIT_JOIN')",
        active.accountId(),
        active.tenantId());
    dsl.execute(
        "INSERT INTO account_tenant_membership "
            + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
            + "membership_version, membership_authority_generation, authority_provenance) "
            + "VALUES (?, ?, FALSE, 'INACTIVE', 1, 1, 'EXPLICIT_JOIN')",
        inactive.accountId(),
        inactive.tenantId());

    IllegalStateException activeFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                membershipTransitionReceiptRepository.findLatestReceipt(
                    active.accountId(), active.tenantId()));
    IllegalStateException inactiveFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                membershipTransitionReceiptRepository.findLatestReceipt(
                    inactive.accountId(), inactive.tenantId()));

    assertThat(activeFailure)
        .hasMessageContaining("Account membership exists without a provisional transition receipt");
    assertThat(inactiveFailure)
        .hasMessageContaining("Account membership exists without a provisional transition receipt");
    assertThat(countMembershipTransitionReceipts(active)).isZero();
    assertThat(countMembershipTransitionReceipts(inactive)).isZero();

    AuthenticationException activeJoinFailure =
        assertThrows(AuthenticationException.class, () -> join(active));
    AuthenticationException inactiveJoinFailure =
        assertThrows(AuthenticationException.class, () -> join(inactive));

    assertThat(activeJoinFailure.getCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(inactiveJoinFailure.getCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(countMembershipTransitionReceipts(active)).isZero();
    assertThat(countMembershipTransitionReceipts(inactive)).isZero();
    assertThat(
            dsl.resultQuery(
                    "SELECT lifecycle_state FROM account_tenant_membership "
                        + "WHERE account_id = ? AND tenant_id = ?",
                    inactive.accountId(),
                    inactive.tenantId())
                .fetchOne(0, String.class))
        .isEqualTo("INACTIVE");
  }

  private JoinFixture fixture(String subscriptionStatus) {
    String suffix = UUID.randomUUID().toString();
    long accountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                    "join-proof-" + suffix,
                    "join-proof-" + suffix + "@example.com",
                    "test-hash")
                .fetchOne(0, Long.class));
    return fixture(subscriptionStatus, accountId, null, suffix);
  }

  private JoinFixture fixture(String subscriptionStatus, long accountId) {
    return fixture(subscriptionStatus, accountId, null, UUID.randomUUID().toString());
  }

  private JoinFixture fixtureForMembership(JoinFixture existing) {
    return fixture(
        "active", existing.accountId(), existing.tenantId(), UUID.randomUUID().toString());
  }

  private JoinFixture fixture(
      String subscriptionStatus, long accountId, Long existingTenantId, String suffix) {
    String requestId = "join-proof-" + suffix;
    long tenantId =
        existingTenantId == null
            ? UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE
            : existingTenantId;
    if (existingTenantId == null) {
      dsl.execute(
          "INSERT INTO subscription (account_id, tenant_id, plan_id, status, entitlement_version) VALUES (?, ?, ?, ?, 1)",
          accountId,
          tenantId,
          "join-proof",
          subscriptionStatus);
    }

    GameplayRealm realm =
        GameplayRealm.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setRealmId(REALM_ID.toString())
            .setWorldSlug(WORLD_SLUG)
            .setRealmSlug(REALM_SLUG)
            .setPlayableStateNamespaceId(NAMESPACE_ID)
            .setGameInstanceId(Long.toString(GAME_INSTANCE_ID))
            .setCatalogRevision(CATALOG_REVISION)
            .setPointerVersion(POINTER_VERSION)
            .setVisible(true)
            .setPublicProductionRealm(true)
            .setStateScope("SHARED")
            .build();
    GameplayAdmissionPointer pointer =
        GameplayAdmissionPointer.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setRealmId(REALM_ID.toString())
            .setWorldSlug(WORLD_SLUG)
            .setRealmSlug(REALM_SLUG)
            .setPlayableStateNamespaceId(NAMESPACE_ID)
            .setGameInstanceId(Long.toString(GAME_INSTANCE_ID))
            .setCatalogRevision(CATALOG_REVISION)
            .setPointerVersion(POINTER_VERSION)
            .setVisible(true)
            .setPublicProductionRealm(true)
            .setStateScope("SHARED")
            .build();
    fixtureRealms.put(tenantId, realm);
    when(gameSessionClient.listGameplayRealms(WORLD_SLUG))
        .thenReturn(List.copyOf(fixtureRealms.values()));
    when(gameSessionClient.getAdmissionPointer(tenantId, WORLD_SLUG, REALM_SLUG))
        .thenReturn(pointer);

    DirectTextCallerContext caller =
        new DirectTextCallerContext(
            accountId,
            tenantId,
            REALM_ID,
            NAMESPACE_ID,
            "SHARED",
            GAME_INSTANCE_ID,
            "join-session-" + suffix,
            requestId);
    DirectTextJoinTarget target =
        new DirectTextJoinTarget(
            tenantId,
            REALM_ID,
            WORLD_SLUG,
            REALM_SLUG,
            NAMESPACE_ID,
            "SHARED",
            GAME_INSTANCE_ID,
            CATALOG_REVISION,
            POINTER_VERSION);
    DirectTextJoinScope scope = accountService.issueDirectTextConnectScope(caller, target);
    return new JoinFixture(accountId, tenantId, requestId, caller, scope.connectScopeId());
  }

  private long countMemberships(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne(0, Long.class));
  }

  private JoinPublicProductionResult join(JoinFixture fixture) {
    return accountService.joinPublicProductionFromGameSession(
        fixture.caller(),
        new JoinPublicProductionRequest(fixture.connectScopeId(), fixture.requestId()));
  }

  private long countJoinOutbox(JoinFixture fixture) {
    UUID eventId =
        UUID.nameUUIDFromBytes(
            ("account-join-audit/v1:" + fixture.requestId()).getBytes(StandardCharsets.UTF_8));
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_audit_outbox WHERE audit_event_id = ? AND scope = 'tenant' AND tenant_id = ? AND event_type = 'ACCOUNT_JOINED_PUBLIC_PRODUCTION'",
                eventId,
                fixture.tenantId())
            .fetchOne(0, Long.class));
  }

  private long countTenantJoinOutboxEvents(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_audit_outbox WHERE scope = 'tenant' "
                    + "AND tenant_id = ? AND event_type = 'ACCOUNT_JOINED_PUBLIC_PRODUCTION'",
                fixture.tenantId())
            .fetchOne(0, Long.class));
  }

  private long countMembershipTransitionReceipts(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_membership_transition_receipts "
                    + "WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne(0, Long.class));
  }

  private String receiptStreamKey(JoinFixture fixture) {
    return MembershipTransitionReceiptDigest.receiptStreamKey(
        fixture.accountId(), fixture.tenantId());
  }

  private void assertMembershipTransitionReceipt(
      JoinFixture fixture, String transitionType, long expectedSequence) {
    var row =
        dsl.resultQuery(
                "SELECT receipt_stream_key, receipt_sequence, evidence_status, transition_type, "
                    + "request_id, membership_id, membership_lifecycle_state, "
                    + "gameplay_admission_allowed, membership_version, "
                    + "membership_authority_generation, authority_provenance, receipt_id, "
                    + "receipt_digest FROM account_membership_transition_receipts "
                    + "WHERE account_id = ? AND tenant_id = ? AND receipt_sequence = ?",
                fixture.accountId(),
                fixture.tenantId(),
                expectedSequence)
            .fetchOne();
    assertThat(row).isNotNull();
    String streamKey = row.get("receipt_stream_key", String.class);
    long sequence = row.get("receipt_sequence", Long.class);
    UUID receiptId = row.get("receipt_id", UUID.class);
    String receiptDigest = row.get("receipt_digest", String.class);
    long membershipId = row.get("membership_id", Long.class);
    String lifecycleState = row.get("membership_lifecycle_state", String.class);
    boolean admissionAllowed = row.get("gameplay_admission_allowed", Boolean.class);
    long membershipVersion = row.get("membership_version", Long.class);
    long authorityGeneration = row.get("membership_authority_generation", Long.class);
    String authorityProvenance = row.get("authority_provenance", String.class);
    String requestId = row.get("request_id", String.class);

    assertThat(streamKey).isEqualTo(receiptStreamKey(fixture));
    assertThat(sequence).isEqualTo(expectedSequence);
    String evidenceStatus = row.get("evidence_status", String.class);
    assertThat(evidenceStatus).isEqualTo(MembershipTransitionReceiptDigest.EVIDENCE_STATUS);
    assertThat(row.get("transition_type", String.class)).isEqualTo(transitionType);
    assertThat(receiptId)
        .isEqualTo(MembershipTransitionReceiptDigest.receiptIdForRequest(requestId));
    assertThat(receiptDigest)
        .isEqualTo(
            MembershipTransitionReceiptDigest.transitionDigest(
                streamKey,
                sequence,
                receiptId,
                transitionType,
                requestId,
                fixture.accountId(),
                fixture.tenantId(),
                membershipId,
                lifecycleState,
                admissionAllowed,
                membershipVersion,
                authorityGeneration,
                authorityProvenance));
    assertThat(
            membershipTransitionReceiptRepository.findLatestReceipt(
                fixture.accountId(), fixture.tenantId()))
        .contains(
            new MembershipTransitionReceipt(
                streamKey,
                sequence,
                receiptId,
                receiptDigest,
                evidenceStatus,
                transitionType,
                requestId,
                membershipId));
  }

  private void assertTransitionAndAuditOutboxOnce(JoinFixture fixture, long membershipId) {
    assertThat(countMemberships(fixture)).isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_tenant_membership WHERE id = ? AND account_id = ? AND tenant_id = ? AND lifecycle_state = 'ACTIVE' AND authority_provenance = 'EXPLICIT_JOIN' AND membership_version = 1 AND membership_authority_generation = 1",
                    membershipId,
                    fixture.accountId(),
                    fixture.tenantId())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(countJoinOutbox(fixture)).isEqualTo(1L);
    assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(1L);
    assertMembershipTransitionReceipt(fixture, "MEMBERSHIP_JOINED", 1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_audit_outbox WHERE audit_event_id = ? AND payload LIKE ?",
                    UUID.nameUUIDFromBytes(
                        ("account-join-audit/v1:" + fixture.requestId())
                            .getBytes(StandardCharsets.UTF_8)),
                    "%" + fixture.requestId() + "%")
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
  }

  private record JoinFixture(
      long accountId,
      long tenantId,
      String requestId,
      DirectTextCallerContext caller,
      String connectScopeId) {}
}
