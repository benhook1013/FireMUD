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
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinScope;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionResult;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
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
  @Autowired private AccountLogoutAllOperationRepository logoutAllOperationRepository;
  @Autowired private AccountPasswordResetOperationRepository passwordResetOperationRepository;
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
      String requestDigest = "a".repeat(64);
      String presentedTokenHash = "b".repeat(64);
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
    return Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT payload FROM account_authority_outbox_events "
                        + "WHERE outbox_stream_key = ? AND outbox_sequence = 1",
                    membershipStreamKey(fixture))
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
}
