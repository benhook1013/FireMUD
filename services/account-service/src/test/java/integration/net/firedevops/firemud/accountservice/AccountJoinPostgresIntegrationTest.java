package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinScope;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionResult;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceiptDigest;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.ProvenPositiveCheckpoint;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository.Action;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository.OperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository.Request;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository.ApprovedAssociation;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.PositiveMembershipSnapshot;
import net.firedevops.firemud.accountservice.service.AccountMembershipLifecycleService;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.AccountTenantRoleMutationService;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
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
      "firemud.grpc.workload-namespace=" + AccountJoinPostgresIntegrationTest.WORKLOAD_NAMESPACE
    })
class AccountJoinPostgresIntegrationTest {
  static final String WORKLOAD_NAMESPACE = "account-service-test";
  private static final UUID REALM_ID = UUID.fromString("4c4b57d8-e3a2-48fe-9977-e7df0fdce901");
  private static final String WORLD_SLUG = "join-proof-world";
  private static final String REALM_SLUG = "production";
  private static final String NAMESPACE_ID = "65f23d2c-3b4f-4ec3-9b11-0c56a9a2a7d1";
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
  @MockitoSpyBean private AccountMembershipAuthorityEventProducer membershipAuthorityEventProducer;
  @Autowired private AccountMembershipLifecycleService membershipLifecycleService;
  @Autowired private AccountTenantRoleMutationService tenantRoleMutationService;
  @MockitoSpyBean private AccountAuthorityOutboxRepository authorityOutboxRepository;
  @MockitoSpyBean private AccountAuditOutboxRepository auditOutboxRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private AccountRepository accountRepository;
  @Autowired private AccountAuthorityGenerationRepository authorityGenerationRepository;
  @Autowired private AccountMembershipPairAuthorityRepository pairAuthorityRepository;
  @Autowired private AccountTenantIdentityResolver tenantIdentityResolver;
  @Autowired private AccountTenantMembershipRepository membershipRepository;
  @Autowired private AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository;

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
                2L,
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
              assertThat(row.get("outcome_membership_version", Long.class)).isEqualTo(2L);
              assertThat(row.get("outcome_membership_authority_generation", Long.class))
                  .isEqualTo(1L);
            });
    assertTransitionAndAuditOutboxOnce(fixture, result.membershipId());
    assertRoleSnapshot(fixture, result.membershipId(), 2L, List.of("player"));
    assertAuthorityMembershipEvent(fixture, 1L, 2L, false);
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
    assertThat(countMembershipAuthorityGenerations(fixture)).isEqualTo(1L);

    JoinPublicProductionResult recovered = join(fixture);

    assertThat(recovered.success()).isTrue();
    assertThat(recovered.outcomeCode()).isEqualTo("JOINED");
    assertThat(recovered.replayed()).isFalse();
    assertThat(countMemberships(fixture)).isEqualTo(1L);
    assertTransitionAndAuditOutboxOnce(fixture, recovered.membershipId());
    assertRoleSnapshot(fixture, recovered.membershipId(), 2L, List.of("player"));
    assertAuthorityMembershipEvent(fixture, 1L, 2L, false);
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
    JoinPublicProductionResult conflict = join(fixture);

    assertThat(conflict.success()).isFalse();
    assertThat(conflict.outcomeCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(conflict.replayed()).isFalse();
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
    assertRoleSnapshot(fixture, joined.membershipId(), 2L, List.of("player"));
    assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(1L);
    assertAuthorityMembershipEvent(fixture, 1L, 2L, false);
  }

  @Test
  void leftCommitsClosedAuthorityAndRetainedInactiveRuntimeSnapshot() {
    JoinFixture fixture = fixture("active");
    JoinPublicProductionResult joined = join(fixture);
    assertThat(joined.success()).isTrue();
    long fenceBeforeLeft = accountIssuanceFence(fixture);
    String leftRequestId = "leave-proof-" + UUID.randomUUID();

    MembershipTransitionReceipt left = leave(fixture, leftRequestId);

    assertThat(left.transitionType()).isEqualTo("MEMBERSHIP_LEFT");
    assertThat(left.requestId()).isEqualTo(leftRequestId);
    assertThat(left.receiptSequence()).isEqualTo(2L);
    assertThat(membershipSnapshot(fixture))
        .containsEntry("lifecycle_state", "INACTIVE")
        .containsEntry("gameplay_admission_allowed", false)
        .containsEntry("membership_version", 3L)
        .containsEntry("membership_authority_generation", 2L)
        .containsEntry("authority_provenance", "EXPLICIT_JOIN");
    assertThat(committedRoles(fixture)).containsExactly("player");
    assertRoleSnapshot(fixture, joined.membershipId(), 3L, List.of("player"));
    assertThat(accountIssuanceFence(fixture)).isEqualTo(fenceBeforeLeft + 1L);
    assertThat(authorityGeneration("MEMBERSHIP", null, fixture.accountUuid(), fixture.tenantUuid()))
        .isEqualTo(2L);
    assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(2L);
    assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(2L);
    assertMembershipTransitionReceipt(fixture, "MEMBERSHIP_LEFT", 2L);
    var pair = membershipPairAuthorityRow(fixture);
    assertThat(pair)
        .containsEntry("membership_exists", true)
        .containsEntry("membership_version", 3L)
        .containsEntry("membership_authority_generation", 2L)
        .containsEntry("last_event_sequence", 2L)
        .containsEntry("last_transition_invalidated", true);
    assertLeftAuthorityMembershipEvent(fixture, leftRequestId, 2L, 3L);

    var runtime = readRuntimeMembershipSnapshot(fixture);
    assertThat(runtime.membershipExists()).isTrue();
    assertThat(runtime.gameplayAdmissionAllowed()).isFalse();
    assertThat(runtime.membershipBaseline().membershipLifecycleState()).isEqualTo("INACTIVE");
    assertThat(runtime.membershipBaseline().membershipVersion())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "3"));
    assertThat(runtime.sourceEvent()).isNotNull();
    assertThat(runtime.sourceEvent().requestId()).isEqualTo(leftRequestId);
    assertThat(runtime.sourceEvent().callerBoundAuthorityInvalidated()).isTrue();
    assertThat(runtime.outboxCheckpoints())
        .containsExactlyElementsOf(expectedMembershipOutboxCheckpoints(fixture, "2"));
    assertThatThrownBy(() -> readPositiveMembershipSnapshot(fixture))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("positive active explicit membership");

    Map<String, Object> membershipBeforeNewLeftId = membershipSnapshot(fixture);
    Map<String, Object> pairBeforeNewLeftId = membershipPairAuthorityRow(fixture);
    long fenceBeforeNewLeftId = accountIssuanceFence(fixture);
    assertThatThrownBy(() -> leave(fixture, "leave-new-id-" + UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("positive active explicit membership");
    assertThat(membershipSnapshot(fixture)).isEqualTo(membershipBeforeNewLeftId);
    assertThat(membershipPairAuthorityRow(fixture)).isEqualTo(pairBeforeNewLeftId);
    assertThat(accountIssuanceFence(fixture)).isEqualTo(fenceBeforeNewLeftId);
    assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(2L);
    assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(2L);
    assertThat(countLeftAuditEvents(fixture)).isEqualTo(1L);

    UUID auditId = leftAuditId(leftRequestId);
    var audit =
        dsl.resultQuery(
                "SELECT event_type, payload, payload_digest FROM account_audit_outbox "
                    + "WHERE audit_event_id = ? AND tenant_id = ?",
                auditId,
                fixture.tenantId())
            .fetchOne();
    assertThat(audit).isNotNull();
    assertThat(audit.get("event_type", String.class)).isEqualTo("ACCOUNT_MEMBERSHIP_LEFT");
    String payload = audit.get("payload", String.class);
    assertThat(payload)
        .contains(fixture.accountUuid().toString())
        .contains(fixture.tenantUuid().toString())
        .contains(leftRequestId)
        .contains(runtime.sourceEvent().eventId())
        .contains(runtime.sourceEvent().eventDigest())
        .contains(left.receiptId().toString())
        .contains(left.receiptDigest());
    assertThat(audit.get("payload_digest", String.class))
        .isEqualTo(net.firedevops.firemud.accountservice.dto.AccountAuditDigest.ofPayload(payload));
  }

  @Test
  void auditAppendReadsBackExactStoredTimestampAndEnvelope() {
    JoinFixture fixture = fixture("active");
    UUID auditEventId = UUID.randomUUID();
    String payload =
        "{\"accountUuid\":\""
            + fixture.accountUuid()
            + "\",\"tenantUuid\":\""
            + fixture.tenantUuid()
            + "\",\"evidence\":\"exact UTF-8 payload\"}";

    List<AccountAuditEnvelope> appendedAndReadBack =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  AccountAuditEnvelope appended =
                      auditOutboxRepository.append(
                          auditEventId,
                          "tenant",
                          fixture.tenantId(),
                          "ACCOUNT_MEMBERSHIP_LEFT",
                          payload);
                  AccountAuditEnvelope durable =
                      auditOutboxRepository
                          .findMembershipLeftEnvelopeForUpdate(auditEventId, fixture.tenantId())
                          .orElseThrow();
                  return List.of(appended, durable);
                });

    assertThat(appendedAndReadBack).hasSize(2);
    AccountAuditEnvelope appended = appendedAndReadBack.get(0);
    AccountAuditEnvelope durable = appendedAndReadBack.get(1);
    assertThat(appended).isEqualTo(durable);
    assertThat(appended.payload()).isEqualTo(payload);
    assertThat(appended.payloadDigest())
        .isEqualTo(net.firedevops.firemud.accountservice.dto.AccountAuditDigest.ofPayload(payload));
    assertThat(appended.occurredAt().getNano() % 1_000).isZero();
    assertThat(countLeftAuditEvents(fixture)).isEqualTo(1L);
  }

  @Test
  void concurrentExactLeftRetriesAndReplayAfterReactivationDoNotChurnCurrentAuthority()
      throws Exception {
    JoinFixture fixture = fixture("active");
    JoinPublicProductionResult joined = join(fixture);
    String leftRequestId = "leave-retry-" + UUID.randomUUID();
    AtomicReference<MembershipTransitionReceipt> firstLeftAttempt = new AtomicReference<>();

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<MembershipTransitionReceipt> first =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return leave(fixture, leftRequestId);
              });
      Future<MembershipTransitionReceipt> second =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return leave(fixture, leftRequestId);
              });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      MembershipTransitionReceipt left = first.get(30, TimeUnit.SECONDS);
      firstLeftAttempt.set(left);
      assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(left);
      assertThat(left.transitionType()).isEqualTo("MEMBERSHIP_LEFT");
      assertThat(left.requestId()).isEqualTo(leftRequestId);
      assertThat(left.receiptSequence()).isEqualTo(2L);
      assertThat(left.membershipId()).isEqualTo(joined.membershipId());
      assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(2L);
      assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(2L);
      assertThat(countLeftAuditEvents(fixture)).isEqualTo(1L);
      assertThat(leave(fixture, leftRequestId)).isEqualTo(left);
      assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(2L);
      assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(2L);
      assertThat(countLeftAuditEvents(fixture)).isEqualTo(1L);
    } finally {
      executor.shutdownNow();
    }
    MembershipTransitionReceipt left = membershipTransitionReceipt(fixture, 2L);
    assertThat(left).isEqualTo(firstLeftAttempt.get());

    JoinFixture reactivation = fixtureForMembership(fixture);
    JoinPublicProductionResult reactivated = join(reactivation);
    assertThat(reactivated.success()).isTrue();
    assertThat(reactivated.outcomeCode()).isEqualTo("JOINED");
    assertThat(reactivated.membershipId()).isEqualTo(joined.membershipId());
    assertThat(reactivated.membershipVersion()).isEqualTo(4L);
    assertThat(reactivated.membershipAuthorityGeneration()).isEqualTo(3L);
    Map<String, Object> membershipBeforeHistoricalReplay = membershipSnapshot(fixture);
    Map<String, Object> pairBeforeHistoricalReplay = membershipPairAuthorityRow(fixture);
    long fenceBeforeHistoricalReplay = accountIssuanceFence(fixture);
    long eventCountBeforeHistoricalReplay = countAuthorityMembershipEvents(fixture);
    long receiptCountBeforeHistoricalReplay = countMembershipTransitionReceipts(fixture);
    long leftAuditCountBeforeHistoricalReplay = countLeftAuditEvents(fixture);

    assertThat(leave(fixture, leftRequestId)).isEqualTo(left);
    assertThat(membershipSnapshot(fixture)).isEqualTo(membershipBeforeHistoricalReplay);
    assertThat(membershipPairAuthorityRow(fixture)).isEqualTo(pairBeforeHistoricalReplay);
    assertThat(accountIssuanceFence(fixture)).isEqualTo(fenceBeforeHistoricalReplay);
    assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(eventCountBeforeHistoricalReplay);
    assertThat(countMembershipTransitionReceipts(fixture))
        .isEqualTo(receiptCountBeforeHistoricalReplay);
    assertThat(countLeftAuditEvents(fixture)).isEqualTo(leftAuditCountBeforeHistoricalReplay);
    assertThat(membershipSnapshot(fixture))
        .containsEntry("lifecycle_state", "ACTIVE")
        .containsEntry("membership_version", 4L)
        .containsEntry("membership_authority_generation", 3L);
  }

  @Test
  void committedLeftWithLostAcknowledgementReadsBackExactReceiptWithoutChurn() {
    JoinFixture fixture = fixture("active");
    JoinPublicProductionResult joined = join(fixture);
    assertThat(joined.success()).isTrue();
    String leftRequestId = "leave-lost-ack-" + UUID.randomUUID();
    long fenceBeforeLeft = accountIssuanceFence(fixture);
    AtomicBoolean loseNextLeftAcknowledgement = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              Object envelope = invocation.callRealMethod();
              if ("ACCOUNT_MEMBERSHIP_LEFT".equals(invocation.getArgument(3))
                  && loseNextLeftAcknowledgement.compareAndSet(true, false)) {
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                      @Override
                      public void afterCommit() {
                        throw new IllegalStateException(
                            "simulated lost Account LEFT commit acknowledgement");
                      }
                    });
              }
              return envelope;
            })
        .when(auditOutboxRepository)
        .append(
            any(UUID.class),
            eq("tenant"),
            eq(Long.valueOf(fixture.tenantId())),
            eq("ACCOUNT_MEMBERSHIP_LEFT"),
            anyString());

    assertThatThrownBy(() -> leave(fixture, leftRequestId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated lost Account LEFT commit acknowledgement");
    assertThat(loseNextLeftAcknowledgement.get()).isFalse();

    MembershipTransitionReceipt committedReceipt = membershipTransitionReceipt(fixture, 2L);
    assertThat(committedReceipt.transitionType()).isEqualTo("MEMBERSHIP_LEFT");
    assertThat(committedReceipt.requestId()).isEqualTo(leftRequestId);
    assertThat(committedReceipt.membershipId()).isEqualTo(joined.membershipId());
    assertThat(membershipSnapshot(fixture))
        .containsEntry("lifecycle_state", "INACTIVE")
        .containsEntry("gameplay_admission_allowed", false)
        .containsEntry("membership_version", 3L)
        .containsEntry("membership_authority_generation", 2L);
    assertThat(membershipPairAuthorityRow(fixture))
        .containsEntry("membership_exists", true)
        .containsEntry("membership_version", 3L)
        .containsEntry("membership_authority_generation", 2L)
        .containsEntry("last_event_sequence", 2L)
        .containsEntry("last_transition_invalidated", true);
    assertThat(accountIssuanceFence(fixture)).isEqualTo(fenceBeforeLeft + 1L);
    assertThat(authorityGeneration("MEMBERSHIP", null, fixture.accountUuid(), fixture.tenantUuid()))
        .isEqualTo(2L);
    assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(2L);
    assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(2L);
    assertThat(countLeftAuditEvents(fixture)).isEqualTo(1L);
    assertLeftAuthorityMembershipEvent(fixture, leftRequestId, 2L, 3L);

    Map<String, Object> membershipAfterCommit = membershipSnapshot(fixture);
    Map<String, Object> pairAfterCommit = membershipPairAuthorityRow(fixture);
    Map<String, Object> rolesAfterCommit = roleSnapshotRowSnapshot(fixture);
    Map<String, Object> firstReceiptAfterCommit =
        membershipTransitionReceiptRowSnapshot(fixture, 1L);
    Map<String, Object> leftReceiptAfterCommit =
        membershipTransitionReceiptRowSnapshot(fixture, 2L);
    Map<String, Object> firstEventAfterCommit = authorityMembershipEventSnapshot(fixture, 1L);
    Map<String, Object> leftEventAfterCommit = authorityMembershipEventSnapshot(fixture, 2L);
    Map<String, Object> leftAuditAfterCommit = leftAuditEnvelopeSnapshot(fixture, leftRequestId);
    long generationAfterCommit =
        authorityGeneration("MEMBERSHIP", null, fixture.accountUuid(), fixture.tenantUuid());
    long fenceAfterCommit = accountIssuanceFence(fixture);
    long authorityOutboxHeadAfterCommit = authorityOutboxHead(fixture);
    long receiptHeadAfterCommit = membershipReceiptHead(fixture);

    assertThat(leave(fixture, leftRequestId)).isEqualTo(committedReceipt);
    assertThat(membershipSnapshot(fixture)).isEqualTo(membershipAfterCommit);
    assertThat(membershipPairAuthorityRow(fixture)).isEqualTo(pairAfterCommit);
    assertThat(roleSnapshotRowSnapshot(fixture)).isEqualTo(rolesAfterCommit);
    assertThat(membershipTransitionReceiptRowSnapshot(fixture, 1L))
        .isEqualTo(firstReceiptAfterCommit);
    assertThat(membershipTransitionReceiptRowSnapshot(fixture, 2L))
        .isEqualTo(leftReceiptAfterCommit);
    assertThat(authorityMembershipEventSnapshot(fixture, 1L)).isEqualTo(firstEventAfterCommit);
    assertThat(authorityMembershipEventSnapshot(fixture, 2L)).isEqualTo(leftEventAfterCommit);
    assertThat(leftAuditEnvelopeSnapshot(fixture, leftRequestId)).isEqualTo(leftAuditAfterCommit);
    assertThat(authorityGeneration("MEMBERSHIP", null, fixture.accountUuid(), fixture.tenantUuid()))
        .isEqualTo(generationAfterCommit);
    assertThat(accountIssuanceFence(fixture)).isEqualTo(fenceAfterCommit);
    assertThat(authorityOutboxHead(fixture)).isEqualTo(authorityOutboxHeadAfterCommit);
    assertThat(membershipReceiptHead(fixture)).isEqualTo(receiptHeadAfterCommit);
    assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(2L);
    assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(2L);
    assertThat(countLeftAuditEvents(fixture)).isEqualTo(1L);
  }

  @Test
  void reusedLeftAndJoinRequestIdsAreRejectedWithoutCrossScopeMutation() {
    JoinFixture leftScope = fixture("active");
    JoinPublicProductionResult originalJoin = join(leftScope);
    assertThat(originalJoin.success()).isTrue();

    JoinFixture otherScope = fixture("active");
    JoinPublicProductionResult otherJoin = join(otherScope);
    assertThat(otherJoin.success()).isTrue();
    assertThat(otherScope.accountId()).isNotEqualTo(leftScope.accountId());
    assertThat(otherScope.tenantId()).isNotEqualTo(leftScope.tenantId());

    String leftRequestId = "leave-cross-scope-" + UUID.randomUUID();
    MembershipTransitionReceipt committedLeft = leave(leftScope, leftRequestId);
    assertThat(committedLeft.transitionType()).isEqualTo("MEMBERSHIP_LEFT");
    MembershipScopeSnapshot leftScopeBeforeReuse =
        membershipScopeSnapshot(leftScope, leftRequestId);
    MembershipScopeSnapshot otherScopeBeforeReuse = membershipScopeSnapshot(otherScope, null);

    assertThatThrownBy(() -> leave(otherScope, leftRequestId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("request ID was reused for another operation");
    assertThat(membershipScopeSnapshot(leftScope, leftRequestId)).isEqualTo(leftScopeBeforeReuse);
    assertThat(membershipScopeSnapshot(otherScope, null)).isEqualTo(otherScopeBeforeReuse);

    assertThatThrownBy(() -> leave(leftScope, leftScope.requestId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("request ID was reused for another operation");
    assertThat(membershipScopeSnapshot(leftScope, leftRequestId)).isEqualTo(leftScopeBeforeReuse);
    assertThat(membershipScopeSnapshot(otherScope, null)).isEqualTo(otherScopeBeforeReuse);
  }

  @Test
  void contradictoryLatestEventDeniesLeftWithoutMembershipOrOutboxMutation() {
    JoinFixture fixture = fixture("active");
    JoinPublicProductionResult joined = join(fixture);
    assertThat(joined.success()).isTrue();
    appendContradictoryCurrentEvent(fixture);
    Map<String, Object> membershipBefore = membershipSnapshot(fixture);
    Map<String, Object> pairBefore = membershipPairAuthorityRow(fixture);
    long fenceBefore = accountIssuanceFence(fixture);
    long eventCountBefore = countAuthorityMembershipEvents(fixture);
    long receiptCountBefore = countMembershipTransitionReceipts(fixture);

    assertThatThrownBy(() -> leave(fixture, "leave-contradictory-" + UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("receipt differs from its latest V33 event");

    assertThat(membershipSnapshot(fixture)).isEqualTo(membershipBefore);
    assertThat(membershipPairAuthorityRow(fixture)).isEqualTo(pairBefore);
    assertThat(accountIssuanceFence(fixture)).isEqualTo(fenceBefore);
    assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(eventCountBefore);
    assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(receiptCountBefore);
    assertThat(countLeftAuditEvents(fixture)).isZero();
  }

  @Test
  void auditFailureRollsBackCompleteOwnerLeftTransaction() {
    JoinFixture fixture = fixture("active");
    JoinPublicProductionResult joined = join(fixture);
    assertThat(joined.success()).isTrue();
    Map<String, Object> membershipBefore = membershipSnapshot(fixture);
    Map<String, Object> pairBefore = membershipPairAuthorityRow(fixture);
    Map<String, Object> rolesBefore = roleSnapshotRowSnapshot(fixture);
    Map<String, Object> receiptBefore = membershipTransitionReceiptRowSnapshot(fixture, 1L);
    Map<String, Object> eventBefore = authorityMembershipEventSnapshot(fixture, 1L);
    long generationBefore =
        authorityGeneration("MEMBERSHIP", null, fixture.accountUuid(), fixture.tenantUuid());
    long fenceBefore = accountIssuanceFence(fixture);
    long authorityOutboxHeadBefore = authorityOutboxHead(fixture);
    long receiptHeadBefore = membershipReceiptHead(fixture);

    doThrow(new IllegalStateException("LEFT audit insert unavailable"))
        .when(auditOutboxRepository)
        .append(
            any(UUID.class),
            eq("tenant"),
            eq(Long.valueOf(fixture.tenantId())),
            eq("ACCOUNT_MEMBERSHIP_LEFT"),
            anyString());

    assertThatThrownBy(() -> leave(fixture, "leave-audit-failure-" + UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LEFT audit insert unavailable");

    assertThat(membershipSnapshot(fixture)).isEqualTo(membershipBefore);
    assertThat(membershipPairAuthorityRow(fixture)).isEqualTo(pairBefore);
    assertThat(roleSnapshotRowSnapshot(fixture)).isEqualTo(rolesBefore);
    assertThat(membershipTransitionReceiptRowSnapshot(fixture, 1L)).isEqualTo(receiptBefore);
    assertThat(authorityMembershipEventSnapshot(fixture, 1L)).isEqualTo(eventBefore);
    assertThat(authorityGeneration("MEMBERSHIP", null, fixture.accountUuid(), fixture.tenantUuid()))
        .isEqualTo(generationBefore);
    assertThat(accountIssuanceFence(fixture)).isEqualTo(fenceBefore);
    assertThat(
            dsl.resultQuery(
                    "SELECT last_sequence FROM account_authority_outbox_streams "
                        + "WHERE outbox_stream_key = ?",
                    authorityStreamKey(fixture))
                .fetchOne(0, Long.class))
        .isEqualTo(authorityOutboxHeadBefore);
    assertThat(
            dsl.resultQuery(
                    "SELECT last_receipt_sequence FROM account_membership_transition_receipt_stream_heads "
                        + "WHERE account_id = ? AND tenant_id = ?",
                    fixture.accountId(),
                    fixture.tenantId())
                .fetchOne(0, Long.class))
        .isEqualTo(receiptHeadBefore);
    assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(1L);
    assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(1L);
    assertThat(countLeftAuditEvents(fixture)).isZero();
  }

  @Test
  void concurrentLeftAndReactivationSerializeAsCompleteAccountTransitions() throws Exception {
    JoinFixture fixture = fixture("active");
    assertThat(join(fixture).success()).isTrue();
    JoinFixture reactivation = fixtureForMembership(fixture);
    String leftRequestId = "leave-race-" + UUID.randomUUID();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<MembershipTransitionReceipt> leftAttempt =
          executor.submit(
              () -> {
                await(start);
                return leave(fixture, leftRequestId);
              });
      Future<JoinPublicProductionResult> reactivationAttempt =
          executor.submit(
              () -> {
                await(start);
                return join(reactivation);
              });
      start.countDown();

      MembershipTransitionReceipt left = leftAttempt.get(30, TimeUnit.SECONDS);
      JoinPublicProductionResult joined = reactivationAttempt.get(30, TimeUnit.SECONDS);
      assertThat(left.transitionType()).isEqualTo("MEMBERSHIP_LEFT");
      assertThat(joined.success()).isTrue();
      assertThat(joined.outcomeCode()).isIn("JOINED", "ALREADY_ACTIVE");
      assertThat(joined.membershipId()).isEqualTo(left.membershipId());

      Map<String, Object> currentMembership = membershipSnapshot(fixture);
      Map<String, Object> currentPair = membershipPairAuthorityRow(fixture);
      if ("JOINED".equals(joined.outcomeCode())) {
        assertThat(currentMembership)
            .containsEntry("lifecycle_state", "ACTIVE")
            .containsEntry("gameplay_admission_allowed", true)
            .containsEntry("membership_version", 4L)
            .containsEntry("membership_authority_generation", 3L);
        assertThat(currentPair)
            .containsEntry("membership_version", 4L)
            .containsEntry("membership_authority_generation", 3L)
            .containsEntry("last_event_sequence", 3L)
            .containsEntry("last_transition_invalidated", true);
        assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(3L);
        assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(3L);
      } else {
        assertThat(currentMembership)
            .containsEntry("lifecycle_state", "INACTIVE")
            .containsEntry("gameplay_admission_allowed", false)
            .containsEntry("membership_version", 3L)
            .containsEntry("membership_authority_generation", 2L);
        assertThat(currentPair)
            .containsEntry("membership_version", 3L)
            .containsEntry("membership_authority_generation", 2L)
            .containsEntry("last_event_sequence", 2L)
            .containsEntry("last_transition_invalidated", true);
        assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(2L);
        assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(2L);
      }
      assertThat(currentPair.get("last_event_sequence"))
          .isEqualTo((long) countAuthorityMembershipEvents(fixture));
      assertThat(countLeftAuditEvents(fixture)).isEqualTo(1L);
      var runtime = readRuntimeMembershipSnapshot(fixture);
      assertThat(runtime.membershipBaseline().membershipLifecycleState())
          .isEqualTo(currentMembership.get("lifecycle_state"));
      assertThat(runtime.gameplayAdmissionAllowed())
          .isEqualTo(currentMembership.get("gameplay_admission_allowed"));
    } finally {
      executor.shutdownNow();
    }
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
      assertThat(firstResult.membershipVersion()).isEqualTo(2L);
      assertThat(secondResult.membershipVersion()).isEqualTo(2L);
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
      assertRoleSnapshot(joinedFixture, membershipId, 2L, List.of("player"));
      assertThat(countAuthorityMembershipEvents(first)).isEqualTo(1L);
      assertThat(countAuthorityMembershipStreams(first)).isEqualTo(1L);
      assertThat(countMembershipAuthorityGenerations(first)).isEqualTo(1L);
      assertAuthorityMembershipEvent(joinedFixture, 1L, 2L, false);
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
                  2L,
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
                  2L,
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
      assertAuthorityMembershipEvent(joinedFixture, 1L, 2L, false);
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
    assertAuthorityMembershipEvent(initialJoin, 1L, 2L, false);
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
        initial.membershipVersion(),
        "designer");

    JoinFixture retry = fixtureForMembership(initialJoin);
    JoinPublicProductionResult result = join(retry);

    assertThat(result.success()).isFalse();
    assertThat(result.outcomeCode()).isEqualTo("MEMBERSHIP_RECONCILIATION_REQUIRED");
    assertThat(countMembershipTransitionReceipts(retry)).isEqualTo(1L);
    assertThat(countRoleSnapshotHeaders(retry)).isEqualTo(1L);
    assertThat(countRoleSnapshotRows(retry)).isEqualTo(1L);
    assertRoleSnapshot(
        retry, initial.membershipId(), initial.membershipVersion(), List.of("designer"));
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
    assertAuthorityMembershipEvent(first, 1L, 2L, false);
    assertAuthorityMembershipEvent(second, 1L, 2L, false);
  }

  @Test
  void reactivationWithoutCanonicalInactiveEventFailsClosedWithoutMutation() {
    JoinFixture initialJoin = fixture("active");
    JoinPublicProductionResult initial = join(initialJoin);
    assertThat(initial.success()).isTrue();
    assertMembershipTransitionReceipt(initialJoin, "MEMBERSHIP_JOINED", 1L);
    assertAuthorityMembershipEvent(initialJoin, 1L, 2L, false);
    Map<String, Object> originalTransitionReceipt =
        membershipTransitionReceiptRowSnapshot(initialJoin, 1L);
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
    assertThat(membershipTransitionReceiptRowSnapshot(initialJoin, 1L))
        .isEqualTo(originalTransitionReceipt);
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
              assertThat(row.get("membership_version", Long.class)).isEqualTo(2L);
              assertThat(row.get("membership_authority_generation", Long.class)).isEqualTo(1L);
              assertThat(row.get("authority_provenance", String.class)).isEqualTo("EXPLICIT_JOIN");
            });
    assertThat(countRoleSnapshotHeaders(reactivation)).isEqualTo(1L);
    assertThat(countRoleSnapshotRows(reactivation)).isEqualTo(1L);
    assertRoleSnapshot(reactivation, initial.membershipId(), 2L, List.of("designer"));
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
  void membershipTransitionReceiptsRejectMutationAndStillAllowAppendAndStreamAdvance() {
    JoinFixture initialJoin = fixture("active");
    assertThat(join(initialJoin).success()).isTrue();
    assertMembershipTransitionReceipt(initialJoin, "MEMBERSHIP_JOINED", 1L);

    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            dsl.execute(
                "UPDATE account_membership_transition_receipts "
                    + "SET transition_type = 'MEMBERSHIP_REACTIVATED' "
                    + "WHERE account_id = ? AND tenant_id = ?",
                initialJoin.accountId(),
                initialJoin.tenantId()));
    assertMembershipTransitionReceipt(initialJoin, "MEMBERSHIP_JOINED", 1L);

    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            dsl.execute(
                "DELETE FROM account_membership_transition_receipts "
                    + "WHERE account_id = ? AND tenant_id = ?",
                initialJoin.accountId(),
                initialJoin.tenantId()));
    assertMembershipTransitionReceipt(initialJoin, "MEMBERSHIP_JOINED", 1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT last_receipt_sequence "
                        + "FROM account_membership_transition_receipt_stream_heads "
                        + "WHERE account_id = ? AND tenant_id = ?",
                    initialJoin.accountId(),
                    initialJoin.tenantId())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);

    String leftRequestId = "leave-proof-" + UUID.randomUUID();
    MembershipTransitionReceipt left = leave(initialJoin, leftRequestId);
    assertThat(left.transitionType()).isEqualTo("MEMBERSHIP_LEFT");
    assertThat(left.requestId()).isEqualTo(leftRequestId);
    assertThat(left.receiptSequence()).isEqualTo(2L);
    assertMembershipTransitionReceipt(initialJoin, "MEMBERSHIP_LEFT", 2L);
    assertThat(membershipSnapshot(initialJoin))
        .containsEntry("lifecycle_state", "INACTIVE")
        .containsEntry("gameplay_admission_allowed", false)
        .containsEntry("membership_version", 3L)
        .containsEntry("membership_authority_generation", 2L);
    assertThat(countAuthorityMembershipEvents(initialJoin)).isEqualTo(2L);
    assertLeftAuthorityMembershipEvent(initialJoin, leftRequestId, 2L, 3L);

    JoinFixture reactivation = fixtureForMembership(initialJoin);

    assertThat(join(reactivation).success()).isTrue();
    assertMembershipTransitionReceipt(reactivation, "MEMBERSHIP_REACTIVATED", 3L);
    assertThat(membershipSnapshot(reactivation))
        .containsEntry("lifecycle_state", "ACTIVE")
        .containsEntry("gameplay_admission_allowed", true)
        .containsEntry("membership_version", 4L)
        .containsEntry("membership_authority_generation", 3L);
    assertThat(countAuthorityMembershipEvents(reactivation)).isEqualTo(3L);
    assertAuthorityMembershipEvent(reactivation, 3L, 4L, true);
    assertThat(countMembershipTransitionReceipts(reactivation)).isEqualTo(3L);
    assertThat(
            dsl.resultQuery(
                    "SELECT last_receipt_sequence "
                        + "FROM account_membership_transition_receipt_stream_heads "
                        + "WHERE account_id = ? AND tenant_id = ?",
                    reactivation.accountId(),
                    reactivation.tenantId())
                .fetchOne(0, Long.class))
        .isEqualTo(3L);
  }

  @Test
  void minimizedPriorJoinAuditWithoutMembershipOrReceiptKeepsJoinRetryable() {
    JoinFixture retry = fixture("active");
    dsl.execute(
        "INSERT INTO account_audit_outbox "
            + "(audit_event_id, scope, tenant_id, tenant_identity_version, tenant_uuid, "
            + "producer_service, event_type, occurred_at, schema_version, payload_digest_version, "
            + "payload_digest, payload, delivery_status, next_attempt_at) "
            + "VALUES (?, 'tenant', ?, 1, NULL, 'account-service', "
            + "'ACCOUNT_JOINED_PUBLIC_PRODUCTION', CURRENT_TIMESTAMP, 1, 1, ?, NULL, "
            + "'MINIMIZED', NULL)",
        UUID.randomUUID(),
        retry.tenantId(),
        "sha256:" + "0".repeat(64));

    for (int attempt = 0; attempt < 2; attempt++) {
      AuthenticationException blocked =
          assertThrows(AuthenticationException.class, () -> join(retry));
      assertThat(blocked.getCode()).isEqualTo("AUTH_UNAVAILABLE");
    }

    assertThat(countMemberships(retry)).isZero();
    assertThat(countMembershipTransitionReceipts(retry)).isZero();
    assertThat(countJoinOutbox(retry)).isZero();
    assertThat(countTenantJoinOutboxEvents(retry)).isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT status, last_attempt_failure_code FROM account_join_operations "
                        + "WHERE request_id = ?",
                    retry.requestId())
                .fetchOne())
        .satisfies(
            row -> {
              assertThat(row.get("status", String.class)).isEqualTo("PENDING");
              assertThat(row.get("last_attempt_failure_code", String.class))
                  .isEqualTo("AUTH_UNAVAILABLE");
            });
    assertThat(
            dsl.resultQuery(
                    "SELECT delivery_status, payload FROM account_audit_outbox "
                        + "WHERE scope = 'tenant' AND tenant_id = ? "
                        + "AND event_type = 'ACCOUNT_JOINED_PUBLIC_PRODUCTION'",
                    retry.tenantId())
                .fetchOne())
        .satisfies(
            row -> {
              assertThat(row.get("delivery_status", String.class)).isEqualTo("MINIMIZED");
              assertThat(row.get("payload", String.class)).isNull();
            });
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
    long activeMembershipId = seedPlayerRoleSnapshot(active);
    long inactiveMembershipId = seedPlayerRoleSnapshot(inactive);

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
    assertThat(countRoleSnapshotHeaders(active)).isEqualTo(1L);
    assertThat(countRoleSnapshotRows(active)).isEqualTo(1L);
    assertRoleSnapshot(active, activeMembershipId, 1L, List.of("player"));
    assertThat(countRoleSnapshotHeaders(inactive)).isEqualTo(1L);
    assertThat(countRoleSnapshotRows(inactive)).isEqualTo(1L);
    assertRoleSnapshot(inactive, inactiveMembershipId, 1L, List.of("player"));
    assertThat(
            dsl.resultQuery(
                    "SELECT lifecycle_state FROM account_tenant_membership "
                        + "WHERE account_id = ? AND tenant_id = ?",
                    inactive.accountId(),
                    inactive.tenantId())
                .fetchOne(0, String.class))
        .isEqualTo("INACTIVE");
  }

  @Test
  void positiveMembershipSnapshotReturnsExactCommittedAuthorityEvidence() {
    JoinFixture fixture = fixture("active");
    JoinPublicProductionResult joined = join(fixture);

    var snapshot = readPositiveMembershipSnapshot(fixture);

    assertThat(joined.success()).isTrue();
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.membershipExists()).isTrue();
    assertThat(snapshot.accountId()).isEqualTo(fixture.accountUuid().toString());
    assertThat(snapshot.tenantId()).isEqualTo(fixture.tenantUuid().toString());
    assertThat(snapshot.membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(snapshot.gameplayAdmissionAllowed()).isTrue();
    assertThat(snapshot.membershipVersion())
        .isEqualTo(
            Map.of(fixture.tenantUuid().toString(), Long.toString(joined.membershipVersion())));
    assertThat(snapshot.authorityEvent().membershipVersion())
        .isEqualTo(
            Map.of(fixture.tenantUuid().toString(), Long.toString(joined.membershipVersion())));
    assertThat(snapshot.membershipAuthorityGeneration())
        .isEqualTo(Long.toString(joined.membershipAuthorityGeneration()));
    assertThat(snapshot.roles()).containsExactly("player");
    assertThat(snapshot.authorityTuple().membershipAuthorityGeneration())
        .containsEntry(fixture.tenantUuid().toString(), snapshot.membershipAuthorityGeneration());
    assertThat(snapshot.evaluatedAt()).isNotNull();
    assertThat(snapshot.authorityEvent().requestId()).isEqualTo(fixture.requestId());
    assertThat(snapshot.transitionReceipt().requestId()).isEqualTo(fixture.requestId());
    assertThat(snapshot.transitionReceipt().membershipId()).isEqualTo(joined.membershipId());
    assertThat(snapshot.outboxCheckpoints())
        .containsExactlyElementsOf(
            expectedMembershipOutboxCheckpoints(
                fixture, snapshot.authorityEvent().outboxSequence()));
    assertThat(snapshot.outboxSourceEvidence())
        .containsExactly(
            new OutboxSourceEvidence(
                authorityStreamKey(fixture),
                snapshot.authorityEvent().outboxSequence(),
                snapshot.authorityEvent().eventId(),
                snapshot.authorityEvent().eventDigest(),
                snapshot.authorityEvent().canonicalJson()));
  }

  @Test
  void tenantRoleMutationDeniesNonAdminWithoutChangingAnyAccountSource() {
    JoinFixture fixture = fixture("active");
    JoinPublicProductionResult joined = join(fixture);
    Map<String, Object> sourceBefore = membershipAuthorityReadEvidenceSnapshot(fixture);
    long auditCountBefore = countAuditOutboxRows(fixture);
    long operationCountBefore =
        Objects.requireNonNull(
            Objects.requireNonNull(
                    dsl.resultQuery(
                            "SELECT count(*) FROM account_tenant_role_operations WHERE request_id = ?",
                            UUID.nameUUIDFromBytes(
                                ("tenant-role-denied:" + fixture.requestId())
                                    .getBytes(StandardCharsets.UTF_8)))
                        .fetchOne(),
                    "Expected tenant-role operation count row")
                .get(0, Long.class),
            "Expected tenant-role operation count value");

    UUID requestId =
        UUID.nameUUIDFromBytes(
            ("tenant-role-denied:" + fixture.requestId()).getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(
            () ->
                tenantRoleMutationService.mutate(
                    new AccountTenantRoleOperationRepository.Request(
                        requestId,
                        fixture.accountUuid(),
                        fixture.tenantUuid(),
                        fixture.accountUuid(),
                        AccountTenantRoleOperationRepository.Action.GRANT_DESIGNER,
                        joined.membershipVersion(),
                        joined.membershipVersion())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a tenant administrator");

    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture)).isEqualTo(sourceBefore);
    assertThat(countAuditOutboxRows(fixture)).isEqualTo(auditCountBefore);
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_tenant_role_operations WHERE request_id = ?",
                    requestId)
                .fetchOne(0, Long.class))
        .isEqualTo(operationCountBefore);
    assertThat(committedRoles(fixture)).containsExactly("player");
  }

  @Test
  void tenantRoleMutationRejectsStaleMembershipWithoutChangingAnyAccountSource() {
    JoinFixture fixture = fixture("active");
    JoinPublicProductionResult joined = join(fixture);
    Map<String, Object> sourceBefore = membershipAuthorityReadEvidenceSnapshot(fixture);
    long auditCountBefore = countAuditOutboxRows(fixture);
    UUID requestId =
        UUID.nameUUIDFromBytes(
            ("tenant-role-stale:" + fixture.requestId()).getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(
            () ->
                tenantRoleMutationService.mutate(
                    new AccountTenantRoleOperationRepository.Request(
                        requestId,
                        fixture.accountUuid(),
                        fixture.tenantUuid(),
                        fixture.accountUuid(),
                        AccountTenantRoleOperationRepository.Action.GRANT_DESIGNER,
                        joined.membershipVersion() + 1,
                        joined.membershipVersion() + 1)))
        .isInstanceOf(AccountTenantRoleOperationRepository.OperationConflictException.class)
        .hasMessageContaining("membership version is stale");

    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture)).isEqualTo(sourceBefore);
    assertThat(countAuditOutboxRows(fixture)).isEqualTo(auditCountBefore);
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_tenant_role_operations WHERE request_id = ?",
                    requestId)
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(committedRoles(fixture)).containsExactly("player");
  }

  @Test
  void tenantRoleMutationRejectsMissingCurrentMembershipWithoutCreatingEvidence() {
    JoinFixture fixture = fixture("active");
    UUID requestId =
        UUID.nameUUIDFromBytes(
            ("tenant-role-no-current:" + fixture.requestId()).getBytes(StandardCharsets.UTF_8));
    long auditCountBefore = countAuditOutboxRows(fixture);

    assertThatThrownBy(
            () ->
                tenantRoleMutationService.mutate(
                    new AccountTenantRoleOperationRepository.Request(
                        requestId,
                        fixture.accountUuid(),
                        fixture.tenantUuid(),
                        fixture.accountUuid(),
                        AccountTenantRoleOperationRepository.Action.GRANT_DESIGNER,
                        1L,
                        1L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Current Account membership row is absent");

    assertThat(countMemberships(fixture)).isZero();
    assertThat(countMembershipPairAuthorities(fixture)).isZero();
    assertThat(countMembershipTransitionReceipts(fixture)).isZero();
    assertThat(countAuthorityMembershipEvents(fixture)).isZero();
    assertThat(countAuthorityMembershipStreams(fixture)).isZero();
    assertThat(countAuditOutboxRows(fixture)).isEqualTo(auditCountBefore);
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_tenant_role_operations WHERE request_id = ?",
                    requestId)
                .fetchOne(0, Long.class))
        .isZero();
  }

  @Test
  void tenantRoleMutationCommitsGrantRevokeTransferAndReplaysOriginalGrantAfterLaterChange() {
    TenantRoleFixture fixture = syntheticRetainedAdminFixture();
    PositiveMembershipSnapshot initialAdmin =
        readExistingPairBoundPositiveMembershipSnapshot(fixture.admin());
    PositiveMembershipSnapshot initialTarget =
        readExistingPairBoundPositiveMembershipSnapshot(fixture.target());
    assertThat(initialAdmin.roles()).containsExactly("player", "tenantAdmin");
    assertThat(initialTarget.roles()).containsExactly("player");
    assertThat(initialAdmin.membershipVersion().get(fixture.admin().tenantUuid().toString()))
        .isEqualTo("2");
    assertThat(initialTarget.membershipVersion().get(fixture.target().tenantUuid().toString()))
        .isEqualTo("2");

    UUID grantRequestId = UUID.randomUUID();
    Request grantRequest =
        new Request(
            grantRequestId,
            fixture.admin().accountUuid(),
            fixture.admin().tenantUuid(),
            fixture.target().accountUuid(),
            Action.GRANT_DESIGNER,
            2L,
            2L);
    OperationEvidence grant = tenantRoleMutationService.mutate(grantRequest);
    assertThat(grant.status()).isEqualTo("COMMITTED");
    assertThat(grant.members()).hasSize(1);
    assertCurrentRoleMutationState(
        fixture.target(), 3L, 1L, 2L, 1L, List.of("designer", "player"), false);
    assertCurrentRoleMutationState(
        fixture.admin(), 2L, 1L, 1L, 1L, List.of("player", "tenantAdmin"), false);

    UUID revokeRequestId = UUID.randomUUID();
    OperationEvidence revoke =
        tenantRoleMutationService.mutate(
            new Request(
                revokeRequestId,
                fixture.admin().accountUuid(),
                fixture.admin().tenantUuid(),
                fixture.target().accountUuid(),
                Action.REVOKE_DESIGNER,
                2L,
                3L));
    assertThat(revoke.status()).isEqualTo("COMMITTED");
    assertCurrentRoleMutationState(fixture.target(), 4L, 2L, 3L, 2L, List.of("player"), true);
    assertCurrentRoleMutationState(
        fixture.admin(), 2L, 1L, 1L, 1L, List.of("player", "tenantAdmin"), false);

    Map<String, Object> adminBeforeRetry = membershipAuthorityReadEvidenceSnapshot(fixture.admin());
    Map<String, Object> targetBeforeRetry =
        membershipAuthorityReadEvidenceSnapshot(fixture.target());
    long auditCountBeforeRetry = countAuditOutboxRows(fixture.admin());
    OperationEvidence replay = tenantRoleMutationService.mutate(grantRequest);
    assertExactTenantRoleOperation(grant, replay);
    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture.admin()))
        .isEqualTo(adminBeforeRetry);
    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture.target()))
        .isEqualTo(targetBeforeRetry);
    assertThat(countAuditOutboxRows(fixture.admin())).isEqualTo(auditCountBeforeRetry);

    assertThatThrownBy(
            () ->
                tenantRoleMutationService.mutate(
                    new Request(
                        grantRequestId,
                        grantRequest.actorAccountUuid(),
                        grantRequest.tenantUuid(),
                        grantRequest.targetAccountUuid(),
                        Action.REVOKE_DESIGNER,
                        grantRequest.expectedActorMembershipVersion(),
                        grantRequest.expectedTargetMembershipVersion())))
        .isInstanceOf(AccountTenantRoleOperationRepository.OperationConflictException.class)
        .hasMessageContaining("changed immutable input");
    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture.admin()))
        .isEqualTo(adminBeforeRetry);
    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture.target()))
        .isEqualTo(targetBeforeRetry);

    OperationEvidence transfer =
        tenantRoleMutationService.mutate(
            new Request(
                UUID.randomUUID(),
                fixture.admin().accountUuid(),
                fixture.admin().tenantUuid(),
                fixture.target().accountUuid(),
                Action.TRANSFER_TENANT_ADMIN,
                2L,
                4L));
    assertThat(transfer.status()).isEqualTo("COMMITTED");
    assertThat(transfer.members()).hasSize(2);
    assertCurrentRoleMutationState(fixture.admin(), 3L, 2L, 2L, 2L, List.of("player"), true);
    assertCurrentRoleMutationState(
        fixture.target(), 5L, 2L, 4L, 2L, List.of("player", "tenantAdmin"), false);
    assertThat(countTenantRoleOperations(fixture.admin())).isEqualTo(3L);
    assertThat(countAuditOutboxRows(fixture.admin())).isEqualTo(3L);
    assertThat(authorityGeneration("TENANT", null, null, fixture.admin().tenantUuid()))
        .isEqualTo(1L);
  }

  @Test
  void tenantRoleMutationRollsBackAllSourcesWhenEventAppendFailsAfterWriting() {
    TenantRoleFixture fixture = syntheticRetainedAdminFixture();
    Map<String, Object> adminBefore = membershipAuthorityReadEvidenceSnapshot(fixture.admin());
    Map<String, Object> targetBefore = membershipAuthorityReadEvidenceSnapshot(fixture.target());
    long auditCountBefore = countAuditOutboxRows(fixture.admin());
    UUID requestId = UUID.randomUUID();
    Request request =
        new Request(
            requestId,
            fixture.admin().accountUuid(),
            fixture.admin().tenantUuid(),
            fixture.target().accountUuid(),
            Action.GRANT_DESIGNER,
            2L,
            2L);

    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("injected failure after role event append");
            })
        .when(membershipAuthorityEventProducer)
        .appendTenantRoleEvent(
            any(Request.class),
            any(AccountTenantMembership.class),
            any(RoleSnapshot.class),
            any(PairAuthority.class),
            anyBoolean());

    assertThatThrownBy(() -> tenantRoleMutationService.mutate(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("injected failure after role event append");
    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture.admin())).isEqualTo(adminBefore);
    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture.target())).isEqualTo(targetBefore);
    assertThat(countAuditOutboxRows(fixture.admin())).isEqualTo(auditCountBefore);
    assertThat(countAuthorityMembershipEvents(fixture.target())).isEqualTo(1L);
    assertTenantRoleOperationAbsent(requestId);
  }

  @Test
  void tenantRoleMutationRollsBackAllSourcesWhenAuditAppendFailsAfterWriting() {
    TenantRoleFixture fixture = syntheticRetainedAdminFixture();
    Map<String, Object> adminBefore = membershipAuthorityReadEvidenceSnapshot(fixture.admin());
    Map<String, Object> targetBefore = membershipAuthorityReadEvidenceSnapshot(fixture.target());
    long auditCountBefore = countAuditOutboxRows(fixture.admin());
    UUID requestId = UUID.randomUUID();
    Request request =
        new Request(
            requestId,
            fixture.admin().accountUuid(),
            fixture.admin().tenantUuid(),
            fixture.target().accountUuid(),
            Action.GRANT_DESIGNER,
            2L,
            2L);

    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("injected failure after role audit append");
            })
        .when(auditOutboxRepository)
        .appendCanonicalTenant(any(UUID.class), anyString(), anyString(), anyString());

    assertThatThrownBy(() -> tenantRoleMutationService.mutate(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("injected failure after role audit append");
    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture.admin())).isEqualTo(adminBefore);
    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture.target())).isEqualTo(targetBefore);
    assertThat(countAuditOutboxRows(fixture.admin())).isEqualTo(auditCountBefore);
    assertThat(countAuthorityMembershipEvents(fixture.target())).isEqualTo(1L);
    assertTenantRoleOperationAbsent(requestId);
  }

  @Test
  void tenantRoleMutationRollsBackAllSourcesWhenFinalCurrentReadbackFails() {
    TenantRoleFixture fixture = syntheticRetainedAdminFixture();
    Map<String, Object> adminBefore = membershipAuthorityReadEvidenceSnapshot(fixture.admin());
    Map<String, Object> targetBefore = membershipAuthorityReadEvidenceSnapshot(fixture.target());
    long auditCountBefore = countAuditOutboxRows(fixture.admin());
    UUID requestId = UUID.randomUUID();
    Request request =
        new Request(
            requestId,
            fixture.admin().accountUuid(),
            fixture.admin().tenantUuid(),
            fixture.target().accountUuid(),
            Action.GRANT_DESIGNER,
            2L,
            2L);
    AtomicInteger readbackCalls = new AtomicInteger();

    doAnswer(
            invocation -> {
              PositiveMembershipSnapshot result =
                  (PositiveMembershipSnapshot) invocation.callRealMethod();
              if (readbackCalls.incrementAndGet() == 3) {
                throw new IllegalStateException("injected failure after role current readback");
              }
              return result;
            })
        .when(membershipAuthorityEventProducer)
        .readCurrentPairBoundPositiveMembershipSnapshot(anyLong(), anyLong());

    assertThatThrownBy(() -> tenantRoleMutationService.mutate(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("injected failure after role current readback");
    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture.admin())).isEqualTo(adminBefore);
    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture.target())).isEqualTo(targetBefore);
    assertThat(countAuditOutboxRows(fixture.admin())).isEqualTo(auditCountBefore);
    assertThat(countAuthorityMembershipEvents(fixture.target())).isEqualTo(1L);
    assertTenantRoleOperationAbsent(requestId);
  }

  @Test
  void tenantRoleMutationRejectsWrongTenantWithoutChangingAnyAccountSource() {
    TenantRoleFixture fixture = syntheticRetainedAdminFixture();
    Map<String, Object> adminBefore = membershipAuthorityReadEvidenceSnapshot(fixture.admin());
    Map<String, Object> targetBefore = membershipAuthorityReadEvidenceSnapshot(fixture.target());
    long auditCountBefore = countAuditOutboxRows(fixture.admin());
    UUID requestId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                tenantRoleMutationService.mutate(
                    new Request(
                        requestId,
                        fixture.admin().accountUuid(),
                        UUID.randomUUID(),
                        fixture.target().accountUuid(),
                        Action.GRANT_DESIGNER,
                        2L,
                        2L)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture.admin())).isEqualTo(adminBefore);
    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture.target())).isEqualTo(targetBefore);
    assertThat(countAuditOutboxRows(fixture.admin())).isEqualTo(auditCountBefore);
    assertTenantRoleOperationAbsent(requestId);
  }

  @Test
  void tenantRoleMutationRejectsNonExplicitMembershipWithoutChangingAnyAccountSource() {
    JoinFixture fixture = fixture("active");
    new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              seedSyntheticRetainedActiveMembership(
                  fixture, List.of("player", "tenantAdmin"), "SEEDED_DEMO");
              return null;
            });
    Map<String, Object> sourceBefore = membershipAuthorityReadEvidenceSnapshot(fixture);
    long auditCountBefore = countAuditOutboxRows(fixture);
    UUID requestId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                tenantRoleMutationService.mutate(
                    new Request(
                        requestId,
                        fixture.accountUuid(),
                        fixture.tenantUuid(),
                        fixture.accountUuid(),
                        Action.GRANT_DESIGNER,
                        2L,
                        2L)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture)).isEqualTo(sourceBefore);
    assertThat(countAuditOutboxRows(fixture)).isEqualTo(auditCountBefore);
    assertTenantRoleOperationAbsent(requestId);
  }

  @Test
  void invalidatingTenantRoleMutationDeniesFenceOverflowWithoutChangingAnyAccountSource() {
    JoinFixture fixture = fixture("active");
    new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              seedSyntheticRetainedActiveMembership(
                  fixture, List.of("designer", "player", "tenantAdmin"), "EXPLICIT_JOIN", true);
              return null;
            });
    PositiveMembershipSnapshot current = readExistingPairBoundPositiveMembershipSnapshot(fixture);
    assertThat(current.issuanceFence()).isEqualTo(Long.toString(Long.MAX_VALUE));
    Map<String, Object> sourceBefore = membershipAuthorityReadEvidenceSnapshot(fixture);
    long auditCountBefore = countAuditOutboxRows(fixture);
    UUID requestId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                tenantRoleMutationService.mutate(
                    new Request(
                        requestId,
                        fixture.accountUuid(),
                        fixture.tenantUuid(),
                        fixture.accountUuid(),
                        Action.REVOKE_DESIGNER,
                        2L,
                        2L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("issuance fence is exhausted");
    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture)).isEqualTo(sourceBefore);
    assertThat(countAuditOutboxRows(fixture)).isEqualTo(auditCountBefore);
    assertTenantRoleOperationAbsent(requestId);
  }

  @Test
  void concurrentOpposingTenantAdminTransfersLeaveOneCurrentAdministrator() throws Exception {
    TenantRoleFixture fixture = syntheticRetainedAdminFixture();
    Request currentAdminTransfer =
        new Request(
            UUID.randomUUID(),
            fixture.admin().accountUuid(),
            fixture.admin().tenantUuid(),
            fixture.target().accountUuid(),
            Action.TRANSFER_TENANT_ADMIN,
            2L,
            2L);
    Request nonAdminOpposition =
        new Request(
            UUID.randomUUID(),
            fixture.target().accountUuid(),
            fixture.admin().tenantUuid(),
            fixture.admin().accountUuid(),
            Action.TRANSFER_TENANT_ADMIN,
            2L,
            2L);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<OperationEvidence> transfer =
          executor.submit(
              () -> {
                start.await();
                return tenantRoleMutationService.mutate(currentAdminTransfer);
              });
      Future<OperationEvidence> opposition =
          executor.submit(
              () -> {
                start.await();
                return tenantRoleMutationService.mutate(nonAdminOpposition);
              });
      start.countDown();

      int committed = 0;
      int denied = 0;
      for (Future<OperationEvidence> result : List.of(transfer, opposition)) {
        try {
          assertThat(result.get(20, TimeUnit.SECONDS).status()).isEqualTo("COMMITTED");
          committed++;
        } catch (ExecutionException failure) {
          assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class);
          denied++;
        }
      }
      assertThat(committed).isEqualTo(1);
      assertThat(denied).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }

    PositiveMembershipSnapshot finalAdmin =
        readExistingPairBoundPositiveMembershipSnapshot(fixture.target());
    PositiveMembershipSnapshot formerAdmin =
        readExistingPairBoundPositiveMembershipSnapshot(fixture.admin());
    assertThat(finalAdmin.roles()).contains("tenantAdmin", "player");
    assertThat(formerAdmin.roles()).containsExactly("player");
    assertThat(finalAdmin.gameplayAdmissionAllowed()).isTrue();
    assertThat(finalAdmin.membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(countTenantRoleOperations(fixture.admin())).isEqualTo(1L);
  }

  @Test
  void canonicalExistingPairBoundPositiveMembershipSnapshotRepeatsWithoutMutation() {
    JoinFixture fixture = fixture("active");
    JoinPublicProductionResult joined = join(fixture);
    Map<String, Object> before = membershipAuthorityReadEvidenceSnapshot(fixture);

    var first = readExistingPairBoundPositiveMembershipSnapshot(fixture);
    var second = readExistingPairBoundPositiveMembershipSnapshot(fixture);

    assertThat(joined.success()).isTrue();
    assertThat(first.membershipExists()).isTrue();
    assertThat(first.gameplayAdmissionAllowed()).isTrue();
    assertThat(first.accountId()).isEqualTo(fixture.accountUuid().toString());
    assertThat(first.tenantId()).isEqualTo(fixture.tenantUuid().toString());
    assertThat(first.membershipVersion()).isEqualTo(second.membershipVersion());
    assertThat(first.membershipAuthorityGeneration())
        .isEqualTo(second.membershipAuthorityGeneration());
    assertThat(first.roles()).containsExactly("player");
    assertThat(second.authorityTuple()).isEqualTo(first.authorityTuple());
    assertThat(second.issuanceFence()).isEqualTo(first.issuanceFence());
    assertThat(second.outboxCheckpoints()).isEqualTo(first.outboxCheckpoints());
    assertThat(second.outboxSourceEvidence()).isEqualTo(first.outboxSourceEvidence());
    assertThat(second.transitionReceipt()).isEqualTo(first.transitionReceipt());
    assertThat(second.authorityEvent().eventId()).isEqualTo(first.authorityEvent().eventId());
    assertThat(second.authorityEvent().eventDigest())
        .isEqualTo(first.authorityEvent().eventDigest());
    assertThat(second.authorityEvent().canonicalJsonUtf8())
        .containsExactly(first.authorityEvent().canonicalJsonUtf8());
    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture)).isEqualTo(before);
  }

  @Test
  void positiveMembershipSnapshotRejectsScalarAndWrongTenantVersionMaps() {
    JoinFixture scalarVersion = fixture("active");
    assertStoredMembershipVersionFailsClosed(scalarVersion, "\"2\"");

    JoinFixture wrongTenantVersion = fixture("active");
    assertStoredMembershipVersionFailsClosed(
        wrongTenantVersion, "{\"%s\":\"2\"}".formatted(differentTenantUuid(wrongTenantVersion)));

    JoinFixture extraTenantVersion = fixture("active");
    assertStoredMembershipVersionFailsClosed(
        extraTenantVersion,
        "{\""
            + extraTenantVersion.tenantUuid()
            + "\":\"2\",\""
            + differentTenantUuid(extraTenantVersion)
            + "\":\"1\"}");
  }

  @Test
  void positiveMembershipSnapshotFailsClosedForAbsentMembershipOrReceipt() {
    JoinFixture absent = fixture("active");
    Map<String, Object> absentBeforeRead = membershipAuthorityReadEvidenceSnapshot(absent);
    assertThatThrownBy(() -> readPositiveMembershipSnapshot(absent))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Current Account membership row is absent");
    assertThatThrownBy(() -> readExistingPairBoundPositiveMembershipSnapshot(absent))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Current Account membership row is absent");
    assertThat(membershipAuthorityReadEvidenceSnapshot(absent)).isEqualTo(absentBeforeRead);

    JoinFixture missingReceipt = fixture("active");
    dsl.execute(
        "INSERT INTO account_tenant_membership "
            + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
            + "membership_version, membership_authority_generation, authority_provenance) "
            + "VALUES (?, ?, TRUE, 'ACTIVE', 1, 1, 'EXPLICIT_JOIN')",
        missingReceipt.accountId(),
        missingReceipt.tenantId());
    long retainedMembershipId = seedPlayerRoleSnapshot(missingReceipt);
    Map<String, Object> retainedMembership = membershipSnapshot(missingReceipt);

    assertThat(retainedMembershipId).isPositive();
    assertThat(countMembershipTransitionReceipts(missingReceipt)).isZero();
    assertThatThrownBy(() -> readPositiveMembershipSnapshot(missingReceipt))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Account membership exists without a provisional transition receipt");
    assertThat(membershipSnapshot(missingReceipt)).isEqualTo(retainedMembership);
    assertThat(countMembershipTransitionReceipts(missingReceipt)).isZero();
    assertThat(countAuthorityMembershipEvents(missingReceipt)).isZero();
  }

  @Test
  void positiveMembershipSnapshotFailsClosedForMissingRolesAndRetainedInactiveState() {
    JoinFixture missingRoles = fixture("active");
    JoinPublicProductionResult joined = join(missingRoles);
    dsl.execute(
        "DELETE FROM account_tenant_membership_role_snapshot_roles "
            + "WHERE membership_id = ? AND snapshot_version = ?",
        joined.membershipId(),
        joined.membershipVersion());

    assertThatThrownBy(() -> readPositiveMembershipSnapshot(missingRoles))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("role snapshot differs from its exact active membership");

    JoinFixture inactive = fixture("active");
    JoinPublicProductionResult inactiveJoin = join(inactive);
    dsl.execute(
        "UPDATE account_tenant_membership SET lifecycle_state = 'INACTIVE', "
            + "gameplay_admission_allowed = FALSE WHERE id = ?",
        inactiveJoin.membershipId());

    assertThatThrownBy(() -> readPositiveMembershipSnapshot(inactive))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a positive active explicit membership");
  }

  @Test
  void positiveMembershipSnapshotFailsClosedForMismatchedAuthorityTuple() {
    JoinFixture mismatchedTuple = fixture("active");
    assertThat(join(mismatchedTuple).success()).isTrue();
    dsl.execute(
        "UPDATE account_authority_generations SET generation = generation + 1, "
            + "source_version = source_version + 1 WHERE scope_kind = 'MEMBERSHIP' "
            + "AND account_uuid = ? AND tenant_uuid = ?",
        mismatchedTuple.accountUuid(),
        mismatchedTuple.tenantUuid());

    assertThatThrownBy(() -> readPositiveMembershipSnapshot(mismatchedTuple))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("differs from its V31 authority generation");
    Map<String, Object> mismatchedBeforeStrictRead =
        membershipAuthorityReadEvidenceSnapshot(mismatchedTuple);
    assertThatThrownBy(() -> readExistingPairBoundPositiveMembershipSnapshot(mismatchedTuple))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("differs from its V31 authority generation");
    assertThat(membershipAuthorityReadEvidenceSnapshot(mismatchedTuple))
        .isEqualTo(mismatchedBeforeStrictRead);
  }

  @Test
  void neverJoinedSnapshotRequiresAndRetainsDurablePairBaselineBeforeFirstJoin() {
    JoinFixture fixture = fixture("active");
    long originalIssuanceFence = accountIssuanceFence(fixture);

    prepareRuntimeSnapshotAuthority(fixture);
    var absence = readNeverJoinedMembershipSnapshot(fixture);
    Map<String, Object> baselineRow = membershipPairAuthorityRow(fixture);

    assertThat(absence.membershipExists()).isFalse();
    assertThat(absence.gameplayAdmissionAllowed()).isFalse();
    assertThat(absence.accountId()).isEqualTo(fixture.accountUuid().toString());
    assertThat(absence.tenantId()).isEqualTo(fixture.tenantUuid().toString());
    assertThat(absence.membershipVersion()).isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(absence.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(absence.outboxCheckpoints())
        .containsExactlyElementsOf(expectedMembershipOutboxCheckpoints(fixture, "0"));
    assertThat(absence.outboxSourceEvidence()).isEmpty();
    assertThat(absence.outboxStreamKey()).isEqualTo(authorityStreamKey(fixture));
    assertThat(absence.authorityTuple().membershipAuthorityGeneration())
        .containsEntry(fixture.tenantUuid().toString(), "1");
    assertThat(countAuthorityMembershipEvents(fixture)).isZero();
    assertThat(countAuthorityMembershipStreams(fixture)).isZero();
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countMembershipTransitionReceipts(fixture)).isZero();
    assertThat(countRoleSnapshotHeaders(fixture)).isZero();
    assertThat(countRoleSnapshotRows(fixture)).isZero();
    assertThat(countMembershipAuthorityGenerations(fixture)).isEqualTo(1L);
    assertThat(countMembershipPairAuthorities(fixture)).isEqualTo(1L);
    assertThat(authorityGeneration("MEMBERSHIP", null, fixture.accountUuid(), fixture.tenantUuid()))
        .isEqualTo(1L);
    assertThat(accountIssuanceFence(fixture)).isEqualTo(originalIssuanceFence);
    assertThat(baselineRow)
        .containsEntry("account_uuid", fixture.accountUuid())
        .containsEntry("tenant_uuid", fixture.tenantUuid())
        .containsEntry("legacy_tenant_id", fixture.tenantId())
        .containsEntry("tenant_provenance_kind", "APPROVED_RETAINED")
        .containsEntry("membership_exists", false)
        .containsEntry("membership_version", 1L)
        .containsEntry("membership_authority_generation", 1L)
        .containsEntry("last_event_sequence", 0L)
        .containsEntry("last_event_id", null)
        .containsEntry("last_event_digest", null)
        .containsEntry("last_transition_invalidated", false);

    prepareRuntimeSnapshotAuthority(fixture);

    assertThat(membershipPairAuthorityRow(fixture)).isEqualTo(baselineRow);
    var retriedAbsence = readNeverJoinedMembershipSnapshot(fixture);
    assertThat(retriedAbsence.membershipVersion())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(retriedAbsence.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(retriedAbsence.outboxCheckpoints())
        .containsExactlyElementsOf(expectedMembershipOutboxCheckpoints(fixture, "0"));
    assertThat(retriedAbsence.outboxSourceEvidence()).isEmpty();
    assertThat(countAuthorityMembershipEvents(fixture)).isZero();
    assertThat(countAuthorityMembershipStreams(fixture)).isZero();
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countMembershipTransitionReceipts(fixture)).isZero();
    assertThat(countRoleSnapshotHeaders(fixture)).isZero();
    assertThat(countRoleSnapshotRows(fixture)).isZero();
    assertThat(countMembershipAuthorityGenerations(fixture)).isEqualTo(1L);
    assertThat(authorityGeneration("MEMBERSHIP", null, fixture.accountUuid(), fixture.tenantUuid()))
        .isEqualTo(1L);
    assertThat(accountIssuanceFence(fixture)).isEqualTo(originalIssuanceFence);

    JoinPublicProductionResult joined = join(fixture);
    var positive = readPairBoundPositiveMembershipSnapshot(fixture);
    Map<String, Object> joinedPairRow = membershipPairAuthorityRow(fixture);

    assertThat(joined.success()).isTrue();
    assertThat(joined.membershipVersion()).isEqualTo(2L);
    assertThat(joined.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(positive.membershipExists()).isTrue();
    assertThat(positive.gameplayAdmissionAllowed()).isTrue();
    assertThat(positive.membershipVersion())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "2"));
    assertThat(positive.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(positive.authorityEvent().outboxSequence()).isEqualTo("1");
    assertThat(positive.authorityEvent().eventId()).isNotBlank();
    assertThat(positive.authorityEvent().eventDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(positive.outboxCheckpoints())
        .containsExactlyElementsOf(expectedMembershipOutboxCheckpoints(fixture, "1"));
    assertThat(positive.outboxSourceEvidence())
        .containsExactly(
            new OutboxSourceEvidence(
                authorityStreamKey(fixture),
                "1",
                positive.authorityEvent().eventId(),
                positive.authorityEvent().eventDigest(),
                positive.authorityEvent().canonicalJson()));
    assertThat(joinedPairRow)
        .containsEntry("membership_exists", true)
        .containsEntry("membership_version", 2L)
        .containsEntry("membership_authority_generation", 1L)
        .containsEntry("last_event_sequence", 1L)
        .containsEntry("last_event_id", positive.authorityEvent().eventId())
        .containsEntry("last_event_digest", positive.authorityEvent().eventDigest())
        .containsEntry("last_transition_invalidated", false);
    assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(1L);

    long receiptCount = countMembershipTransitionReceipts(fixture);
    long eventCount = countAuthorityMembershipEvents(fixture);
    long eventStreamCount = countAuthorityMembershipStreams(fixture);
    long membershipGeneration =
        authorityGeneration("MEMBERSHIP", null, fixture.accountUuid(), fixture.tenantUuid());
    long issuanceFence = accountIssuanceFence(fixture);
    prepareRuntimeSnapshotAuthority(fixture);
    assertThat(membershipPairAuthorityRow(fixture)).isEqualTo(joinedPairRow);
    assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(receiptCount);
    assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(eventCount);
    assertThat(countAuthorityMembershipStreams(fixture)).isEqualTo(eventStreamCount);
    assertThat(authorityGeneration("MEMBERSHIP", null, fixture.accountUuid(), fixture.tenantUuid()))
        .isEqualTo(membershipGeneration);
    assertThat(accountIssuanceFence(fixture)).isEqualTo(issuanceFence);
    var priorEvent = positive.authorityEvent();
    var readbackEvent = readPairBoundPositiveMembershipSnapshot(fixture).authorityEvent();
    assertThat(readbackEvent.eventId()).isEqualTo(priorEvent.eventId());
    assertThat(readbackEvent.eventDigest()).isEqualTo(priorEvent.eventDigest());
    assertThat(readbackEvent.canonicalJson().getBytes(StandardCharsets.UTF_8))
        .containsExactly(priorEvent.canonicalJson().getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void runtimeMembershipSnapshotKeepsAbsenceAndFirstJoinEvidenceInOneFencedResult() {
    JoinFixture fixture = fixture("active");

    var absent = readRuntimeMembershipSnapshot(fixture);
    Map<String, Object> baselineRow = membershipPairAuthorityRow(fixture);
    assertThat(absent.requestAccountUuid()).isEqualTo(fixture.accountUuid().toString());
    assertThat(absent.requestTenantUuid()).isEqualTo(fixture.tenantUuid().toString());
    assertThat(absent.accountUuid()).isEqualTo(fixture.accountUuid().toString());
    assertThat(absent.tenantUuid()).isEqualTo(fixture.tenantUuid().toString());
    assertThat(absent.membershipExists()).isFalse();
    assertThat(absent.gameplayAdmissionAllowed()).isFalse();
    assertThat(absent.membershipBaseline().membershipLifecycleState()).isEqualTo("MISSING");
    assertThat(absent.membershipBaseline().membershipVersion())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(absent.membershipBaseline().membershipAuthorityGeneration())
        .isEqualTo(
            absent
                .authorityTuple()
                .membershipAuthorityGeneration()
                .get(fixture.tenantUuid().toString()));
    assertThat(absent.outboxCheckpoints())
        .containsExactlyElementsOf(expectedMembershipOutboxCheckpoints(fixture, "0"));
    assertThat(absent.outboxSourceEvidence()).isEmpty();
    assertThat(absent.roles()).isEmpty();
    assertThat(absent.issuanceFence()).matches("[1-9][0-9]*");
    assertThat(baselineRow)
        .containsEntry("membership_exists", false)
        .containsEntry("membership_version", 1L)
        .containsEntry("membership_authority_generation", 1L)
        .containsEntry("last_event_sequence", 0L)
        .containsEntry("last_event_id", null)
        .containsEntry("last_event_digest", null);
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countAuthorityMembershipEvents(fixture)).isZero();
    assertThat(countAuthorityMembershipStreams(fixture)).isZero();
    assertThat(countMembershipTransitionReceipts(fixture)).isZero();
    assertThat(countMembershipAuthorityGenerations(fixture)).isEqualTo(1L);
    assertThat(countMembershipPairAuthorities(fixture)).isEqualTo(1L);

    var retriedAbsence = readRuntimeMembershipSnapshot(fixture);
    assertThat(retriedAbsence.membershipExists()).isFalse();
    assertThat(retriedAbsence.membershipBaseline()).isEqualTo(absent.membershipBaseline());
    assertThat(retriedAbsence.authorityTuple()).isEqualTo(absent.authorityTuple());
    assertThat(retriedAbsence.outboxCheckpoints()).isEqualTo(absent.outboxCheckpoints());
    assertThat(retriedAbsence.outboxSourceEvidence()).isEmpty();
    assertThat(membershipPairAuthorityRow(fixture)).isEqualTo(baselineRow);
    assertThat(countAuthorityMembershipEvents(fixture)).isZero();
    assertThat(countAuthorityMembershipStreams(fixture)).isZero();
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countMembershipTransitionReceipts(fixture)).isZero();

    JoinPublicProductionResult joined = join(fixture);
    assertThat(joined.success()).isTrue();
    assertThat(joined.membershipVersion()).isEqualTo(2L);
    assertThat(joined.membershipAuthorityGeneration()).isEqualTo(1L);
    var active = readRuntimeMembershipSnapshot(fixture);
    var event = readPairBoundPositiveMembershipSnapshot(fixture).authorityEvent();
    assertThat(active.membershipExists()).isTrue();
    assertThat(active.gameplayAdmissionAllowed()).isTrue();
    assertThat(active.membershipBaseline().membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(active.membershipBaseline().membershipVersion())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "2"));
    assertThat(active.membershipBaseline().membershipAuthorityGeneration())
        .isEqualTo(
            active
                .authorityTuple()
                .membershipAuthorityGeneration()
                .get(fixture.tenantUuid().toString()));
    assertThat(active.outboxCheckpoints())
        .containsExactlyElementsOf(expectedMembershipOutboxCheckpoints(fixture, "1"));
    assertThat(active.outboxSourceEvidence())
        .containsExactly(
            new OutboxSourceEvidence(
                authorityStreamKey(fixture),
                "1",
                event.eventId(),
                event.eventDigest(),
                event.canonicalJson()));
    assertThat(active.roles()).contains("player");
    assertThat(active.issuanceFence()).matches("[1-9][0-9]*");
    assertThat(active.outboxSourceEvidence()).hasSize(1);
  }

  @Test
  void runtimeMembershipSnapshotRejectsContradictoryGeneration() {
    JoinFixture contradictoryPair = fixture("active");
    preparePairAuthorityBaseline(contradictoryPair);
    dsl.execute(
        "UPDATE account_authority_generations SET generation = generation + 1, "
            + "source_version = source_version + 1 WHERE scope_kind = 'MEMBERSHIP' "
            + "AND account_uuid = ? AND tenant_uuid = ?",
        contradictoryPair.accountUuid(),
        contradictoryPair.tenantUuid());
    assertThatThrownBy(() -> readRuntimeMembershipSnapshot(contradictoryPair))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "Absent Account membership differs from its durable pair authority baseline");
    Map<String, Object> beforeStrictRead =
        membershipAuthorityReadEvidenceSnapshot(contradictoryPair);
    assertThatThrownBy(() -> readExistingPairBoundPositiveMembershipSnapshot(contradictoryPair))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Current Account membership row is absent");
    assertThat(membershipAuthorityReadEvidenceSnapshot(contradictoryPair))
        .isEqualTo(beforeStrictRead);
  }

  @Test
  void concurrentRuntimeSnapshotPreparationAndFirstJoinPreservePairOrdering() throws Exception {
    JoinFixture fixture = fixture("active");
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countMembershipAuthorityGenerations(fixture)).isZero();
    assertThat(countMembershipPairAuthorities(fixture)).isZero();
    assertThat(countAuthorityMembershipEvents(fixture)).isZero();

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(3);
    try {
      Future<?> firstPreparation =
          executor.submit(
              () -> {
                await(start);
                prepareRuntimeSnapshotAuthority(fixture);
              });
      Future<?> secondPreparation =
          executor.submit(
              () -> {
                await(start);
                prepareRuntimeSnapshotAuthority(fixture);
              });
      Future<JoinPublicProductionResult> joinAttempt =
          executor.submit(
              () -> {
                await(start);
                return join(fixture);
              });
      start.countDown();

      firstPreparation.get(30, TimeUnit.SECONDS);
      secondPreparation.get(30, TimeUnit.SECONDS);
      JoinPublicProductionResult joined = joinAttempt.get(30, TimeUnit.SECONDS);

      assertThat(joined.success()).isTrue();
      assertThat(joined.outcomeCode()).isEqualTo("JOINED");
      assertThat(countMemberships(fixture)).isEqualTo(1L);
      assertThat(countMembershipAuthorityGenerations(fixture)).isEqualTo(1L);
      assertThat(countMembershipPairAuthorities(fixture)).isEqualTo(1L);
      assertThat(membershipPairAuthorityRow(fixture))
          .containsEntry("membership_exists", true)
          .containsEntry("membership_version", 2L)
          .containsEntry("membership_authority_generation", 1L)
          .containsEntry("last_event_sequence", 1L);
      assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(1L);
      assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(1L);
      assertThat(countAuthorityMembershipStreams(fixture)).isEqualTo(1L);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentFirstJoinCannotMixWithCompleteFencedRuntimeMembershipSnapshot() throws Exception {
    JoinFixture fixture = fixture("active");
    CountDownLatch snapshotCaptured = new CountDownLatch(1);
    CountDownLatch releaseSnapshotCommit = new CountDownLatch(1);
    CountDownLatch joinReachedIntentInsert = new CountDownLatch(1);
    AtomicReference<RuntimeMembershipSnapshotDto> capturedSnapshot = new AtomicReference<>();
    AtomicReference<Thread> joinThread = new AtomicReference<>();
    AtomicReference<Integer> snapshotBackendPid = new AtomicReference<>();
    AtomicReference<Integer> joinBackendPid = new AtomicReference<>();
    // JOIN first inserts its PENDING intent; the account_id FK can block on this row lock before
    // JOIN reaches its later explicit lockAccount call.
    doAnswer(
            invocation -> {
              if (Thread.currentThread() == joinThread.get()) {
                joinBackendPid.set(
                    dsl.resultQuery("SELECT pg_backend_pid()").fetchOne(0, Integer.class));
                joinReachedIntentInsert.countDown();
              }
              return invocation.callRealMethod();
            })
        .when(joinOperationRepository)
        .insertIntent(anyString(), any(), anyString(), anyString());

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> snapshotAttempt =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          status -> {
                            capturedSnapshot.set(
                                membershipAuthorityEventProducer.readRuntimeMembershipSnapshot(
                                    fixture.accountUuid(), fixture.tenantUuid()));
                            snapshotBackendPid.set(
                                dsl.resultQuery("SELECT pg_backend_pid()")
                                    .fetchOne(0, Integer.class));
                            snapshotCaptured.countDown();
                            try {
                              if (!releaseSnapshotCommit.await(45, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                    "runtime snapshot transaction was not released before its bounded wait expired");
                              }
                            } catch (InterruptedException ex) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(
                                  "runtime snapshot transaction was interrupted before commit", ex);
                            }
                          }));

      if (!snapshotCaptured.await(10, TimeUnit.SECONDS)) {
        if (snapshotAttempt.isDone()) {
          try {
            snapshotAttempt.get();
          } catch (ExecutionException workerFailure) {
            throw new AssertionError(
                "runtime snapshot worker failed before capturing its result",
                workerFailure.getCause());
          }
        }
        throw new AssertionError("runtime snapshot worker did not capture its result in time");
      }
      Future<JoinPublicProductionResult> joinAttempt =
          executor.submit(
              () -> {
                joinThread.set(Thread.currentThread());
                return join(fixture);
              });

      if (!joinReachedIntentInsert.await(10, TimeUnit.SECONDS)) {
        if (joinAttempt.isDone()) {
          try {
            JoinPublicProductionResult earlyResult = joinAttempt.get();
            throw new AssertionError(
                "JOIN completed before its intent-insert instrumentation: "
                    + earlyResult.outcomeCode());
          } catch (ExecutionException workerFailure) {
            throw new AssertionError(
                "JOIN worker failed before its intent-insert instrumentation",
                workerFailure.getCause());
          }
        }
        throw new AssertionError("JOIN did not reach its intent insert in time");
      }
      awaitAccountFenceLockWait(snapshotBackendPid.get(), joinBackendPid.get());
      assertThat(joinAttempt.isDone())
          .as("first JOIN must remain blocked until the snapshot owner transaction commits")
          .isFalse();

      RuntimeMembershipSnapshotDto absent = capturedSnapshot.get();
      assertThat(absent).isNotNull();
      assertThat(absent.requestAccountUuid()).isEqualTo(fixture.accountUuid().toString());
      assertThat(absent.requestTenantUuid()).isEqualTo(fixture.tenantUuid().toString());
      assertThat(absent.accountUuid()).isEqualTo(fixture.accountUuid().toString());
      assertThat(absent.tenantUuid()).isEqualTo(fixture.tenantUuid().toString());
      assertThat(absent.membershipExists()).isFalse();
      assertThat(absent.gameplayAdmissionAllowed()).isFalse();
      assertThat(absent.membershipBaseline().membershipLifecycleState()).isEqualTo("MISSING");
      assertThat(absent.membershipBaseline().membershipVersion())
          .isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
      assertThat(absent.membershipBaseline().membershipAuthorityGeneration()).isEqualTo("1");
      assertThat(absent.authorityTuple().membershipAuthorityGeneration())
          .isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
      assertThat(absent.issuanceFence()).matches("[1-9][0-9]*");
      assertThat(absent.outboxCheckpoints())
          .containsExactlyElementsOf(expectedMembershipOutboxCheckpoints(fixture, "0"));
      assertThat(absent.outboxSourceEvidence()).isEmpty();
      assertThat(absent.sourceEvent()).isNull();
      assertThat(absent.roles()).isEmpty();

      releaseSnapshotCommit.countDown();
      snapshotAttempt.get(30, TimeUnit.SECONDS);
      JoinPublicProductionResult joined = joinAttempt.get(30, TimeUnit.SECONDS);

      assertThat(joined.success()).isTrue();
      assertThat(joined.outcomeCode()).isEqualTo("JOINED");
      assertThat(joined.membershipVersion()).isEqualTo(2L);
      assertThat(joined.membershipAuthorityGeneration()).isEqualTo(1L);
      assertAuthorityMembershipEvent(fixture, 1L, 2L, false);

      RuntimeMembershipSnapshotDto active = readRuntimeMembershipSnapshot(fixture);
      var eventRow = authorityMembershipEventRow(fixture, 1L);
      String canonicalEventJson =
          new String(eventRow.get("payload", byte[].class), StandardCharsets.UTF_8);
      MembershipAuthorityEventV1Codec.MembershipEvent event =
          MembershipAuthorityEventV1Codec.verify(canonicalEventJson);
      assertThat(active.membershipExists()).isTrue();
      assertThat(active.gameplayAdmissionAllowed()).isTrue();
      assertThat(active.membershipBaseline().membershipLifecycleState()).isEqualTo("ACTIVE");
      assertThat(active.membershipBaseline().membershipVersion())
          .isEqualTo(Map.of(fixture.tenantUuid().toString(), "2"));
      assertThat(active.membershipBaseline().membershipAuthorityGeneration()).isEqualTo("1");
      assertThat(active.membershipBaseline().membershipVersion())
          .isEqualTo(event.membershipVersion());
      assertThat(active.membershipBaseline().membershipAuthorityGeneration())
          .isEqualTo(event.membershipAuthorityGeneration());
      assertThat(active.authorityTuple()).isEqualTo(event.authorityTuple());
      assertThat(active.authorityTuple()).isEqualTo(absent.authorityTuple());
      assertThat(active.issuanceFence()).isEqualTo(event.issuanceFence());
      assertThat(active.issuanceFence()).isEqualTo(absent.issuanceFence());
      assertThat(active.sourceEvent().eventId()).isEqualTo(event.eventId());
      assertThat(active.sourceEvent().eventDigest()).isEqualTo(event.eventDigest());
      assertThat(active.sourceEvent().canonicalJson().getBytes(StandardCharsets.UTF_8))
          .containsExactly(event.canonicalJson().getBytes(StandardCharsets.UTF_8));
      assertThat(active.outboxCheckpoints())
          .containsExactlyElementsOf(expectedMembershipOutboxCheckpoints(fixture, "1"));
      assertThat(active.outboxSourceEvidence())
          .containsExactly(
              new OutboxSourceEvidence(
                  authorityStreamKey(fixture),
                  "1",
                  event.eventId(),
                  event.eventDigest(),
                  event.canonicalJson()));
      assertThat(active.roles()).containsExactlyElementsOf(event.roles());
      assertThat(countMemberships(fixture)).isEqualTo(1L);
      assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(1L);
      assertThat(countAuthorityMembershipStreams(fixture)).isEqualTo(1L);
    } finally {
      releaseSnapshotCommit.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void neverJoinedSnapshotReaderRejectsMissingPairWithoutEnrollingIt() {
    JoinFixture missingPair = fixture("active");
    assertThatThrownBy(() -> readNeverJoinedMembershipSnapshot(missingPair))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Never-joined pair baseline is absent");
    assertThat(countMembershipPairAuthorities(missingPair)).isZero();
    assertThat(countMembershipAuthorityGenerations(missingPair)).isZero();
    assertThat(countMemberships(missingPair)).isZero();
    assertThat(countAuthorityMembershipEvents(missingPair)).isZero();
  }

  @Test
  void runtimeMembershipSnapshotRejectsUnmappedTenantAndAbsentMembershipHistory() {
    JoinFixture unmappedTenant = fixture("active", TenantAssociationSetup.MISSING);
    assertThatThrownBy(() -> readRuntimeMembershipSnapshot(unmappedTenant))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("approved Account tenant association is absent");
    assertThat(countMemberships(unmappedTenant)).isZero();
    assertThat(countMembershipAuthorityGenerations(unmappedTenant)).isZero();
    assertThat(countMembershipPairAuthorities(unmappedTenant)).isZero();
    assertThat(countAuthorityMembershipEvents(unmappedTenant)).isZero();

    JoinFixture removedMembership = fixture("active");
    JoinPublicProductionResult joined = join(removedMembership);
    Map<String, Object> retainedPair = membershipPairAuthorityRow(removedMembership);
    dsl.execute(
        "DELETE FROM account_join_operations WHERE request_id = ?", removedMembership.requestId());
    dsl.execute(
        "DELETE FROM account_tenant_membership_role_snapshot_roles " + "WHERE membership_id = ?",
        joined.membershipId());
    dsl.execute(
        "DELETE FROM account_tenant_membership_role_snapshots WHERE membership_id = ?",
        joined.membershipId());
    dsl.execute("DELETE FROM account_tenant_membership WHERE id = ?", joined.membershipId());

    assertThat(joined.success()).isTrue();
    assertThat(countMemberships(removedMembership)).isZero();
    assertThat(countAuthorityMembershipEvents(removedMembership)).isEqualTo(1L);
    assertThatThrownBy(() -> readRuntimeMembershipSnapshot(removedMembership))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "Retained Account membership history has no supported restoration path");
    assertThat(membershipPairAuthorityRow(removedMembership)).isEqualTo(retainedPair);
    assertThat(countMembershipAuthorityGenerations(removedMembership)).isEqualTo(1L);
    assertThat(countAuthorityMembershipEvents(removedMembership)).isEqualTo(1L);
  }

  @Test
  void runtimeMembershipSnapshotRejectsInactiveMembershipWithoutChangingAuthority() {
    JoinFixture fixture = fixture("active");
    JoinPublicProductionResult joined = join(fixture);
    Map<String, Object> pairRow = membershipPairAuthorityRow(fixture);
    long membershipGeneration =
        authorityGeneration("MEMBERSHIP", null, fixture.accountUuid(), fixture.tenantUuid());
    dsl.execute(
        "UPDATE account_tenant_membership SET lifecycle_state = 'INACTIVE', "
            + "gameplay_admission_allowed = FALSE WHERE id = ?",
        joined.membershipId());
    Map<String, Object> inactiveBeforeStrictRead = membershipAuthorityReadEvidenceSnapshot(fixture);

    assertThatThrownBy(() -> readRuntimeMembershipSnapshot(fixture))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not match its latest provisional receipt");
    assertThatThrownBy(() -> readExistingPairBoundPositiveMembershipSnapshot(fixture))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a positive active explicit membership");
    assertThat(membershipAuthorityReadEvidenceSnapshot(fixture))
        .isEqualTo(inactiveBeforeStrictRead);
    assertThat(membershipPairAuthorityRow(fixture)).isEqualTo(pairRow);
    assertThat(authorityGeneration("MEMBERSHIP", null, fixture.accountUuid(), fixture.tenantUuid()))
        .isEqualTo(membershipGeneration);
    assertThat(countAuthorityMembershipEvents(fixture)).isEqualTo(1L);
    assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(1L);
  }

  @Test
  void neverJoinedSnapshotRejectsAdvancedMembershipGenerationWithoutSourceEvent() {
    JoinFixture advancedMembership = fixture("active");
    preparePairAuthorityBaseline(advancedMembership);
    dsl.execute(
        "UPDATE account_authority_generations SET generation = generation + 1, "
            + "source_version = source_version + 1 WHERE scope_kind = 'MEMBERSHIP' "
            + "AND account_uuid = ? AND tenant_uuid = ?",
        advancedMembership.accountUuid(),
        advancedMembership.tenantUuid());

    assertThat(
            countAuthorityStreamEvents(
                MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
                    + "membership/"
                    + advancedMembership.accountUuid()
                    + "/"
                    + advancedMembership.tenantUuid()))
        .isZero();
    assertThatThrownBy(() -> readNeverJoinedMembershipSnapshot(advancedMembership))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "membership authority generation cannot prove its sequence-zero baseline");
  }

  @Test
  void neverJoinedSnapshotRejectsAdvancedUpstreamGenerationWithoutSourceEvent() {
    JoinFixture advancedTenant = fixture("active");
    preparePairAuthorityBaseline(advancedTenant);
    dsl.execute(
        "UPDATE account_authority_generations SET generation = generation + 1, "
            + "source_version = source_version + 1 WHERE scope_kind = 'TENANT' "
            + "AND tenant_uuid = ?",
        advancedTenant.tenantUuid());

    assertThat(
            countAuthorityStreamEvents(
                MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
                    + "tenant/"
                    + advancedTenant.tenantUuid()))
        .isZero();
    assertThatThrownBy(() -> readNeverJoinedMembershipSnapshot(advancedTenant))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tenant source history is missing");
  }

  private AccountMembershipAuthorityEventProducer.PositiveMembershipSnapshot
      readPositiveMembershipSnapshot(JoinFixture fixture) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status ->
                membershipAuthorityEventProducer.readCurrentPositiveMembershipSnapshot(
                    fixture.accountId(), fixture.tenantId()));
  }

  private void preparePairAuthorityBaseline(JoinFixture fixture) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                membershipAuthorityEventProducer.preparePairAuthorityForJoin(
                    fixture.accountId(), fixture.tenantId()));
  }

  private void prepareRuntimeSnapshotAuthority(JoinFixture fixture) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                membershipAuthorityEventProducer.preparePairAuthorityForRuntimeSnapshot(
                    fixture.accountId(), fixture.tenantId()));
  }

  private AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot
      readNeverJoinedMembershipSnapshot(JoinFixture fixture) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status ->
                membershipAuthorityEventProducer.readNeverJoinedMembershipSnapshot(
                    fixture.accountId(), fixture.tenantId()));
  }

  private net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto
      readRuntimeMembershipSnapshot(JoinFixture fixture) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status ->
                membershipAuthorityEventProducer.readRuntimeMembershipSnapshot(
                    fixture.accountUuid(), fixture.tenantUuid()));
  }

  private AccountMembershipAuthorityEventProducer.PositiveMembershipSnapshot
      readPairBoundPositiveMembershipSnapshot(JoinFixture fixture) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status ->
                membershipAuthorityEventProducer.readCurrentPairBoundPositiveMembershipSnapshot(
                    fixture.accountId(), fixture.tenantId()));
  }

  private AccountMembershipAuthorityEventProducer.PositiveMembershipSnapshot
      readExistingPairBoundPositiveMembershipSnapshot(JoinFixture fixture) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status ->
                membershipAuthorityEventProducer.readExistingPairBoundPositiveMembershipSnapshot(
                    fixture.accountUuid(), fixture.tenantUuid()));
  }

  private Map<String, Object> membershipAuthorityReadEvidenceSnapshot(JoinFixture fixture) {
    String streamKey = authorityStreamKey(fixture);
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put(
        "account",
        dsl.fetch(
                "SELECT xmin::text AS row_xmin, a.* FROM accounts a WHERE a.id = ?",
                fixture.accountId())
            .intoMaps());
    snapshot.put(
        "membership",
        dsl.fetch(
                "SELECT xmin::text AS row_xmin, m.* FROM account_tenant_membership m "
                    + "WHERE m.account_id = ? AND m.tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .intoMaps());
    snapshot.put(
        "pairAuthority",
        dsl.fetch(
                "SELECT xmin::text AS row_xmin, p.* FROM account_membership_pair_authority p "
                    + "WHERE p.account_uuid = ? AND p.tenant_uuid = ?",
                fixture.accountUuid(),
                fixture.tenantUuid())
            .intoMaps());
    snapshot.put(
        "roleSnapshots",
        dsl.fetch(
                "SELECT xmin::text AS row_xmin, s.* "
                    + "FROM account_tenant_membership_role_snapshots s "
                    + "WHERE s.membership_id = (SELECT id FROM account_tenant_membership "
                    + "WHERE account_id = ? AND tenant_id = ?)",
                fixture.accountId(),
                fixture.tenantId())
            .intoMaps());
    snapshot.put(
        "roleSnapshotRoles",
        dsl.fetch(
                "SELECT xmin::text AS row_xmin, r.* "
                    + "FROM account_tenant_membership_role_snapshot_roles r "
                    + "WHERE r.membership_id = (SELECT id FROM account_tenant_membership "
                    + "WHERE account_id = ? AND tenant_id = ?)",
                fixture.accountId(),
                fixture.tenantId())
            .intoMaps());
    snapshot.put(
        "transitionReceipts",
        dsl.fetch(
                "SELECT xmin::text AS row_xmin, r.* "
                    + "FROM account_membership_transition_receipts r "
                    + "WHERE r.account_id = ? AND r.tenant_id = ? ORDER BY r.receipt_sequence",
                fixture.accountId(),
                fixture.tenantId())
            .intoMaps());
    snapshot.put(
        "transitionReceiptStreamHead",
        dsl.fetch(
                "SELECT xmin::text AS row_xmin, h.* "
                    + "FROM account_membership_transition_receipt_stream_heads h "
                    + "WHERE h.account_id = ? AND h.tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .intoMaps());
    snapshot.put(
        "authorityGenerations",
        dsl.fetch(
                "SELECT xmin::text AS row_xmin, g.* FROM account_authority_generations g "
                    + "WHERE (g.scope_kind = 'ACCOUNT' AND g.account_uuid = ?) "
                    + "OR (g.scope_kind = 'TENANT' AND g.tenant_uuid = ?) "
                    + "OR (g.scope_kind = 'MEMBERSHIP' AND g.account_uuid = ? "
                    + "AND g.tenant_uuid = ?) ORDER BY g.scope_kind",
                fixture.accountUuid(),
                fixture.tenantUuid(),
                fixture.accountUuid(),
                fixture.tenantUuid())
            .intoMaps());
    snapshot.put(
        "issuanceFence",
        dsl.fetch(
                "SELECT xmin::text AS row_xmin, f.* "
                    + "FROM account_authority_issuance_fences f WHERE f.account_uuid = ?",
                fixture.accountUuid())
            .intoMaps());
    snapshot.put(
        "authorityOutboxStream",
        dsl.fetch(
                "SELECT xmin::text AS row_xmin, s.* FROM account_authority_outbox_streams s "
                    + "WHERE s.outbox_stream_key = ?",
                streamKey)
            .intoMaps());
    snapshot.put(
        "authorityOutboxEvents",
        dsl.fetch(
                "SELECT xmin::text AS row_xmin, e.outbox_stream_key, e.outbox_sequence, "
                    + "e.request_id, e.event_id, e.event_digest, encode(e.payload, 'hex') "
                    + "AS payload_hex, e.created_at FROM account_authority_outbox_events e "
                    + "WHERE e.outbox_stream_key = ? ORDER BY e.outbox_sequence",
                streamKey)
            .intoMaps());
    snapshot.put(
        "joinOperation",
        dsl.fetch(
                "SELECT xmin::text AS row_xmin, o.* FROM account_join_operations o "
                    + "WHERE o.request_id = ?",
                fixture.requestId())
            .intoMaps());
    return Map.copyOf(snapshot);
  }

  private Map<String, Object> membershipPairAuthorityRow(JoinFixture fixture) {
    var row =
        dsl.resultQuery(
                "SELECT * FROM account_membership_pair_authority "
                    + "WHERE account_uuid = ? AND tenant_uuid = ?",
                fixture.accountUuid(),
                fixture.tenantUuid())
            .fetchOne();
    assertThat(row).isNotNull();
    return row.intoMap();
  }

  private long countMembershipPairAuthorities(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_membership_pair_authority "
                    + "WHERE account_uuid = ? AND tenant_uuid = ?",
                fixture.accountUuid(),
                fixture.tenantUuid())
            .fetchOne(0, Long.class));
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

  private JoinFixture fixtureForSecondAccountInTenant(JoinFixture existing) {
    String suffix = UUID.randomUUID().toString();
    String requestId = "join-proof-" + suffix;
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
    dsl.execute(
        "INSERT INTO subscription (account_id, tenant_id, plan_id, status, entitlement_version) "
            + "VALUES (?, ?, 'join-proof', 'active', 1)",
        accountId,
        existing.tenantId());
    DirectTextCallerContext caller =
        new DirectTextCallerContext(
            accountId,
            existing.tenantId(),
            REALM_ID,
            NAMESPACE_ID,
            "SHARED",
            GAME_INSTANCE_ID,
            "join-session-" + suffix,
            requestId);
    DirectTextJoinTarget target =
        new DirectTextJoinTarget(
            existing.tenantId(),
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
        accountId,
        accountUuid,
        existing.tenantId(),
        existing.tenantUuid(),
        requestId,
        caller,
        scope.connectScopeId());
  }

  /**
   * Creates a test-only historical retained current source, not an authorization or bootstrap
   * producer proof. It preserves the ordinary JOIN writer and event history; the test installs a
   * coherent preexisting V1 receipt, V1-sealed membership event, role source, generation, and pair.
   */
  private TenantRoleFixture syntheticRetainedAdminFixture() {
    JoinFixture admin = fixture("active");
    JoinFixture target = fixtureForSecondAccountInTenant(admin);
    new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              seedSyntheticRetainedActiveMembership(admin, List.of("player", "tenantAdmin"));
              seedSyntheticRetainedActiveMembership(target, List.of("player"));
              PositiveMembershipSnapshot adminSnapshot =
                  membershipAuthorityEventProducer.readExistingPairBoundPositiveMembershipSnapshot(
                      admin.accountUuid(), admin.tenantUuid());
              PositiveMembershipSnapshot targetSnapshot =
                  membershipAuthorityEventProducer.readExistingPairBoundPositiveMembershipSnapshot(
                      target.accountUuid(), target.tenantUuid());
              assertThat(adminSnapshot.roles()).containsExactly("player", "tenantAdmin");
              assertThat(targetSnapshot.roles()).containsExactly("player");
              return null;
            });
    return new TenantRoleFixture(admin, target);
  }

  private void seedSyntheticRetainedActiveMembership(JoinFixture fixture, List<String> roles) {
    seedSyntheticRetainedActiveMembership(fixture, roles, "EXPLICIT_JOIN");
  }

  private void seedSyntheticRetainedActiveMembership(
      JoinFixture fixture, List<String> roles, String authorityProvenance) {
    seedSyntheticRetainedActiveMembership(fixture, roles, authorityProvenance, false);
  }

  private void seedSyntheticRetainedActiveMembership(
      JoinFixture fixture,
      List<String> roles,
      String authorityProvenance,
      boolean exhaustedIssuanceFence) {
    Account account =
        accountRepository.findByAccountUuidForUpdate(fixture.accountUuid()).orElseThrow();
    ApprovedAssociation association = tenantIdentityResolver.resolve(fixture.tenantUuid());
    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            association.legacyTenantId(),
            TenantProvenanceKind.APPROVED_RETAINED,
            association.operationId(),
            association.manifestDigest());

    authorityGenerationRepository.initializeIssuerIfAbsent(AccountServiceImpl.ACCOUNT_JWT_ISSUER);
    authorityGenerationRepository.initialize(
        AuthorityScope.membership(fixture.accountUuid(), fixture.tenantUuid()));

    AccountTenantMembership membership = new AccountTenantMembership();
    membership.setAccount(account);
    membership.setTenantId(association.legacyTenantId());
    membership.setTenantUuid(fixture.tenantUuid());
    membership.setTenantProvenanceKind(TenantProvenanceKind.APPROVED_RETAINED.name());
    membership.setTenantSourceOperationId(association.operationId());
    membership.setTenantProvenanceDigest(association.manifestDigest());
    membership.setGameplayAdmissionAllowed(true);
    membership.setLifecycleState("ACTIVE");
    membership.setMembershipVersion(2L);
    membership.setMembershipAuthorityGeneration(1L);
    membership.setAuthorityProvenance(authorityProvenance);
    AccountTenantMembership persisted =
        membershipRepository.saveCanonical(
            membership, fixture.accountUuid(), fixture.tenantUuid(), provenance);
    RoleSnapshot roleSnapshot =
        roleSnapshotRepository.replaceCanonical(
            persisted, fixture.accountUuid(), fixture.tenantUuid(), provenance, 2L, roles);

    String historicalRequestId = "synthetic-retained-membership-join-" + UUID.randomUUID();
    MembershipTransitionReceipt receipt =
        membershipTransitionReceiptRepository.appendTransition(
            persisted, "MEMBERSHIP_JOINED", historicalRequestId);
    assertThat(receipt.requestId()).isEqualTo(historicalRequestId);

    if (exhaustedIssuanceFence) {
      assertThat(
              dsl.execute(
                  "UPDATE account_authority_issuance_fences SET issuance_fence = ? "
                      + "WHERE account_uuid = ?",
                  Long.MAX_VALUE,
                  fixture.accountUuid()))
          .isEqualTo(1);
    }

    var authority =
        authorityGenerationRepository.readCompositeSnapshot(
            AccountServiceImpl.ACCOUNT_JWT_ISSUER,
            fixture.accountUuid(),
            List.of(fixture.tenantUuid()),
            List.of(fixture.tenantUuid()));
    String streamKey = authorityStreamKey(fixture);
    UUID eventUuid =
        UUID.nameUUIDFromBytes(
            (MembershipAuthorityEventV1Codec.SCHEMA_VERSION + ":" + historicalRequestId)
                .getBytes(StandardCharsets.UTF_8));
    MembershipEvent event =
        MembershipAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry("eventId", eventUuid.toString()),
                Map.entry("requestId", historicalRequestId),
                Map.entry("outboxStreamKey", streamKey),
                Map.entry("outboxSequence", "1"),
                Map.entry(
                    "sourceScope",
                    streamKey.substring(
                        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX.length())),
                Map.entry("accountId", fixture.accountUuid().toString()),
                Map.entry("tenantId", fixture.tenantUuid().toString()),
                Map.entry("membershipExists", true),
                Map.entry("membershipLifecycleState", "ACTIVE"),
                Map.entry("membershipVersion", Map.of(fixture.tenantUuid().toString(), "2")),
                Map.entry("membershipAuthorityGeneration", "1"),
                Map.entry(
                    "authorityTuple",
                    Map.of(
                        "issuerAuthGeneration", Long.toString(authority.issuer().generation()),
                        "accountAuthorityGeneration",
                            Long.toString(authority.account().generation()),
                        "tenantAuthorityGeneration",
                            Map.of(
                                fixture.tenantUuid().toString(),
                                Long.toString(authority.tenants().getFirst().generation())),
                        "membershipAuthorityGeneration",
                            Map.of(fixture.tenantUuid().toString(), "1"),
                        "privateRealmGrantVersions", List.of())),
                Map.entry("issuanceFence", Long.toString(authority.issuanceFence().value())),
                Map.entry("roles", roleSnapshot.roles()),
                Map.entry("gameplayAdmissionAllowed", true),
                Map.entry("callerBoundAuthorityInvalidated", false)));
    AccountAuthorityOutboxRepository.Event appended =
        authorityOutboxRepository.append(
            streamKey,
            historicalRequestId,
            event.eventId(),
            event.eventDigest(),
            event.canonicalJsonUtf8());
    pairAuthorityRepository.enrollProvenPositive(
        fixture.accountUuid(),
        fixture.tenantUuid(),
        provenance,
        new ProvenPositiveCheckpoint(
            2L, 1L, 1L, appended.eventId(), appended.eventDigest(), false));
  }

  private void assertCurrentRoleMutationState(
      JoinFixture fixture,
      long version,
      long generation,
      long sequence,
      long fence,
      List<String> roles,
      boolean invalidated) {
    PositiveMembershipSnapshot snapshot = readExistingPairBoundPositiveMembershipSnapshot(fixture);
    assertThat(snapshot.membershipExists()).isTrue();
    assertThat(snapshot.membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(snapshot.gameplayAdmissionAllowed()).isTrue();
    assertThat(snapshot.membershipVersion())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), Long.toString(version)));
    assertThat(snapshot.membershipAuthorityGeneration()).isEqualTo(Long.toString(generation));
    assertThat(snapshot.authorityTuple().membershipAuthorityGeneration())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), Long.toString(generation)));
    assertThat(snapshot.authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(snapshot.authorityTuple().privateRealmGrantVersions()).isEmpty();
    assertThat(snapshot.authorityEvent().outboxSequence()).isEqualTo(Long.toString(sequence));
    assertThat(snapshot.authorityEvent().callerBoundAuthorityInvalidated()).isEqualTo(invalidated);
    assertThat(snapshot.issuanceFence()).isEqualTo(Long.toString(fence));
    assertThat(snapshot.roles()).containsExactlyElementsOf(roles);
    assertThat(snapshot.authorityEvent().membershipVersion())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), Long.toString(version)));
    assertThat(snapshot.authorityEvent().roles()).containsExactlyElementsOf(roles);
    assertThat(snapshot.outboxCheckpoints())
        .contains(new OutboxCheckpointEntry(authorityStreamKey(fixture), Long.toString(sequence)));
    assertThat(snapshot.outboxSourceEvidence())
        .anySatisfy(
            evidence -> {
              assertThat(evidence.outboxStreamKey()).isEqualTo(authorityStreamKey(fixture));
              assertThat(evidence.outboxSequence()).isEqualTo(Long.toString(sequence));
              assertThat(evidence.eventId()).isEqualTo(snapshot.authorityEvent().eventId());
              assertThat(evidence.eventDigest()).isEqualTo(snapshot.authorityEvent().eventDigest());
              assertThat(evidence.canonicalEventJson())
                  .isEqualTo(snapshot.authorityEvent().canonicalJson());
            });

    var pair =
        dsl.resultQuery(
                "SELECT membership_version, membership_authority_generation, last_event_sequence, "
                    + "last_transition_invalidated FROM account_membership_pair_authority "
                    + "WHERE account_uuid = ? AND tenant_uuid = ?",
                fixture.accountUuid(),
                fixture.tenantUuid())
            .fetchOne();
    assertThat(pair).isNotNull();
    assertThat(pair.get("membership_version", Long.class)).isEqualTo(version);
    assertThat(pair.get("membership_authority_generation", Long.class)).isEqualTo(generation);
    assertThat(pair.get("last_event_sequence", Long.class)).isEqualTo(sequence);
    assertThat(pair.get("last_transition_invalidated", Boolean.class)).isEqualTo(invalidated);
    assertThat(accountIssuanceFence(fixture)).isEqualTo(fence);
    assertThat(authorityGeneration("MEMBERSHIP", null, fixture.accountUuid(), fixture.tenantUuid()))
        .isEqualTo(generation);
  }

  private static void assertExactTenantRoleOperation(
      OperationEvidence expected, OperationEvidence actual) {
    assertThat(actual.request()).isEqualTo(expected.request());
    assertThat(actual.requestPayload()).containsExactly(expected.requestPayload());
    assertThat(actual.requestDigest()).isEqualTo(expected.requestDigest());
    assertThat(actual.status()).isEqualTo(expected.status());
    assertThat(actual.audit().auditEventId()).isEqualTo(expected.audit().auditEventId());
    assertThat(actual.audit().eventType()).isEqualTo(expected.audit().eventType());
    assertThat(actual.audit().occurredAt()).isEqualTo(expected.audit().occurredAt());
    assertThat(actual.audit().payloadDigest()).isEqualTo(expected.audit().payloadDigest());
    assertThat(actual.audit().payload()).containsExactly(expected.audit().payload());
    assertThat(actual.resultPayload()).containsExactly(expected.resultPayload());
    assertThat(actual.resultDigest()).isEqualTo(expected.resultDigest());
    assertThat(actual.members()).hasSize(expected.members().size());
    for (int index = 0; index < expected.members().size(); index++) {
      var expectedMember = expected.members().get(index);
      var actualMember = actual.members().get(index);
      assertThat(actualMember.accountUuid()).isEqualTo(expectedMember.accountUuid());
      assertThat(actualMember.tenantUuid()).isEqualTo(expectedMember.tenantUuid());
      assertThat(actualMember.membershipVersion()).isEqualTo(expectedMember.membershipVersion());
      assertThat(actualMember.membershipAuthorityGeneration())
          .isEqualTo(expectedMember.membershipAuthorityGeneration());
      assertThat(actualMember.eventSequence()).isEqualTo(expectedMember.eventSequence());
      assertThat(actualMember.eventRequestId()).isEqualTo(expectedMember.eventRequestId());
      assertThat(actualMember.eventId()).isEqualTo(expectedMember.eventId());
      assertThat(actualMember.eventDigest()).isEqualTo(expectedMember.eventDigest());
      assertThat(actualMember.callerBoundAuthorityInvalidated())
          .isEqualTo(expectedMember.callerBoundAuthorityInvalidated());
      assertThat(actualMember.eventPayload()).containsExactly(expectedMember.eventPayload());
    }
  }

  private long countTenantRoleOperations(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT count(*) FROM account_tenant_role_operations WHERE tenant_uuid = ?",
                fixture.tenantUuid())
            .fetchOne(0, Long.class));
  }

  private void assertTenantRoleOperationAbsent(UUID requestId) {
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_tenant_role_operations WHERE request_id = ?",
                    requestId)
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            dsl.resultQuery(
                    "SELECT count(*) FROM account_tenant_role_operation_members WHERE request_id = ?",
                    requestId)
                .fetchOne(0, Long.class))
        .isZero();
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
        .setTargetNamespace(WORKLOAD_NAMESPACE)
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

  private Map<String, Object> roleSnapshotRowSnapshot(JoinFixture fixture) {
    var row =
        dsl.resultQuery(
                "SELECT s.membership_id, s.snapshot_version "
                    + "FROM account_tenant_membership_role_snapshots s "
                    + "JOIN account_tenant_membership m ON m.id = s.membership_id "
                    + "WHERE m.account_id = ? AND m.tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne();
    assertThat(row).isNotNull();
    return Map.of("header", row.intoMap(), "roles", committedRoles(fixture));
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
    assertThat(row.get("outcome_membership_version", Long.class)).isEqualTo(2L);
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

  private MembershipTransitionReceipt leave(JoinFixture fixture, String requestId) {
    return membershipLifecycleService.leave(fixture.accountId(), fixture.tenantId(), requestId);
  }

  private UUID leftAuditId(String requestId) {
    return UUID.nameUUIDFromBytes(
        ("account-membership-left-audit/v1:" + requestId).getBytes(StandardCharsets.UTF_8));
  }

  private long countLeftAuditEvents(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_audit_outbox WHERE scope = 'tenant' "
                    + "AND tenant_id = ? AND event_type = 'ACCOUNT_MEMBERSHIP_LEFT'",
                fixture.tenantId())
            .fetchOne(0, Long.class));
  }

  private long countAuditOutboxRows(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_audit_outbox WHERE scope = 'tenant' "
                    + "AND tenant_uuid = ?",
                fixture.tenantUuid())
            .fetchOne(0, Long.class));
  }

  private Map<String, Object> leftAuditEnvelopeSnapshot(JoinFixture fixture, String requestId) {
    var row =
        dsl.resultQuery(
                "SELECT audit_event_id, scope, tenant_id, producer_service, event_type, "
                    + "occurred_at, schema_version, payload_digest_version, payload_digest, payload "
                    + "FROM account_audit_outbox WHERE audit_event_id = ? AND tenant_id = ?",
                leftAuditId(requestId),
                fixture.tenantId())
            .fetchOne();
    assertThat(row).isNotNull();
    return row.intoMap();
  }

  private MembershipScopeSnapshot membershipScopeSnapshot(
      JoinFixture fixture, String leftRequestId) {
    List<Map<String, Object>> auditEnvelopes =
        leftRequestId == null
            ? List.of(joinAuditEnvelopeSnapshot(fixture))
            : List.of(
                joinAuditEnvelopeSnapshot(fixture),
                leftAuditEnvelopeSnapshot(fixture, leftRequestId));
    return new MembershipScopeSnapshot(
        membershipSnapshot(fixture),
        membershipPairAuthorityRow(fixture),
        roleSnapshotRowSnapshot(fixture),
        membershipTransitionReceiptRows(fixture),
        authorityMembershipEventSnapshots(fixture),
        auditEnvelopes,
        joinOperationSnapshot(fixture),
        authorityGeneration("MEMBERSHIP", null, fixture.accountUuid(), fixture.tenantUuid()),
        accountIssuanceFence(fixture),
        authorityOutboxHead(fixture),
        membershipReceiptHead(fixture),
        countMembershipTransitionReceipts(fixture),
        countAuthorityMembershipEvents(fixture),
        countLeftAuditEvents(fixture));
  }

  private List<Map<String, Object>> membershipTransitionReceiptRows(JoinFixture fixture) {
    return dsl
        .resultQuery(
            "SELECT receipt_stream_key, receipt_sequence, account_id, tenant_id, evidence_status, "
                + "transition_type, request_id, membership_id, membership_lifecycle_state, "
                + "gameplay_admission_allowed, membership_version, membership_authority_generation, "
                + "authority_provenance, receipt_id, receipt_digest, created_at "
                + "FROM account_membership_transition_receipts "
                + "WHERE account_id = ? AND tenant_id = ? ORDER BY receipt_sequence",
            fixture.accountId(),
            fixture.tenantId())
        .fetch()
        .stream()
        .map(row -> row.intoMap())
        .toList();
  }

  private List<Map<String, Object>> authorityMembershipEventSnapshots(JoinFixture fixture) {
    return dsl
        .resultQuery(
            "SELECT outbox_sequence, request_id, event_id, event_digest, payload "
                + "FROM account_authority_outbox_events WHERE outbox_stream_key = ? "
                + "ORDER BY outbox_sequence",
            authorityStreamKey(fixture))
        .fetch()
        .stream()
        .map(
            row ->
                Map.<String, Object>of(
                    "outbox_sequence", row.get("outbox_sequence", Long.class),
                    "request_id", row.get("request_id", String.class),
                    "event_id", row.get("event_id", String.class),
                    "event_digest", row.get("event_digest", String.class),
                    "payload",
                        new String(row.get("payload", byte[].class), StandardCharsets.UTF_8)))
        .toList();
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
    assertThat(event.membershipVersion())
        .isEqualTo(
            Map.of(fixture.tenantUuid().toString(), Long.toString(expectedMembershipVersion)));
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

  private void assertLeftAuthorityMembershipEvent(
      JoinFixture fixture,
      String expectedRequestId,
      long expectedSequence,
      long expectedMembershipVersion) {
    var row = authorityMembershipEventRow(fixture, expectedSequence);
    assertThat(row).isNotNull();
    byte[] storedPayload = row.get("payload", byte[].class);
    String payloadJson = new String(storedPayload, StandardCharsets.UTF_8);
    MembershipAuthorityEventV1Codec.MembershipEvent event =
        MembershipAuthorityEventV1Codec.verify(payloadJson);
    assertThat(row.get("outbox_sequence", Long.class)).isEqualTo(expectedSequence);
    assertThat(row.get("request_id", String.class)).isEqualTo(expectedRequestId);
    assertThat(row.get("event_id", String.class)).isEqualTo(event.eventId());
    assertThat(row.get("event_digest", String.class)).isEqualTo(event.eventDigest());
    assertThat(event.accountId()).isEqualTo(fixture.accountUuid().toString());
    assertThat(event.tenantId()).isEqualTo(fixture.tenantUuid().toString());
    assertThat(event.outboxStreamKey()).isEqualTo(authorityStreamKey(fixture));
    assertThat(event.outboxSequence()).isEqualTo(Long.toString(expectedSequence));
    assertThat(event.canonicalJson()).contains("\"membershipExists\":true");
    assertThat(event.membershipLifecycleState()).isEqualTo("INACTIVE");
    assertThat(event.membershipVersion())
        .isEqualTo(
            Map.of(fixture.tenantUuid().toString(), Long.toString(expectedMembershipVersion)));
    assertThat(event.membershipAuthorityGeneration()).isEqualTo("2");
    assertThat(event.roles()).containsExactlyElementsOf(committedRoles(fixture));
    assertThat(event.gameplayAdmissionAllowed()).isFalse();
    assertThat(event.callerBoundAuthorityInvalidated()).isTrue();
    assertThat(event.canonicalJson()).isEqualTo(payloadJson);
    assertThat(storedPayload).containsExactly(event.canonicalJsonUtf8());
    assertThat(event.issuanceFence()).isEqualTo(Long.toString(accountIssuanceFence(fixture)));
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

  private Map<String, Object> authorityMembershipEventSnapshot(JoinFixture fixture, long sequence) {
    var row = authorityMembershipEventRow(fixture, sequence);
    assertThat(row).isNotNull();
    return Map.of(
        "outbox_sequence", row.get("outbox_sequence", Long.class),
        "request_id", row.get("request_id", String.class),
        "event_id", row.get("event_id", String.class),
        "event_digest", row.get("event_digest", String.class),
        "payload", new String(row.get("payload", byte[].class), StandardCharsets.UTF_8));
  }

  private void appendContradictoryCurrentEvent(JoinFixture fixture) {
    MembershipAuthorityEventV1Codec.MembershipEvent prior =
        MembershipAuthorityEventV1Codec.verify(
            new String(
                authorityMembershipEventRow(fixture, 1L).get("payload", byte[].class),
                StandardCharsets.UTF_8));
    String requestId = "contradictory-current-" + UUID.randomUUID();
    String eventId =
        UUID.nameUUIDFromBytes(
                (MembershipAuthorityEventV1Codec.SCHEMA_VERSION + ":" + requestId)
                    .getBytes(StandardCharsets.UTF_8))
            .toString();
    Map<String, Object> tuple = new LinkedHashMap<>();
    tuple.put("issuerAuthGeneration", prior.authorityTuple().issuerAuthGeneration());
    tuple.put("accountAuthorityGeneration", prior.authorityTuple().accountAuthorityGeneration());
    tuple.put("tenantAuthorityGeneration", prior.authorityTuple().tenantAuthorityGeneration());
    tuple.put(
        "membershipAuthorityGeneration", prior.authorityTuple().membershipAuthorityGeneration());
    tuple.put("privateRealmGrantVersions", List.of());
    Map<String, Object> eventPreimage = new LinkedHashMap<>();
    eventPreimage.put("schemaVersion", prior.schemaVersion());
    eventPreimage.put("eventType", prior.eventType());
    eventPreimage.put("eventId", eventId);
    eventPreimage.put("requestId", requestId);
    eventPreimage.put("outboxStreamKey", authorityStreamKey(fixture));
    eventPreimage.put("outboxSequence", "2");
    eventPreimage.put(
        "sourceScope", "membership/" + fixture.accountUuid() + "/" + fixture.tenantUuid());
    eventPreimage.put("accountId", fixture.accountUuid().toString());
    eventPreimage.put("tenantId", fixture.tenantUuid().toString());
    eventPreimage.put("membershipExists", true);
    eventPreimage.put("membershipLifecycleState", "ACTIVE");
    eventPreimage.put("membershipVersion", prior.membershipVersion());
    eventPreimage.put("membershipAuthorityGeneration", prior.membershipAuthorityGeneration());
    eventPreimage.put("authorityTuple", tuple);
    eventPreimage.put("issuanceFence", prior.issuanceFence());
    eventPreimage.put("roles", prior.roles());
    eventPreimage.put("gameplayAdmissionAllowed", true);
    eventPreimage.put("callerBoundAuthorityInvalidated", false);
    MembershipAuthorityEventV1Codec.MembershipEvent candidate =
        MembershipAuthorityEventV1Codec.seal(eventPreimage);
    var appended =
        new TransactionTemplate(transactionManager)
            .execute(
                status ->
                    authorityOutboxRepository.append(
                        authorityStreamKey(fixture),
                        requestId,
                        candidate.eventId(),
                        candidate.eventDigest(),
                        candidate.canonicalJsonUtf8()));
    assertThat(appended).isNotNull();
    assertThat(appended.outboxSequence()).isEqualTo(2L);
  }

  private void assertStoredMembershipVersionFailsClosed(
      JoinFixture fixture, String malformedMembershipVersionJson) {
    JoinPublicProductionResult joined = join(fixture);
    assertThat(joined.success()).isTrue();
    var row = authorityMembershipEventRow(fixture, 1L);
    String payload = new String(row.get("payload", byte[].class), StandardCharsets.UTF_8);
    String exactMembershipVersionJson =
        "\"membershipVersion\":{\""
            + fixture.tenantUuid()
            + "\":\""
            + joined.membershipVersion()
            + "\"}";
    assertThat(payload).contains(exactMembershipVersionJson);
    String malformedPayload =
        payload.replace(
            exactMembershipVersionJson, "\"membershipVersion\":" + malformedMembershipVersionJson);
    assertThat(malformedPayload).isNotEqualTo(payload);
    assertThatThrownBy(() -> MembershipAuthorityEventV1Codec.verify(malformedPayload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("event.membershipVersion ");
    // V33 events are immutable; append a malformed newer event through the opaque storage boundary.
    String malformedRequestId = "malformed-version-" + UUID.randomUUID();
    var malformedEvent =
        new TransactionTemplate(transactionManager)
            .execute(
                status ->
                    authorityOutboxRepository.append(
                        authorityStreamKey(fixture),
                        malformedRequestId,
                        malformedRequestId + "-event",
                        row.get("event_digest", String.class),
                        malformedPayload.getBytes(StandardCharsets.UTF_8)));
    assertThat(malformedEvent).isNotNull();
    assertThat(malformedEvent.outboxSequence()).isEqualTo(2L);

    assertThatThrownBy(() -> readPositiveMembershipSnapshot(fixture))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Stored Account authority event is invalid");
  }

  private UUID differentTenantUuid(JoinFixture fixture) {
    UUID candidate = UUID.fromString("00000000-0000-0000-0000-000000000001");
    return candidate.equals(fixture.tenantUuid())
        ? UUID.fromString("00000000-0000-0000-0000-000000000002")
        : candidate;
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

  private long accountIssuanceFence(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT issuance_fence FROM account_authority_issuance_fences "
                    + "WHERE account_uuid = ?",
                fixture.accountUuid())
            .fetchOne(0, Long.class));
  }

  private long authorityOutboxHead(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT last_sequence FROM account_authority_outbox_streams "
                    + "WHERE outbox_stream_key = ?",
                authorityStreamKey(fixture))
            .fetchOne(0, Long.class));
  }

  private long membershipReceiptHead(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT last_receipt_sequence "
                    + "FROM account_membership_transition_receipt_stream_heads "
                    + "WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
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
        throw new IllegalStateException(
            "concurrent Account membership test did not receive its start signal");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("concurrent JOIN test was interrupted", ex);
    }
  }

  private void awaitAccountFenceLockWait(Integer snapshotBackendPid, Integer joinBackendPid)
      throws InterruptedException {
    assertThat(snapshotBackendPid).isNotNull();
    assertThat(joinBackendPid).isNotNull();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Boolean blockedBySnapshot =
          dsl.resultQuery(
                  "SELECT CAST(? AS integer) = ANY(pg_blocking_pids(CAST(? AS integer)))",
                  snapshotBackendPid,
                  joinBackendPid)
              .fetchOne(0, Boolean.class);
      if (Boolean.TRUE.equals(blockedBySnapshot)) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError(
        "JOIN backend did not enter a PostgreSQL lock wait on the snapshot owner transaction");
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

  private List<OutboxCheckpointEntry> expectedMembershipOutboxCheckpoints(
      JoinFixture fixture, String membershipSequence) {
    return List.of(
        new OutboxCheckpointEntry(
            MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
                + "account/"
                + fixture.accountUuid(),
            "0"),
        new OutboxCheckpointEntry(
            MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
                + "issuer/"
                + AccountServiceImpl.ACCOUNT_JWT_ISSUER,
            "0"),
        new OutboxCheckpointEntry(authorityStreamKey(fixture), membershipSequence),
        new OutboxCheckpointEntry(
            MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "tenant/" + fixture.tenantUuid(),
            "0"));
  }

  private long countAuthorityMembershipEvents(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_events "
                    + "WHERE outbox_stream_key = ?",
                authorityStreamKey(fixture))
            .fetchOne(0, Long.class));
  }

  private long countAuthorityStreamEvents(String streamKey) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_events "
                    + "WHERE outbox_stream_key = ?",
                streamKey)
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

  private long seedPlayerRoleSnapshot(JoinFixture fixture) {
    var membership =
        dsl.resultQuery(
                "SELECT id, membership_version FROM account_tenant_membership "
                    + "WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne();
    assertThat(membership).isNotNull();
    long membershipId = membership.get("id", Long.class);
    long membershipVersion = membership.get("membership_version", Long.class);
    assertThat(
            dsl.execute(
                "INSERT INTO account_tenant_membership_role_snapshots "
                    + "(membership_id, snapshot_version) VALUES (?, ?)",
                membershipId,
                membershipVersion))
        .isEqualTo(1);
    assertThat(
            dsl.execute(
                "INSERT INTO account_tenant_membership_role_snapshot_roles "
                    + "(membership_id, snapshot_version, role_identifier) VALUES (?, ?, 'player')",
                membershipId,
                membershipVersion))
        .isEqualTo(1);
    return membershipId;
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

  private Map<String, Object> membershipTransitionReceiptRowSnapshot(
      JoinFixture fixture, long sequence) {
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
                sequence)
            .fetchOne();
    assertThat(row).isNotNull();
    return row.intoMap();
  }

  private MembershipTransitionReceipt membershipTransitionReceipt(
      JoinFixture fixture, long sequence) {
    var row =
        dsl.resultQuery(
                "SELECT receipt_stream_key, receipt_sequence, receipt_id, receipt_digest, "
                    + "evidence_status, transition_type, request_id, membership_id "
                    + "FROM account_membership_transition_receipts "
                    + "WHERE account_id = ? AND tenant_id = ? AND receipt_sequence = ?",
                fixture.accountId(),
                fixture.tenantId(),
                sequence)
            .fetchOne();
    assertThat(row).isNotNull();
    return new MembershipTransitionReceipt(
        row.get("receipt_stream_key", String.class),
        row.get("receipt_sequence", Long.class),
        row.get("receipt_id", UUID.class),
        row.get("receipt_digest", String.class),
        row.get("evidence_status", String.class),
        row.get("transition_type", String.class),
        row.get("request_id", String.class),
        row.get("membership_id", Long.class));
  }

  private void assertTransitionAndAuditOutboxOnce(JoinFixture fixture, long membershipId) {
    assertThat(countMemberships(fixture)).isEqualTo(1L);
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_tenant_membership WHERE id = ? AND account_id = ? AND tenant_id = ? AND lifecycle_state = 'ACTIVE' AND authority_provenance = 'EXPLICIT_JOIN' AND membership_version = 2 AND membership_authority_generation = 1",
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

  private record TenantRoleFixture(JoinFixture admin, JoinFixture target) {}

  private record MembershipScopeSnapshot(
      Map<String, Object> membership,
      Map<String, Object> pairAuthority,
      Map<String, Object> roles,
      List<Map<String, Object>> transitionReceipts,
      List<Map<String, Object>> authorityEvents,
      List<Map<String, Object>> auditEnvelopes,
      Map<String, Object> joinOperation,
      long membershipGeneration,
      long issuanceFence,
      long authorityOutboxHead,
      long membershipReceiptHead,
      long receiptCount,
      long eventCount,
      long leftAuditCount) {}
}
