package crossservice.net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.accountservice.AccountServiceApplication;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.AccountLogoutRequestDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinScope;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionResult;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountCanonicalJoinReconciliationService;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountMembershipLifecycleService;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.AccountTenantAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.impl.AccountMembershipAuthorityGrpcService;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
import net.firedevops.firemud.gamesession.client.AccountMembershipAuthorityClient;
import net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.v1.GameplayRealm;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * Real Account producer and PostgreSQL readback composed with the physical Game Session mTLS
 * client.
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
      "firemud.grpc.workload-namespace=test"
    })
class AccountMembershipAuthorityGrpcPostgresMtlsCrossServiceTest {
  private static final String NAMESPACE = "test";
  private static final String WORLD_SLUG = "membership-mtls-proof-world";
  private static final String REALM_SLUG = "production";
  private static final String PLAYABLE_STATE_NAMESPACE_ID = "3fb3292a-8737-4d4f-bd24-90419d325747";
  private static final UUID REALM_ID = UUID.fromString("7bda1169-a8a3-4b43-96a4-53f8579ac164");
  private static final UUID GAME_INSTANCE_CONTEXT_ID =
      UUID.fromString("2c5f6201-68ba-4d4f-afb0-928a925830ec");
  private static final long RETAINED_JOIN_GAME_INSTANCE_ID = 79L;
  private static final long CATALOG_REVISION = 31L;
  private static final long POINTER_VERSION = 13L;
  private static final String AUTHORITY_STREAM_PREFIX = "account:auth-authority:v1:";
  private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ISO_INSTANT;
  private static final int CANONICAL_JOIN_MAX_ATTEMPTS = 12;

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
  @Autowired private AccountMembershipAuthorityEventProducer membershipAuthorityEventProducer;
  @Autowired private AccountAuthorityGenerationRepository authorityGenerationRepository;
  @Autowired private AccountAuthorityOutboxRepository authorityOutboxRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private AccountMembershipLifecycleService membershipLifecycleService;
  @Autowired private AccountLogoutAllOperationRepository logoutAllOperationRepository;
  @Autowired private AccountPasswordResetOperationRepository passwordResetOperationRepository;
  @Autowired private AccountAuditOutboxRepository auditOutboxRepository;
  @Autowired private AccountConnectScopeRepository connectScopeRepository;
  @Autowired private AccountJoinOperationRepository joinOperationRepository;
  @Autowired private AccountMembershipPairAuthorityRepository membershipPairAuthorityRepository;
  @Autowired private AccountMembershipTransitionReceiptRepository transitionReceiptRepository;
  @Autowired private AccountTenantMembershipRepository tenantMembershipRepository;
  @Autowired private AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository;
  @Autowired private FreshTenantIdentityAssociationRepository freshTenantAssociations;
  @Autowired private AccountCanonicalJoinReconciliationService canonicalJoinReconciliationService;
  @Autowired private ApprovedLegacyTenantAssociationRepository tenantAssociationRepository;
  @Autowired private LegacyTenantSourceEvidence legacyTenantSourceEvidence;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private LoggingAdminClient loggingAdminClient;
  @MockitoBean private JavaMailSender mailSender;

  @TempDir private Path tempDir;

  private final Map<String, Path> copiedCertificates = new HashMap<>();

  @Test
  void committedFreshUuidCanonicalJoinSnapshotRoundTripsThroughGameSessionClientOverPostgresMtls()
      throws Exception {
    // This composes Account's committed V2 JOIN owner readback with the actual Game Session
    // consumer over the physical socket. Caller/policy evidence is synthetic: this proves neither
    // upstream caller/policy authentication, registry authorization, nor gameplay admission, and
    // it does not register or enable the runtime RPC.
    FreshCanonicalJoinFixture fixture = committedFreshCanonicalJoinFixture();
    CanonicalJoinOwnerState before = readCanonicalJoinOwnerState(fixture);
    RuntimeMembershipSnapshotDto expected = before.snapshot();
    assertThat(before.operation().status()).isEqualTo("COMMITTED");
    assertThat(before.operation().outcome()).isEqualTo("JOINED");
    assertThat(before.operation().requestId()).isEqualTo(fixture.requestId());
    assertThat(before.operation().scopeEvidence().accountUuid()).isEqualTo(fixture.accountUuid());
    assertThat(before.operation().scopeEvidence().tenantUuid()).isEqualTo(fixture.tenantUuid());
    assertThat(before.operation().scopeEvidence().realmId()).isEqualTo(fixture.scope().realmId());
    assertThat(before.operation().scopeEvidence().catalogRevision())
        .isEqualTo(fixture.scope().catalogRevision());
    assertThat(before.operation().scopeEvidence().pointerVersion())
        .isEqualTo(fixture.scope().pointerVersion());
    assertThat(before.operation().scopeEvidence().tenantProvenance())
        .isEqualTo(fixture.provenance());
    assertThat(before.freshTenantSource()).isEqualTo(fixture.creationEvidence());
    assertThat(before.freshTenantSource().canonicalTenantId()).isEqualTo(fixture.tenantUuid());
    assertThat(before.pairAuthority().provenance()).isEqualTo(fixture.provenance());
    assertThat(expected.membershipExists()).isTrue();
    assertThat(expected.gameplayAdmissionAllowed()).isTrue();
    assertThat(expected.membershipBaseline().membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(expected.membershipBaseline().membershipVersion())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "2"));
    assertThat(expected.membershipBaseline().membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(expected.roles()).containsExactly("player");
    assertThat(expected.authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(expected.authorityTuple().membershipAuthorityGeneration())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(expected.outboxCheckpoints()).hasSize(4);
    assertThat(expected.outboxCheckpoints())
        .anySatisfy(
            checkpoint -> {
              assertThat(checkpoint.outboxStreamKey())
                  .isEqualTo(membershipStreamKey(fixture.accountUuid(), fixture.tenantUuid()));
              assertThat(checkpoint.outboxSequence()).isEqualTo("1");
            });

    Server server = startServer();
    try (AccountMembershipAuthorityClient client = newClient(server.getPort(), "game-session")) {
      client.init();

      PlayerExecutionContext originalContext = freshPlayerContext(fixture, fixture.requestId());
      GetTenantMembershipForRuntimeResponse originalResponse =
          client.getTenantMembershipForRuntime(originalContext);
      assertResponseMatchesSnapshot(originalResponse, originalContext, expected);
      assertFreshCanonicalJoinMembershipEvent(originalResponse, fixture, expected);
      RuntimeMembershipSnapshotDto repeatedSnapshot = readRuntimeMembershipSnapshot(fixture);
      assertSameCurrentMembershipSnapshot(expected, repeatedSnapshot);
      GetTenantMembershipForRuntimeResponse repeatedResponse =
          client.getTenantMembershipForRuntime(originalContext);
      assertResponseMatchesSnapshot(repeatedResponse, originalContext, repeatedSnapshot);
      assertThat(stableCarrier(repeatedResponse)).isEqualTo(stableCarrier(originalResponse));

      // A distinct caller request ID is not allowed to inherit the first response's correlation.
      PlayerExecutionContext changedRequestContext =
          originalContext.toBuilder().setRequestId(UUID.randomUUID().toString()).build();
      GetTenantMembershipForRuntimeResponse changedRequestResponse =
          client.getTenantMembershipForRuntime(changedRequestContext);
      assertResponseMatchesSnapshot(
          changedRequestResponse, changedRequestContext, repeatedSnapshot);
      assertThat(changedRequestResponse.getRequestId())
          .isEqualTo(changedRequestContext.getRequestId())
          .isNotEqualTo(originalContext.getRequestId());
      assertThat(stableCarrier(changedRequestResponse)).isEqualTo(stableCarrier(originalResponse));
      assertFreshCanonicalJoinMembershipEvent(changedRequestResponse, fixture, repeatedSnapshot);

      // The Account snapshot is selected by the exact canonical Account/tenant pair. An unknown
      // fresh tenant cannot be treated as this JOIN or cause an absence baseline to be created.
      UUID changedTenantUuid = UUID.randomUUID();
      PlayerExecutionContext changedTargetContext =
          originalContext.toBuilder()
              .setTenantId(changedTenantUuid.toString())
              .setRequestId(UUID.randomUUID().toString())
              .build();
      assertRemoteFailure(
          () -> client.getTenantMembershipForRuntime(changedTargetContext),
          Status.Code.FAILED_PRECONDITION);
      assertThat(countMembershipPairRows(fixture.accountUuid(), changedTenantUuid)).isZero();
      assertThat(countMembershipGenerationRows(fixture.accountUuid(), changedTenantUuid)).isZero();
      assertThat(countCanonicalMembershipRows(fixture.accountId(), changedTenantUuid)).isZero();
      assertThat(countStreamEvents(membershipStreamKey(fixture.accountUuid(), changedTenantUuid)))
          .isZero();
      assertCanonicalJoinOwnerEvidenceUnchanged(fixture, before);
    } finally {
      stop(server);
    }
  }

  @Test
  void realMembershipSnapshotAndSequenceZeroBaselineCrossPostgresAndAuthenticatedSocket()
      throws Exception {
    // Account JOIN creates the committed fixture source; this test does not issue auth credentials
    // or invoke Game Session gameplay admission through the membership read.
    JoinFixture joined = fixture();
    JoinPublicProductionResult joinResult = join(joined);
    assertThat(joinResult.success()).isTrue();

    RuntimeMembershipSnapshotDto beforeCutoff = readRuntimeMembershipSnapshot(joined);
    var immutableMembershipEvent = beforeCutoff.sourceEvent();
    byte[] immutableMembershipBytes = immutableMembershipEvent.canonicalJsonUtf8();
    assertThat(beforeCutoff.membershipExists()).isTrue();
    assertThat(beforeCutoff.gameplayAdmissionAllowed()).isTrue();
    assertThat(beforeCutoff.issuanceFence()).isEqualTo("1");
    assertThat(immutableMembershipEvent.issuanceFence()).isEqualTo("1");
    assertThat(immutableMembershipEvent.authorityTuple().issuerAuthGeneration()).isEqualTo("1");
    assertThat(immutableMembershipEvent.authorityTuple().accountAuthorityGeneration())
        .isEqualTo("1");
    assertThat(immutableMembershipEvent.authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(Map.of(joined.tenantUuid().toString(), "1"));

    Server server = startServer();
    try (AccountMembershipAuthorityClient client = newClient(server.getPort(), "game-session")) {
      client.init();

      PlayerExecutionContext initialContext =
          playerContext(joined, joined.tenantUuid(), "membership-read-before-cutoff");
      GetTenantMembershipForRuntimeResponse initialRead =
          client.getTenantMembershipForRuntime(initialContext);
      assertResponseMatchesSnapshot(initialRead, initialContext, beforeCutoff);
      assertMembershipEvidence(
          initialRead, joined, immutableMembershipEvent, immutableMembershipBytes);
      assertThat(initialRead.getOutboxSourceEvidenceCount()).isEqualTo(1);
      assertThat(initialRead.getAuthorityTuple().getIssuerAuthGeneration()).isEqualTo("1");
      assertThat(initialRead.getAuthorityTuple().getAccountAuthorityGeneration()).isEqualTo("1");
      assertThat(initialRead.getAuthorityTuple().getTenantAuthorityGenerationMap())
          .isEqualTo(Map.of(joined.tenantUuid().toString(), "1"));
      assertThat(initialRead.getAuthorityTuple().getMembershipAuthorityGenerationMap())
          .isEqualTo(Map.of(joined.tenantUuid().toString(), "1"));

      UUID issuerRequestId = UUID.randomUUID();
      UUID tenantRequestId = UUID.randomUUID();
      UUID logoutRequestId = UUID.randomUUID();
      String presentedTokenHash = "b".repeat(64);
      String tokenProfile = "control-ui";
      String requestDigest =
          AccountLogoutRequestDigest.accountLogoutAll(
              joined.accountUuid(), tokenProfile, presentedTokenHash);
      AccountIssuerAuthorityEventProducer issuerProducer = issuerProducer();
      AccountTenantAuthorityEventProducer tenantProducer = tenantProducer();
      AccountLogoutAllAuthorityEventProducer logoutProducer = logoutProducer();
      var issuerEvent =
          issuerProducer.advance(AccountServiceImpl.ACCOUNT_JWT_ISSUER, issuerRequestId, 1L, 1L);
      var tenantEvent = tenantProducer.advance(joined.tenantUuid(), tenantRequestId, 1L, 1L);
      ScopeState preLogoutAccountState = readAccountAuthority(joined.accountUuid());
      Account account = readAccount(joined.accountId());
      assertThat(
              logoutProducer.commit(
                  logoutRequestId,
                  1,
                  requestDigest,
                  tokenProfile,
                  presentedTokenHash,
                  account,
                  preLogoutAccountState))
          .isEqualTo(AccountLogoutAllAuthorityEventProducer.LogoutAllResult.LOGOUT_ALL_COMMITTED);

      RuntimeMembershipSnapshotDto afterCutoff = readRuntimeMembershipSnapshot(joined);
      assertThat(afterCutoff.membershipBaseline()).isEqualTo(beforeCutoff.membershipBaseline());
      assertThat(afterCutoff.roles()).isEqualTo(beforeCutoff.roles());
      assertThat(afterCutoff.authorityTuple().issuerAuthGeneration()).isEqualTo("2");
      assertThat(afterCutoff.authorityTuple().accountAuthorityGeneration()).isEqualTo("2");
      assertThat(afterCutoff.authorityTuple().tenantAuthorityGeneration())
          .isEqualTo(Map.of(joined.tenantUuid().toString(), "2"));
      assertThat(afterCutoff.authorityTuple().membershipAuthorityGeneration())
          .isEqualTo(Map.of(joined.tenantUuid().toString(), "1"));
      assertThat(afterCutoff.issuanceFence()).isEqualTo("2");
      assertThat(afterCutoff.authorityTuple().accountSecurityCutoff())
          .hasValueSatisfying(
              cutoff -> {
                assertThat(cutoff.accountAuthorityGeneration()).isEqualTo("2");
                assertThat(cutoff.outboxStreamKey())
                    .isEqualTo(accountStreamKey(joined.accountUuid()));
                assertThat(cutoff.outboxSequence()).isEqualTo("1");
              });
      assertThat(afterCutoff.outboxCheckpoints())
          .containsExactly(
              new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                  accountStreamKey(joined.accountUuid()), "1"),
              new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                  issuerStreamKey(), "1"),
              new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                  membershipStreamKey(joined), "1"),
              new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                  tenantStreamKey(joined.tenantUuid()), "1"));
      assertThat(afterCutoff.outboxSourceEvidence())
          .extracting(AccountMembershipAuthorityEventProducer.OutboxSourceEvidence::outboxStreamKey)
          .containsExactly(
              accountStreamKey(joined.accountUuid()),
              issuerStreamKey(),
              membershipStreamKey(joined),
              tenantStreamKey(joined.tenantUuid()));

      PlayerExecutionContext currentContext =
          playerContext(joined, joined.tenantUuid(), "membership-read-after-cutoff");
      GetTenantMembershipForRuntimeResponse currentRead =
          client.getTenantMembershipForRuntime(currentContext);
      assertResponseMatchesSnapshot(currentRead, currentContext, afterCutoff);
      assertMembershipEvidence(
          currentRead, joined, immutableMembershipEvent, immutableMembershipBytes);
      assertThat(currentRead.getAuthorityTuple().getIssuerAuthGeneration()).isEqualTo("2");
      assertThat(currentRead.getAuthorityTuple().getAccountAuthorityGeneration()).isEqualTo("2");
      assertThat(currentRead.getAuthorityTuple().getTenantAuthorityGenerationMap())
          .isEqualTo(Map.of(joined.tenantUuid().toString(), "2"));
      assertThat(currentRead.getAuthorityTuple().getMembershipAuthorityGenerationMap())
          .isEqualTo(Map.of(joined.tenantUuid().toString(), "1"));
      assertThat(currentRead.getIssuanceFence()).isEqualTo("2");
      assertThat(currentRead.getAuthorityTuple().hasAccountSecurityCutoff()).isTrue();
      assertThat(
              currentRead
                  .getAuthorityTuple()
                  .getAccountSecurityCutoff()
                  .getAccountAuthorityGeneration())
          .isEqualTo("2");
      assertThat(currentRead.getAuthorityTuple().getAccountSecurityCutoff().getOutboxStreamKey())
          .isEqualTo(accountStreamKey(joined.accountUuid()));
      assertThat(currentRead.getAuthorityTuple().getAccountSecurityCutoff().getOutboxSequence())
          .isEqualTo("1");
      assertThat(currentRead.getOutboxCheckpointsList())
          .extracting(
              checkpoint -> checkpoint.getOutboxStreamKey() + "=" + checkpoint.getOutboxSequence())
          .containsExactly(
              accountStreamKey(joined.accountUuid()) + "=1",
              issuerStreamKey() + "=1",
              membershipStreamKey(joined) + "=1",
              tenantStreamKey(joined.tenantUuid()) + "=1");
      assertThat(currentRead.getOutboxSourceEvidenceCount()).isEqualTo(4);
      assertSourceEvidence(
          currentRead, issuerStreamKey(), issuerEvent.eventId(), issuerEvent.eventDigest());
      assertSourceEvidence(
          currentRead,
          tenantStreamKey(joined.tenantUuid()),
          tenantEvent.eventId(),
          tenantEvent.eventDigest());
      assertThat(currentRead.getOutboxSourceEvidenceList())
          .allSatisfy(
              evidence -> {
                assertThat(evidence.getEventId()).isNotBlank();
                assertThat(evidence.getEventDigest()).matches("sha256:[0-9a-f]{64}");
                assertThat(evidence.getCanonicalEventJson()).isNotBlank();
              });

      assertThat(
              issuerProducer
                  .advance(AccountServiceImpl.ACCOUNT_JWT_ISSUER, issuerRequestId, 1L, 1L)
                  .eventDigest())
          .isEqualTo(issuerEvent.eventDigest());
      assertThat(tenantProducer.advance(joined.tenantUuid(), tenantRequestId, 1L, 1L).eventDigest())
          .isEqualTo(tenantEvent.eventDigest());
      assertThat(
              logoutProducer.commit(
                  logoutRequestId,
                  1,
                  requestDigest,
                  tokenProfile,
                  presentedTokenHash,
                  account,
                  preLogoutAccountState))
          .isEqualTo(AccountLogoutAllAuthorityEventProducer.LogoutAllResult.LOGOUT_ALL_COMMITTED);

      RuntimeMembershipSnapshotDto retryReadback = readRuntimeMembershipSnapshot(joined);
      assertThat(retryReadback.authorityTuple()).isEqualTo(afterCutoff.authorityTuple());
      assertThat(retryReadback.issuanceFence()).isEqualTo(afterCutoff.issuanceFence());
      assertThat(retryReadback.outboxCheckpoints()).isEqualTo(afterCutoff.outboxCheckpoints());
      assertThat(retryReadback.outboxSourceEvidence())
          .isEqualTo(afterCutoff.outboxSourceEvidence());
      assertThat(retryReadback.sourceEvent().canonicalJsonUtf8())
          .containsExactly(immutableMembershipBytes);
      assertThat(membershipEventBytes(joined)).containsExactly(immutableMembershipBytes);
      PlayerExecutionContext retryContext =
          playerContext(joined, joined.tenantUuid(), "membership-read-retry");
      GetTenantMembershipForRuntimeResponse retryResponse =
          client.getTenantMembershipForRuntime(retryContext);
      assertResponseMatchesSnapshot(retryResponse, retryContext, retryReadback);
      assertMembershipEvidence(
          retryResponse, joined, immutableMembershipEvent, immutableMembershipBytes);
      assertThat(stableCarrier(retryResponse)).isEqualTo(stableCarrier(currentRead));

      JoinFixture neverJoined = fixture();
      UUID neverJoinedTenantRequestId = UUID.randomUUID();
      var neverJoinedTenantEvent =
          tenantProducer.advance(neverJoined.tenantUuid(), neverJoinedTenantRequestId, 1L, 1L);
      DenialReadback beforeUnauthorizedRead = denialReadback(neverJoined);
      assertThat(beforeUnauthorizedRead.membershipPairRows()).isZero();
      assertThat(beforeUnauthorizedRead.membershipGenerationRows()).isZero();
      assertThat(beforeUnauthorizedRead.membershipRows()).isZero();
      assertThat(beforeUnauthorizedRead.membershipEvents()).isZero();

      for (String wrongPeer : List.of("wrong-service", "wrong-namespace")) {
        try (AccountMembershipAuthorityClient wrongPeerClient =
            newClient(server.getPort(), wrongPeer)) {
          wrongPeerClient.init();
          PlayerExecutionContext deniedContext =
              playerContext(neverJoined, neverJoined.tenantUuid(), "membership-read-wrong-peer");
          assertRemoteFailure(
              () -> wrongPeerClient.getTenantMembershipForRuntime(deniedContext),
              Status.Code.PERMISSION_DENIED);
        }
        assertThat(denialReadback(neverJoined)).isEqualTo(beforeUnauthorizedRead);
      }

      PlayerExecutionContext absentContext =
          playerContext(neverJoined, neverJoined.tenantUuid(), "membership-read-never-joined");
      GetTenantMembershipForRuntimeResponse absentRead =
          client.getTenantMembershipForRuntime(absentContext);
      RuntimeMembershipSnapshotDto absentSnapshot = readRuntimeMembershipSnapshot(neverJoined);
      assertResponseMatchesSnapshot(absentRead, absentContext, absentSnapshot);
      assertThat(absentRead.getMembershipExists()).isFalse();
      assertThat(absentRead.getGameplayAdmissionAllowed()).isFalse();
      assertThat(absentRead.getMembershipLifecycleState()).isEqualTo("MISSING");
      assertThat(absentRead.getMembershipVersionMap())
          .isEqualTo(Map.of(neverJoined.tenantUuid().toString(), "1"));
      assertThat(absentRead.getMembershipAuthorityGeneration()).isEqualTo("1");
      assertThat(absentRead.getMembershipBaseline().getMembershipLifecycleState())
          .isEqualTo("MISSING");
      assertThat(absentRead.getRolesList()).isEmpty();
      assertThat(absentRead.getIssuanceFence()).isEqualTo("1");
      assertThat(absentRead.getAuthorityTuple().getIssuerAuthGeneration()).isEqualTo("2");
      assertThat(absentRead.getAuthorityTuple().getAccountAuthorityGeneration()).isEqualTo("1");
      assertThat(absentRead.getAuthorityTuple().getTenantAuthorityGenerationMap())
          .isEqualTo(Map.of(neverJoined.tenantUuid().toString(), "2"));
      assertThat(absentRead.getAuthorityTuple().getMembershipAuthorityGenerationMap())
          .isEqualTo(Map.of(neverJoined.tenantUuid().toString(), "1"));
      assertThat(absentRead.getOutboxCheckpointsList())
          .extracting(
              checkpoint -> checkpoint.getOutboxStreamKey() + "=" + checkpoint.getOutboxSequence())
          .containsExactly(
              accountStreamKey(neverJoined.accountUuid()) + "=0",
              issuerStreamKey() + "=1",
              membershipStreamKey(neverJoined) + "=0",
              tenantStreamKey(neverJoined.tenantUuid()) + "=1");
      assertThat(absentRead.getOutboxSourceEvidenceList())
          .extracting(evidence -> evidence.getOutboxStreamKey())
          .containsExactly(issuerStreamKey(), tenantStreamKey(neverJoined.tenantUuid()));
      assertThat(absentRead.getOutboxSourceEvidenceList().get(1).getEventId())
          .isEqualTo(neverJoinedTenantEvent.eventId());
      assertThat(absentRead.getOutboxSourceEvidenceList().get(1).getEventDigest())
          .isEqualTo(neverJoinedTenantEvent.eventDigest());
      assertThat(absentRead.getOutboxSourceEvidenceList())
          .noneMatch(
              evidence -> membershipStreamKey(neverJoined).equals(evidence.getOutboxStreamKey()));
      assertDurableSequenceZeroPairBaseline(neverJoined);
      assertThat(countMembershipRows(neverJoined)).isZero();
      assertThat(countStreamEvents(membershipStreamKey(neverJoined))).isZero();
      GetTenantMembershipForRuntimeResponse absentReadback =
          client.getTenantMembershipForRuntime(
              playerContext(
                  neverJoined, neverJoined.tenantUuid(), "membership-read-absence-retry"));
      assertThat(stableCarrier(absentReadback)).isEqualTo(stableCarrier(absentRead));
      assertThat(denialReadback(neverJoined).membershipPairRows()).isEqualTo(1L);

      UUID unmappedTenantUuid = UUID.randomUUID();
      DenialReadback beforeUnmappedRead = denialReadback(neverJoined);
      PlayerExecutionContext unmappedContext =
          playerContext(neverJoined, unmappedTenantUuid, "membership-read-unmapped-tenant");
      assertRemoteFailure(
          () -> client.getTenantMembershipForRuntime(unmappedContext),
          Status.Code.FAILED_PRECONDITION);
      assertThat(denialReadback(neverJoined)).isEqualTo(beforeUnmappedRead);
      assertThat(countMembershipPairRows(neverJoined.accountUuid(), unmappedTenantUuid)).isZero();
      assertThat(countMembershipGenerationRows(neverJoined.accountUuid(), unmappedTenantUuid))
          .isZero();
      assertThat(
              countStreamEvents(membershipStreamKey(neverJoined.accountUuid(), unmappedTenantUuid)))
          .isZero();
    } finally {
      stop(server);
    }
  }

  @Test
  void retainedInactiveLeftAndReactivationSnapshotsCrossPostgresAndAuthenticatedSocket()
      throws Exception {
    // JOIN/LEFT here create Account-owned source state only. This transport read is not gameplay
    // admission, does not mint credentials, and does not establish a Game Session binding.
    JoinFixture joined = fixture();
    JoinPublicProductionResult joinResult = join(joined);
    assertThat(joinResult.success()).isTrue();
    assertThat(joinResult.outcomeCode()).isEqualTo("JOINED");

    RuntimeMembershipSnapshotDto beforeLeft = readRuntimeMembershipSnapshot(joined);
    assertThat(beforeLeft.membershipBaseline().membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(beforeLeft.membershipBaseline().membershipVersion())
        .isEqualTo(Map.of(joined.tenantUuid().toString(), "2"));
    assertThat(beforeLeft.membershipBaseline().membershipAuthorityGeneration()).isEqualTo("1");
    byte[] originalJoinEventBytes = membershipEventBytes(joined, 1L);
    assertThat(beforeLeft.sourceEvent().canonicalJsonUtf8())
        .containsExactly(originalJoinEventBytes);

    Server server = startServer();
    try (AccountMembershipAuthorityClient client = newClient(server.getPort(), "game-session")) {
      client.init();

      PlayerExecutionContext beforeLeftContext =
          playerContext(joined, joined.tenantUuid(), "membership-left-before-transition");
      GetTenantMembershipForRuntimeResponse beforeLeftResponse =
          client.getTenantMembershipForRuntime(beforeLeftContext);
      assertResponseMatchesSnapshot(beforeLeftResponse, beforeLeftContext, beforeLeft);
      assertCurrentMembershipEvent(beforeLeftResponse, joined, beforeLeft, "1", "ACTIVE");
      assertThat(beforeLeftResponse.getGameplayAdmissionAllowed()).isTrue();

      String leftRequestId = "membership-mtls-left-" + UUID.randomUUID();
      MembershipTransitionReceipt left =
          membershipLifecycleService.leave(
              joined.accountId(), joined.legacyTenantId(), leftRequestId);
      assertThat(left.transitionType()).isEqualTo("MEMBERSHIP_LEFT");
      assertThat(left.requestId()).isEqualTo(leftRequestId);
      assertThat(left.receiptSequence()).isEqualTo(2L);
      assertThat(left.membershipId()).isEqualTo(joinResult.membershipId());

      MembershipPersistenceSnapshot afterLeftCommit = membershipPersistenceSnapshot(joined);
      RuntimeMembershipSnapshotDto inactive = readRuntimeMembershipSnapshot(joined);
      PlayerExecutionContext inactiveContext =
          playerContext(joined, joined.tenantUuid(), "membership-left-current-read");
      GetTenantMembershipForRuntimeResponse inactiveResponse =
          client.getTenantMembershipForRuntime(inactiveContext);
      assertResponseMatchesSnapshot(inactiveResponse, inactiveContext, inactive);
      assertThat(inactive.membershipExists()).isTrue();
      assertThat(inactive.gameplayAdmissionAllowed()).isFalse();
      assertThat(inactive.membershipBaseline().membershipLifecycleState()).isEqualTo("INACTIVE");
      assertThat(inactive.membershipBaseline().membershipVersion())
          .isEqualTo(Map.of(joined.tenantUuid().toString(), "3"));
      assertThat(inactive.membershipBaseline().membershipAuthorityGeneration()).isEqualTo("2");
      assertThat(inactive.roles()).containsExactly("player");
      assertThat(inactive.issuanceFence())
          .isEqualTo(Long.toString(Long.parseLong(beforeLeft.issuanceFence()) + 1L));
      assertThat(inactive.authorityTuple().issuerAuthGeneration())
          .isEqualTo(beforeLeft.authorityTuple().issuerAuthGeneration());
      assertThat(inactive.authorityTuple().accountAuthorityGeneration())
          .isEqualTo(beforeLeft.authorityTuple().accountAuthorityGeneration());
      assertThat(inactive.authorityTuple().tenantAuthorityGeneration())
          .isEqualTo(beforeLeft.authorityTuple().tenantAuthorityGeneration());
      assertThat(inactive.authorityTuple().membershipAuthorityGeneration())
          .isEqualTo(Map.of(joined.tenantUuid().toString(), "2"));
      assertCurrentMembershipEvent(inactiveResponse, joined, inactive, "2", "INACTIVE");
      assertNonMembershipAuthorityEvidenceUnchanged(beforeLeft, inactive, joined);
      assertThat(inactive.sourceEvent().requestId()).isEqualTo(leftRequestId);
      assertThat(inactive.sourceEvent().callerBoundAuthorityInvalidated()).isTrue();
      assertMembershipCheckpoint(beforeLeft, inactive, joined, "2");
      assertThat(inactiveResponse.getGameplayAdmissionAllowed()).isFalse();
      assertThat(afterLeftCommit.membershipRow())
          .containsEntry("lifecycle_state", "INACTIVE")
          .containsEntry("gameplay_admission_allowed", false)
          .containsEntry("membership_version", 3L)
          .containsEntry("membership_authority_generation", 2L)
          .containsEntry("authority_provenance", "EXPLICIT_JOIN");
      assertThat(afterLeftCommit.pairAuthorityRow())
          .containsEntry("membership_exists", true)
          .containsEntry("membership_version", 3L)
          .containsEntry("membership_authority_generation", 2L)
          .containsEntry("last_event_sequence", 2L)
          .containsEntry("last_event_id", inactive.sourceEvent().eventId())
          .containsEntry("last_event_digest", inactive.sourceEvent().eventDigest())
          .containsEntry("last_transition_invalidated", true);
      assertThat(afterLeftCommit.membershipAuthorityState().generation()).isEqualTo(2L);
      assertThat(afterLeftCommit.issuanceFence()).isEqualTo(2L);
      assertThat(afterLeftCommit.authorityEvents()).hasSize(2);
      assertThat(
              afterLeftCommit
                  .authorityEvents()
                  .get(0)
                  .canonicalJson()
                  .getBytes(StandardCharsets.UTF_8))
          .containsExactly(originalJoinEventBytes);
      byte[] leftEventBytes = membershipEventBytes(joined, 2L);
      assertThat(inactive.sourceEvent().canonicalJsonUtf8()).containsExactly(leftEventBytes);
      assertThat(afterLeftCommit.authorityEvents().get(1).requestId()).isEqualTo(leftRequestId);
      assertThat(
              afterLeftCommit
                  .authorityEvents()
                  .get(1)
                  .canonicalJson()
                  .getBytes(StandardCharsets.UTF_8))
          .containsExactly(leftEventBytes);
      assertThat(afterLeftCommit.transitionReceiptCount()).isEqualTo(2L);
      assertThat(afterLeftCommit.leftAuditCount()).isEqualTo(1L);

      // Exact LEFT retry is a receipt read, not a new lifecycle mutation.
      assertThat(
              membershipLifecycleService.leave(
                  joined.accountId(), joined.legacyTenantId(), leftRequestId))
          .isEqualTo(left);
      assertThat(membershipPersistenceSnapshot(joined)).isEqualTo(afterLeftCommit);
      RuntimeMembershipSnapshotDto leftRetryReadback = readRuntimeMembershipSnapshot(joined);
      assertSameCurrentMembershipSnapshot(inactive, leftRetryReadback);
      PlayerExecutionContext leftRetryContext =
          playerContext(joined, joined.tenantUuid(), "membership-left-exact-retry-read");
      GetTenantMembershipForRuntimeResponse leftRetryResponse =
          client.getTenantMembershipForRuntime(leftRetryContext);
      assertResponseMatchesSnapshot(leftRetryResponse, leftRetryContext, leftRetryReadback);
      assertThat(stableCarrier(leftRetryResponse)).isEqualTo(stableCarrier(inactiveResponse));

      JoinFixture reactivation = reactivationFixture(joined);
      JoinPublicProductionResult reactivated = join(reactivation);
      assertThat(reactivated.success()).isTrue();
      assertThat(reactivated.outcomeCode()).isEqualTo("JOINED");
      assertThat(reactivated.membershipId()).isEqualTo(joinResult.membershipId());
      assertThat(reactivated.membershipVersion()).isEqualTo(4L);
      assertThat(reactivated.membershipAuthorityGeneration()).isEqualTo(3L);

      MembershipPersistenceSnapshot afterReactivation = membershipPersistenceSnapshot(joined);
      RuntimeMembershipSnapshotDto active = readRuntimeMembershipSnapshot(joined);
      PlayerExecutionContext activeContext =
          playerContext(reactivation, reactivation.tenantUuid(), reactivation.requestId());
      GetTenantMembershipForRuntimeResponse activeResponse =
          client.getTenantMembershipForRuntime(activeContext);
      assertResponseMatchesSnapshot(activeResponse, activeContext, active);
      assertThat(active.membershipExists()).isTrue();
      assertThat(active.gameplayAdmissionAllowed()).isTrue();
      assertThat(active.membershipBaseline().membershipLifecycleState()).isEqualTo("ACTIVE");
      assertThat(active.membershipBaseline().membershipVersion())
          .isEqualTo(Map.of(joined.tenantUuid().toString(), "4"));
      assertThat(active.membershipBaseline().membershipAuthorityGeneration()).isEqualTo("3");
      assertThat(active.issuanceFence())
          .isEqualTo(Long.toString(Long.parseLong(beforeLeft.issuanceFence()) + 2L));
      assertThat(active.authorityTuple().membershipAuthorityGeneration())
          .isEqualTo(Map.of(joined.tenantUuid().toString(), "3"));
      assertCurrentMembershipEvent(activeResponse, joined, active, "3", "ACTIVE");
      assertNonMembershipAuthorityEvidenceUnchanged(beforeLeft, active, joined);
      assertThat(active.sourceEvent().requestId()).isEqualTo(reactivation.requestId());
      assertThat(active.sourceEvent().callerBoundAuthorityInvalidated()).isTrue();
      assertMembershipCheckpoint(beforeLeft, active, joined, "3");
      assertThat(afterReactivation.membershipRow())
          .containsEntry("lifecycle_state", "ACTIVE")
          .containsEntry("gameplay_admission_allowed", true)
          .containsEntry("membership_version", 4L)
          .containsEntry("membership_authority_generation", 3L)
          .containsEntry("authority_provenance", "EXPLICIT_JOIN");
      assertThat(afterReactivation.pairAuthorityRow())
          .containsEntry("membership_exists", true)
          .containsEntry("membership_version", 4L)
          .containsEntry("membership_authority_generation", 3L)
          .containsEntry("last_event_sequence", 3L)
          .containsEntry("last_event_id", active.sourceEvent().eventId())
          .containsEntry("last_event_digest", active.sourceEvent().eventDigest())
          .containsEntry("last_transition_invalidated", true);
      assertThat(afterReactivation.membershipAuthorityState().generation()).isEqualTo(3L);
      assertThat(afterReactivation.issuanceFence()).isEqualTo(3L);
      assertThat(afterReactivation.authorityEvents()).hasSize(3);
      assertThat(
              afterReactivation
                  .authorityEvents()
                  .get(0)
                  .canonicalJson()
                  .getBytes(StandardCharsets.UTF_8))
          .containsExactly(originalJoinEventBytes);
      assertThat(
              afterReactivation
                  .authorityEvents()
                  .get(1)
                  .canonicalJson()
                  .getBytes(StandardCharsets.UTF_8))
          .containsExactly(leftEventBytes);
      assertThat(afterReactivation.authorityEvents().get(2).requestId())
          .isEqualTo(reactivation.requestId());
      assertThat(
              afterReactivation
                  .authorityEvents()
                  .get(2)
                  .canonicalJson()
                  .getBytes(StandardCharsets.UTF_8))
          .containsExactly(active.sourceEvent().canonicalJsonUtf8());
      assertThat(afterReactivation.transitionReceiptCount()).isEqualTo(3L);
      assertThat(afterReactivation.leftAuditCount()).isEqualTo(1L);

      // A late replay returns the historical LEFT receipt while current ACTIVE evidence remains
      // byte-for-byte stable; the historical receipt cannot roll the current pair back to INACTIVE.
      assertThat(
              membershipLifecycleService.leave(
                  joined.accountId(), joined.legacyTenantId(), leftRequestId))
          .isEqualTo(left);
      assertThat(membershipPersistenceSnapshot(joined)).isEqualTo(afterReactivation);
      RuntimeMembershipSnapshotDto afterHistoricalRetry = readRuntimeMembershipSnapshot(joined);
      assertSameCurrentMembershipSnapshot(active, afterHistoricalRetry);
      PlayerExecutionContext lateRetryContext =
          playerContext(joined, joined.tenantUuid(), "membership-left-late-retry-read");
      GetTenantMembershipForRuntimeResponse lateRetryResponse =
          client.getTenantMembershipForRuntime(lateRetryContext);
      assertResponseMatchesSnapshot(lateRetryResponse, lateRetryContext, afterHistoricalRetry);
      assertThat(stableCarrier(lateRetryResponse)).isEqualTo(stableCarrier(activeResponse));
      assertThat(membershipEventBytes(joined, 1L)).containsExactly(originalJoinEventBytes);
      assertThat(membershipEventBytes(joined, 2L)).containsExactly(leftEventBytes);
    } finally {
      stop(server);
    }
  }

  private FreshCanonicalJoinFixture committedFreshCanonicalJoinFixture() {
    FreshCanonicalJoinFixture fixture = freshCanonicalJoinFixture();
    persistCanonicalJoinEvidence(fixture);
    Instant diagnosticAt = Instant.now();
    inTransactionWithoutResult(
        () -> {
          joinOperationRepository.recordCanonicalPolicyUnavailable(
              fixture.requestId(), fixture.scope(), fixture.callerBinding(), "ENTITLEMENT_TIMEOUT");
          joinOperationRepository.recordCanonicalReconciliationAttempt(
              fixture.requestId(),
              0,
              CANONICAL_JOIN_MAX_ATTEMPTS,
              diagnosticAt,
              "CANONICAL_JOIN_READBACK_UNAVAILABLE",
              diagnosticAt);
        });
    canonicalJoinReconciliationService.reconcileDueOperations(
        Instant.parse(fixture.scope().connectScopeExpiresAt()).plus(Duration.ofDays(1)));
    CanonicalJoinOperationEvidence operation =
        new TransactionTemplate(transactionManager)
            .execute(
                status ->
                    joinOperationRepository
                        .findCanonicalEvidenceByRequestId(fixture.requestId())
                        .orElseThrow());
    assertThat(operation.status()).isEqualTo("COMMITTED");
    assertThat(operation.outcome()).isEqualTo("JOINED");
    return fixture;
  }

  private FreshCanonicalJoinFixture freshCanonicalJoinFixture() {
    String suffix = UUID.randomUUID().toString();
    String shortSuffix = suffix.replace("-", "").substring(0, 12);
    long accountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                    "fresh-mtls-" + shortSuffix,
                    "fresh-membership-mtls-" + suffix + "@example.test",
                    "synthetic-fixture-hash")
                .fetchOne(0, Long.class));
    UUID accountUuid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, UUID.class));
    seedAccountAuthorityState(accountUuid);

    UUID tenantUuid = UUID.randomUUID();
    FreshTenantCreationEvidence creationEvidence = freshTenantCreationEvidence(tenantUuid);
    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            creationEvidence.operationId(),
            creationEvidence.evidenceDigest());
    inTransactionWithoutResult(
        () -> {
          freshTenantAssociations.importVerified(creationEvidence);
          authorityGenerationRepository.initializeTenantIfAbsent(tenantUuid);
          authorityGenerationRepository.initializeIssuerIfAbsent(
              AccountServiceImpl.ACCOUNT_JWT_ISSUER);
          membershipAuthorityEventProducer.readFreshNeverJoinedMembershipSnapshot(
              accountUuid, tenantUuid);
        });

    Instant evaluatedAt = Instant.now().minus(Duration.ofHours(1));
    Instant expiresAt = Instant.now().minus(Duration.ofMinutes(30));
    CanonicalJoinScopeV2 scope =
        new CanonicalJoinScopeV2(
            "fresh-mtls-join-" + UUID.randomUUID(),
            accountUuid,
            tenantUuid,
            UUID.randomUUID(),
            "fresh-tenant-" + shortSuffix,
            "fresh-world-" + shortSuffix,
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
    inTransactionWithoutResult(
        () -> {
          connectScopeRepository.insertCanonical(accountId, scope, provenance);
          joinOperationRepository.insertCanonicalIntent(requestId, scope, callerBinding);
          joinOperationRepository.bindCanonicalPolicyEvidence(
              requestId, scope, callerBinding, true, 11L);
        });
    return new FreshCanonicalJoinFixture(
        accountId,
        accountUuid,
        tenantUuid,
        provenance,
        creationEvidence,
        scope,
        requestId,
        callerBinding);
  }

  private FreshTenantCreationEvidence freshTenantCreationEvidence(UUID tenantUuid) {
    UUID creationRequestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String sourceTenantKey = "f-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            NAMESPACE, creationRequestId, sourceTenantKey, "Fresh membership mTLS fixture", null);
    long sourceGameRowId = positiveRandomLong();
    String provenanceKind = "NEW_GAME_ROW";
    return new FreshTenantCreationEvidence(
        1,
        NAMESPACE,
        creationRequestId,
        operationId,
        requestDigest,
        tenantUuid,
        sourceGameRowId,
        sourceTenantKey,
        provenanceKind,
        GameTenantCreationDigest.evidenceDigest(
            NAMESPACE,
            creationRequestId,
            operationId,
            requestDigest,
            tenantUuid,
            sourceGameRowId,
            sourceTenantKey,
            provenanceKind));
  }

  private void inTransactionWithoutResult(Runnable callback) {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> callback.run());
  }

  private void persistCanonicalJoinEvidence(FreshCanonicalJoinFixture fixture) {
    inTransactionWithoutResult(
        () -> {
          Account account =
              accountRepository.findByAccountUuid(fixture.accountUuid()).orElseThrow();
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
          tenantMembershipRepository.saveCanonical(
              membership, fixture.accountUuid(), fixture.tenantUuid(), fixture.provenance());
          roleSnapshotRepository.replaceCanonical(
              membership,
              fixture.accountUuid(),
              fixture.tenantUuid(),
              fixture.provenance(),
              2L,
              List.of("player"));
          membershipAuthorityEventProducer.publishCanonicalFirstJoinMembershipChange(
              fixture.scope(), fixture.requestId(), fixture.callerBinding());
          transitionReceiptRepository.appendCanonicalTransition(
              fixture.accountUuid(),
              fixture.tenantUuid(),
              "MEMBERSHIP_JOINED",
              fixture.requestId());
          String auditPayload =
              AccountAuditOutboxRepository.canonicalJoinPayload(
                  fixture.accountUuid(),
                  fixture.tenantUuid(),
                  fixture.scope().worldSlug(),
                  fixture.scope().realmSlug(),
                  Map.of(fixture.tenantUuid().toString(), "2"),
                  fixture.requestId());
          auditOutboxRepository.appendCanonicalTenant(
              canonicalJoinAuditEventId(fixture.requestId()),
              fixture.tenantUuid().toString(),
              "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
              auditPayload);
        });
  }

  private PlayerExecutionContext freshPlayerContext(
      FreshCanonicalJoinFixture fixture, String requestId) {
    return PlayerExecutionContext.newBuilder()
        .setAccountId(fixture.accountUuid().toString())
        .setTenantId(fixture.tenantUuid().toString())
        .setRealmId(fixture.scope().realmId().toString())
        .setPlayableStateNamespaceId(fixture.scope().playableStateNamespaceId().toString())
        .setPlayableStateScope(fixture.scope().playableStateScope())
        .setGameInstanceId(fixture.scope().gameInstanceId().toString())
        .setSessionId("9001")
        .setRequestId(requestId)
        .build();
  }

  private static UUID canonicalJoinAuditEventId(String requestId) {
    return UUID.nameUUIDFromBytes(
        ("account-join-audit/v1:" + requestId).getBytes(StandardCharsets.UTF_8));
  }

  private JoinFixture fixture() {
    String suffix = UUID.randomUUID().toString();
    String shortSuffix = suffix.replace("-", "").substring(0, 12);
    long accountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                    "amg-" + shortSuffix,
                    "membership-mtls-" + suffix + "@example.com",
                    "test-hash")
                .fetchOne(0, Long.class));
    UUID accountUuid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, UUID.class));
    seedAccountAuthorityState(accountUuid);

    // Numeric retained tenant and Game Instance selectors are private to this Account JOIN fixture.
    // The membership client sends the separately stored canonical tenant UUID and its own UUID
    // Game Instance selector; neither is inferred from a numeric key. The synthetic approved
    // association does not claim a real legacy association.
    long legacyTenantId = positiveRandomLong();
    seedRetainedV26TenantEvidence(legacyTenantId, suffix);
    dsl.execute(
        "INSERT INTO subscription (account_id, tenant_id, plan_id, status, entitlement_version) "
            + "VALUES (?, ?, 'membership-mtls-proof', 'active', 1)",
        accountId,
        legacyTenantId);
    UUID tenantUuid = UUID.randomUUID();
    String evidenceDigest = legacyTenantSourceEvidence.digest(legacyTenantId);
    tenantAssociationRepository.importApproved(
        legacyTenantId,
        approvedTenantAssociation(legacyTenantId, tenantUuid, evidenceDigest, suffix));
    var association =
        tenantAssociationRepository.findByLegacyTenantId(legacyTenantId).orElseThrow();
    assertThat(association.canonicalTenantId()).isEqualTo(tenantUuid);

    GameplayRealm realm =
        GameplayRealm.newBuilder()
            .setTenantId(Long.toString(legacyTenantId))
            .setRealmId(REALM_ID.toString())
            .setWorldSlug(WORLD_SLUG)
            .setRealmSlug(REALM_SLUG)
            .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
            .setGameInstanceId(Long.toString(RETAINED_JOIN_GAME_INSTANCE_ID))
            .setCatalogRevision(CATALOG_REVISION)
            .setPointerVersion(POINTER_VERSION)
            .setVisible(true)
            .setPublicProductionRealm(true)
            .setStateScope("SHARED")
            .build();
    GameplayAdmissionPointer pointer =
        GameplayAdmissionPointer.newBuilder()
            .setTenantId(Long.toString(legacyTenantId))
            .setRealmId(REALM_ID.toString())
            .setWorldSlug(WORLD_SLUG)
            .setRealmSlug(REALM_SLUG)
            .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
            .setGameInstanceId(Long.toString(RETAINED_JOIN_GAME_INSTANCE_ID))
            .setCatalogRevision(CATALOG_REVISION)
            .setPointerVersion(POINTER_VERSION)
            .setVisible(true)
            .setPublicProductionRealm(true)
            .setStateScope("SHARED")
            .build();
    when(gameSessionClient.listGameplayRealms(WORLD_SLUG)).thenReturn(List.of(realm));
    when(gameSessionClient.getAdmissionPointer(legacyTenantId, WORLD_SLUG, REALM_SLUG))
        .thenReturn(pointer);

    String requestId = "membership-mtls-join-" + suffix;
    DirectTextCallerContext caller =
        new DirectTextCallerContext(
            accountId,
            legacyTenantId,
            REALM_ID,
            PLAYABLE_STATE_NAMESPACE_ID,
            "SHARED",
            RETAINED_JOIN_GAME_INSTANCE_ID,
            "membership-mtls-session-" + suffix,
            requestId);
    DirectTextJoinTarget target =
        new DirectTextJoinTarget(
            legacyTenantId,
            REALM_ID,
            WORLD_SLUG,
            REALM_SLUG,
            PLAYABLE_STATE_NAMESPACE_ID,
            "SHARED",
            RETAINED_JOIN_GAME_INSTANCE_ID,
            CATALOG_REVISION,
            POINTER_VERSION);
    DirectTextJoinScope scope = accountService.issueDirectTextConnectScope(caller, target);
    return new JoinFixture(
        accountId, accountUuid, legacyTenantId, tenantUuid, requestId, caller, scope);
  }

  private JoinPublicProductionResult join(JoinFixture fixture) {
    return accountService.joinPublicProductionFromGameSession(
        fixture.caller(),
        new JoinPublicProductionRequest(fixture.scope().connectScopeId(), fixture.requestId()));
  }

  private RuntimeMembershipSnapshotDto readRuntimeMembershipSnapshot(JoinFixture fixture) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status ->
                membershipAuthorityEventProducer.readRuntimeMembershipSnapshot(
                    fixture.accountUuid(), fixture.tenantUuid()));
  }

  private RuntimeMembershipSnapshotDto readRuntimeMembershipSnapshot(
      FreshCanonicalJoinFixture fixture) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status ->
                membershipAuthorityEventProducer.readRuntimeMembershipSnapshot(
                    fixture.accountUuid(), fixture.tenantUuid()));
  }

  private CanonicalJoinOwnerState readCanonicalJoinOwnerState(FreshCanonicalJoinFixture fixture) {
    UUID accountUuid = fixture.accountUuid();
    UUID tenantUuid = fixture.tenantUuid();
    return new CanonicalJoinOwnerState(
        readRuntimeMembershipSnapshot(fixture),
        new TransactionTemplate(transactionManager)
            .execute(
                status ->
                    joinOperationRepository
                        .findCanonicalEvidenceByRequestId(fixture.requestId())
                        .orElseThrow()),
        new TransactionTemplate(transactionManager)
            .execute(
                status ->
                    membershipPairAuthorityRepository
                        .readForUpdate(accountUuid, tenantUuid)
                        .orElseThrow()),
        new TransactionTemplate(transactionManager)
            .execute(status -> freshTenantAssociations.read(tenantUuid).orElseThrow()),
        canonicalJoinEvidenceCounts(fixture),
        readAuthorityState(AuthorityScope.issuer(AccountServiceImpl.ACCOUNT_JWT_ISSUER)),
        readAuthorityState(AuthorityScope.account(accountUuid)),
        readAuthorityState(AuthorityScope.tenant(tenantUuid)),
        readAuthorityState(AuthorityScope.membership(accountUuid, tenantUuid)),
        accountIssuanceFence(accountUuid));
  }

  private void assertCanonicalJoinOwnerEvidenceUnchanged(
      FreshCanonicalJoinFixture fixture, CanonicalJoinOwnerState before) {
    CanonicalJoinOwnerState after = readCanonicalJoinOwnerState(fixture);
    assertSameCurrentMembershipSnapshot(before.snapshot(), after.snapshot());
    assertThat(after.operation()).isEqualTo(before.operation());
    assertThat(after.pairAuthority()).isEqualTo(before.pairAuthority());
    assertThat(after.freshTenantSource()).isEqualTo(before.freshTenantSource());
    assertThat(after.evidenceCounts()).isEqualTo(before.evidenceCounts());
    assertThat(after.issuerAuthority()).isEqualTo(before.issuerAuthority());
    assertThat(after.accountAuthority()).isEqualTo(before.accountAuthority());
    assertThat(after.tenantAuthority()).isEqualTo(before.tenantAuthority());
    assertThat(after.membershipAuthority()).isEqualTo(before.membershipAuthority());
    assertThat(after.issuanceFence()).isEqualTo(before.issuanceFence());
  }

  private CanonicalJoinEvidenceCounts canonicalJoinEvidenceCounts(
      FreshCanonicalJoinFixture fixture) {
    return new CanonicalJoinEvidenceCounts(
        countCanonicalMembershipRows(fixture.accountId(), fixture.tenantUuid()),
        countStreamEvents(membershipStreamKey(fixture.accountUuid(), fixture.tenantUuid())),
        Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_membership_transition_receipts "
                        + "WHERE account_uuid = ? AND tenant_uuid = ? AND receipt_version = 2",
                    fixture.accountUuid(),
                    fixture.tenantUuid())
                .fetchOne(0, Long.class)),
        Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_audit_outbox WHERE audit_event_id = ?",
                    canonicalJoinAuditEventId(fixture.requestId()))
                .fetchOne(0, Long.class)));
  }

  private long countCanonicalMembershipRows(long accountId, UUID tenantUuid) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_tenant_membership "
                    + "WHERE account_id = ? AND tenant_uuid = ?",
                accountId,
                tenantUuid)
            .fetchOne(0, Long.class));
  }

  private void assertFreshCanonicalJoinMembershipEvent(
      GetTenantMembershipForRuntimeResponse response,
      FreshCanonicalJoinFixture fixture,
      RuntimeMembershipSnapshotDto snapshot) {
    var event = snapshot.sourceEvent();
    assertThat(event).isNotNull();
    assertThat(event.accountId()).isEqualTo(fixture.accountUuid().toString());
    assertThat(event.tenantId()).isEqualTo(fixture.tenantUuid().toString());
    assertThat(event.requestId()).isEqualTo(fixture.requestId());
    assertThat(event.outboxStreamKey())
        .isEqualTo(membershipStreamKey(fixture.accountUuid(), fixture.tenantUuid()));
    assertThat(event.outboxSequence()).isEqualTo("1");
    assertThat(event.membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(event.gameplayAdmissionAllowed()).isTrue();
    assertThat(event.membershipVersion()).isEqualTo(Map.of(fixture.tenantUuid().toString(), "2"));
    assertThat(event.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(event.authorityTuple()).isEqualTo(snapshot.authorityTuple());
    assertThat(event.issuanceFence()).isEqualTo("1");

    var membershipEvidence =
        response.getOutboxSourceEvidenceList().stream()
            .filter(source -> event.outboxStreamKey().equals(source.getOutboxStreamKey()))
            .toList();
    assertThat(membershipEvidence).hasSize(1);
    assertThat(membershipEvidence.getFirst().getOutboxSequence()).isEqualTo(event.outboxSequence());
    assertThat(membershipEvidence.getFirst().getEventId()).isEqualTo(event.eventId());
    assertThat(membershipEvidence.getFirst().getEventDigest()).isEqualTo(event.eventDigest());
    assertThat(
            membershipEvidence.getFirst().getCanonicalEventJson().getBytes(StandardCharsets.UTF_8))
        .containsExactly(event.canonicalJsonUtf8());

    var persistedEvent =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT request_id, event_id, event_digest FROM account_authority_outbox_events "
                        + "WHERE outbox_stream_key = ? AND outbox_sequence = 1",
                    event.outboxStreamKey())
                .fetchOne());
    assertThat(persistedEvent.get("request_id", String.class)).isEqualTo(fixture.requestId());
    assertThat(persistedEvent.get("event_id", String.class)).isEqualTo(event.eventId());
    assertThat(persistedEvent.get("event_digest", String.class)).isEqualTo(event.eventDigest());
  }

  private JoinFixture reactivationFixture(JoinFixture existing) {
    String suffix = UUID.randomUUID().toString();
    String requestId = "membership-mtls-reactivation-" + suffix;
    DirectTextCallerContext caller =
        new DirectTextCallerContext(
            existing.accountId(),
            existing.legacyTenantId(),
            REALM_ID,
            PLAYABLE_STATE_NAMESPACE_ID,
            "SHARED",
            RETAINED_JOIN_GAME_INSTANCE_ID,
            "membership-mtls-reactivation-session-" + suffix,
            requestId);
    DirectTextJoinTarget target =
        new DirectTextJoinTarget(
            existing.legacyTenantId(),
            REALM_ID,
            WORLD_SLUG,
            REALM_SLUG,
            PLAYABLE_STATE_NAMESPACE_ID,
            "SHARED",
            RETAINED_JOIN_GAME_INSTANCE_ID,
            CATALOG_REVISION,
            POINTER_VERSION);
    DirectTextJoinScope scope = accountService.issueDirectTextConnectScope(caller, target);
    return new JoinFixture(
        existing.accountId(),
        existing.accountUuid(),
        existing.legacyTenantId(),
        existing.tenantUuid(),
        requestId,
        caller,
        scope);
  }

  private ScopeState readAuthorityState(AuthorityScope scope) {
    return new TransactionTemplate(transactionManager)
        .execute(status -> authorityGenerationRepository.read(scope));
  }

  private ScopeState readAccountAuthority(UUID accountUuid) {
    return readAuthorityState(AuthorityScope.account(accountUuid));
  }

  private Account readAccount(long accountId) {
    return new TransactionTemplate(transactionManager)
        .execute(status -> accountRepository.findById(accountId).orElseThrow());
  }

  private AccountIssuerAuthorityEventProducer issuerProducer() {
    return new AccountIssuerAuthorityEventProducer(
        AccountServiceImpl.ACCOUNT_JWT_ISSUER,
        authorityGenerationRepository,
        authorityOutboxRepository,
        dsl,
        transactionManager);
  }

  private AccountTenantAuthorityEventProducer tenantProducer() {
    return new AccountTenantAuthorityEventProducer(
        authorityGenerationRepository, authorityOutboxRepository, dsl, transactionManager);
  }

  private AccountLogoutAllAuthorityEventProducer logoutProducer() {
    return new AccountLogoutAllAuthorityEventProducer(
        accountRepository,
        authorityGenerationRepository,
        authorityOutboxRepository,
        logoutAllOperationRepository,
        new AccountAuthoritySourceEventReadback(
            authorityOutboxRepository,
            passwordResetOperationRepository,
            logoutAllOperationRepository),
        dsl,
        transactionManager);
  }

  private PlayerExecutionContext playerContext(
      JoinFixture fixture, UUID tenantUuid, String requestId) {
    // The transport's UUID Game Instance selector is explicit and independent of the private
    // numeric Game Instance used by the retained JOIN fixture.
    return PlayerExecutionContext.newBuilder()
        .setAccountId(fixture.accountUuid().toString())
        .setTenantId(tenantUuid.toString())
        .setRealmId(REALM_ID.toString())
        .setPlayableStateNamespaceId(PLAYABLE_STATE_NAMESPACE_ID)
        .setGameInstanceId(GAME_INSTANCE_CONTEXT_ID.toString())
        .setSessionId("9001")
        .setPlayableStateScope("SHARED")
        .setRequestId(requestId)
        .build();
  }

  private void assertResponseMatchesSnapshot(
      GetTenantMembershipForRuntimeResponse response,
      PlayerExecutionContext context,
      RuntimeMembershipSnapshotDto expected) {
    assertThat(response.getUnknownFields().asMap()).isEmpty();
    assertThat(response.hasError()).isFalse();
    assertThat(response.getAuthorityAvailability()).isEqualTo("AVAILABLE");
    assertThat(response.getAccountId()).isEqualTo(expected.accountUuid());
    assertThat(response.getTenantId()).isEqualTo(expected.tenantUuid());
    assertThat(response.getRequestAccountId()).isEqualTo(context.getAccountId());
    assertThat(response.getRequestTenantId()).isEqualTo(context.getTenantId());
    assertThat(response.getRequestId()).isEqualTo(context.getRequestId());
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

    var tuple = response.getAuthorityTuple();
    assertThat(tuple.getIssuerAuthGeneration())
        .isEqualTo(expected.authorityTuple().issuerAuthGeneration());
    assertThat(tuple.getAccountAuthorityGeneration())
        .isEqualTo(expected.authorityTuple().accountAuthorityGeneration());
    assertThat(tuple.getTenantAuthorityGenerationMap())
        .isEqualTo(expected.authorityTuple().tenantAuthorityGeneration());
    assertThat(tuple.getMembershipAuthorityGenerationMap())
        .isEqualTo(expected.authorityTuple().membershipAuthorityGeneration());
    assertThat(tuple.getPrivateRealmGrantVersionsCount()).isZero();
    assertThat(tuple.hasTenantBillingCutoff()).isFalse();
    assertThat(tuple.hasAccountSecurityCutoff())
        .isEqualTo(expected.authorityTuple().accountSecurityCutoff().isPresent());
    expected
        .authorityTuple()
        .accountSecurityCutoff()
        .ifPresent(
            cutoff -> {
              assertThat(tuple.getAccountSecurityCutoff().getAccountAuthorityGeneration())
                  .isEqualTo(cutoff.accountAuthorityGeneration());
              assertThat(tuple.getAccountSecurityCutoff().getOutboxStreamKey())
                  .isEqualTo(cutoff.outboxStreamKey());
              assertThat(tuple.getAccountSecurityCutoff().getOutboxSequence())
                  .isEqualTo(cutoff.outboxSequence());
            });

    assertThat(response.getOutboxCheckpointsCount()).isEqualTo(expected.outboxCheckpoints().size());
    for (int index = 0; index < expected.outboxCheckpoints().size(); index++) {
      var actual = response.getOutboxCheckpoints(index);
      var source = expected.outboxCheckpoints().get(index);
      assertThat(actual.getOutboxStreamKey()).isEqualTo(source.outboxStreamKey());
      assertThat(actual.getOutboxSequence()).isEqualTo(source.outboxSequence());
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
  }

  private void assertMembershipEvidence(
      GetTenantMembershipForRuntimeResponse response,
      JoinFixture fixture,
      MembershipAuthorityEventV1Codec.MembershipEvent immutableEvent,
      byte[] immutableBytes) {
    var membershipEvidence =
        response.getOutboxSourceEvidenceList().stream()
            .filter(evidence -> membershipStreamKey(fixture).equals(evidence.getOutboxStreamKey()))
            .findFirst()
            .orElseThrow();
    assertThat(membershipEvidence.getOutboxSequence()).isEqualTo("1");
    assertThat(membershipEvidence.getEventId()).isEqualTo(immutableEvent.eventId());
    assertThat(membershipEvidence.getEventDigest()).isEqualTo(immutableEvent.eventDigest());
    assertThat(membershipEvidence.getCanonicalEventJson().getBytes(StandardCharsets.UTF_8))
        .containsExactly(immutableBytes);
    assertThat(immutableEvent.canonicalJsonUtf8()).containsExactly(immutableBytes);
    assertThat(immutableEvent.issuanceFence()).isEqualTo("1");
    assertThat(immutableEvent.authorityTuple().issuerAuthGeneration()).isEqualTo("1");
    assertThat(immutableEvent.authorityTuple().accountAuthorityGeneration()).isEqualTo("1");
    assertThat(immutableEvent.authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(immutableEvent.authorityTuple().membershipAuthorityGeneration())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
  }

  private void assertCurrentMembershipEvent(
      GetTenantMembershipForRuntimeResponse response,
      JoinFixture fixture,
      RuntimeMembershipSnapshotDto snapshot,
      String expectedSequence,
      String expectedLifecycle) {
    var event = snapshot.sourceEvent();
    assertThat(event).isNotNull();
    assertThat(event.accountId()).isEqualTo(fixture.accountUuid().toString());
    assertThat(event.tenantId()).isEqualTo(fixture.tenantUuid().toString());
    assertThat(event.outboxStreamKey()).isEqualTo(membershipStreamKey(fixture));
    assertThat(event.outboxSequence()).isEqualTo(expectedSequence);
    assertThat(event.membershipLifecycleState()).isEqualTo(expectedLifecycle);
    assertThat(event.gameplayAdmissionAllowed()).isEqualTo("ACTIVE".equals(expectedLifecycle));
    assertThat(event.membershipVersion())
        .isEqualTo(snapshot.membershipBaseline().membershipVersion());
    assertThat(event.membershipAuthorityGeneration())
        .isEqualTo(snapshot.membershipBaseline().membershipAuthorityGeneration());
    assertThat(event.authorityTuple()).isEqualTo(snapshot.authorityTuple());
    assertThat(event.issuanceFence()).isEqualTo(snapshot.issuanceFence());
    var membershipEvidence =
        response.getOutboxSourceEvidenceList().stream()
            .filter(source -> membershipStreamKey(fixture).equals(source.getOutboxStreamKey()))
            .toList();
    assertThat(membershipEvidence).hasSize(1);
    var exactMembershipEvidence = membershipEvidence.get(0);
    assertThat(exactMembershipEvidence.getOutboxStreamKey())
        .isEqualTo(membershipStreamKey(fixture));
    assertThat(exactMembershipEvidence.getOutboxSequence()).isEqualTo(expectedSequence);
    assertThat(exactMembershipEvidence.getEventId()).isEqualTo(event.eventId());
    assertThat(exactMembershipEvidence.getEventDigest()).isEqualTo(event.eventDigest());
    assertThat(exactMembershipEvidence.getCanonicalEventJson().getBytes(StandardCharsets.UTF_8))
        .containsExactly(event.canonicalJsonUtf8());
  }

  private void assertMembershipCheckpoint(
      RuntimeMembershipSnapshotDto baseline,
      RuntimeMembershipSnapshotDto current,
      JoinFixture fixture,
      String expectedMembershipSequence) {
    String streamKey = membershipStreamKey(fixture);
    List<AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry> baselineNonMembership =
        baseline.outboxCheckpoints().stream()
            .filter(checkpoint -> !streamKey.equals(checkpoint.outboxStreamKey()))
            .toList();
    List<AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry> currentNonMembership =
        current.outboxCheckpoints().stream()
            .filter(checkpoint -> !streamKey.equals(checkpoint.outboxStreamKey()))
            .toList();
    List<AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry> baselineMembership =
        baseline.outboxCheckpoints().stream()
            .filter(checkpoint -> streamKey.equals(checkpoint.outboxStreamKey()))
            .toList();
    assertThat(baseline.outboxCheckpoints()).hasSize(4);
    assertThat(current.outboxCheckpoints()).hasSize(4);
    assertThat(currentNonMembership).containsExactlyElementsOf(baselineNonMembership);
    assertThat(baselineMembership)
        .containsExactly(
            new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(streamKey, "1"));
    List<AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry> membershipCheckpoint =
        current.outboxCheckpoints().stream()
            .filter(checkpoint -> streamKey.equals(checkpoint.outboxStreamKey()))
            .toList();
    assertThat(membershipCheckpoint)
        .containsExactly(
            new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                streamKey, expectedMembershipSequence));
  }

  private void assertNonMembershipAuthorityEvidenceUnchanged(
      RuntimeMembershipSnapshotDto baseline,
      RuntimeMembershipSnapshotDto current,
      JoinFixture fixture) {
    String membershipStream = membershipStreamKey(fixture);
    assertThat(current.authorityTuple().issuerAuthGeneration())
        .isEqualTo(baseline.authorityTuple().issuerAuthGeneration());
    assertThat(current.authorityTuple().accountAuthorityGeneration())
        .isEqualTo(baseline.authorityTuple().accountAuthorityGeneration());
    assertThat(current.authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(baseline.authorityTuple().tenantAuthorityGeneration());
    assertThat(current.authorityTuple().privateRealmGrantVersions())
        .isEqualTo(baseline.authorityTuple().privateRealmGrantVersions());
    assertThat(current.authorityTuple().accountSecurityCutoff())
        .isEqualTo(baseline.authorityTuple().accountSecurityCutoff());
    assertThat(current.authorityTuple().tenantBillingCutoff())
        .isEqualTo(baseline.authorityTuple().tenantBillingCutoff());
    assertThat(
            current.outboxSourceEvidence().stream()
                .filter(evidence -> !membershipStream.equals(evidence.outboxStreamKey()))
                .toList())
        .containsExactlyElementsOf(
            baseline.outboxSourceEvidence().stream()
                .filter(evidence -> !membershipStream.equals(evidence.outboxStreamKey()))
                .toList());
  }

  private void assertSameCurrentMembershipSnapshot(
      RuntimeMembershipSnapshotDto expected, RuntimeMembershipSnapshotDto actual) {
    assertThat(actual.membershipExists()).isEqualTo(expected.membershipExists());
    assertThat(actual.gameplayAdmissionAllowed()).isEqualTo(expected.gameplayAdmissionAllowed());
    assertThat(actual.membershipBaseline()).isEqualTo(expected.membershipBaseline());
    assertThat(actual.roles()).isEqualTo(expected.roles());
    assertThat(actual.authorityTuple()).isEqualTo(expected.authorityTuple());
    assertThat(actual.issuanceFence()).isEqualTo(expected.issuanceFence());
    assertThat(actual.outboxCheckpoints()).isEqualTo(expected.outboxCheckpoints());
    assertThat(actual.outboxSourceEvidence()).isEqualTo(expected.outboxSourceEvidence());
    assertThat(actual.sourceEvent().canonicalJsonUtf8())
        .containsExactly(expected.sourceEvent().canonicalJsonUtf8());
  }

  private MembershipPersistenceSnapshot membershipPersistenceSnapshot(JoinFixture fixture) {
    var membershipRow =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT id, lifecycle_state, gameplay_admission_allowed, membership_version, "
                        + "membership_authority_generation, authority_provenance "
                        + "FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?",
                    fixture.accountId(),
                    fixture.legacyTenantId())
                .fetchOne());
    var pairRow =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT membership_exists, membership_version, "
                        + "membership_authority_generation, last_event_sequence, last_event_id, "
                        + "last_event_digest, last_transition_invalidated "
                        + "FROM account_membership_pair_authority "
                        + "WHERE account_uuid = ? AND tenant_uuid = ?",
                    fixture.accountUuid(),
                    fixture.tenantUuid())
                .fetchOne());
    List<MembershipAuthorityEventSnapshot> authorityEvents =
        dsl.resultQuery(
                "SELECT outbox_sequence, request_id, event_id, event_digest, payload "
                    + "FROM account_authority_outbox_events WHERE outbox_stream_key = ? "
                    + "ORDER BY outbox_sequence",
                membershipStreamKey(fixture))
            .fetch()
            .map(
                row ->
                    new MembershipAuthorityEventSnapshot(
                        row.get("outbox_sequence", Long.class),
                        row.get("request_id", String.class),
                        row.get("event_id", String.class),
                        row.get("event_digest", String.class),
                        new String(row.get("payload", byte[].class), StandardCharsets.UTF_8)));
    return new MembershipPersistenceSnapshot(
        membershipRow.intoMap(),
        pairRow.intoMap(),
        readAuthorityState(AuthorityScope.membership(fixture.accountUuid(), fixture.tenantUuid())),
        accountIssuanceFence(fixture.accountUuid()),
        authorityEvents,
        countMembershipTransitionReceipts(fixture),
        countLeftAuditEvents(fixture));
  }

  private long accountIssuanceFence(UUID accountUuid) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT issuance_fence FROM account_authority_issuance_fences "
                    + "WHERE account_uuid = ?",
                accountUuid)
            .fetchOne(0, Long.class));
  }

  private long countMembershipTransitionReceipts(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_membership_transition_receipts "
                    + "WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.legacyTenantId())
            .fetchOne(0, Long.class));
  }

  private long countLeftAuditEvents(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_audit_outbox WHERE scope = 'tenant' "
                    + "AND tenant_id = ? AND event_type = 'ACCOUNT_MEMBERSHIP_LEFT'",
                fixture.legacyTenantId())
            .fetchOne(0, Long.class));
  }

  private void assertSourceEvidence(
      GetTenantMembershipForRuntimeResponse response,
      String streamKey,
      String eventId,
      String eventDigest) {
    var evidence =
        response.getOutboxSourceEvidenceList().stream()
            .filter(source -> streamKey.equals(source.getOutboxStreamKey()))
            .findFirst()
            .orElseThrow();
    assertThat(evidence.getOutboxSequence()).isEqualTo("1");
    assertThat(evidence.getEventId()).isEqualTo(eventId);
    assertThat(evidence.getEventDigest()).isEqualTo(eventDigest);
    assertThat(evidence.getCanonicalEventJson()).isNotBlank();
  }

  private static GetTenantMembershipForRuntimeResponse stableCarrier(
      GetTenantMembershipForRuntimeResponse response) {
    return response.toBuilder().clearEvaluatedAt().clearRequestId().build();
  }

  private void assertRemoteFailure(Runnable request, Status.Code expectedCode) {
    assertThatThrownBy(request::run)
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure.getCause()).getCode())
                    .isEqualTo(expectedCode));
  }

  private DenialReadback denialReadback(JoinFixture fixture) {
    UUID accountUuid = fixture.accountUuid();
    UUID tenantUuid = fixture.tenantUuid();
    return new DenialReadback(
        readAuthorityState(AuthorityScope.issuer(AccountServiceImpl.ACCOUNT_JWT_ISSUER)),
        readAuthorityState(AuthorityScope.account(accountUuid)),
        readAuthorityState(AuthorityScope.tenant(tenantUuid)),
        countMembershipPairRows(accountUuid, tenantUuid),
        countMembershipGenerationRows(accountUuid, tenantUuid),
        countMembershipRows(fixture),
        countStreamEvents(membershipStreamKey(fixture)),
        countStreamEvents(accountStreamKey(accountUuid)),
        countStreamEvents(issuerStreamKey()),
        countStreamEvents(tenantStreamKey(tenantUuid)));
  }

  private void assertDurableSequenceZeroPairBaseline(JoinFixture fixture) {
    var pair =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT membership_exists, membership_version, "
                        + "membership_authority_generation, last_event_sequence, last_event_id, "
                        + "last_event_digest, last_transition_invalidated "
                        + "FROM account_membership_pair_authority "
                        + "WHERE account_uuid = ? AND tenant_uuid = ?",
                    fixture.accountUuid(),
                    fixture.tenantUuid())
                .fetchOne());
    assertThat(pair.get(0, Boolean.class)).isFalse();
    assertThat(pair.get(1, Long.class)).isEqualTo(1L);
    assertThat(pair.get(2, Long.class)).isEqualTo(1L);
    assertThat(pair.get(3, Long.class)).isZero();
    assertThat(pair.get(4, String.class)).isNull();
    assertThat(pair.get(5, String.class)).isNull();
    assertThat(pair.get(6, Boolean.class)).isFalse();
  }

  private long countMembershipPairRows(UUID accountUuid, UUID tenantUuid) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_membership_pair_authority "
                    + "WHERE account_uuid = ? AND tenant_uuid = ?",
                accountUuid,
                tenantUuid)
            .fetchOne(0, Long.class));
  }

  private long countMembershipGenerationRows(UUID accountUuid, UUID tenantUuid) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_authority_generations "
                    + "WHERE scope_kind = 'MEMBERSHIP' AND account_uuid = ? AND tenant_uuid = ?",
                accountUuid,
                tenantUuid)
            .fetchOne(0, Long.class));
  }

  private long countMembershipRows(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_tenant_membership "
                    + "WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.legacyTenantId())
            .fetchOne(0, Long.class));
  }

  private long countStreamEvents(String streamKey) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
                streamKey)
            .fetchOne(0, Long.class));
  }

  private byte[] membershipEventBytes(JoinFixture fixture) {
    return membershipEventBytes(fixture, 1L);
  }

  private byte[] membershipEventBytes(JoinFixture fixture, long sequence) {
    return Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT payload FROM account_authority_outbox_events "
                        + "WHERE outbox_stream_key = ? AND outbox_sequence = ?",
                    membershipStreamKey(fixture),
                    sequence)
                .fetchOne())
        .get("payload", byte[].class);
  }

  private void seedRetainedV26TenantEvidence(long legacyTenantId, String suffix) {
    String shortSuffix = suffix.replace("-", "").substring(0, 12);
    long donorAccountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash, tenant_id) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                    "amspd-" + shortSuffix,
                    "membership-mtls-donor-" + suffix + "@example.com",
                    "test-hash",
                    legacyTenantId)
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
                    legacyTenantId)
                .fetchOne(0, Long.class));
    dsl.execute(
        "INSERT INTO account_legacy_tenant_sources "
            + "(account_id, legacy_tenant_id, matching_membership_id, "
            + "matching_membership_admission_allowed, profile_tenant_count, "
            + "matching_profile_count, disposition) VALUES (?, ?, ?, FALSE, 0, 0, 'UNVERIFIED')",
        donorAccountId,
        legacyTenantId,
        retainedMembershipId);
    dsl.execute(
        "INSERT INTO account_legacy_membership_sources "
            + "(membership_id, account_id, tenant_id, original_gameplay_admission_allowed, "
            + "matches_account_legacy_tenant, disposition) "
            + "VALUES (?, ?, ?, FALSE, TRUE, 'UNVERIFIED')",
        retainedMembershipId,
        donorAccountId,
        legacyTenantId);
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
        .setTargetNamespace(NAMESPACE)
        .setSignerKeyId("game-design-owner-test")
        .setApprovedBy("owner@example.test")
        .setApprovalReference("unit-1b-membership-mtls-proof")
        .setSignedAt("2026-09-26T00:00:00Z")
        .setOperationEntryCount(1)
        .setManifestSchemaVersion(1)
        .build();
  }

  private long positiveRandomLong() {
    long candidate = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    return candidate == 0L ? 1L : candidate;
  }

  private String accountStreamKey(UUID accountUuid) {
    return AUTHORITY_STREAM_PREFIX + "account/" + accountUuid;
  }

  private String issuerStreamKey() {
    return AUTHORITY_STREAM_PREFIX + "issuer/" + AccountServiceImpl.ACCOUNT_JWT_ISSUER;
  }

  private String tenantStreamKey(UUID tenantUuid) {
    return AUTHORITY_STREAM_PREFIX + "tenant/" + tenantUuid;
  }

  private String membershipStreamKey(JoinFixture fixture) {
    return membershipStreamKey(fixture.accountUuid(), fixture.tenantUuid());
  }

  private String membershipStreamKey(UUID accountUuid, UUID tenantUuid) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "membership/"
        + accountUuid
        + "/"
        + tenantUuid;
  }

  private Server startServer() throws Exception {
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.forServer(
                    certificate("account.crt").toFile(), certificate("account.key").toFile())
                .trustManager(certificate("ca.crt").toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(
            ServerInterceptors.intercept(
                new AccountMembershipAuthorityGrpcService(
                    membershipAuthorityEventProducer, transactionManager, NAMESPACE),
                new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private AccountMembershipAuthorityClient newClient(int port, String clientIdentity)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + port);
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate(clientIdentity + ".crt").toString());
    tls.setPrivateKey(certificate(clientIdentity + ".key").toString());
    tls.setCaCert(certificate("ca.crt").toString());
    return new AccountMembershipAuthorityClient(
        endpoints, tls, new GrpcChannelFactory(), BlockingGrpcStubCustomizer.noop(), NAMESPACE);
  }

  private Path certificate(String name) throws IOException {
    Path existing = copiedCertificates.get(name);
    if (existing != null) {
      return existing;
    }

    String resourcePath = "/certs/issuer-authority/" + name;
    try (InputStream resource =
        AccountMembershipAuthorityGrpcPostgresMtlsCrossServiceTest.class.getResourceAsStream(
            resourcePath)) {
      if (resource == null) {
        throw new IllegalStateException(
            "Missing membership-authority TLS fixture: " + resourcePath);
      }
      Path copied = tempDir.resolve(name);
      Files.copy(resource, copied);
      copiedCertificates.put(name, copied);
      return copied;
    }
  }

  private static void stop(Server server) throws InterruptedException {
    server.shutdownNow();
    server.awaitTermination(2, TimeUnit.SECONDS);
  }

  private record JoinFixture(
      long accountId,
      UUID accountUuid,
      long legacyTenantId,
      UUID tenantUuid,
      String requestId,
      DirectTextCallerContext caller,
      DirectTextJoinScope scope) {}

  private record FreshCanonicalJoinFixture(
      long accountId,
      UUID accountUuid,
      UUID tenantUuid,
      VerifiedTenantProvenance provenance,
      FreshTenantCreationEvidence creationEvidence,
      CanonicalJoinScopeV2 scope,
      String requestId,
      String callerBinding) {}

  private record CanonicalJoinOwnerState(
      RuntimeMembershipSnapshotDto snapshot,
      CanonicalJoinOperationEvidence operation,
      PairAuthority pairAuthority,
      FreshTenantCreationEvidence freshTenantSource,
      CanonicalJoinEvidenceCounts evidenceCounts,
      ScopeState issuerAuthority,
      ScopeState accountAuthority,
      ScopeState tenantAuthority,
      ScopeState membershipAuthority,
      long issuanceFence) {}

  private record CanonicalJoinEvidenceCounts(
      long memberships, long events, long receipts, long audits) {}

  private record DenialReadback(
      ScopeState issuerState,
      ScopeState accountState,
      ScopeState tenantState,
      long membershipPairRows,
      long membershipGenerationRows,
      long membershipRows,
      long membershipEvents,
      long accountEvents,
      long issuerEvents,
      long tenantEvents) {}

  private record MembershipAuthorityEventSnapshot(
      long sequence, String requestId, String eventId, String eventDigest, String canonicalJson) {}

  private record MembershipPersistenceSnapshot(
      Map<String, Object> membershipRow,
      Map<String, Object> pairAuthorityRow,
      ScopeState membershipAuthorityState,
      long issuanceFence,
      List<MembershipAuthorityEventSnapshot> authorityEvents,
      long transitionReceiptCount,
      long leftAuditCount) {
    private MembershipPersistenceSnapshot {
      membershipRow = Map.copyOf(membershipRow);
      pairAuthorityRow = Map.copyOf(pairAuthorityRow);
      authorityEvents = List.copyOf(authorityEvents);
    }
  }
}
