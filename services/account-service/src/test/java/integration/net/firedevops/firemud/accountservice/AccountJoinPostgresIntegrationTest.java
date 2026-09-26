package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
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
      GatewayTestProperties.FIREMUD_GRPC_CA_CERT_PATH,
      "firemud.grpc.workload-namespace=account_service"
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

  @Autowired private ApprovedLegacyTenantAssociationRepository tenantAssociationRepository;
  @Autowired private LegacyTenantSourceEvidence legacyTenantSourceEvidence;

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
    assertRoleSnapshot(fixture, result.membershipId(), 1L, List.of("player"));
    assertAuthorityMembershipEvent(fixture, 1L, 1L, false);
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
    assertThat(countRoleSnapshotHeaders(fixture)).isZero();
    assertThat(countRoleSnapshotRows(fixture)).isZero();
    assertThat(countAuthorityMembershipEvents(fixture)).isZero();
    assertThat(countAuthorityMembershipStreams(fixture)).isZero();
    assertThat(countMembershipAuthorityGenerations(fixture)).isZero();

    JoinPublicProductionResult recovered = join(fixture);

    assertThat(recovered.success()).isTrue();
    assertThat(recovered.outcomeCode()).isEqualTo("JOINED");
    assertThat(recovered.replayed()).isFalse();
    assertThat(countMemberships(fixture)).isEqualTo(1L);
    assertTransitionAndAuditOutboxOnce(fixture, recovered.membershipId());
    assertRoleSnapshot(fixture, recovered.membershipId(), 1L, List.of("player"));
    assertAuthorityMembershipEvent(fixture, 1L, 1L, false);
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
    assertThat(countRoleSnapshotHeaders(fixture)).isEqualTo(1L);
    assertThat(countRoleSnapshotRows(fixture)).isEqualTo(1L);
    assertRoleSnapshot(fixture, joined.membershipId(), 1L, List.of("player"));
    assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(1L);
    assertAuthorityMembershipEvent(fixture, 1L, 1L, false);
  }

  @Test
  void concurrentDistinctJoinRequestsSerializeOneTransitionAndReplayWithoutMutation()
      throws Exception {
    JoinFixture first = fixture("active");
    JoinFixture second = fixtureForMembership(first);
    assertThat(second.accountId()).isEqualTo(first.accountId());
    assertThat(second.tenantId()).isEqualTo(first.tenantId());
    assertThat(second.accountUuid()).isEqualTo(first.accountUuid());
    assertThat(second.tenantUuid()).isEqualTo(first.tenantUuid());
    assertThat(second.requestId()).isNotEqualTo(first.requestId());
    assertThat(countMemberships(first)).isZero();
    assertThat(countMembershipTransitionReceipts(first)).isZero();
    assertThat(countAuthorityMembershipEvents(first)).isZero();
    assertThat(countAuthorityMembershipStreams(first)).isZero();
    assertThat(countMembershipAuthorityGenerations(first)).isZero();
    assertThat(countTenantJoinOutboxEvents(first)).isZero();

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<JoinPublicProductionResult> firstAttempt =
          executor.submit(
              () -> {
                await(start);
                return join(first);
              });
      Future<JoinPublicProductionResult> secondAttempt =
          executor.submit(
              () -> {
                await(start);
                return join(second);
              });
      start.countDown();

      JoinPublicProductionResult firstResult = firstAttempt.get(30, TimeUnit.SECONDS);
      JoinPublicProductionResult secondResult = secondAttempt.get(30, TimeUnit.SECONDS);

      assertThat(List.of(firstResult.outcomeCode(), secondResult.outcomeCode()))
          .containsExactlyInAnyOrder("JOINED", "ALREADY_ACTIVE");
      assertThat(firstResult.success()).isTrue();
      assertThat(secondResult.success()).isTrue();
      assertThat(firstResult.replayed()).isFalse();
      assertThat(secondResult.replayed()).isFalse();
      assertThat(firstResult.membershipId()).isPositive();
      assertThat(secondResult.membershipId()).isEqualTo(firstResult.membershipId());
      assertThat(firstResult.membershipVersion()).isEqualTo(1L);
      assertThat(secondResult.membershipVersion()).isEqualTo(1L);
      assertThat(firstResult.membershipAuthorityGeneration()).isEqualTo(1L);
      assertThat(secondResult.membershipAuthorityGeneration()).isEqualTo(1L);

      JoinFixture joinedFixture = "JOINED".equals(firstResult.outcomeCode()) ? first : second;
      JoinFixture alreadyActiveFixture = joinedFixture == first ? second : first;
      JoinPublicProductionResult joinedResult = joinedFixture == first ? firstResult : secondResult;
      JoinPublicProductionResult alreadyActiveResult =
          alreadyActiveFixture == first ? firstResult : secondResult;
      long membershipId = joinedResult.membershipId();
      assertThat(joinedResult.outcomeCode()).isEqualTo("JOINED");
      assertThat(alreadyActiveResult.outcomeCode()).isEqualTo("ALREADY_ACTIVE");
      assertThat(alreadyActiveResult.success()).isTrue();
      assertThat(alreadyActiveResult.membershipId()).isEqualTo(membershipId);

      assertThat(countMemberships(first)).isEqualTo(1L);
      assertThat(countTenantJoinOutboxEvents(first)).isEqualTo(1L);
      assertThat(countJoinOutbox(joinedFixture)).isEqualTo(1L);
      assertThat(countJoinOutbox(alreadyActiveFixture)).isZero();
      assertThat(countMembershipTransitionReceipts(first)).isEqualTo(1L);
      assertThat(countTransitionReceiptsForRequest(alreadyActiveFixture.requestId())).isZero();
      assertMembershipTransitionReceipt(joinedFixture, "MEMBERSHIP_JOINED", 1L);
      assertTransitionAndAuditOutboxOnce(joinedFixture, membershipId);
      assertRoleSnapshot(joinedFixture, membershipId, 1L, List.of("player"));
      assertThat(countAuthorityMembershipEvents(first)).isEqualTo(1L);
      assertThat(countAuthorityMembershipStreams(first)).isEqualTo(1L);
      assertThat(countMembershipAuthorityGenerations(first)).isEqualTo(1L);
      assertAuthorityMembershipEvent(joinedFixture, 1L, 1L, false);
      assertCommittedJoinOperation(joinedFixture, "JOINED", membershipId);
      assertCommittedJoinOperation(alreadyActiveFixture, "ALREADY_ACTIVE", membershipId);
      assertThat(
              dsl.resultQuery(
                      "SELECT COUNT(*) FROM account_join_operations "
                          + "WHERE account_id = ? AND tenant_id = ?",
                      first.accountId(),
                      first.tenantId())
                  .fetchOne(0, Long.class))
          .isEqualTo(2L);

      Map<String, Object> joinedOperationBeforeReplay = joinOperationSnapshot(joinedFixture);
      Map<String, Object> alreadyActiveOperationBeforeReplay =
          joinOperationSnapshot(alreadyActiveFixture);
      Map<String, Object> membershipBeforeReplay = membershipSnapshot(joinedFixture);
      Map<String, Object> receiptBeforeReplay = membershipTransitionReceiptSnapshot(joinedFixture);
      Map<String, Object> auditBeforeReplay = joinAuditEnvelopeSnapshot(joinedFixture);
      var eventBeforeReplay = authorityMembershipEventRow(joinedFixture, 1L);
      String eventIdBeforeReplay = eventBeforeReplay.get("event_id", String.class);
      String eventDigestBeforeReplay = eventBeforeReplay.get("event_digest", String.class);
      byte[] eventPayloadBeforeReplay = eventBeforeReplay.get("payload", byte[].class);

      JoinPublicProductionResult joinedReplay = join(joinedFixture);
      JoinPublicProductionResult alreadyActiveReplay = join(alreadyActiveFixture);

      assertThat(joinedReplay)
          .isEqualTo(
              new JoinPublicProductionResult(
                  true,
                  "JOINED",
                  joinedFixture.accountId(),
                  joinedFixture.tenantId(),
                  membershipId,
                  1L,
                  1L,
                  true));
      assertThat(alreadyActiveReplay)
          .isEqualTo(
              new JoinPublicProductionResult(
                  true,
                  "ALREADY_ACTIVE",
                  alreadyActiveFixture.accountId(),
                  alreadyActiveFixture.tenantId(),
                  membershipId,
                  1L,
                  1L,
                  true));

      assertThat(joinOperationSnapshot(joinedFixture)).isEqualTo(joinedOperationBeforeReplay);
      assertThat(joinOperationSnapshot(alreadyActiveFixture))
          .isEqualTo(alreadyActiveOperationBeforeReplay);
      assertThat(membershipSnapshot(joinedFixture)).isEqualTo(membershipBeforeReplay);
      assertThat(membershipTransitionReceiptSnapshot(joinedFixture)).isEqualTo(receiptBeforeReplay);
      assertThat(joinAuditEnvelopeSnapshot(joinedFixture)).isEqualTo(auditBeforeReplay);
      var eventAfterReplay = authorityMembershipEventRow(joinedFixture, 1L);
      assertThat(eventAfterReplay.get("event_id", String.class)).isEqualTo(eventIdBeforeReplay);
      assertThat(eventAfterReplay.get("event_digest", String.class))
          .isEqualTo(eventDigestBeforeReplay);
      assertThat(eventAfterReplay.get("payload", byte[].class))
          .containsExactly(eventPayloadBeforeReplay);
      assertThat(countMemberships(first)).isEqualTo(1L);
      assertThat(countTenantJoinOutboxEvents(first)).isEqualTo(1L);
      assertThat(countMembershipTransitionReceipts(first)).isEqualTo(1L);
      assertThat(countAuthorityMembershipEvents(first)).isEqualTo(1L);
      assertThat(countAuthorityMembershipStreams(first)).isEqualTo(1L);
      assertThat(countMembershipAuthorityGenerations(first)).isEqualTo(1L);
      assertAuthorityMembershipEvent(joinedFixture, 1L, 1L, false);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void missingApprovedTenantAssociationLeavesJoinPendingWithoutAuthorityMutation() {
    JoinFixture fixture = fixture("active", TenantAssociationSetup.MISSING);

    AuthenticationException unavailable =
        assertThrows(AuthenticationException.class, () -> join(fixture));

    assertThat(unavailable.getCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertJoinAuthorityFailureRemainsPending(fixture);
  }

  @Test
  void mismatchedRetainedTenantEvidenceLeavesJoinPendingWithoutAuthorityMutation() {
    JoinFixture fixture = fixture("active", TenantAssociationSetup.MISMATCHED_EVIDENCE);

    AuthenticationException unavailable =
        assertThrows(AuthenticationException.class, () -> join(fixture));

    assertThat(unavailable.getCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertJoinAuthorityFailureRemainsPending(fixture);
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
    assertThat(countAuthorityMembershipEvents(initialJoin)).isEqualTo(1L);
    assertAuthorityMembershipEvent(initialJoin, 1L, 1L, false);
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_join_operations "
                        + "WHERE request_id = ? AND status = 'COMMITTED' AND outcome = 'ALREADY_ACTIVE'",
                    alreadyActive.requestId())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void activeSnapshotWithoutLowercasePlayerFailsClosedInsteadOfAlreadyActive() {
    JoinFixture initialJoin = fixture("active");
    JoinPublicProductionResult initial = join(initialJoin);
    assertThat(initial.success()).isTrue();

    dsl.execute(
        "DELETE FROM account_tenant_membership_role_snapshot_roles WHERE membership_id = ?",
        initial.membershipId());
    dsl.execute(
        "INSERT INTO account_tenant_membership_role_snapshot_roles "
            + "(membership_id, snapshot_version, role_identifier) VALUES (?, ?, ?)",
        initial.membershipId(),
        1L,
        "designer");

    JoinFixture retry = fixtureForMembership(initialJoin);
    JoinPublicProductionResult result = join(retry);

    assertThat(result.success()).isFalse();
    assertThat(result.outcomeCode()).isEqualTo("MEMBERSHIP_RECONCILIATION_REQUIRED");
    assertThat(countMembershipTransitionReceipts(retry)).isEqualTo(1L);
    assertThat(countRoleSnapshotHeaders(retry)).isEqualTo(1L);
    assertThat(countRoleSnapshotRows(retry)).isEqualTo(1L);
    assertRoleSnapshot(retry, initial.membershipId(), 1L, List.of("designer"));
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
    assertAuthorityMembershipEvent(first, 1L, 1L, false);
    assertAuthorityMembershipEvent(second, 1L, 1L, false);
  }

  @Test
  void reactivationWithoutCanonicalInactiveEventFailsClosedWithoutMutation() {
    JoinFixture initialJoin = fixture("active");
    JoinPublicProductionResult initial = join(initialJoin);
    assertThat(initial.success()).isTrue();
    assertMembershipTransitionReceipt(initialJoin, "MEMBERSHIP_JOINED", 1L);
    assertAuthorityMembershipEvent(initialJoin, 1L, 1L, false);
    var originalAuthorityEvent = authorityMembershipEventRow(initialJoin, 1L);
    String originalEventId = originalAuthorityEvent.get("event_id", String.class);
    String originalEventDigest = originalAuthorityEvent.get("event_digest", String.class);
    byte[] originalEventPayload = originalAuthorityEvent.get("payload", byte[].class);

    dsl.execute(
        "DELETE FROM account_tenant_membership_role_snapshot_roles "
            + "WHERE membership_id IN (SELECT id FROM account_tenant_membership "
            + "WHERE account_id = ? AND tenant_id = ?)",
        initialJoin.accountId(),
        initialJoin.tenantId());
    dsl.execute(
        "INSERT INTO account_tenant_membership_role_snapshot_roles "
            + "(membership_id, snapshot_version, role_identifier) "
            + "SELECT id, membership_version, role_identifier FROM account_tenant_membership "
            + "JOIN (VALUES ('designer')) AS roles(role_identifier) ON TRUE "
            + "WHERE account_id = ? AND tenant_id = ?",
        initialJoin.accountId(),
        initialJoin.tenantId());
    dsl.execute(
        "UPDATE account_tenant_membership SET lifecycle_state = 'INACTIVE', "
            + "gameplay_admission_allowed = FALSE WHERE account_id = ? AND tenant_id = ?",
        initialJoin.accountId(),
        initialJoin.tenantId());
    long priorMembershipGeneration =
        authorityGeneration(
            "MEMBERSHIP", null, initialJoin.accountUuid(), initialJoin.tenantUuid());
    long priorIssuanceFence =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT issuance_fence FROM account_authority_issuance_fences "
                        + "WHERE account_uuid = ?",
                    initialJoin.accountUuid())
                .fetchOne(0, Long.class));
    JoinFixture reactivation = fixtureForMembership(initialJoin);

    AuthenticationException unavailable =
        assertThrows(AuthenticationException.class, () -> join(reactivation));

    assertThat(unavailable.getCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(countMembershipTransitionReceipts(reactivation)).isEqualTo(1L);
    assertMembershipTransitionReceipt(initialJoin, "MEMBERSHIP_JOINED", 1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT lifecycle_state, gameplay_admission_allowed, membership_version, "
                        + "membership_authority_generation, authority_provenance "
                        + "FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
                    reactivation.accountId(),
                    reactivation.tenantId())
                .fetchOne())
        .satisfies(
            row -> {
              assertThat(row.get("lifecycle_state", String.class)).isEqualTo("INACTIVE");
              assertThat(row.get("gameplay_admission_allowed", Boolean.class)).isFalse();
              assertThat(row.get("membership_version", Long.class)).isEqualTo(1L);
              assertThat(row.get("membership_authority_generation", Long.class)).isEqualTo(1L);
              assertThat(row.get("authority_provenance", String.class)).isEqualTo("EXPLICIT_JOIN");
            });
    assertThat(countRoleSnapshotHeaders(reactivation)).isEqualTo(1L);
    assertThat(countRoleSnapshotRows(reactivation)).isEqualTo(1L);
    assertRoleSnapshot(reactivation, initial.membershipId(), 1L, List.of("designer"));
    assertThat(countAuthorityMembershipEvents(reactivation)).isEqualTo(1L);
    assertThat(countAuthorityMembershipStreams(reactivation)).isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT last_sequence FROM account_authority_outbox_streams "
                        + "WHERE outbox_stream_key = ?",
                    authorityStreamKey(reactivation))
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            authorityGeneration(
                "MEMBERSHIP", null, reactivation.accountUuid(), reactivation.tenantUuid()))
        .isEqualTo(priorMembershipGeneration);
    assertThat(
            dsl.resultQuery(
                    "SELECT issuance_fence FROM account_authority_issuance_fences "
                        + "WHERE account_uuid = ?",
                    reactivation.accountUuid())
                .fetchOne(0, Long.class))
        .isEqualTo(priorIssuanceFence);
    var authorityEventAfter = authorityMembershipEventRow(reactivation, 1L);
    assertThat(authorityEventAfter.get("event_id", String.class)).isEqualTo(originalEventId);
    assertThat(authorityEventAfter.get("event_digest", String.class))
        .isEqualTo(originalEventDigest);
    assertThat(authorityEventAfter.get("payload", byte[].class))
        .containsExactly(originalEventPayload);
    assertThat(countTenantJoinOutboxEvents(reactivation)).isEqualTo(1L);
    assertThat(countJoinOutbox(reactivation)).isZero();
    assertThat(
            dsl.resultQuery(
                    "SELECT status, outcome, membership_id, outcome_membership_version, "
                        + "outcome_membership_authority_generation "
                        + "FROM account_join_operations WHERE request_id = ?",
                    reactivation.requestId())
                .fetchOne())
        .satisfies(
            row -> {
              assertThat(row.get("status", String.class)).isEqualTo("PENDING");
              assertThat(row.get("outcome", String.class)).isNull();
              assertThat(row.get("membership_id", Long.class)).isNull();
              assertThat(row.get("outcome_membership_version", Long.class)).isNull();
              assertThat(row.get("outcome_membership_authority_generation", Long.class)).isNull();
            });
  }

  @Test
  void committedMembershipTransitionReceiptHistorySurvivesAbsenceOfCurrentMembershipRow() {
    JoinFixture fixture = fixture("active");
    assertThat(join(fixture).success()).isTrue();
    assertMembershipTransitionReceipt(fixture, "MEMBERSHIP_JOINED", 1L);

    dsl.execute("DELETE FROM account_join_operations WHERE request_id = ?", fixture.requestId());

    dsl.execute(
        "DELETE FROM account_tenant_membership_role_snapshot_roles "
            + "WHERE membership_id IN (SELECT id FROM account_tenant_membership "
            + "WHERE account_id = ? AND tenant_id = ?)",
        fixture.accountId(),
        fixture.tenantId());
    dsl.execute(
        "DELETE FROM account_tenant_membership_role_snapshots "
            + "WHERE membership_id IN (SELECT id FROM account_tenant_membership "
            + "WHERE account_id = ? AND tenant_id = ?)",
        fixture.accountId(),
        fixture.tenantId());
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
        "DELETE FROM account_tenant_membership_role_snapshot_roles "
            + "WHERE membership_id IN (SELECT id FROM account_tenant_membership "
            + "WHERE account_id = ? AND tenant_id = ?)",
        initialJoin.accountId(),
        initialJoin.tenantId());
    dsl.execute(
        "DELETE FROM account_tenant_membership_role_snapshots "
            + "WHERE membership_id IN (SELECT id FROM account_tenant_membership "
            + "WHERE account_id = ? AND tenant_id = ?)",
        initialJoin.accountId(),
        initialJoin.tenantId());
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
        "DELETE FROM account_tenant_membership_role_snapshot_roles "
            + "WHERE membership_id IN (SELECT id FROM account_tenant_membership "
            + "WHERE account_id = ? AND tenant_id = ?)",
        initialJoin.accountId(),
        initialJoin.tenantId());
    dsl.execute(
        "DELETE FROM account_tenant_membership_role_snapshots "
            + "WHERE membership_id IN (SELECT id FROM account_tenant_membership "
            + "WHERE account_id = ? AND tenant_id = ?)",
        initialJoin.accountId(),
        initialJoin.tenantId());
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

    JoinPublicProductionResult activeJoinFailure = join(active);
    JoinPublicProductionResult inactiveJoinFailure = join(inactive);

    assertThat(activeJoinFailure.success()).isFalse();
    assertThat(activeJoinFailure.outcomeCode()).isEqualTo("MEMBERSHIP_RECONCILIATION_REQUIRED");
    assertThat(inactiveJoinFailure.success()).isFalse();
    assertThat(inactiveJoinFailure.outcomeCode()).isEqualTo("MEMBERSHIP_RECONCILIATION_REQUIRED");
    assertThat(countMembershipTransitionReceipts(active)).isZero();
    assertThat(countMembershipTransitionReceipts(inactive)).isZero();
    assertThat(countRoleSnapshotHeaders(active)).isZero();
    assertThat(countRoleSnapshotRows(active)).isZero();
    assertThat(countRoleSnapshotHeaders(inactive)).isZero();
    assertThat(countRoleSnapshotRows(inactive)).isZero();
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
    return fixture(subscriptionStatus, TenantAssociationSetup.APPROVED);
  }

  private JoinFixture fixture(String subscriptionStatus, TenantAssociationSetup associationSetup) {
    String suffix = UUID.randomUUID().toString();
    long accountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash) "
                        + "VALUES (?, ?, ?) RETURNING id",
                    "join-proof-" + suffix,
                    "join-proof-" + suffix + "@example.com",
                    "test-hash")
                .fetchOne(0, Long.class));
    UUID accountUuid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, UUID.class));
    seedAccountAuthorityState(accountUuid);
    return fixture(subscriptionStatus, accountId, accountUuid, associationSetup, suffix);
  }

  private JoinFixture fixture(String subscriptionStatus, long accountId) {
    return fixture(subscriptionStatus, accountId, TenantAssociationSetup.APPROVED);
  }

  private JoinFixture fixture(
      String subscriptionStatus, long accountId, TenantAssociationSetup associationSetup) {
    String suffix = UUID.randomUUID().toString();
    UUID accountUuid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, UUID.class));
    return fixture(subscriptionStatus, accountId, accountUuid, associationSetup, suffix);
  }

  private JoinFixture fixture(
      String subscriptionStatus,
      long accountId,
      UUID accountUuid,
      TenantAssociationSetup associationSetup,
      String suffix) {
    long tenantId = positiveRandomLong();
    seedRetainedV26TenantEvidence(tenantId, suffix);
    dsl.execute(
        "INSERT INTO subscription (account_id, tenant_id, plan_id, status, entitlement_version) "
            + "VALUES (?, ?, ?, ?, 1)",
        accountId,
        tenantId,
        "join-proof",
        subscriptionStatus);

    UUID tenantUuid = UUID.randomUUID();
    String evidenceDigest = legacyTenantSourceEvidence.digest(tenantId);
    if (associationSetup == TenantAssociationSetup.APPROVED) {
      tenantAssociationRepository.importApproved(
          tenantId, approvedTenantAssociation(tenantId, tenantUuid, evidenceDigest, suffix));
      var storedAssociation =
          tenantAssociationRepository.findByLegacyTenantId(tenantId).orElseThrow();
      assertThat(storedAssociation.legacyTenantId()).isEqualTo(tenantId);
      assertThat(storedAssociation.canonicalTenantId()).isEqualTo(tenantUuid);
      assertThat(storedAssociation.accountEvidenceDigest()).isEqualTo(evidenceDigest);
      assertThat(authorityGeneration("TENANT", null, null, tenantUuid)).isEqualTo(1L);
    } else if (associationSetup == TenantAssociationSetup.MISMATCHED_EVIDENCE) {
      insertApprovedTenantAssociation(tenantId, tenantUuid, "sha256:" + "0".repeat(64), suffix);
      seedTenantAuthorityGeneration(tenantUuid);
      assertThat(authorityGeneration("TENANT", null, null, tenantUuid)).isEqualTo(1L);
    } else {
      assertThat(tenantAssociationRepository.findByLegacyTenantId(tenantId)).isEmpty();
      assertThat(
              dsl.resultQuery(
                      "SELECT COUNT(*) FROM account_authority_generations "
                          + "WHERE scope_kind = 'TENANT' AND tenant_uuid = ?",
                      tenantUuid)
                  .fetchOne(0, Long.class))
          .isZero();
    }
    return fixture(accountId, accountUuid, tenantId, tenantUuid, suffix);
  }

  private JoinFixture fixtureForMembership(JoinFixture existing) {
    return fixture(
        existing.accountId(),
        existing.accountUuid(),
        existing.tenantId(),
        existing.tenantUuid(),
        UUID.randomUUID().toString());
  }

  private JoinFixture fixture(
      long accountId, UUID accountUuid, long tenantId, UUID tenantUuid, String suffix) {
    String requestId = "join-proof-" + suffix;

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
    return new JoinFixture(
        accountId, accountUuid, tenantId, tenantUuid, requestId, caller, scope.connectScopeId());
  }

  private void seedRetainedV26TenantEvidence(long tenantId, String suffix) {
    // Keep V26's unverified membership history on a separate retained account, not the joiner.
    long donorAccountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash, tenant_id) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                    "jpd-" + suffix,
                    "join-proof-retained-donor-" + suffix + "@example.com",
                    "test-hash",
                    tenantId)
                .fetchOne(0, Long.class));
    UUID donorAccountUuid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", donorAccountId)
                .fetchOne(0, UUID.class));
    seedAccountAuthorityState(donorAccountUuid);
    long retainedMembershipId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO account_tenant_membership "
                        + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
                        + "membership_version, membership_authority_generation, authority_provenance) "
                        + "VALUES (?, ?, FALSE, 'LEGACY_UNVERIFIED', 1, 1, 'LEGACY_UNVERIFIED') "
                        + "RETURNING id",
                    donorAccountId,
                    tenantId)
                .fetchOne(0, Long.class));
    dsl.execute(
        "INSERT INTO account_legacy_tenant_sources "
            + "(account_id, legacy_tenant_id, matching_membership_id, "
            + "matching_membership_admission_allowed, profile_tenant_count, "
            + "matching_profile_count, disposition) VALUES (?, ?, ?, FALSE, 0, 0, 'UNVERIFIED')",
        donorAccountId,
        tenantId,
        retainedMembershipId);
    dsl.execute(
        "INSERT INTO account_legacy_membership_sources "
            + "(membership_id, account_id, tenant_id, original_gameplay_admission_allowed, "
            + "matches_account_legacy_tenant, disposition) "
            + "VALUES (?, ?, ?, FALSE, TRUE, 'UNVERIFIED')",
        retainedMembershipId,
        donorAccountId,
        tenantId);
  }

  private void seedAccountAuthorityState(UUID accountUuid) {
    dsl.execute(
        "INSERT INTO account_authority_generations "
            + "(scope_kind, account_uuid, generation, source_version) "
            + "VALUES ('ACCOUNT', ?, 1, 1)",
        accountUuid);
    dsl.execute(
        "INSERT INTO account_authority_issuance_fences "
            + "(account_uuid, issuance_fence, source_version) VALUES (?, 1, 1)",
        accountUuid);
  }

  private void seedTenantAuthorityGeneration(UUID tenantUuid) {
    dsl.execute(
        "INSERT INTO account_authority_generations "
            + "(scope_kind, tenant_uuid, generation, source_version) "
            + "VALUES ('TENANT', ?, 1, 1)",
        tenantUuid);
  }

  private ResolveLegacyAccountTenantAssociationResponse approvedTenantAssociation(
      long legacyTenantId, UUID tenantUuid, String evidenceDigest, String suffix) {
    String sourceLegacyGameTenantId = "legacy-game-" + suffix.replace("-", "").substring(0, 24);
    return ResolveLegacyAccountTenantAssociationResponse.newBuilder()
        .setLegacyAccountTenantId(legacyTenantId)
        .setCanonicalTenantId(tenantUuid.toString())
        .setSourceLegacyGameTenantId(sourceLegacyGameTenantId)
        .setSourceGameRowId(positiveRandomLong())
        .setAccountEvidenceDigest(evidenceDigest)
        .setOperationId(UUID.randomUUID().toString())
        .setManifestDigest("sha256:" + "b".repeat(64))
        .setManifestSignature(Base64.getEncoder().encodeToString(new byte[64]))
        .setTargetNamespace("account_service")
        .setSignerKeyId("game-design-owner-test")
        .setApprovedBy("owner@example.test")
        .setApprovalReference("unit-1b-postgres-fixture")
        .setSignedAt("2026-09-26T00:00:00Z")
        .setOperationEntryCount(1)
        .setManifestSchemaVersion(1)
        .build();
  }

  private void insertApprovedTenantAssociation(
      long legacyTenantId, UUID tenantUuid, String evidenceDigest, String suffix) {
    ResolveLegacyAccountTenantAssociationResponse association =
        approvedTenantAssociation(legacyTenantId, tenantUuid, evidenceDigest, suffix);
    dsl.execute(
        "INSERT INTO account_approved_legacy_tenant_associations "
            + "(legacy_tenant_id, canonical_tenant_id, source_legacy_game_tenant_id, "
            + "source_game_row_id, account_evidence_digest, operation_id, manifest_digest, "
            + "manifest_signature, target_namespace, signer_key_id, approved_by, "
            + "approval_reference, signed_at, operation_entry_count, manifest_schema_version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        legacyTenantId,
        UUID.fromString(association.getCanonicalTenantId()),
        association.getSourceLegacyGameTenantId(),
        association.getSourceGameRowId(),
        association.getAccountEvidenceDigest(),
        UUID.fromString(association.getOperationId()),
        association.getManifestDigest(),
        association.getManifestSignature(),
        association.getTargetNamespace(),
        association.getSignerKeyId(),
        association.getApprovedBy(),
        association.getApprovalReference(),
        association.getSignedAt(),
        association.getOperationEntryCount(),
        association.getManifestSchemaVersion());
  }

  private long positiveRandomLong() {
    long candidate = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    return candidate == 0L ? 1L : candidate;
  }

  private long countMemberships(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne(0, Long.class));
  }

  private Map<String, Object> joinOperationSnapshot(JoinFixture fixture) {
    var row =
        dsl.resultQuery(
                "SELECT request_id, status, outcome, membership_id, outcome_membership_version, "
                    + "outcome_membership_authority_generation, request_digest, "
                    + "request_digest_version, entitlement_version, allow_public_join, "
                    + "caller_bound_authority_invalidated, last_attempt_failure_code, "
                    + "entitlement_authority_availability, last_attempt_authority_availability, "
                    + "created_at, updated_at FROM account_join_operations WHERE request_id = ?",
                fixture.requestId())
            .fetchOne();
    assertThat(row).isNotNull();
    return row.intoMap();
  }

  private Map<String, Object> membershipSnapshot(JoinFixture fixture) {
    var row =
        dsl.resultQuery(
                "SELECT id, account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
                    + "membership_version, membership_authority_generation, authority_provenance "
                    + "FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne();
    assertThat(row).isNotNull();
    return row.intoMap();
  }

  private Map<String, Object> membershipTransitionReceiptSnapshot(JoinFixture fixture) {
    var row =
        dsl.resultQuery(
                "SELECT receipt_stream_key, receipt_sequence, account_id, tenant_id, "
                    + "evidence_status, transition_type, request_id, membership_id, "
                    + "membership_lifecycle_state, gameplay_admission_allowed, membership_version, "
                    + "membership_authority_generation, authority_provenance, receipt_id, "
                    + "receipt_digest, created_at FROM account_membership_transition_receipts "
                    + "WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne();
    assertThat(row).isNotNull();
    return row.intoMap();
  }

  private Map<String, Object> joinAuditEnvelopeSnapshot(JoinFixture fixture) {
    UUID eventId =
        UUID.nameUUIDFromBytes(
            ("account-join-audit/v1:" + fixture.requestId()).getBytes(StandardCharsets.UTF_8));
    var row =
        dsl.resultQuery(
                "SELECT audit_event_id, scope, tenant_id, producer_service, event_type, "
                    + "occurred_at, schema_version, payload_digest_version, payload_digest, "
                    + "payload, created_at FROM account_audit_outbox "
                    + "WHERE audit_event_id = ? AND tenant_id = ?",
                eventId,
                fixture.tenantId())
            .fetchOne();
    assertThat(row).isNotNull();
    return row.intoMap();
  }

  private void assertCommittedJoinOperation(
      JoinFixture fixture, String outcome, long membershipId) {
    var row =
        dsl.resultQuery(
                "SELECT status, outcome, membership_id, outcome_membership_version, "
                    + "outcome_membership_authority_generation, request_digest, "
                    + "request_digest_version, entitlement_version, allow_public_join "
                    + "FROM account_join_operations WHERE request_id = ?",
                fixture.requestId())
            .fetchOne();
    assertThat(row).isNotNull();
    assertThat(row.get("status", String.class)).isEqualTo("COMMITTED");
    assertThat(row.get("outcome", String.class)).isEqualTo(outcome);
    assertThat(row.get("membership_id", Long.class)).isEqualTo(membershipId);
    assertThat(row.get("outcome_membership_version", Long.class)).isEqualTo(1L);
    assertThat(row.get("outcome_membership_authority_generation", Long.class)).isEqualTo(1L);
    assertThat(row.get("request_digest", String.class)).matches("sha256:[0-9a-f]{64}");
    assertThat(row.get("request_digest_version", Integer.class)).isEqualTo(1);
    assertThat(row.get("entitlement_version", Long.class)).isEqualTo(1L);
    assertThat(row.get("allow_public_join", Boolean.class)).isTrue();
  }

  private long countTransitionReceiptsForRequest(String requestId) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_membership_transition_receipts WHERE request_id = ?",
                requestId)
            .fetchOne(0, Long.class));
  }

  private JoinPublicProductionResult join(JoinFixture fixture) {
    return accountService.joinPublicProductionFromGameSession(
        fixture.caller(),
        new JoinPublicProductionRequest(fixture.connectScopeId(), fixture.requestId()));
  }

  private void assertAuthorityMembershipEvent(
      JoinFixture fixture,
      long expectedSequence,
      long expectedMembershipVersion,
      boolean expectedCallerBoundAuthorityInvalidated) {
    String streamKey = authorityStreamKey(fixture);
    long eventCount = countAuthorityMembershipEvents(fixture);
    assertThat(eventCount).isGreaterThanOrEqualTo(expectedSequence);
    assertThat(
            dsl.resultQuery(
                    "SELECT last_sequence FROM account_authority_outbox_streams "
                        + "WHERE outbox_stream_key = ?",
                    streamKey)
                .fetchOne(0, Long.class))
        .isEqualTo(eventCount);

    var row = authorityMembershipEventRow(fixture, expectedSequence);
    assertThat(row).isNotNull();
    long sequence = row.get("outbox_sequence", Long.class);
    String requestId = row.get("request_id", String.class);
    String eventId = row.get("event_id", String.class);
    String storedDigest = row.get("event_digest", String.class);
    byte[] storedPayload = row.get("payload", byte[].class);
    String payloadJson = new String(storedPayload, StandardCharsets.UTF_8);
    MembershipAuthorityEventV1Codec.MembershipEvent event =
        MembershipAuthorityEventV1Codec.verify(payloadJson);

    assertThat(sequence).isEqualTo(expectedSequence);
    assertThat(requestId).isEqualTo(fixture.requestId());
    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.requestId()).isEqualTo(requestId);
    assertThat(event.schemaVersion()).isEqualTo(MembershipAuthorityEventV1Codec.SCHEMA_VERSION);
    assertThat(event.eventType()).isEqualTo(MembershipAuthorityEventV1Codec.EVENT_TYPE);
    assertThat(event.outboxStreamKey()).isEqualTo(streamKey);
    assertThat(event.outboxSequence()).isEqualTo(Long.toString(expectedSequence));
    assertThat(event.sourceScope())
        .isEqualTo("membership/" + fixture.accountUuid() + "/" + fixture.tenantUuid());
    assertThat(event.accountId()).isEqualTo(fixture.accountUuid().toString());
    assertThat(event.tenantId()).isEqualTo(fixture.tenantUuid().toString());
    assertThat(event.membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(event.membershipVersion()).isEqualTo(Long.toString(expectedMembershipVersion));
    assertThat(event.roles()).containsExactlyElementsOf(committedRoles(fixture));
    assertThat(event.gameplayAdmissionAllowed()).isTrue();
    assertThat(event.callerBoundAuthorityInvalidated())
        .isEqualTo(expectedCallerBoundAuthorityInvalidated);
    assertThat(storedDigest).isEqualTo(event.eventDigest());
    assertThat(storedDigest).matches("sha256:[0-9a-f]{64}");
    assertThat(event.canonicalJson()).isEqualTo(payloadJson);
    assertThat(storedPayload).containsExactly(event.canonicalJsonUtf8());

    long issuerGeneration =
        authorityGeneration("ISSUER", AccountServiceImpl.ACCOUNT_JWT_ISSUER, null, null);
    long accountGeneration = authorityGeneration("ACCOUNT", null, fixture.accountUuid(), null);
    long tenantGeneration = authorityGeneration("TENANT", null, null, fixture.tenantUuid());
    long membershipGeneration =
        authorityGeneration("MEMBERSHIP", null, fixture.accountUuid(), fixture.tenantUuid());
    long issuanceFence =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT issuance_fence FROM account_authority_issuance_fences "
                        + "WHERE account_uuid = ?",
                    fixture.accountUuid())
                .fetchOne(0, Long.class));
    var membership =
        dsl.resultQuery(
                "SELECT membership_version, membership_authority_generation "
                    + "FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ? "
                    + "AND lifecycle_state = 'ACTIVE' AND gameplay_admission_allowed = TRUE",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne();
    assertThat(membership).isNotNull();
    long committedMembershipVersion = membership.get("membership_version", Long.class);
    long committedMembershipGeneration =
        membership.get("membership_authority_generation", Long.class);
    assertThat(committedMembershipVersion).isEqualTo(expectedMembershipVersion);
    assertThat(membershipGeneration).isEqualTo(committedMembershipGeneration);
    assertThat(event.membershipAuthorityGeneration())
        .isEqualTo(Long.toString(committedMembershipGeneration));
    assertThat(event.issuanceFence()).isEqualTo(Long.toString(issuanceFence));
    assertThat(event.authorityTuple())
        .isEqualTo(
            new MembershipAuthorityEventV1Codec.AuthorityTuple(
                Long.toString(issuerGeneration),
                Long.toString(accountGeneration),
                Map.of(fixture.tenantUuid().toString(), Long.toString(tenantGeneration)),
                Map.of(fixture.tenantUuid().toString(), Long.toString(membershipGeneration)),
                List.of(),
                Optional.empty(),
                Optional.empty()));
  }

  private org.jooq.Record authorityMembershipEventRow(JoinFixture fixture, long sequence) {
    return dsl.resultQuery(
            "SELECT outbox_sequence, request_id, event_id, event_digest, payload "
                + "FROM account_authority_outbox_events "
                + "WHERE outbox_stream_key = ? AND outbox_sequence = ?",
            authorityStreamKey(fixture),
            sequence)
        .fetchOne();
  }

  private long authorityGeneration(
      String scopeKind, String issuerId, UUID accountUuid, UUID tenantUuid) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT generation FROM account_authority_generations "
                    + "WHERE scope_kind = ? AND issuer_id IS NOT DISTINCT FROM ? "
                    + "AND account_uuid IS NOT DISTINCT FROM ? "
                    + "AND tenant_uuid IS NOT DISTINCT FROM ?",
                scopeKind,
                issuerId,
                accountUuid,
                tenantUuid)
            .fetchOne(0, Long.class));
  }

  private List<String> committedRoles(JoinFixture fixture) {
    return dsl.resultQuery(
            "SELECT roles.role_identifier FROM account_tenant_membership_role_snapshot_roles roles "
                + "JOIN account_tenant_membership membership "
                + "ON membership.id = roles.membership_id "
                + "WHERE membership.account_id = ? AND membership.tenant_id = ? "
                + "AND roles.snapshot_version = membership.membership_version "
                + "ORDER BY convert_to(roles.role_identifier, 'UTF8')",
            fixture.accountId(),
            fixture.tenantId())
        .fetch("role_identifier", String.class);
  }

  private static void await(CountDownLatch start) {
    try {
      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("concurrent JOIN test did not receive its start signal");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("concurrent JOIN test was interrupted", ex);
    }
  }

  private void assertJoinAuthorityFailureRemainsPending(JoinFixture fixture) {
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countRoleSnapshotHeaders(fixture)).isZero();
    assertThat(countRoleSnapshotRows(fixture)).isZero();
    assertThat(countMembershipTransitionReceipts(fixture)).isZero();
    assertThat(countJoinOutbox(fixture)).isZero();
    assertThat(countTenantJoinOutboxEvents(fixture)).isZero();
    assertThat(countAuthorityMembershipEvents(fixture)).isZero();
    assertThat(countAuthorityMembershipStreams(fixture)).isZero();
    assertThat(countMembershipAuthorityGenerations(fixture)).isZero();
    assertThat(
            dsl.resultQuery(
                    "SELECT status, outcome, membership_id, outcome_membership_version, "
                        + "outcome_membership_authority_generation "
                        + "FROM account_join_operations WHERE request_id = ?",
                    fixture.requestId())
                .fetchOne())
        .satisfies(
            row -> {
              assertThat(row.get("status", String.class)).isEqualTo("PENDING");
              assertThat(row.get("outcome", String.class)).isNull();
              assertThat(row.get("membership_id", Long.class)).isNull();
              assertThat(row.get("outcome_membership_version", Long.class)).isNull();
              assertThat(row.get("outcome_membership_authority_generation", Long.class)).isNull();
            });
  }

  private String authorityStreamKey(JoinFixture fixture) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "membership/"
        + fixture.accountUuid()
        + "/"
        + fixture.tenantUuid();
  }

  private long countAuthorityMembershipEvents(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_events "
                    + "WHERE outbox_stream_key = ?",
                authorityStreamKey(fixture))
            .fetchOne(0, Long.class));
  }

  private long countAuthorityMembershipStreams(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_streams "
                    + "WHERE outbox_stream_key = ?",
                authorityStreamKey(fixture))
            .fetchOne(0, Long.class));
  }

  private long countMembershipAuthorityGenerations(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_authority_generations "
                    + "WHERE scope_kind = 'MEMBERSHIP' AND account_uuid = ? "
                    + "AND tenant_uuid = ?",
                fixture.accountUuid(),
                fixture.tenantUuid())
            .fetchOne(0, Long.class));
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

  private long countRoleSnapshotHeaders(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_tenant_membership_role_snapshots s "
                    + "JOIN account_tenant_membership m ON m.id = s.membership_id "
                    + "WHERE m.account_id = ? AND m.tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne(0, Long.class));
  }

  private long countRoleSnapshotRows(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_tenant_membership_role_snapshot_roles r "
                    + "JOIN account_tenant_membership m ON m.id = r.membership_id "
                    + "WHERE m.account_id = ? AND m.tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne(0, Long.class));
  }

  private void assertRoleSnapshot(
      JoinFixture fixture, long membershipId, long expectedVersion, List<String> expectedRoles) {
    var header =
        dsl.resultQuery(
                "SELECT membership_id, snapshot_version "
                    + "FROM account_tenant_membership_role_snapshots "
                    + "WHERE membership_id = ?",
                membershipId)
            .fetchOne();
    assertThat(header).isNotNull();
    assertThat(header.get("membership_id", Long.class)).isEqualTo(membershipId);
    assertThat(header.get("snapshot_version", Long.class)).isEqualTo(expectedVersion);
    assertThat(
            dsl.resultQuery(
                    "SELECT r.role_identifier "
                        + "FROM account_tenant_membership_role_snapshot_roles r "
                        + "JOIN account_tenant_membership m ON m.id = r.membership_id "
                        + "WHERE m.account_id = ? AND m.tenant_id = ? "
                        + "AND r.snapshot_version = ? "
                        + "ORDER BY convert_to(r.role_identifier, 'UTF8')",
                    fixture.accountId(),
                    fixture.tenantId(),
                    expectedVersion)
                .fetch("role_identifier", String.class))
        .containsExactlyElementsOf(expectedRoles);
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

  private enum TenantAssociationSetup {
    APPROVED,
    MISSING,
    MISMATCHED_EVIDENCE
  }

  private record JoinFixture(
      long accountId,
      UUID accountUuid,
      long tenantId,
      UUID tenantUuid,
      String requestId,
      DirectTextCallerContext caller,
      String connectScopeId) {}
}
