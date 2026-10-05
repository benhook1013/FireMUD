package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.accountservice.AccountServiceApplication;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountMembershipCaptureSources;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.EventEvidence;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCanonicalJoinReconciliationService;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.impl.AccountMembershipAuthorityGrpcService;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TlsTestSupport;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL composition and physical-transport proof for explicitly unwired canonical JOIN and
 * membership readback components.
 *
 * <p>The storage fixtures call Account owner repositories directly. The physical mTLS cases serve
 * only the held-back Account membership producer and use synthetic JOIN caller/policy evidence;
 * none of these tests proves authenticated entitlement, caller, or public JOIN authorization.
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
  private static final String ACCOUNT_SERVER_URI =
      "spiffe://firemud/ns/firemud-unit1b/sa/account-service";
  private static final String GAME_SESSION_CLIENT_URI =
      "spiffe://firemud/ns/firemud-unit1b/sa/game-session-service";
  private static final AtomicLong CERTIFICATE_SERIAL = new AtomicLong(1L);
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
  @Autowired private AccountAuthorityOutboxRepository authorityOutbox;
  @Autowired private AccountMembershipPairAuthorityRepository pairAuthorities;
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
  void committedFreshUuidJoinReturnsExactPositiveSnapshotAndCaptureWithoutChangingSources() {
    // This fixture proves persisted Account-owner composition only. Its caller binding is synthetic
    // and does not exercise or claim an authenticated JOIN producer.
    JoinFixture fixture = committedFreshJoinFixture();
    EvidenceCounts before = evidenceCounts(fixture);
    PairAuthority pairBefore = pairAuthority(fixture);
    long fenceBefore = issuanceFence(fixture.account().accountUuid());
    FreshTenantCreationEvidence freshSource =
        inTransaction(() -> freshAssociations.read(fixture.tenantUuid()).orElseThrow());

    RuntimeMembershipSnapshotDto existingOnly =
        inTransaction(
            () ->
                membershipEventProducer.readExistingRuntimeMembershipSnapshot(
                    fixture.account().accountUuid(), fixture.tenantUuid()));
    RuntimeMembershipSnapshotDto enrollMode =
        inTransaction(
            () ->
                membershipEventProducer.readRuntimeMembershipSnapshot(
                    fixture.account().accountUuid(), fixture.tenantUuid()));
    AccountMembershipCaptureSources capturedSources =
        inTransaction(
            () ->
                membershipEventProducer.readExistingRuntimeMembershipCaptureSources(
                    fixture.account().accountUuid(), fixture.tenantUuid()));

    assertThat(existingOnly.membershipExists()).isTrue();
    assertThat(existingOnly.gameplayAdmissionAllowed()).isTrue();
    assertThat(existingOnly.membershipBaseline().membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(existingOnly.membershipBaseline().membershipVersion())
        .containsExactly(Map.entry(fixture.tenantUuid().toString(), "2"));
    assertThat(existingOnly.membershipBaseline().membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(existingOnly.roles()).contains("player");
    assertThat(existingOnly.requireConsistentSourceEvent().outboxSequence()).isEqualTo("1");
    assertThat(enrollMode.membershipBaseline()).isEqualTo(existingOnly.membershipBaseline());
    assertThat(enrollMode.roles()).isEqualTo(existingOnly.roles());
    assertThat(enrollMode.sourceEvent().canonicalJson())
        .isEqualTo(existingOnly.sourceEvent().canonicalJson());
    assertThat(capturedSources.membershipSnapshot().membershipExists()).isTrue();
    assertThat(capturedSources.membershipSnapshot().sourceEvent().canonicalJson())
        .isEqualTo(existingOnly.sourceEvent().canonicalJson());
    assertThat(capturedSources.freshTenantAssociation()).contains(freshSource);
    assertThat(capturedSources.retainedTenantAssociation()).isEmpty();
    var capturedRole = capturedSources.roleSource().orElseThrow();
    assertThat(capturedRole.accountId()).isEqualTo(fixture.account().accountId());
    assertThat(capturedRole.accountUuid()).isEqualTo(fixture.account().accountUuid());
    assertThat(capturedRole.membershipId()).isEqualTo(readOperation(fixture).membershipId());
    assertThat(capturedRole.tenantId()).isNull();
    assertThat(capturedRole.tenantUuid()).isEqualTo(fixture.tenantUuid());
    assertThat(capturedRole.tenantProvenance())
        .isEqualTo(
            new VerifiedTenantProvenance(
                null,
                TenantProvenanceKind.FRESH_GAME_DESIGN,
                freshSource.operationId(),
                freshSource.evidenceDigest()));
    assertThat(capturedRole.snapshotVersion()).isEqualTo(2L);
    assertThat(capturedRole.roles()).isEqualTo(existingOnly.roles());
    assertThat(capturedSources.authoritySnapshot().memberships().getFirst().generation())
        .isEqualTo(1L);
    assertThat(capturedSources.authoritySnapshot().memberships().getFirst().sourceVersion())
        .isEqualTo(1L);
    assertThat(capturedSources.membershipSnapshot().outboxCheckpoints())
        .anySatisfy(
            checkpoint -> {
              assertThat(checkpoint.outboxStreamKey()).isEqualTo(eventStreamKey(fixture));
              assertThat(checkpoint.outboxSequence()).isEqualTo("1");
            });
    assertThat(capturedSources.membershipSnapshot().outboxSourceEvidence())
        .anySatisfy(
            source -> {
              assertThat(source.outboxStreamKey()).isEqualTo(eventStreamKey(fixture));
              assertThat(source.outboxSequence()).isEqualTo("1");
            });
    assertThat(evidenceCounts(fixture)).isEqualTo(before);
    assertThat(pairAuthority(fixture)).isEqualTo(pairBefore);
    assertThat(issuanceFence(fixture.account().accountUuid())).isEqualTo(fenceBefore);
  }

  @Test
  void existingOnlyFreshUuidReadPreservesProvedAbsenceAndPendingOperation() {
    JoinFixture fixture = fixture(true);
    EvidenceCounts before = evidenceCounts(fixture);
    PairAuthority pairBefore = pairAuthority(fixture);

    RuntimeMembershipSnapshotDto absent =
        inTransaction(
            () ->
                membershipEventProducer.readExistingRuntimeMembershipSnapshot(
                    fixture.account().accountUuid(), fixture.tenantUuid()));

    assertThat(absent.membershipExists()).isFalse();
    assertThat(absent.membershipBaseline().membershipLifecycleState()).isEqualTo("MISSING");
    assertThat(absent.membershipBaseline().membershipVersion())
        .containsExactly(Map.entry(fixture.tenantUuid().toString(), "1"));
    assertThat(readOperation(fixture).status()).isEqualTo("PENDING");
    assertThat(evidenceCounts(fixture)).isEqualTo(before);
    assertThat(pairAuthority(fixture)).isEqualTo(pairBefore);
  }

  @Test
  void pendingCanonicalJoinCannotAuthorizePositiveReadbackOrCauseMutation() {
    JoinFixture fixture = fixture(true);
    persistEvidence(fixture, EvidenceShape.COMPLETE);
    EvidenceCounts before = evidenceCounts(fixture);
    PairAuthority pairBefore = pairAuthority(fixture);

    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        membershipEventProducer.readExistingRuntimeMembershipSnapshot(
                            fixture.account().accountUuid(), fixture.tenantUuid())))
        .isInstanceOf(IllegalStateException.class);

    assertThat(readOperation(fixture).status()).isEqualTo("PENDING");
    assertThat(evidenceCounts(fixture)).isEqualTo(before);
    assertThat(pairAuthority(fixture)).isEqualTo(pairBefore);
  }

  @Test
  void freshUuidReadRejectsContradictoryReceiptEventRolesPairAndAuditWithoutMutation() {
    for (Contradiction contradiction : Contradiction.values()) {
      JoinFixture fixture = committedFreshJoinFixture();
      introduceContradiction(fixture, contradiction);
      EvidenceCounts before = evidenceCounts(fixture);
      PairAuthority pairBefore = pairAuthority(fixture);

      assertThatThrownBy(
              () ->
                  inTransaction(
                      () ->
                          membershipEventProducer.readExistingRuntimeMembershipSnapshot(
                              fixture.account().accountUuid(), fixture.tenantUuid())))
          .as(contradiction.name())
          .isInstanceOf(IllegalStateException.class);

      assertThat(evidenceCounts(fixture)).as(contradiction.name()).isEqualTo(before);
      assertThat(pairAuthority(fixture)).as(contradiction.name()).isEqualTo(pairBefore);
    }
  }

  @Test
  void existingOnlyFreshUuidReadRejectsWrongAccountAndTenantSources() {
    JoinFixture fixture = committedFreshJoinFixture();
    PairAuthority pairBefore = pairAuthority(fixture);
    AccountFixture otherAccount = accountFixture();
    UUID otherTenantUuid = UUID.randomUUID();
    FreshTenantCreationEvidence otherTenantSource = freshTenantEvidence(otherTenantUuid);
    inTransaction(
        () -> {
          freshAssociations.importVerified(otherTenantSource);
          generations.initializeTenantIfAbsent(otherTenantUuid);
          return null;
        });

    // Neither different scope has its own durable absence baseline. Existing-only readback must
    // deny rather than fabricate MISSING authority from an absent row or borrow the joined pair.
    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        membershipEventProducer.readExistingRuntimeMembershipSnapshot(
                            otherAccount.accountUuid(), fixture.tenantUuid())))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        membershipEventProducer.readExistingRuntimeMembershipSnapshot(
                            fixture.account().accountUuid(), otherTenantUuid)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(evidenceCounts(fixture)).isEqualTo(new EvidenceCounts(1L, 1L, 1L, 1L));
    assertThat(pairAuthority(fixture)).isEqualTo(pairBefore);
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
      assertThat(retried.lastAttemptAuthorityAvailability()).isEqualTo("UNAVAILABLE");
      assertThat(retried.lastAttemptFailureCode()).isEqualTo("ENTITLEMENT_TIMEOUT");
      assertThat(exactTerminalRetry(fixture)).isEqualTo(retried);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    CanonicalJoinOperationEvidence committed = readOperation(fixture);
    assertThat(committed.status()).isEqualTo("COMMITTED");
    assertThat(committed.outcome()).isEqualTo("JOINED");
    assertThat(committed.lastAttemptAuthorityAvailability()).isEqualTo("UNAVAILABLE");
    assertThat(committed.lastAttemptFailureCode()).isEqualTo("ENTITLEMENT_TIMEOUT");
    assertThat(evidenceCounts(fixture)).isEqualTo(before);
  }

  @Test
  void committedFreshUuidJoinRoundTripsExactSnapshotOverPhysicalMtls() throws Exception {
    // The persisted policy/caller fixture is synthetic. This proves only the held-back producer
    // transport and does not claim authenticated public JOIN or Game Session consumer activation.
    JoinFixture fixture = committedFreshJoinFixture();
    EvidenceCounts countsBefore = evidenceCounts(fixture);
    CanonicalJoinOperationEvidence operationBefore = readOperation(fixture);
    PairAuthority pairBefore = pairAuthority(fixture);
    long fenceBefore = issuanceFence(fixture.account().accountUuid());
    FreshTenantCreationEvidence freshSourceBefore =
        inTransaction(() -> freshAssociations.read(fixture.tenantUuid()).orElseThrow());
    RuntimeMembershipSnapshotDto snapshotBefore = readRuntimeSnapshot(fixture);

    AccountMembershipAuthorityEventProducer producerTarget =
        AopTestUtils.getUltimateTargetObject(membershipEventProducer);
    AccountMembershipAuthorityEventProducer producerTargetSpy = spy(producerTarget);
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return invocation.callRealMethod();
            })
        .when(producerTargetSpy)
        .readRuntimeMembershipSnapshot(fixture.account().accountUuid(), fixture.tenantUuid());

    TestPki pki = newTestPki();
    AtomicInteger applicationCalls = new AtomicInteger();
    Server server = startPhysicalMembershipServer(producerTargetSpy, pki, applicationCalls);
    ManagedChannel channel =
        newPhysicalMembershipChannel(server.getPort(), pki.gameSessionClient(), pki);
    PlayerExecutionContext playerContext = runtimePlayerContext(fixture);
    try {
      GetTenantMembershipForRuntimeResponse response =
          membershipStub(channel)
              .withDeadlineAfter(10, TimeUnit.SECONDS)
              .getTenantMembershipForRuntime(
                  GetTenantMembershipForRuntimeRequest.newBuilder()
                      .setPlayerContext(playerContext)
                      .build());
      RuntimeMembershipSnapshotDto snapshotAfter = readRuntimeSnapshot(fixture);

      assertRuntimeMembershipResponseMatches(response, playerContext, snapshotBefore);
      assertThat(Instant.parse(response.getEvaluatedAt()))
          .isBetween(snapshotBefore.evaluatedAt(), snapshotAfter.evaluatedAt());
      assertThat(GetTenantMembershipForRuntimeResponse.parseFrom(response.toByteArray()))
          .isEqualTo(response);
      assertThat(applicationCalls).hasValue(1);
      verify(producerTargetSpy, times(1))
          .readRuntimeMembershipSnapshot(fixture.account().accountUuid(), fixture.tenantUuid());
      assertOwnerEvidenceUnchanged(
          fixture,
          snapshotBefore,
          snapshotAfter,
          operationBefore,
          pairBefore,
          freshSourceBefore,
          countsBefore,
          fenceBefore);
    } finally {
      stopPhysicalTransport(server, channel);
    }
  }

  @Test
  void physicalMembershipTransportRejectsWrongPeersBeforeProducerAccess() throws Exception {
    JoinFixture fixture = committedFreshJoinFixture();
    EvidenceCounts countsBefore = evidenceCounts(fixture);
    CanonicalJoinOperationEvidence operationBefore = readOperation(fixture);
    PairAuthority pairBefore = pairAuthority(fixture);
    long fenceBefore = issuanceFence(fixture.account().accountUuid());
    FreshTenantCreationEvidence freshSourceBefore =
        inTransaction(() -> freshAssociations.read(fixture.tenantUuid()).orElseThrow());
    RuntimeMembershipSnapshotDto snapshotBefore = readRuntimeSnapshot(fixture);

    AccountMembershipAuthorityEventProducer producerTarget =
        AopTestUtils.getUltimateTargetObject(membershipEventProducer);
    AccountMembershipAuthorityEventProducer producerTargetSpy = spy(producerTarget);
    TestPki pki = newTestPki();
    AtomicInteger applicationCalls = new AtomicInteger();
    Server server = startPhysicalMembershipServer(producerTargetSpy, pki, applicationCalls);
    ManagedChannel wrongWorkloadChannel =
        newPhysicalMembershipChannel(server.getPort(), pki.wrongWorkloadClient(), pki);
    ManagedChannel wrongNamespaceChannel =
        newPhysicalMembershipChannel(server.getPort(), pki.wrongNamespaceGameSessionClient(), pki);
    PlayerExecutionContext playerContext = runtimePlayerContext(fixture);
    try {
      assertMembershipPeerDenied(wrongWorkloadChannel, playerContext);
      assertThat(applicationCalls).hasValue(1);
      verifyNoInteractions(producerTargetSpy);

      assertMembershipPeerDenied(wrongNamespaceChannel, playerContext);
      assertThat(applicationCalls).hasValue(2);
      verifyNoInteractions(producerTargetSpy);

      assertMissingClientCertificateHandshakeRejected(server, pki);
      assertThat(applicationCalls)
          .as("a missing client certificate must fail before gRPC application dispatch")
          .hasValue(2);
      verifyNoInteractions(producerTargetSpy);

      RuntimeMembershipSnapshotDto snapshotAfter = readRuntimeSnapshot(fixture);
      assertOwnerEvidenceUnchanged(
          fixture,
          snapshotBefore,
          snapshotAfter,
          operationBefore,
          pairBefore,
          freshSourceBefore,
          countsBefore,
          fenceBefore);
    } finally {
      stopPhysicalTransport(server, wrongWorkloadChannel, wrongNamespaceChannel);
    }
  }

  private RuntimeMembershipSnapshotDto readRuntimeSnapshot(JoinFixture fixture) {
    return inTransaction(
        () ->
            membershipEventProducer.readRuntimeMembershipSnapshot(
                fixture.account().accountUuid(), fixture.tenantUuid()));
  }

  private void assertRuntimeMembershipResponseMatches(
      GetTenantMembershipForRuntimeResponse response,
      PlayerExecutionContext request,
      RuntimeMembershipSnapshotDto expected) {
    assertThat(response.getUnknownFields().asMap()).isEmpty();
    assertThat(response.hasError()).isFalse();
    assertThat(response.getAuthorityAvailability()).isEqualTo("AVAILABLE");
    assertThat(response.getAccountId()).isEqualTo(expected.accountUuid());
    assertThat(response.getTenantId()).isEqualTo(expected.tenantUuid());
    assertThat(response.getRequestAccountId()).isEqualTo(request.getAccountId());
    assertThat(response.getRequestTenantId()).isEqualTo(request.getTenantId());
    assertThat(response.getRequestId()).isEqualTo(request.getRequestId());
    assertThat(response.getMembershipExists()).isEqualTo(expected.membershipExists());
    assertThat(response.getGameplayAdmissionAllowed())
        .isEqualTo(expected.gameplayAdmissionAllowed());
    assertThat(response.getMembershipLifecycleState())
        .isEqualTo(expected.membershipBaseline().membershipLifecycleState());
    assertThat(response.getMembershipVersionMap())
        .isEqualTo(expected.membershipBaseline().membershipVersion());
    assertThat(response.getMembershipAuthorityGeneration())
        .isEqualTo(expected.membershipBaseline().membershipAuthorityGeneration());
    assertThat(response.getMembershipBaseline().getMembershipLifecycleState())
        .isEqualTo(expected.membershipBaseline().membershipLifecycleState());
    assertThat(response.getMembershipBaseline().getMembershipVersionMap())
        .isEqualTo(expected.membershipBaseline().membershipVersion());
    assertThat(response.getMembershipBaseline().getMembershipAuthorityGeneration())
        .isEqualTo(expected.membershipBaseline().membershipAuthorityGeneration());
    assertThat(response.getRolesList()).containsExactlyElementsOf(expected.roles());
    assertThat(response.getIssuanceFence()).isEqualTo(expected.issuanceFence());

    String tenantUuid = expected.tenantUuid();
    assertThat(expected.membershipExists()).isTrue();
    assertThat(expected.gameplayAdmissionAllowed()).isTrue();
    assertThat(expected.membershipBaseline().membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(expected.membershipBaseline().membershipVersion())
        .isEqualTo(Map.of(tenantUuid, "2"));
    assertThat(expected.membershipBaseline().membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(expected.issuanceFence()).isEqualTo("1");
    var expectedTuple = expected.authorityTuple();
    var actualTuple = response.getAuthorityTuple();
    assertThat(actualTuple.getIssuerAuthGeneration())
        .isEqualTo(expectedTuple.issuerAuthGeneration());
    assertThat(actualTuple.getAccountAuthorityGeneration())
        .isEqualTo(expectedTuple.accountAuthorityGeneration());
    assertThat(actualTuple.getTenantAuthorityGenerationMap())
        .isEqualTo(expectedTuple.tenantAuthorityGeneration());
    assertThat(actualTuple.getMembershipAuthorityGenerationMap())
        .isEqualTo(expectedTuple.membershipAuthorityGeneration());
    assertThat(expectedTuple.issuerAuthGeneration()).isEqualTo("1");
    assertThat(expectedTuple.accountAuthorityGeneration()).isEqualTo("1");
    assertThat(expectedTuple.tenantAuthorityGeneration()).isEqualTo(Map.of(tenantUuid, "1"));
    assertThat(expectedTuple.membershipAuthorityGeneration()).isEqualTo(Map.of(tenantUuid, "1"));
    assertThat(actualTuple.getPrivateRealmGrantVersionsCount())
        .isEqualTo(expectedTuple.privateRealmGrantVersions().size());
    assertThat(actualTuple.hasAccountSecurityCutoff())
        .isEqualTo(expectedTuple.accountSecurityCutoff().isPresent());
    expectedTuple
        .accountSecurityCutoff()
        .ifPresent(
            cutoff -> {
              assertThat(actualTuple.getAccountSecurityCutoff().getAccountAuthorityGeneration())
                  .isEqualTo(cutoff.accountAuthorityGeneration());
              assertThat(actualTuple.getAccountSecurityCutoff().getOutboxStreamKey())
                  .isEqualTo(cutoff.outboxStreamKey());
              assertThat(actualTuple.getAccountSecurityCutoff().getOutboxSequence())
                  .isEqualTo(cutoff.outboxSequence());
            });
    assertThat(actualTuple.hasTenantBillingCutoff())
        .isEqualTo(expectedTuple.tenantBillingCutoff().isPresent());
    assertThat(expectedTuple.privateRealmGrantVersions()).isEmpty();
    assertThat(expectedTuple.accountSecurityCutoff()).isEmpty();
    assertThat(expectedTuple.tenantBillingCutoff()).isEmpty();

    assertThat(response.getOutboxCheckpointsCount()).isEqualTo(expected.outboxCheckpoints().size());
    for (int index = 0; index < expected.outboxCheckpoints().size(); index++) {
      var actual = response.getOutboxCheckpoints(index);
      var checkpoint = expected.outboxCheckpoints().get(index);
      assertThat(actual.getOutboxStreamKey()).isEqualTo(checkpoint.outboxStreamKey());
      assertThat(actual.getOutboxSequence()).isEqualTo(checkpoint.outboxSequence());
      assertThat(actual.getUnknownFields().asMap()).isEmpty();
    }
    assertThat(response.getOutboxSourceEvidenceCount())
        .isEqualTo(expected.outboxSourceEvidence().size());
    for (int index = 0; index < expected.outboxSourceEvidence().size(); index++) {
      var actual = response.getOutboxSourceEvidence(index);
      var source = expected.outboxSourceEvidence().get(index);
      assertThat(actual.getOutboxStreamKey()).isEqualTo(source.outboxStreamKey());
      assertThat(actual.getOutboxSequence()).isEqualTo(source.outboxSequence());
      assertThat(actual.getEventId()).isEqualTo(source.eventId());
      assertThat(actual.getEventDigest()).isEqualTo(source.eventDigest());
      assertThat(actual.getCanonicalEventJson()).isEqualTo(source.canonicalEventJson());
      assertThat(actual.getUnknownFields().asMap()).isEmpty();
    }
    assertThat(expected.sourceEvent()).isNotNull();
    assertThat(response.getOutboxSourceEvidenceCount()).isEqualTo(1);
    assertThat(response.getOutboxSourceEvidence(0).getOutboxStreamKey())
        .isEqualTo(eventStreamKey(expected.accountUuid(), expected.tenantUuid()));
    assertThat(response.getOutboxSourceEvidence(0).getOutboxSequence()).isEqualTo("1");
    assertThat(response.getOutboxSourceEvidence(0).getCanonicalEventJson())
        .isEqualTo(expected.sourceEvent().canonicalJson());
  }

  private void assertMembershipPeerDenied(
      ManagedChannel channel, PlayerExecutionContext playerContext) {
    Throwable failure =
        catchThrowable(
            () ->
                membershipStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .getTenantMembershipForRuntime(
                        GetTenantMembershipForRuntimeRequest.newBuilder()
                            .setPlayerContext(playerContext)
                            .build()));
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
  }

  private AccountServiceGrpc.AccountServiceBlockingStub membershipStub(ManagedChannel channel) {
    return AccountServiceGrpc.newBlockingStub(channel)
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(ACCOUNT_SERVER_URI));
  }

  private void assertOwnerEvidenceUnchanged(
      JoinFixture fixture,
      RuntimeMembershipSnapshotDto snapshotBefore,
      RuntimeMembershipSnapshotDto snapshotAfter,
      CanonicalJoinOperationEvidence operationBefore,
      PairAuthority pairBefore,
      FreshTenantCreationEvidence freshSourceBefore,
      EvidenceCounts countsBefore,
      long fenceBefore) {
    assertThat(snapshotAfter)
        .usingRecursiveComparison()
        .ignoringFields("evaluatedAt")
        .isEqualTo(snapshotBefore);
    assertThat(readOperation(fixture)).isEqualTo(operationBefore);
    assertThat(pairAuthority(fixture)).isEqualTo(pairBefore);
    assertThat(inTransaction(() -> freshAssociations.read(fixture.tenantUuid()).orElseThrow()))
        .isEqualTo(freshSourceBefore);
    assertThat(evidenceCounts(fixture)).isEqualTo(countsBefore);
    assertThat(issuanceFence(fixture.account().accountUuid())).isEqualTo(fenceBefore);
  }

  private Server startPhysicalMembershipServer(
      AccountMembershipAuthorityEventProducer producer, TestPki pki, AtomicInteger applicationCalls)
      throws Exception {
    AccountMembershipAuthorityGrpcService candidate =
        new AccountMembershipAuthorityGrpcService(producer, transactionManager, TEST_NAMESPACE);
    ServerInterceptor dispatchCounter =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            applicationCalls.incrementAndGet();
            return next.startCall(call, headers);
          }
        };
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(
                        pki.accountServer().privateKey(), pki.accountServer().certificate()))
                .trustManager(pki.caCertificate())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(
            ServerInterceptors.intercept(
                candidate, dispatchCounter, new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private ManagedChannel newPhysicalMembershipChannel(
      int port, TestCertificate clientCertificate, TestPki pki) throws Exception {
    return NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", port))
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forClient()
                        .trustManager(pki.caCertificate())
                        .keyManager(
                            clientCertificate.privateKey(), clientCertificate.certificate()))
                .build())
        .build();
  }

  private void assertMissingClientCertificateHandshakeRejected(Server server, TestPki pki)
      throws Exception {
    Throwable handshakeFailure =
        catchThrowable(
            () -> {
              try (Socket transport = new Socket()) {
                transport.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 5_000);
                try (SSLSocket socket =
                    (SSLSocket)
                        trustOnlyClientContext(pki)
                            .getSocketFactory()
                            .createSocket(transport, "127.0.0.1", server.getPort(), true)) {
                  socket.setSoTimeout(5_000);
                  SSLParameters parameters = socket.getSSLParameters();
                  parameters.setApplicationProtocols(new String[] {"h2"});
                  parameters.setEndpointIdentificationAlgorithm("HTTPS");
                  socket.setSSLParameters(parameters);
                  socket.startHandshake();
                  // TLS 1.3 can report the required client-certificate rejection on first read.
                  socket
                      .getOutputStream()
                      .write(
                          "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                  socket.getOutputStream().flush();
                  socket.getInputStream().read();
                }
              }
            });
    assertThat(handshakeFailure)
        .as("the REQUIRED client-certificate handshake must fail")
        .isNotNull();
    assertThat(TlsTestSupport.isTlsHandshakeRejection(handshakeFailure))
        .as("failure must identify certificate rejection")
        .isTrue();
  }

  private SSLContext trustOnlyClientContext(TestPki pki) throws Exception {
    KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null);
    trustStore.setCertificateEntry("account-membership-test-ca", pki.caCertificate());
    TrustManagerFactory trustManagers =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trustStore);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(new KeyManager[0], trustManagers.getTrustManagers(), null);
    return context;
  }

  private TestPki newTestPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair caKeyPair = newRsaKeyPair();
    X500Name caName = new X500Name("CN=Account Membership Integration Test CA, O=FireMUD Test");
    X509Certificate caCertificate =
        issueCertificate(caName, caKeyPair.getPublic(), caName, caKeyPair.getPrivate(), true, null);
    return new TestPki(
        caCertificate,
        issueLeaf(caName, caKeyPair.getPrivate(), "account-service", ACCOUNT_SERVER_URI),
        issueLeaf(caName, caKeyPair.getPrivate(), "game-session-service", GAME_SESSION_CLIENT_URI),
        issueLeaf(
            caName,
            caKeyPair.getPrivate(),
            "world-management-service",
            "spiffe://firemud/ns/" + TEST_NAMESPACE + "/sa/world-management-service"),
        issueLeaf(
            caName,
            caKeyPair.getPrivate(),
            "game-session-service-wrong-namespace",
            "spiffe://firemud/ns/other/sa/game-session-service"));
  }

  private static TestCertificate issueLeaf(
      X500Name caName, PrivateKey caPrivateKey, String commonName, String workloadUri)
      throws Exception {
    KeyPair keyPair = newRsaKeyPair();
    X500Name subject = new X500Name("CN=" + commonName + ", O=FireMUD Test");
    GeneralNames subjectAltNames =
        new GeneralNames(
            new GeneralName[] {
              new GeneralName(GeneralName.uniformResourceIdentifier, workloadUri),
              new GeneralName(GeneralName.dNSName, "localhost"),
              new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            });
    X509Certificate certificate =
        issueCertificate(
            subject, keyPair.getPublic(), caName, caPrivateKey, false, subjectAltNames);
    return new TestCertificate(keyPair.getPrivate(), certificate);
  }

  private static X509Certificate issueCertificate(
      X500Name subject,
      java.security.PublicKey publicKey,
      X500Name issuer,
      PrivateKey issuerPrivateKey,
      boolean ca,
      GeneralNames subjectAltNames)
      throws Exception {
    Instant notBefore = Instant.now().minusSeconds(60);
    JcaX509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(CERTIFICATE_SERIAL.getAndIncrement()),
            Date.from(notBefore),
            Date.from(notBefore.plusSeconds(60L * 60L * 24L * 14L)),
            subject,
            publicKey);
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
    if (ca) {
      builder.addExtension(
          Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
    } else {
      builder.addExtension(
          Extension.keyUsage,
          true,
          new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
      builder.addExtension(
          Extension.extendedKeyUsage,
          false,
          new ExtendedKeyUsage(
              new KeyPurposeId[] {KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
      builder.addExtension(Extension.subjectAlternativeName, false, subjectAltNames);
    }
    return new JcaX509CertificateConverter()
        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        .getCertificate(
            builder.build(
                new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(issuerPrivateKey)));
  }

  private static KeyPair newRsaKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private void stopPhysicalTransport(Server server, ManagedChannel... channels)
      throws InterruptedException {
    for (ManagedChannel channel : channels) {
      channel.shutdownNow();
      assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
    server.shutdownNow();
    assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
  }

  private PlayerExecutionContext runtimePlayerContext(JoinFixture fixture) {
    return PlayerExecutionContext.newBuilder()
        .setAccountId(fixture.account().accountUuid().toString())
        .setTenantId(fixture.tenantUuid().toString())
        .setRealmId(UUID.randomUUID().toString())
        .setPlayableStateNamespaceId(UUID.randomUUID().toString())
        .setPlayableStateScope("SHARED")
        .setGameInstanceId(UUID.randomUUID().toString())
        .setSessionId("9001")
        .setRequestId(UUID.randomUUID().toString())
        .build();
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

  private JoinFixture committedFreshJoinFixture() {
    JoinFixture fixture = fixture(true);
    persistEvidence(fixture, EvidenceShape.COMPLETE);
    recordLaterUnavailableAttempt(fixture);
    reconciliationService.reconcileDueOperations(reconciliationTime(fixture));
    assertThat(readOperation(fixture).status()).isEqualTo("COMMITTED");
    return fixture;
  }

  private void introduceContradiction(JoinFixture fixture, Contradiction contradiction) {
    switch (contradiction) {
      case LATEST_RECEIPT ->
          inTransactionWithoutResult(
              () ->
                  receipts.appendCanonicalTransition(
                      fixture.account().accountUuid(),
                      fixture.tenantUuid(),
                      "MEMBERSHIP_JOINED",
                      UUID.randomUUID().toString()));
      case LATEST_EVENT ->
          inTransactionWithoutResult(
              () -> {
                String stream = eventStreamKey(fixture);
                Event current =
                    authorityOutbox
                        .readCheckpoint(stream)
                        .flatMap(
                            checkpoint ->
                                authorityOutbox.findEvent(stream, checkpoint.outboxSequence()))
                        .orElseThrow();
                authorityOutbox.append(
                    stream,
                    "unmatched-current-event-" + UUID.randomUUID(),
                    ignoredSequence ->
                        new EventEvidence(
                            UUID.randomUUID().toString(),
                            "sha256:" + "0".repeat(64),
                            current.payload()));
              });
      case CURRENT_ROLES ->
          inTransactionWithoutResult(
              () -> {
                AccountTenantMembership membership =
                    memberships
                        .findCanonicalMembershipForUpdate(
                            fixture.account().accountUuid(), fixture.tenantUuid())
                        .orElseThrow();
                roleSnapshots.replaceCanonical(
                    membership,
                    fixture.account().accountUuid(),
                    fixture.tenantUuid(),
                    fixture.provenance(),
                    membership.getMembershipVersion(),
                    List.of("player", "moderator"));
              });
      case PAIR ->
          dsl.execute(
              "UPDATE account_membership_pair_authority SET membership_version = membership_version + 1, "
                  + "last_event_sequence = last_event_sequence + 1, last_event_id = ?, "
                  + "last_event_digest = ? WHERE account_uuid = ? AND tenant_uuid = ?",
              "unmatched-pair-event-" + UUID.randomUUID(),
              "sha256:" + "1".repeat(64),
              fixture.account().accountUuid(),
              fixture.tenantUuid());
      case AUDIT -> {
        String contradictoryPayload =
            AccountAuditOutboxRepository.canonicalJoinPayload(
                UUID.randomUUID(),
                fixture.tenantUuid(),
                fixture.scope().worldSlug(),
                fixture.scope().realmSlug(),
                Map.of(fixture.tenantUuid().toString(), "2"),
                fixture.requestId());
        dsl.execute(
            "UPDATE account_audit_outbox SET payload = ?, payload_digest = ? WHERE audit_event_id = ?",
            contradictoryPayload,
            AccountAuditDigest.ofPayload(contradictoryPayload),
            auditEventId(fixture.requestId()));
      }
    }
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
                    "canon-rec-" + suffix,
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

  private PairAuthority pairAuthority(JoinFixture fixture) {
    return inTransaction(
        () ->
            pairAuthorities
                .readForUpdate(fixture.account().accountUuid(), fixture.tenantUuid())
                .orElseThrow());
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

  private String eventStreamKey(String accountUuid, String tenantUuid) {
    return "account:auth-authority:v1:membership/" + accountUuid + "/" + tenantUuid;
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

  private record TestCertificate(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestCertificate accountServer,
      TestCertificate gameSessionClient,
      TestCertificate wrongWorkloadClient,
      TestCertificate wrongNamespaceGameSessionClient) {}

  private enum Contradiction {
    LATEST_RECEIPT,
    LATEST_EVENT,
    CURRENT_ROLES,
    PAIR,
    AUDIT
  }

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
