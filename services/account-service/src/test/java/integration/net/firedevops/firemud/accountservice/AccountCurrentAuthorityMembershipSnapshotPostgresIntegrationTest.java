package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.AccountLogoutRequestDigest;
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinScope;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionResult;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountCommittedConnectSource;
import net.firedevops.firemud.accountservice.repository.AccountConnectIssuanceFenceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceIdentity;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceOperation.Lifecycle;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceRepository.ClaimResult;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import net.firedevops.firemud.accountservice.security.AccountGameplayConnectSourceVerifier;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountBareLoginCurrentAuthorityReader;
import net.firedevops.firemud.accountservice.service.AccountCommittedConnectSourceReader;
import net.firedevops.firemud.accountservice.service.AccountConnectTokenAuthorityCaptureService;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountMembershipLifecycleService;
import net.firedevops.firemud.accountservice.service.AccountMembershipSourceReader;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.AccountTenantAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.GatewayConnectContextCodec;
import net.firedevops.firemud.common.security.GatewayConnectContextSignature;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
import net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.v1.GameplayRealm;
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
import tools.jackson.databind.ObjectMapper;

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
      "firemud.grpc.workload-namespace=account-current-authority-test"
    })
class AccountCurrentAuthorityMembershipSnapshotPostgresIntegrationTest {
  private static final String WORKLOAD_NAMESPACE = "account-current-authority-test";
  private static final String WORLD_SLUG = "current-authority-proof-world";
  private static final String REALM_SLUG = "production";
  private static final String NAMESPACE_ID = "current-authority-proof-namespace";
  private static final UUID REALM_ID = UUID.fromString("7bda1169-a8a3-4b43-96a4-53f8579ac164");
  private static final long GAME_INSTANCE_ID = 79L;
  private static final long CATALOG_REVISION = 31L;
  private static final long POINTER_VERSION = 13L;
  private static final String AUTHORITY_STREAM_PREFIX = "account:auth-authority:v1:";
  private static final String ACCOUNT_SOURCE_KEY_ID = "account-capture-proof-key";
  private static final String GATEWAY_CONTEXT_KEY_ID = "gateway-capture-proof-key";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
  private static final SecureRandom FIXTURE_RANDOM = new SecureRandom();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @TempDir Path tempDir;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(registry, postgres, "account_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private DSLContext dsl;
  @Autowired private AccountService accountService;
  @Autowired private AccountMembershipAuthorityEventProducer membershipAuthorityEventProducer;
  @Autowired private AccountMembershipLifecycleService membershipLifecycleService;
  @Autowired private AccountAuthorityGenerationRepository authorityGenerationRepository;
  @Autowired private AccountAuthorityOutboxRepository authorityOutboxRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private AccountJoinOperationRepository joinOperationRepository;
  @Autowired private AccountConnectTokenIssuanceRepository connectIssuanceRepository;
  @Autowired private AccountLogoutAllOperationRepository logoutAllOperationRepository;
  @Autowired private AccountPasswordResetOperationRepository passwordResetOperationRepository;
  @Autowired private ApprovedLegacyTenantAssociationRepository tenantAssociationRepository;
  @Autowired private FreshTenantIdentityAssociationRepository freshTenantIdentityRepository;
  @Autowired private LegacyTenantSourceEvidence legacyTenantSourceEvidence;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;

  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private LoggingAdminClient loggingAdminClient;
  @MockitoBean private JavaMailSender mailSender;

  @Test
  void postJoinSnapshotCarriesCurrentCompositeSourcesWithoutRewritingMembershipEvent() {
    JoinFixture fixture = fixture();
    JoinPublicProductionResult joined = join(fixture);
    assertThat(joined.success()).isTrue();

    RuntimeMembershipSnapshotDto before = readRuntimeMembershipSnapshot(fixture);
    var immutableMembershipEvent = before.sourceEvent();
    byte[] immutableMembershipBytes = immutableMembershipEvent.canonicalJsonUtf8();
    Map<String, Object> membershipRowBeforeSourceRead = membershipRow(fixture);
    Map<String, Object> membershipPairBeforeSourceRead = membershipPairAuthorityRow(fixture);
    Map<String, Object> membershipGenerationBeforeSourceRead = membershipGenerationRow(fixture);
    long fenceBeforeSourceRead = accountIssuanceFence(fixture);
    Map<String, Object> fenceRowBeforeSourceRead = accountIssuanceFenceRow(fixture);
    var beforeMembershipSource =
        membershipSourceReader().readCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertThat(beforeMembershipSource.accountId()).isEqualTo(fixture.accountUuid());
    assertThat(beforeMembershipSource.tenantId()).isEqualTo(fixture.tenantUuid());
    assertThat(beforeMembershipSource.sourceState().scope())
        .isEqualTo(AuthorityScope.membership(fixture.accountUuid(), fixture.tenantUuid()));
    assertThat(beforeMembershipSource.sourceState().generation()).isEqualTo(1L);
    assertThat(beforeMembershipSource.sourceState().sourceVersion()).isEqualTo(1L);
    assertThat(beforeMembershipSource.sourceState().issuanceFence().accountId())
        .isEqualTo(fixture.accountUuid());
    assertThat(beforeMembershipSource.sourceState().issuanceFence().value())
        .isEqualTo(fenceBeforeSourceRead);
    assertThat(beforeMembershipSource.snapshot().membershipBaseline().membershipVersion())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "2"));
    assertThat(
            beforeMembershipSource.snapshot().membershipBaseline().membershipAuthorityGeneration())
        .isEqualTo("1");
    assertThat(beforeMembershipSource.snapshot().sourceEvent().eventId())
        .isEqualTo(immutableMembershipEvent.eventId());
    assertThat(beforeMembershipSource.snapshot().sourceEvent().outboxSequence()).isEqualTo("1");
    assertThat(beforeMembershipSource.snapshot().sourceEvent().eventDigest())
        .isEqualTo(immutableMembershipEvent.eventDigest());
    assertThat(beforeMembershipSource.snapshot().sourceEvent().outboxStreamKey())
        .isEqualTo(membershipStreamKey(fixture));
    assertThat(beforeMembershipSource.snapshot().sourceEvent().membershipVersion())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "2"));
    assertThat(beforeMembershipSource.snapshot().sourceEvent().membershipAuthorityGeneration())
        .isEqualTo("1");
    assertThat(beforeMembershipSource.snapshot().sourceEvent().canonicalJsonUtf8())
        .containsExactly(immutableMembershipBytes);
    assertThat(beforeMembershipSource.snapshot().outboxCheckpoints())
        .isEqualTo(before.outboxCheckpoints());
    assertThat(beforeMembershipSource.snapshot().outboxSourceEvidence())
        .isEqualTo(before.outboxSourceEvidence());
    assertThat(membershipRow(fixture)).isEqualTo(membershipRowBeforeSourceRead);
    assertThat(membershipPairAuthorityRow(fixture)).isEqualTo(membershipPairBeforeSourceRead);
    assertThat(membershipGenerationRow(fixture)).isEqualTo(membershipGenerationBeforeSourceRead);
    assertThat(accountIssuanceFence(fixture)).isEqualTo(fenceBeforeSourceRead);
    assertThat(accountIssuanceFenceRow(fixture)).isEqualTo(fenceRowBeforeSourceRead);
    assertThat(countMembershipRows(fixture)).isEqualTo(1L);
    assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(1L);
    assertThat(countStreamEvents(membershipStreamKey(fixture))).isEqualTo(1L);
    assertThat(before.membershipExists()).isTrue();
    assertThat(before.gameplayAdmissionAllowed()).isTrue();
    assertThat(before.authorityTuple().issuerAuthGeneration()).isEqualTo("1");
    assertThat(before.authorityTuple().accountAuthorityGeneration()).isEqualTo("1");
    assertThat(before.authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(before.authorityTuple().membershipAuthorityGeneration())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(before.outboxCheckpoints())
        .containsExactly(
            new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                accountStreamKey(fixture.accountUuid()), "0"),
            new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                issuerStreamKey(), "0"),
            new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                membershipStreamKey(fixture), "1"),
            new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                tenantStreamKey(fixture.tenantUuid()), "0"));
    assertThat(before.outboxSourceEvidence()).hasSize(1);

    UUID issuerRequestId = UUID.randomUUID();
    UUID tenantRequestId = UUID.randomUUID();
    UUID logoutRequestId = UUID.randomUUID();
    String presentedTokenHash = "b".repeat(64);
    String tokenProfile = "control-ui";
    String requestDigest =
        AccountLogoutRequestDigest.accountLogoutAll(
            fixture.accountUuid(), tokenProfile, presentedTokenHash);
    AccountIssuerAuthorityEventProducer issuerProducer =
        new AccountIssuerAuthorityEventProducer(
            AccountServiceImpl.ACCOUNT_JWT_ISSUER,
            authorityGenerationRepository,
            authorityOutboxRepository,
            dsl,
            transactionManager);
    AccountTenantAuthorityEventProducer tenantProducer =
        new AccountTenantAuthorityEventProducer(
            authorityGenerationRepository, authorityOutboxRepository, dsl, transactionManager);
    AccountLogoutAllAuthorityEventProducer logoutProducer = logoutProducer();

    var issuerEvent =
        issuerProducer.advance(AccountServiceImpl.ACCOUNT_JWT_ISSUER, issuerRequestId, 1L, 1L);
    var tenantEvent = tenantProducer.advance(fixture.tenantUuid(), tenantRequestId, 1L, 1L);
    ScopeState preLogoutAccountState = readAccountAuthority(fixture.accountUuid());
    Account account = readAccount(fixture.accountId());
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

    RuntimeMembershipSnapshotDto current = readRuntimeMembershipSnapshot(fixture);
    assertThat(current.membershipExists()).isTrue();
    assertThat(current.gameplayAdmissionAllowed()).isTrue();
    assertThat(current.membershipBaseline()).isEqualTo(before.membershipBaseline());
    assertThat(current.roles()).isEqualTo(before.roles());
    assertThat(current.authorityTuple().issuerAuthGeneration()).isEqualTo("2");
    assertThat(current.authorityTuple().accountAuthorityGeneration()).isEqualTo("2");
    assertThat(current.authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "2"));
    assertThat(current.authorityTuple().membershipAuthorityGeneration())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(current.issuanceFence()).isEqualTo("2");
    assertThat(current.authorityTuple().accountSecurityCutoff())
        .hasValueSatisfying(
            cutoff -> {
              assertThat(cutoff.accountAuthorityGeneration()).isEqualTo("2");
              assertThat(cutoff.outboxStreamKey())
                  .isEqualTo(accountStreamKey(fixture.accountUuid()));
              assertThat(cutoff.outboxSequence()).isEqualTo("1");
            });
    assertThat(current.outboxCheckpoints())
        .containsExactly(
            new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                accountStreamKey(fixture.accountUuid()), "1"),
            new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                issuerStreamKey(), "1"),
            new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                membershipStreamKey(fixture), "1"),
            new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                tenantStreamKey(fixture.tenantUuid()), "1"));
    assertThat(current.outboxSourceEvidence())
        .extracting(AccountMembershipAuthorityEventProducer.OutboxSourceEvidence::outboxStreamKey)
        .containsExactly(
            accountStreamKey(fixture.accountUuid()),
            issuerStreamKey(),
            membershipStreamKey(fixture),
            tenantStreamKey(fixture.tenantUuid()));
    var currentMembershipEvidence =
        current.outboxSourceEvidence().stream()
            .filter(evidence -> evidence.outboxStreamKey().equals(membershipStreamKey(fixture)))
            .findFirst()
            .orElseThrow();
    assertThat(currentMembershipEvidence.eventId()).isEqualTo(immutableMembershipEvent.eventId());
    assertThat(currentMembershipEvidence.eventDigest())
        .isEqualTo(immutableMembershipEvent.eventDigest());
    assertThat(currentMembershipEvidence.canonicalEventJson().getBytes(StandardCharsets.UTF_8))
        .containsExactly(immutableMembershipBytes);
    assertThat(current.sourceEvent().eventId()).isEqualTo(immutableMembershipEvent.eventId());
    assertThat(current.sourceEvent().eventDigest())
        .isEqualTo(immutableMembershipEvent.eventDigest());
    assertThat(current.sourceEvent().canonicalJsonUtf8()).containsExactly(immutableMembershipBytes);
    assertThat(current.sourceEvent().authorityTuple())
        .isEqualTo(immutableMembershipEvent.authorityTuple());
    assertThat(current.sourceEvent().issuanceFence()).isEqualTo("1");
    assertThat(current.sourceEvent().authorityTuple().issuerAuthGeneration()).isEqualTo("1");

    assertThat(
            issuerProducer
                .advance(AccountServiceImpl.ACCOUNT_JWT_ISSUER, issuerRequestId, 1L, 1L)
                .eventDigest())
        .isEqualTo(issuerEvent.eventDigest());
    assertThat(tenantProducer.advance(fixture.tenantUuid(), tenantRequestId, 1L, 1L).eventDigest())
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

    RuntimeMembershipSnapshotDto readback = readRuntimeMembershipSnapshot(fixture);
    assertThat(readback.authorityTuple()).isEqualTo(current.authorityTuple());
    assertThat(readback.issuanceFence()).isEqualTo(current.issuanceFence());
    assertThat(readback.outboxCheckpoints()).isEqualTo(current.outboxCheckpoints());
    assertThat(readback.outboxSourceEvidence()).isEqualTo(current.outboxSourceEvidence());
    assertThat(readback.sourceEvent().canonicalJsonUtf8())
        .containsExactly(immutableMembershipBytes);

    var retainedZeroFixture = fixture();
    RuntimeMembershipSnapshotDto retainedZeroBefore =
        readRuntimeMembershipSnapshot(retainedZeroFixture);
    Map<String, Object> retainedZeroPair = membershipPairAuthorityRow(retainedZeroFixture);
    Map<String, Object> retainedZeroMembershipGeneration =
        membershipGenerationRow(retainedZeroFixture);
    long retainedZeroFence = accountIssuanceFence(retainedZeroFixture);
    Map<String, Object> retainedZeroFenceRow = accountIssuanceFenceRow(retainedZeroFixture);
    long retainedZeroTenantGeneration =
        readTenantAuthority(retainedZeroFixture.tenantUuid()).generation();
    var retainedZero =
        membershipSourceReader()
            .readCurrent(retainedZeroFixture.accountUuid(), retainedZeroFixture.tenantUuid());
    assertThat(retainedZero.accountId()).isEqualTo(retainedZeroFixture.accountUuid());
    assertThat(retainedZero.tenantId()).isEqualTo(retainedZeroFixture.tenantUuid());
    assertThat(retainedZero.sourceState().scope())
        .isEqualTo(
            AuthorityScope.membership(
                retainedZeroFixture.accountUuid(), retainedZeroFixture.tenantUuid()));
    assertThat(retainedZero.sourceState().generation()).isEqualTo(1L);
    assertThat(retainedZero.sourceState().sourceVersion()).isEqualTo(1L);
    assertThat(retainedZero.sourceState().issuanceFence().value()).isEqualTo(retainedZeroFence);
    assertThat(retainedZero.snapshot().membershipExists()).isFalse();
    assertThat(retainedZero.snapshot().gameplayAdmissionAllowed()).isFalse();
    assertThat(retainedZero.snapshot().membershipBaseline().membershipLifecycleState())
        .isEqualTo("MISSING");
    assertThat(retainedZero.snapshot().membershipBaseline().membershipVersion())
        .isEqualTo(Map.of(retainedZeroFixture.tenantUuid().toString(), "1"));
    assertThat(retainedZero.snapshot().sourceEvent()).isNull();
    assertThat(retainedZero.snapshot().outboxCheckpoints())
        .isEqualTo(retainedZeroBefore.outboxCheckpoints());
    assertThat(retainedZero.snapshot().outboxSourceEvidence())
        .isEqualTo(retainedZeroBefore.outboxSourceEvidence());
    assertThat(membershipPairAuthorityRow(retainedZeroFixture)).isEqualTo(retainedZeroPair);
    assertThat(membershipGenerationRow(retainedZeroFixture))
        .isEqualTo(retainedZeroMembershipGeneration);
    assertThat(readTenantAuthority(retainedZeroFixture.tenantUuid()).generation())
        .isEqualTo(retainedZeroTenantGeneration);
    assertThat(accountIssuanceFence(retainedZeroFixture)).isEqualTo(retainedZeroFence);
    assertThat(accountIssuanceFenceRow(retainedZeroFixture)).isEqualTo(retainedZeroFenceRow);
    assertThat(countMembershipRows(retainedZeroFixture)).isZero();
    assertThat(countMembershipTransitionReceipts(retainedZeroFixture)).isZero();
    assertThat(countStreamEvents(membershipStreamKey(retainedZeroFixture))).isZero();

    JoinFixture neverJoined = fixture();
    UUID neverJoinedTenantRequestId = UUID.randomUUID();
    var neverJoinedTenantEvent =
        tenantProducer.advance(neverJoined.tenantUuid(), neverJoinedTenantRequestId, 1L, 1L);
    RuntimeMembershipSnapshotDto absent = readRuntimeMembershipSnapshot(neverJoined);
    assertThat(absent.membershipExists()).isFalse();
    assertThat(absent.gameplayAdmissionAllowed()).isFalse();
    assertThat(absent.membershipBaseline().membershipLifecycleState()).isEqualTo("MISSING");
    assertThat(absent.membershipBaseline().membershipVersion())
        .isEqualTo(Map.of(neverJoined.tenantUuid().toString(), "1"));
    assertThat(absent.authorityTuple().issuerAuthGeneration()).isEqualTo("2");
    assertThat(absent.authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(Map.of(neverJoined.tenantUuid().toString(), "2"));
    assertThat(absent.outboxCheckpoints())
        .containsExactly(
            new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                accountStreamKey(neverJoined.accountUuid()), "0"),
            new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                issuerStreamKey(), "1"),
            new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                membershipStreamKey(neverJoined), "0"),
            new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                tenantStreamKey(neverJoined.tenantUuid()), "1"));
    assertThat(absent.outboxSourceEvidence())
        .extracting(AccountMembershipAuthorityEventProducer.OutboxSourceEvidence::outboxStreamKey)
        .containsExactly(issuerStreamKey(), tenantStreamKey(neverJoined.tenantUuid()));
    assertThat(absent.outboxSourceEvidence().get(1).eventId())
        .isEqualTo(neverJoinedTenantEvent.eventId());
    assertThat(absent.outboxSourceEvidence().get(1).eventDigest())
        .isEqualTo(neverJoinedTenantEvent.eventDigest());
    assertThat(countMembershipRows(neverJoined)).isZero();
    assertThat(countStreamEvents(membershipStreamKey(neverJoined))).isZero();
    RuntimeMembershipSnapshotDto absentReadback = readRuntimeMembershipSnapshot(neverJoined);
    assertThat(absentReadback.membershipExists()).isFalse();
    assertThat(absentReadback.authorityTuple()).isEqualTo(absent.authorityTuple());
    assertThat(absentReadback.outboxCheckpoints()).isEqualTo(absent.outboxCheckpoints());
    assertThat(absentReadback.outboxSourceEvidence()).isEqualTo(absent.outboxSourceEvidence());

    dsl.execute(
        "UPDATE account_authority_generations SET generation = generation + 1, "
            + "source_version = source_version + 1 WHERE scope_kind = 'ISSUER' AND issuer_id = ?",
        AccountServiceImpl.ACCOUNT_JWT_ISSUER);
    assertThatThrownBy(() -> readRuntimeMembershipSnapshot(fixture))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Current issuer source event differs from its generation");
    assertThat(membershipEventBytes(fixture)).containsExactly(immutableMembershipBytes);
  }

  @Test
  void membershipSourceReaderReadsRetainedInactiveRetryAndReactivationWithoutMutation() {
    JoinFixture fixture = fixture();
    JoinPublicProductionResult joined = join(fixture);
    assertThat(joined.success()).isTrue();

    String leftRequestId = "membership-source-left-" + UUID.randomUUID();
    MembershipTransitionReceipt committedLeft =
        membershipLifecycleService.leave(fixture.accountId(), fixture.tenantId(), leftRequestId);
    Map<String, Object> inactiveMembership = membershipRow(fixture);
    Map<String, Object> inactivePair = membershipPairAuthorityRow(fixture);
    Map<String, Object> inactiveGeneration = membershipGenerationRow(fixture);
    long inactiveFence = accountIssuanceFence(fixture);
    Map<String, Object> inactiveFenceRow = accountIssuanceFenceRow(fixture);
    byte[] leftEventBytes = membershipEventBytes(fixture, 2L);
    long inactiveReceiptCount = countMembershipTransitionReceipts(fixture);
    long inactiveEventCount = countStreamEvents(membershipStreamKey(fixture));

    var inactive =
        membershipSourceReader().readCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertThat(inactive.sourceState().scope())
        .isEqualTo(AuthorityScope.membership(fixture.accountUuid(), fixture.tenantUuid()));
    assertThat(inactive.sourceState().generation()).isEqualTo(2L);
    assertThat(inactive.sourceState().sourceVersion()).isEqualTo(2L);
    assertThat(inactive.sourceState().issuanceFence().value()).isEqualTo(inactiveFence);
    assertThat(inactive.snapshot().membershipExists()).isTrue();
    assertThat(inactive.snapshot().gameplayAdmissionAllowed()).isFalse();
    assertThat(inactive.snapshot().membershipBaseline().membershipLifecycleState())
        .isEqualTo("INACTIVE");
    assertThat(inactive.snapshot().membershipBaseline().membershipVersion())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "3"));
    assertThat(inactive.snapshot().membershipBaseline().membershipAuthorityGeneration())
        .isEqualTo("2");
    assertThat(inactive.snapshot().sourceEvent().requestId()).isEqualTo(leftRequestId);
    assertThat(inactive.snapshot().sourceEvent().outboxSequence()).isEqualTo("2");
    assertThat(inactive.snapshot().sourceEvent().canonicalJsonUtf8())
        .containsExactly(leftEventBytes);
    assertThat(inactive.snapshot().outboxCheckpoints())
        .contains(
            new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                membershipStreamKey(fixture), "2"));
    assertThat(inactive.snapshot().outboxSourceEvidence())
        .anySatisfy(
            evidence -> {
              assertThat(evidence.outboxStreamKey()).isEqualTo(membershipStreamKey(fixture));
              assertThat(evidence.outboxSequence()).isEqualTo("2");
              assertThat(evidence.eventDigest())
                  .isEqualTo(inactive.snapshot().sourceEvent().eventDigest());
              assertThat(evidence.canonicalEventJson().getBytes(StandardCharsets.UTF_8))
                  .containsExactly(leftEventBytes);
            });
    assertThat(membershipRow(fixture)).isEqualTo(inactiveMembership);
    assertThat(membershipPairAuthorityRow(fixture)).isEqualTo(inactivePair);
    assertThat(membershipGenerationRow(fixture)).isEqualTo(inactiveGeneration);
    assertThat(accountIssuanceFence(fixture)).isEqualTo(inactiveFence);
    assertThat(accountIssuanceFenceRow(fixture)).isEqualTo(inactiveFenceRow);
    assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(inactiveReceiptCount);
    assertThat(countStreamEvents(membershipStreamKey(fixture))).isEqualTo(inactiveEventCount);

    assertThat(
            membershipLifecycleService.leave(
                fixture.accountId(), fixture.tenantId(), leftRequestId))
        .isEqualTo(committedLeft);
    var inactiveRetry =
        membershipSourceReader().readCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertThat(inactiveRetry.snapshot().membershipBaseline())
        .isEqualTo(inactive.snapshot().membershipBaseline());
    assertThat(inactiveRetry.snapshot().sourceEvent().canonicalJsonUtf8())
        .containsExactly(leftEventBytes);
    assertThat(membershipRow(fixture)).isEqualTo(inactiveMembership);
    assertThat(membershipPairAuthorityRow(fixture)).isEqualTo(inactivePair);
    assertThat(membershipGenerationRow(fixture)).isEqualTo(inactiveGeneration);
    assertThat(accountIssuanceFence(fixture)).isEqualTo(inactiveFence);
    assertThat(accountIssuanceFenceRow(fixture)).isEqualTo(inactiveFenceRow);
    assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(inactiveReceiptCount);
    assertThat(countStreamEvents(membershipStreamKey(fixture))).isEqualTo(inactiveEventCount);

    JoinFixture reactivation = fixtureForMembership(fixture);
    JoinPublicProductionResult reactivated = join(reactivation);
    assertThat(reactivated.success()).isTrue();
    assertThat(reactivated.outcomeCode()).isEqualTo("JOINED");
    var active = membershipSourceReader().readCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertThat(active.sourceState().generation()).isEqualTo(3L);
    assertThat(active.sourceState().sourceVersion()).isEqualTo(3L);
    assertThat(active.snapshot().membershipExists()).isTrue();
    assertThat(active.snapshot().gameplayAdmissionAllowed()).isTrue();
    assertThat(active.snapshot().membershipBaseline().membershipLifecycleState())
        .isEqualTo("ACTIVE");
    assertThat(active.snapshot().membershipBaseline().membershipVersion())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "4"));
    assertThat(active.snapshot().membershipBaseline().membershipAuthorityGeneration())
        .isEqualTo("3");
    assertThat(active.snapshot().outboxCheckpoints())
        .contains(
            new AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry(
                membershipStreamKey(fixture), "3"));

    Map<String, Object> activeMembership = membershipRow(fixture);
    Map<String, Object> activePair = membershipPairAuthorityRow(fixture);
    Map<String, Object> activeGeneration = membershipGenerationRow(fixture);
    long activeFence = accountIssuanceFence(fixture);
    Map<String, Object> activeFenceRow = accountIssuanceFenceRow(fixture);
    long activeReceiptCount = countMembershipTransitionReceipts(fixture);
    long activeEventCount = countStreamEvents(membershipStreamKey(fixture));
    assertThat(
            membershipLifecycleService.leave(
                fixture.accountId(), fixture.tenantId(), leftRequestId))
        .isEqualTo(committedLeft);
    var activeRetry =
        membershipSourceReader().readCurrent(fixture.accountUuid(), fixture.tenantUuid());
    assertThat(activeRetry.snapshot().membershipBaseline())
        .isEqualTo(active.snapshot().membershipBaseline());
    assertThat(activeRetry.snapshot().sourceEvent().canonicalJsonUtf8())
        .containsExactly(active.snapshot().sourceEvent().canonicalJsonUtf8());
    assertThat(membershipRow(fixture)).isEqualTo(activeMembership);
    assertThat(membershipPairAuthorityRow(fixture)).isEqualTo(activePair);
    assertThat(membershipGenerationRow(fixture)).isEqualTo(activeGeneration);
    assertThat(accountIssuanceFence(fixture)).isEqualTo(activeFence);
    assertThat(accountIssuanceFenceRow(fixture)).isEqualTo(activeFenceRow);
    assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(activeReceiptCount);
    assertThat(countStreamEvents(membershipStreamKey(fixture))).isEqualTo(activeEventCount);
  }

  @Test
  void activeMembershipCaptureCommitsBesideSyntheticV35SourceAndExactReadbackDoesNotRewriteIt() {
    JoinFixture fixture = fixture();
    assertThat(join(fixture).success()).isTrue();
    RuntimeMembershipSnapshotDto membershipBefore = readRuntimeMembershipSnapshot(fixture);
    List<List<String>> accountSourceBefore = accountSourceFingerprint(fixture);

    ConnectSourceFixture source = createCommittedConnectSource(fixture, true);
    assertThat(source.evidence().accountUuid()).isEqualTo(fixture.accountUuid());
    assertThat(source.evidence().tenantUuid()).isEqualTo(fixture.tenantUuid());
    assertThat(source.evidence().issuanceFence()).isEqualTo(accountIssuanceFence(fixture));
    assertThat(source.evidence().fenceSourceVersion())
        .isEqualTo(accountIssuanceFenceSourceVersion(fixture));
    assertThat(source.evidence().digest())
        .containsExactly(independentlyRehashCapture(source.evidence()));
    assertThat(source.binding().issuanceFenceDigest()).containsExactly(source.evidence().digest());

    String operationBeforeRead = operationFingerprint(source.claim().operation().operationId());
    String envelopeBeforeRead = envelopeFingerprint(source.claim().operation().operationId());
    CaptureReadback readback =
        ownerTransaction(
            () ->
                new CaptureReadback(
                    captureService().read(source.identity(), source.requestDigest()).orElseThrow(),
                    connectIssuanceRepository
                        .readCommittedResponseEnvelope(source.identity())
                        .orElseThrow()));

    assertThat(readback.evidence()).isEqualTo(source.evidence());
    assertThat(readback.source().operation().operationId())
        .isEqualTo(source.claim().operation().operationId());
    assertThat(readback.source().operation().issuanceFenceDigest())
        .containsExactly(source.evidence().digest());
    assertThat(readback.source().responseEnvelope().binding()).isEqualTo(source.binding());
    assertThat(readback.source().responseEnvelope().envelope()).isEqualTo(source.envelope());
    assertThat(operationFingerprint(source.claim().operation().operationId()))
        .isEqualTo(operationBeforeRead);
    assertThat(envelopeFingerprint(source.claim().operation().operationId()))
        .isEqualTo(envelopeBeforeRead);
    assertThat(accountSourceFingerprint(fixture)).isEqualTo(accountSourceBefore);

    RuntimeMembershipSnapshotDto membershipAfter = readRuntimeMembershipSnapshot(fixture);
    assertThat(membershipAfter.membershipBaseline())
        .isEqualTo(membershipBefore.membershipBaseline());
    assertThat(membershipAfter.roles()).isEqualTo(membershipBefore.roles());
    assertThat(membershipAfter.authorityTuple()).isEqualTo(membershipBefore.authorityTuple());
    assertThat(membershipAfter.issuanceFence()).isEqualTo(membershipBefore.issuanceFence());
    assertThat(membershipAfter.sourceEvent().canonicalJsonUtf8())
        .containsExactly(membershipBefore.sourceEvent().canonicalJsonUtf8());
    assertThat(membershipAfter.outboxCheckpoints()).isEqualTo(membershipBefore.outboxCheckpoints());
    assertThat(membershipAfter.outboxSourceEvidence())
        .isEqualTo(membershipBefore.outboxSourceEvidence());
  }

  @Test
  void bareLoginCurrentAuthorityReaderReturnsExactRedactedReadbackWithoutMutation()
      throws Exception {
    JoinFixture fixture = fixture();
    assertThat(join(fixture).success()).isTrue();
    ComposedConnectSourceFixture source = createCryptographicConnectSource(fixture, true);
    String operationBefore = operationFingerprint(source.claim().operation().operationId());
    String envelopeBefore = envelopeFingerprint(source.claim().operation().operationId());
    List<List<String>> accountSourceBefore = accountSourceFingerprint(fixture);

    AccountBareLoginCurrentAuthorityReader.CurrentAuthorityReadback readback =
        readCurrentAuthority(source, source.requestDigest());

    assertThat(readback.operationId()).isEqualTo(source.claim().operation().operationId());
    assertThat(readback.accountUuid()).isEqualTo(fixture.accountUuid());
    assertThat(readback.tenantUuid()).isEqualTo(fixture.tenantUuid());
    assertThat(readback.requestId()).isEqualTo(source.identity().requestId());
    assertThat(readback.connectScopeHash())
        .isEqualTo(AccountJoinDigest.tokenHash(source.identity().connectScopeId()));
    assertThat(readback.responseEnvelopeKeyId()).isEqualTo(source.envelope().keyId());
    assertThat(readback.gatewayKeyId()).isEqualTo(GATEWAY_CONTEXT_KEY_ID);
    assertThat(readback.sourceTokenHash()).containsExactly(sha256(source.compactJwt()));
    assertThat(readback.authorityTuple()).isEqualTo(source.membershipSnapshot().authorityTuple());
    assertThat(readback.membershipVersion())
        .isEqualTo(source.membershipSnapshot().membershipBaseline().membershipVersion());
    assertThat(readback.membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(readback.roles()).contains("player");
    assertThat(source.capture().accountUuid()).isEqualTo(fixture.accountUuid());
    assertThat(source.capture().tenantUuid()).isEqualTo(fixture.tenantUuid());
    assertThat(source.capture().issuanceFence()).isEqualTo(accountIssuanceFence(fixture));
    assertThat(source.capture().fenceSourceVersion())
        .isEqualTo(accountIssuanceFenceSourceVersion(fixture));
    assertThat(source.capture().digest())
        .containsExactly(independentlyRehashCapture(source.capture()));
    assertThat(source.binding().issuanceFenceDigest()).containsExactly(source.capture().digest());
    assertThat(readback.issuanceFence()).isEqualTo(source.capture().issuanceFence());
    assertThat(readback.fenceSourceVersion()).isEqualTo(source.capture().fenceSourceVersion());
    assertThat(readback.requestDigest()).containsExactly(source.requestDigest());
    assertThat(readback.captureDigest()).containsExactly(source.capture().digest());
    assertThat(readback.checkpoints())
        .containsExactlyElementsOf(
            source.membershipSnapshot().outboxCheckpoints().stream()
                .map(
                    checkpoint ->
                        new AccountBareLoginCurrentAuthorityReader.CheckpointReadback(
                            checkpoint.outboxStreamKey(), checkpoint.outboxSequence()))
                .toList());
    assertThat(readback.sourceEvents())
        .containsExactlyElementsOf(
            source.membershipSnapshot().outboxSourceEvidence().stream()
                .map(
                    evidence ->
                        new AccountBareLoginCurrentAuthorityReader.SourceEventReadback(
                            evidence.outboxStreamKey(),
                            evidence.outboxSequence(),
                            evidence.eventId(),
                            evidence.eventDigest()))
                .toList());
    assertThat(source.sourceClaims()).doesNotContainKey("issuanceFence");
    assertThat((BigInteger) source.sourceClaims().get("replayAdmissionFence"))
        .isNotEqualTo(BigInteger.valueOf(source.capture().issuanceFence()));
    assertThat(readback.toString())
        .doesNotContain(source.identity().connectScopeId())
        .doesNotContain(source.identity().requestId())
        .doesNotContain(source.tokenIdentity())
        .doesNotContain(source.compactJwt())
        .doesNotContain(source.signedGatewayContext());

    assertThatThrownBy(() -> readCurrentAuthority(source, digest(132)))
        .isInstanceOf(AccountConnectTokenIssuanceRepository.IdempotencyConflictException.class);
    assertThat(operationFingerprint(source.claim().operation().operationId()))
        .isEqualTo(operationBefore);
    assertThat(envelopeFingerprint(source.claim().operation().operationId()))
        .isEqualTo(envelopeBefore);
    assertThat(accountSourceFingerprint(fixture)).isEqualTo(accountSourceBefore);
  }

  @Test
  void bareLoginCurrentAuthorityReaderRejectsMissingCaptureForValidCommittedSource()
      throws Exception {
    JoinFixture fixture = fixture();
    assertThat(join(fixture).success()).isTrue();
    ComposedConnectSourceFixture source = createCryptographicConnectSource(fixture, false);
    String operationBefore = operationFingerprint(source.claim().operation().operationId());
    String envelopeBefore = envelopeFingerprint(source.claim().operation().operationId());
    List<List<String>> accountSourceBefore = accountSourceFingerprint(fixture);

    assertThatThrownBy(() -> readCurrentAuthority(source, source.requestDigest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Exact Account issuance-fence capture is not proved");

    assertThat(
            ownerTransaction(
                () ->
                    connectIssuanceRepository.readIssuanceFenceCapture(
                        source.identity(), source.requestDigest())))
        .isEmpty();
    assertThat(operationFingerprint(source.claim().operation().operationId()))
        .isEqualTo(operationBefore);
    assertThat(envelopeFingerprint(source.claim().operation().operationId()))
        .isEqualTo(envelopeBefore);
    assertThat(accountSourceFingerprint(fixture)).isEqualTo(accountSourceBefore);
  }

  @Test
  void bareLoginCurrentAuthorityReaderRechecksDeadlineAfterAuthorityRead() throws Exception {
    JoinFixture fixture = fixture();
    assertThat(join(fixture).success()).isTrue();
    ComposedConnectSourceFixture source = createCryptographicConnectSource(fixture, true);
    UUID operationId = source.claim().operation().operationId();
    String operationBefore = operationFingerprint(operationId);
    String envelopeBefore = envelopeFingerprint(operationId);
    List<List<String>> accountSourceBefore = accountSourceFingerprint(fixture);
    // The first strict source read consumes four clock reads; the fifth forces its final reread
    // past both signed deadlines after the current-authority reads have completed.
    source.clock().expireAfterReads(5, Duration.ofSeconds(21));

    assertThatThrownBy(() -> readCurrentAuthority(source, source.requestDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Gateway context is expired");

    assertThat(operationFingerprint(operationId)).isEqualTo(operationBefore);
    assertThat(envelopeFingerprint(operationId)).isEqualTo(envelopeBefore);
    assertThat(accountSourceFingerprint(fixture)).isEqualTo(accountSourceBefore);
    assertThat(
            ownerTransaction(
                () ->
                    connectIssuanceRepository
                        .readIssuanceFenceCapture(source.identity(), source.requestDigest())
                        .orElseThrow()))
        .isEqualTo(source.capture());
  }

  @Test
  void bareLoginCurrentAuthorityReaderRejectsSourceAfterAccountAuthorityAndFenceAdvance()
      throws Exception {
    JoinFixture fixture = fixture();
    assertThat(join(fixture).success()).isTrue();
    ComposedConnectSourceFixture source = createCryptographicConnectSource(fixture, true);
    UUID operationId = source.claim().operation().operationId();
    long fenceBefore = accountIssuanceFence(fixture);
    ScopeState expectedAccountState = readAccountAuthority(fixture.accountUuid());
    Account account = readAccount(fixture.accountId());
    String presentedTokenHash = "c".repeat(64);
    String logoutDigest =
        AccountLogoutRequestDigest.accountLogoutAll(
            fixture.accountUuid(), "control-ui", presentedTokenHash);

    assertThat(
            logoutProducer()
                .commit(
                    UUID.randomUUID(),
                    1,
                    logoutDigest,
                    "control-ui",
                    presentedTokenHash,
                    account,
                    expectedAccountState))
        .isEqualTo(AccountLogoutAllAuthorityEventProducer.LogoutAllResult.LOGOUT_ALL_COMMITTED);
    RuntimeMembershipSnapshotDto afterAdvance = readRuntimeMembershipSnapshot(fixture);
    assertThat(afterAdvance.authorityTuple().accountAuthorityGeneration())
        .isEqualTo(Long.toString(expectedAccountState.generation() + 1L));
    assertThat(afterAdvance.issuanceFence()).isEqualTo(Long.toString(fenceBefore + 1L));
    List<List<String>> accountSourceAfterAdvance = accountSourceFingerprint(fixture);
    byte[] membershipEventAfterAdvance = membershipEventBytes(fixture);
    String operationBefore = operationFingerprint(operationId);
    String envelopeBefore = envelopeFingerprint(operationId);

    assertThatThrownBy(() -> readCurrentAuthority(source, source.requestDigest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "Committed gameplay-connect source differs from current Account authority");

    assertThat(operationFingerprint(operationId)).isEqualTo(operationBefore);
    assertThat(envelopeFingerprint(operationId)).isEqualTo(envelopeBefore);
    assertThat(
            ownerTransaction(
                () ->
                    connectIssuanceRepository
                        .readIssuanceFenceCapture(source.identity(), source.requestDigest())
                        .orElseThrow()))
        .isEqualTo(source.capture());
    assertThat(accountSourceFingerprint(fixture)).isEqualTo(accountSourceAfterAdvance);
    assertThat(membershipEventBytes(fixture)).containsExactly(membershipEventAfterAdvance);
    assertThat(membershipRow(fixture).get("lifecycle_state")).isEqualTo("ACTIVE");
  }

  @Test
  void captureRollbackLeavesNeitherCaptureNorPartialCommittedSource() {
    JoinFixture fixture = fixture();
    assertThat(join(fixture).success()).isTrue();
    List<List<String>> accountSourceBefore = accountSourceFingerprint(fixture);
    AccountConnectTokenIssuanceIdentity identity = connectIdentity(fixture);
    byte[] requestDigest = digest(117);

    new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              ClaimResult claim = connectIssuanceRepository.claim(identity, requestDigest);
              AccountConnectIssuanceFenceEvidence evidence =
                  captureService().capture(claim, requestDigest);
              AccountEnvelopeBinding binding =
                  connectSourceBinding(claim, identity, requestDigest, evidence.digest());
              connectIssuanceRepository.completeWithEnvelope(
                  claim,
                  requestDigest,
                  Lifecycle.COMMITTED,
                  "SUCCESS",
                  syntheticTokenIdentity(),
                  digest(118),
                  binding,
                  syntheticEnvelope());
              status.setRollbackOnly();
              return null;
            });

    assertThat(
            ownerTransaction(
                () -> connectIssuanceRepository.find(identity, requestDigest).orElse(null)))
        .isNull();
    assertThat(countConnectEnvelopes(identity)).isZero();
    assertThat(accountSourceFingerprint(fixture)).isEqualTo(accountSourceBefore);
    assertThat(countMembershipRows(fixture)).isEqualTo(1L);
    assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(1L);
    assertThat(countStreamEvents(membershipStreamKey(fixture))).isEqualTo(1L);
  }

  @Test
  void absentAndInactiveMembershipRejectCaptureWithoutInitializingOrRejoining() {
    JoinFixture neverJoined = fixture();
    assertCaptureRejectedWithoutMembershipMutation(neverJoined, false);

    JoinFixture inactive = fixture();
    assertThat(join(inactive).success()).isTrue();
    MembershipTransitionReceipt leftReceipt =
        membershipLifecycleService.leave(
            inactive.accountId(), inactive.tenantId(), "capture-left-" + UUID.randomUUID());
    assertThat(leftReceipt.transitionType()).isEqualTo("MEMBERSHIP_LEFT");
    assertCaptureRejectedWithoutMembershipMutation(inactive, true);
  }

  @Test
  void capturedEvidenceCannotBeReplacedByChangedIdentityDigestOrFenceValues() {
    JoinFixture fixture = fixture();
    assertThat(join(fixture).success()).isTrue();
    ConnectClaimFixture claim = claimAndCapture(fixture);
    assertThat(claim.claim().operation().lifecycle()).isEqualTo(Lifecycle.PENDING);
    assertThat(countConnectEnvelopes(claim.identity())).isZero();
    String operationBefore = operationFingerprint(claim.claim().operation().operationId());
    AccountConnectIssuanceFenceEvidence original = claim.evidence();

    assertCaptureReplacementRejected(
        claim,
        AccountConnectIssuanceFenceEvidence.capture(
            UUID.randomUUID(),
            original.accountUuid(),
            original.tenantUuid(),
            original.connectScopeHash(),
            original.requestId(),
            original.requestDigest(),
            original.issuanceFence(),
            original.fenceSourceVersion()));
    assertCaptureReplacementRejected(
        claim,
        AccountConnectIssuanceFenceEvidence.capture(
            original.operationId(),
            UUID.randomUUID(),
            original.tenantUuid(),
            original.connectScopeHash(),
            original.requestId(),
            original.requestDigest(),
            original.issuanceFence(),
            original.fenceSourceVersion()));
    assertCaptureReplacementRejected(
        claim,
        AccountConnectIssuanceFenceEvidence.capture(
            original.operationId(),
            original.accountUuid(),
            UUID.randomUUID(),
            original.connectScopeHash(),
            original.requestId(),
            original.requestDigest(),
            original.issuanceFence(),
            original.fenceSourceVersion()));
    assertCaptureReplacementRejected(
        claim,
        AccountConnectIssuanceFenceEvidence.capture(
            original.operationId(),
            original.accountUuid(),
            original.tenantUuid(),
            AccountJoinDigest.tokenHash("changed-connect-scope"),
            original.requestId(),
            original.requestDigest(),
            original.issuanceFence(),
            original.fenceSourceVersion()));
    assertCaptureReplacementRejected(
        claim,
        AccountConnectIssuanceFenceEvidence.capture(
            original.operationId(),
            original.accountUuid(),
            original.tenantUuid(),
            original.connectScopeHash(),
            "changed-request-id",
            original.requestDigest(),
            original.issuanceFence(),
            original.fenceSourceVersion()));
    assertCaptureReplacementRejected(
        claim,
        AccountConnectIssuanceFenceEvidence.capture(
            original.operationId(),
            original.accountUuid(),
            original.tenantUuid(),
            original.connectScopeHash(),
            original.requestId(),
            digest(119),
            original.issuanceFence(),
            original.fenceSourceVersion()));
    assertCaptureReplacementRejected(
        claim,
        AccountConnectIssuanceFenceEvidence.capture(
            original.operationId(),
            original.accountUuid(),
            original.tenantUuid(),
            original.connectScopeHash(),
            original.requestId(),
            original.requestDigest(),
            original.issuanceFence() + 1L,
            original.fenceSourceVersion()));
    assertCaptureReplacementRejected(
        claim,
        AccountConnectIssuanceFenceEvidence.capture(
            original.operationId(),
            original.accountUuid(),
            original.tenantUuid(),
            original.connectScopeHash(),
            original.requestId(),
            original.requestDigest(),
            original.issuanceFence(),
            original.fenceSourceVersion() + 1L));
    assertThatThrownBy(
            () -> ownerTransaction(() -> captureService().capture(claim.claim(), digest(120))))
        .isInstanceOf(AccountConnectTokenIssuanceRepository.IdempotencyConflictException.class);

    assertThat(operationFingerprint(claim.claim().operation().operationId()))
        .isEqualTo(operationBefore);
    assertThat(
            ownerTransaction(
                () -> captureService().read(claim.identity(), claim.requestDigest()).orElseThrow()))
        .isEqualTo(original);
  }

  @Test
  void directRepositoryRejectsCaptureForAnotherPersistedAccountUuid() {
    JoinFixture fixture = fixture();
    assertThat(join(fixture).success()).isTrue();
    AccountConnectTokenIssuanceIdentity identity = connectIdentity(fixture);
    byte[] requestDigest = digest(130);
    ClaimResult claim =
        ownerTransaction(() -> connectIssuanceRepository.claim(identity, requestDigest));
    long foreignAccountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash) "
                        + "VALUES (?, ?, ?) RETURNING id",
                    "capture-foreign-" + UUID.randomUUID(),
                    "capture-foreign-" + UUID.randomUUID() + "@example.com",
                    "test-hash")
                .fetchOne(0, Long.class));
    UUID foreignAccountUuid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", foreignAccountId)
                .fetchOne(0, UUID.class));
    assertThat(foreignAccountId).isNotEqualTo(fixture.accountId());
    assertThat(foreignAccountUuid).isNotEqualTo(fixture.accountUuid());
    AccountConnectIssuanceFenceEvidence foreignAccountEvidence =
        AccountConnectIssuanceFenceEvidence.capture(
            claim.operation().operationId(),
            foreignAccountUuid,
            fixture.tenantUuid(),
            AccountJoinDigest.tokenHash(identity.connectScopeId()),
            identity.requestId(),
            requestDigest,
            accountIssuanceFence(fixture),
            accountIssuanceFenceSourceVersion(fixture));
    String operationBefore = operationFingerprint(claim.operation().operationId());
    List<List<String>> accountSourceBefore = accountSourceFingerprint(fixture);

    assertThatThrownBy(
            () ->
                ownerTransaction(
                    () ->
                        connectIssuanceRepository.captureIssuanceFence(
                            claim, requestDigest, foreignAccountEvidence)))
        .isInstanceOf(AccountConnectTokenIssuanceRepository.EvidenceMismatchException.class)
        .hasMessageContaining("exact Account UUID provenance");

    assertThat(operationFingerprint(claim.operation().operationId())).isEqualTo(operationBefore);
    assertThat(accountSourceFingerprint(fixture)).isEqualTo(accountSourceBefore);
    assertThat(countConnectEnvelopes(identity)).isZero();
    assertThat(
            ownerTransaction(
                () -> connectIssuanceRepository.readIssuanceFenceCapture(identity, requestDigest)))
        .isEmpty();
  }

  @Test
  void populatedCaptureIsImmutableAndTerminalSourceCannotBeBackfilled() {
    JoinFixture fixture = fixture();
    assertThat(join(fixture).success()).isTrue();
    ConnectSourceFixture committed = createCommittedConnectSource(fixture, true);
    UUID operationId = committed.claim().operation().operationId();
    String operationBefore = operationFingerprint(operationId);
    String envelopeBefore = envelopeFingerprint(operationId);

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE account_connect_token_issuance_operations "
                        + "SET issuance_fence_capture_value = ? WHERE operation_id = ?",
                    committed.evidence().issuanceFence() + 1L,
                    operationId))
        .hasMessageContaining("capture cannot be replaced or removed");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE account_connect_token_issuance_operations SET "
                        + "issuance_fence_capture_schema = NULL, "
                        + "issuance_fence_capture_account_uuid = NULL, "
                        + "issuance_fence_capture_value = NULL, "
                        + "issuance_fence_capture_source_version = NULL, "
                        + "issuance_fence_capture_digest = NULL WHERE operation_id = ?",
                    operationId))
        .hasMessageContaining("capture cannot be replaced or removed");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE account_connect_token_response_envelopes SET ciphertext = ? "
                        + "WHERE operation_id = ?",
                    new byte[AccountEncryptedEnvelope.AUTHENTICATION_TAG_LENGTH_BYTES],
                    operationId))
        .hasMessageContaining("response envelope is immutable");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM account_connect_token_issuance_operations WHERE operation_id = ?",
                    operationId))
        .hasMessageContaining("replay evidence cannot be deleted");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM account_connect_token_response_envelopes WHERE operation_id = ?",
                    operationId))
        .hasMessageContaining("replay evidence cannot be deleted");
    assertThat(operationFingerprint(operationId)).isEqualTo(operationBefore);
    assertThat(envelopeFingerprint(operationId)).isEqualTo(envelopeBefore);

    ConnectSourceFixture legacyTerminal = createCommittedConnectSource(fixture, false);
    UUID legacyOperationId = legacyTerminal.claim().operation().operationId();
    String legacyOperationBefore = operationFingerprint(legacyOperationId);
    String legacyEnvelopeBefore = envelopeFingerprint(legacyOperationId);
    assertThat(
            ownerTransaction(
                () ->
                    captureService()
                        .read(legacyTerminal.identity(), legacyTerminal.requestDigest())))
        .isEmpty();
    assertThatThrownBy(
            () ->
                ownerTransaction(
                    () ->
                        captureService()
                            .capture(legacyTerminal.claim(), legacyTerminal.requestDigest())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pending");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE account_connect_token_issuance_operations SET "
                        + "issuance_fence_capture_schema = ?, "
                        + "issuance_fence_capture_account_uuid = ?, "
                        + "issuance_fence_capture_value = ?, "
                        + "issuance_fence_capture_source_version = ?, "
                        + "issuance_fence_capture_digest = ? WHERE operation_id = ?",
                    legacyTerminal.evidence().schemaName(),
                    legacyTerminal.evidence().accountUuid(),
                    legacyTerminal.evidence().issuanceFence(),
                    legacyTerminal.evidence().fenceSourceVersion(),
                    legacyTerminal.evidence().digest(),
                    legacyOperationId))
        .hasMessageContaining("capture requires a pending operation");
    assertThat(operationFingerprint(legacyOperationId)).isEqualTo(legacyOperationBefore);
    assertThat(envelopeFingerprint(legacyOperationId)).isEqualTo(legacyEnvelopeBefore);
    assertThat(
            ownerTransaction(
                () ->
                    captureService()
                        .read(legacyTerminal.identity(), legacyTerminal.requestDigest())))
        .isEmpty();
  }

  private void assertCaptureRejectedWithoutMembershipMutation(
      JoinFixture fixture, boolean hasHistoricalMembership) {
    RuntimeMembershipSnapshotDto membershipBefore = readRuntimeMembershipSnapshot(fixture);
    List<List<String>> accountSourceBefore = accountSourceFingerprint(fixture);
    AccountConnectTokenIssuanceIdentity identity = connectIdentity(fixture);
    byte[] requestDigest = digest(hasHistoricalMembership ? 122 : 121);
    ClaimResult claim =
        ownerTransaction(() -> connectIssuanceRepository.claim(identity, requestDigest));
    String operationBefore = operationFingerprint(claim.operation().operationId());

    assertThatThrownBy(() -> ownerTransaction(() -> captureService().capture(claim, requestDigest)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("existing ACTIVE Account membership");

    assertThat(
            ownerTransaction(
                () -> connectIssuanceRepository.readIssuanceFenceCapture(identity, requestDigest)))
        .isEmpty();
    assertThat(operationFingerprint(claim.operation().operationId())).isEqualTo(operationBefore);
    assertThat(accountSourceFingerprint(fixture)).isEqualTo(accountSourceBefore);
    RuntimeMembershipSnapshotDto membershipAfter = readRuntimeMembershipSnapshot(fixture);
    assertThat(membershipAfter.membershipBaseline())
        .isEqualTo(membershipBefore.membershipBaseline());
    assertThat(membershipAfter.roles()).isEqualTo(membershipBefore.roles());
    assertThat(membershipAfter.outboxCheckpoints()).isEqualTo(membershipBefore.outboxCheckpoints());
    assertThat(membershipAfter.outboxSourceEvidence())
        .isEqualTo(membershipBefore.outboxSourceEvidence());
    if (hasHistoricalMembership) {
      assertThat(membershipAfter.membershipExists()).isTrue();
      assertThat(membershipAfter.membershipBaseline().membershipLifecycleState())
          .isEqualTo("INACTIVE");
      assertThat(countMembershipRows(fixture)).isEqualTo(1L);
      assertThat(countMembershipTransitionReceipts(fixture)).isEqualTo(2L);
      assertThat(countStreamEvents(membershipStreamKey(fixture))).isEqualTo(2L);
    } else {
      assertThat(membershipAfter.membershipExists()).isFalse();
      assertThat(membershipAfter.gameplayAdmissionAllowed()).isFalse();
      assertThat(membershipAfter.membershipBaseline().membershipLifecycleState())
          .isEqualTo("MISSING");
      assertThat(countMembershipRows(fixture)).isZero();
      assertThat(countMembershipTransitionReceipts(fixture)).isZero();
      assertThat(countStreamEvents(membershipStreamKey(fixture))).isZero();
    }
  }

  private ConnectClaimFixture claimAndCapture(JoinFixture fixture) {
    AccountConnectTokenIssuanceIdentity identity = connectIdentity(fixture);
    byte[] requestDigest = digest(123);
    return ownerTransaction(
        () -> {
          ClaimResult claim = connectIssuanceRepository.claim(identity, requestDigest);
          AccountConnectIssuanceFenceEvidence evidence =
              captureService().capture(claim, requestDigest);
          return new ConnectClaimFixture(identity, requestDigest, claim, evidence);
        });
  }

  private void assertCaptureReplacementRejected(
      ConnectClaimFixture claim, AccountConnectIssuanceFenceEvidence candidate) {
    assertThatThrownBy(
            () ->
                ownerTransaction(
                    () ->
                        connectIssuanceRepository.captureIssuanceFence(
                            claim.claim(), claim.requestDigest(), candidate)))
        .isInstanceOf(AccountConnectTokenIssuanceRepository.EvidenceMismatchException.class);
  }

  private ConnectSourceFixture createCommittedConnectSource(
      JoinFixture fixture, boolean captureCurrentFence) {
    AccountConnectTokenIssuanceIdentity identity = connectIdentity(fixture);
    byte[] requestDigest = digest(captureCurrentFence ? 124 : 125);
    return Objects.requireNonNull(
        ownerTransaction(
            () -> {
              ClaimResult claim = connectIssuanceRepository.claim(identity, requestDigest);
              AccountConnectIssuanceFenceEvidence evidence =
                  captureCurrentFence
                      ? captureService().capture(claim, requestDigest)
                      : currentFenceCandidate(fixture, identity, requestDigest, claim);
              if (captureCurrentFence) {
                assertThat(captureService().capture(claim, requestDigest)).isEqualTo(evidence);
              }
              AccountEnvelopeBinding binding =
                  connectSourceBinding(claim, identity, requestDigest, evidence.digest());
              AccountEncryptedEnvelope envelope = syntheticEnvelope();
              connectIssuanceRepository.completeWithEnvelope(
                  claim,
                  requestDigest,
                  Lifecycle.COMMITTED,
                  "SUCCESS",
                  syntheticTokenIdentity(),
                  digest(126),
                  binding,
                  envelope);
              return new ConnectSourceFixture(
                  identity, requestDigest, claim, evidence, binding, envelope);
            }));
  }

  private ComposedConnectSourceFixture createCryptographicConnectSource(
      JoinFixture fixture, boolean persistCapture) throws Exception {
    ProofClock clock = new ProofClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    KeyPair accountSigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair gatewaySigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    Map<String, java.security.PublicKey> gatewayKeys =
        Map.of(GATEWAY_CONTEXT_KEY_ID, gatewaySigner.getPublic());
    AccountEnvelopeCrypto envelopeCrypto = ephemeralEnvelopeCrypto();
    AccountGameplayConnectSourceVerifier sourceVerifier =
        new AccountGameplayConnectSourceVerifier(
            AccountServiceImpl.ACCOUNT_JWT_ISSUER,
            Map.of(ACCOUNT_SOURCE_KEY_ID, accountSigner.getPublic()),
            16 * 1024,
            clock);
    AccountTenantIdentityResolver tenantIdentityResolver =
        new AccountTenantIdentityResolver(
            tenantAssociationRepository, legacyTenantSourceEvidence, WORKLOAD_NAMESPACE);
    AccountCommittedConnectSourceReader sourceReader =
        new AccountCommittedConnectSourceReader(
            connectIssuanceRepository,
            accountRepository,
            tenantIdentityResolver,
            freshTenantIdentityRepository,
            joinOperationRepository,
            envelopeCrypto,
            sourceVerifier,
            gatewayKeys,
            clock,
            WORKLOAD_NAMESPACE);
    AccountBareLoginCurrentAuthorityReader composedReader =
        new AccountBareLoginCurrentAuthorityReader(
            sourceReader,
            captureService(),
            membershipAuthorityEventProducer,
            authorityGenerationRepository);
    // Synthetic source scope/target only: this composes durable evidence without invoking
    // connect-token issuance, selected-target catalog resolution, or an entitlement issuer.
    AccountConnectTokenIssuanceIdentity identity =
        new AccountConnectTokenIssuanceIdentity(
            fixture.accountId(),
            fixture.tenantUuid(),
            "synthetic-capture-scope-" + UUID.randomUUID(),
            "synthetic-capture-request-" + UUID.randomUUID());
    byte[] requestDigest = digest(131);
    String tokenIdentity = "capture-composition-jti-" + UUID.randomUUID();

    return ownerTransaction(
        () -> {
          ClaimResult claim = connectIssuanceRepository.claim(identity, requestDigest);
          AccountConnectIssuanceFenceEvidence capture =
              persistCapture
                  ? captureService().capture(claim, requestDigest)
                  : currentFenceCandidate(fixture, identity, requestDigest, claim);
          RuntimeMembershipSnapshotDto membershipSnapshot =
              membershipAuthorityEventProducer.readExistingRuntimeMembershipSnapshot(
                  fixture.accountUuid(), fixture.tenantUuid());
          Map<String, Object> sourceClaims =
              currentGameplayConnectClaims(
                  fixture, identity, membershipSnapshot, tokenIdentity, clock.instant());
          byte[] compactJwtBytes = signAccountGameplayConnectJwt(sourceClaims, accountSigner);
          String compactJwt = new String(compactJwtBytes, StandardCharsets.US_ASCII);
          Map<String, Object> gatewayClaims =
              GatewayConnectContextCodec.projectVerifiedAccountGameplayConnectClaims(
                  sourceClaims,
                  clock.instant().getEpochSecond(),
                  "gateway-capture-proof-" + UUID.randomUUID());
          String signedGatewayContext =
              GatewayConnectContextSignature.sign(
                  JSON.writeValueAsBytes(gatewayClaims),
                  GATEWAY_CONTEXT_KEY_ID,
                  gatewaySigner.getPrivate());
          AccountEnvelopeBinding binding =
              connectSourceBinding(claim, identity, requestDigest, capture.digest());
          AccountEncryptedEnvelope envelope =
              envelopeCrypto.encrypt(
                  AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, binding, compactJwtBytes);
          connectIssuanceRepository.completeWithEnvelope(
              claim,
              requestDigest,
              Lifecycle.COMMITTED,
              "SUCCESS",
              tokenIdentity,
              sha256(compactJwtBytes),
              binding,
              envelope);
          return new ComposedConnectSourceFixture(
              composedReader,
              identity,
              requestDigest,
              claim,
              capture,
              membershipSnapshot,
              binding,
              envelope,
              tokenIdentity,
              compactJwt,
              signedGatewayContext,
              sourceClaims,
              clock);
        });
  }

  private Map<String, Object> currentGameplayConnectClaims(
      JoinFixture fixture,
      AccountConnectTokenIssuanceIdentity identity,
      RuntimeMembershipSnapshotDto membershipSnapshot,
      String tokenIdentity,
      Instant issuedAt) {
    BigInteger now = BigInteger.valueOf(issuedAt.getEpochSecond());
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", AccountServiceImpl.ACCOUNT_JWT_ISSUER);
    claims.put("aud", "gameplay-connect");
    claims.put("iat", now.subtract(BigInteger.ONE));
    claims.put("exp", now.add(BigInteger.valueOf(20L)));
    claims.put("jti", tokenIdentity);
    claims.put("accountId", fixture.accountUuid().toString());
    claims.put("tenantId", fixture.tenantUuid().toString());
    // Routing claims are synthetic fixture values; the authority tuple and membership version
    // below are copied from the real current Account snapshot.
    claims.put("realmId", REALM_ID.toString());
    claims.put("worldSlug", WORLD_SLUG);
    claims.put("realmSlug", REALM_SLUG);
    claims.put("playableStateNamespaceId", "cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    claims.put("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED");
    claims.put("gameInstanceId", "dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    claims.put("pointerVersion", BigInteger.valueOf(POINTER_VERSION));
    claims.put("catalogRevision", BigInteger.valueOf(CATALOG_REVISION));
    claims.put("connectScopeId", identity.connectScopeId());
    claims.put("requestId", identity.requestId());
    claims.put("authorityTuple", authorityTupleClaims(membershipSnapshot.authorityTuple()));
    claims.put(
        "membershipVersion",
        jsonCounterMap(membershipSnapshot.membershipBaseline().membershipVersion()));
    // This is a synthetic replay-admission fixture value, not Account's separate capture fence.
    claims.put("replayAdmissionFence", BigInteger.valueOf(9_001L));
    return Map.copyOf(claims);
  }

  private Map<String, Object> authorityTupleClaims(
      MembershipAuthorityEventV1Codec.AuthorityTuple tuple) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("issuerAuthGeneration", new BigInteger(tuple.issuerAuthGeneration()));
    claims.put("accountAuthorityGeneration", new BigInteger(tuple.accountAuthorityGeneration()));
    claims.put("tenantAuthorityGeneration", jsonCounterMap(tuple.tenantAuthorityGeneration()));
    claims.put(
        "membershipAuthorityGeneration", jsonCounterMap(tuple.membershipAuthorityGeneration()));
    claims.put(
        "privateRealmGrantVersions",
        tuple.privateRealmGrantVersions().stream()
            .map(
                grant ->
                    Map.<String, Object>of(
                        "tenantId", grant.tenantId(),
                        "worldSlug", grant.worldSlug(),
                        "realmSlug", grant.realmSlug(),
                        "playtestLifecycleId", grant.playtestLifecycleId(),
                        "grantVersion", new BigInteger(grant.grantVersion())))
            .toList());
    tuple
        .accountSecurityCutoff()
        .ifPresent(
            cutoff ->
                claims.put(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration",
                        new BigInteger(cutoff.accountAuthorityGeneration()),
                        "outboxStreamKey",
                        cutoff.outboxStreamKey(),
                        "outboxSequence",
                        new BigInteger(cutoff.outboxSequence()))));
    tuple
        .tenantBillingCutoff()
        .ifPresent(
            cutoffs -> {
              Map<String, Object> projected = new LinkedHashMap<>();
              cutoffs.forEach(
                  (tenantId, cutoff) ->
                      projected.put(
                          tenantId,
                          Map.of(
                              "tenantAuthorityGeneration",
                              new BigInteger(cutoff.tenantAuthorityGeneration()),
                              "tenantBillingSequence",
                              new BigInteger(cutoff.tenantBillingSequence()),
                              "outboxStreamKey",
                              cutoff.outboxStreamKey(),
                              "outboxSequence",
                              new BigInteger(cutoff.outboxSequence()))));
              claims.put("tenantBillingCutoff", Map.copyOf(projected));
            });
    return Map.copyOf(claims);
  }

  private Map<String, Object> jsonCounterMap(Map<String, String> values) {
    Map<String, Object> projected = new LinkedHashMap<>();
    values.forEach((key, value) -> projected.put(key, new BigInteger(value)));
    return Map.copyOf(projected);
  }

  private byte[] signAccountGameplayConnectJwt(Map<String, Object> claims, KeyPair accountSigner) {
    try {
      String header =
          "{\"alg\":\"EdDSA\",\"kid\":\"" + ACCOUNT_SOURCE_KEY_ID + "\",\"typ\":\"JWT\"}";
      String encodedHeader = BASE64_URL.encodeToString(header.getBytes(StandardCharsets.US_ASCII));
      String encodedPayload = BASE64_URL.encodeToString(JSON.writeValueAsBytes(claims));
      String signingInput = encodedHeader + "." + encodedPayload;
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(accountSigner.getPrivate());
      signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return (signingInput + "." + BASE64_URL.encodeToString(signature.sign()))
          .getBytes(StandardCharsets.US_ASCII);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(
          "Could not sign synthetic Account gameplay-connect source fixture", exception);
    }
  }

  private AccountEnvelopeCrypto ephemeralEnvelopeCrypto() throws Exception {
    byte[] bareLoginKey = new byte[32];
    byte[] connectTokenKey = new byte[32];
    FIXTURE_RANDOM.nextBytes(bareLoginKey);
    do {
      FIXTURE_RANDOM.nextBytes(connectTokenKey);
    } while (MessageDigest.isEqual(bareLoginKey, connectTokenKey));
    String manifest =
        "version=1\nactiveKeyId=capture-test-key\nkey:capture-test-key:bare-login="
            + BASE64_URL.encodeToString(bareLoginKey)
            + "\nkey:capture-test-key:connect-token="
            + BASE64_URL.encodeToString(connectTokenKey)
            + "\n";
    Path manifestPath = tempDir.resolve("capture-envelope-ring-" + UUID.randomUUID() + ".v1");
    Files.writeString(manifestPath, manifest, StandardCharsets.US_ASCII);
    java.util.Arrays.fill(bareLoginKey, (byte) 0);
    java.util.Arrays.fill(connectTokenKey, (byte) 0);
    return new AccountEnvelopeCrypto(manifestPath);
  }

  private AccountBareLoginCurrentAuthorityReader.CurrentAuthorityReadback readCurrentAuthority(
      ComposedConnectSourceFixture source, byte[] requestDigest) {
    return withGameSessionPeer(
        () ->
            ownerTransaction(
                () ->
                    source
                        .reader()
                        .read(source.identity(), requestDigest, source.signedGatewayContext())));
  }

  private <T> T withGameSessionPeer(Supplier<T> operation) {
    Context scoped =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri(
                        "spiffe://firemud/ns/" + WORKLOAD_NAMESPACE + "/sa/game-session-service")
                    .orElseThrow());
    Context previous = scoped.attach();
    try {
      return operation.get();
    } finally {
      scoped.detach(previous);
    }
  }

  private byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private byte[] sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.US_ASCII));
  }

  private List<List<String>> membershipAuthoritySourceFingerprint(JoinFixture fixture) {
    String streamKey = membershipStreamKey(fixture);
    return List.of(
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_tenant_membership "
                + "WHERE account_id = ? AND tenant_id = ?",
            fixture.accountId(),
            fixture.tenantId()),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_membership_pair_authority "
                + "WHERE account_uuid = ? AND tenant_uuid = ?",
            fixture.accountUuid(),
            fixture.tenantUuid()),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_authority_generations "
                + "WHERE scope_kind = 'MEMBERSHIP' AND account_uuid = ? AND tenant_uuid = ?",
            fixture.accountUuid(),
            fixture.tenantUuid()),
        canonicalRows(
            "SELECT s.xmin::text AS snapshot_xmin, r.xmin::text AS role_xmin, "
                + "s.membership_id, s.snapshot_version, r.role_identifier "
                + "FROM account_tenant_membership_role_snapshots s "
                + "LEFT JOIN account_tenant_membership_role_snapshot_roles r "
                + "USING (membership_id, snapshot_version) WHERE s.membership_id = "
                + "(SELECT id FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?) "
                + "ORDER BY r.role_identifier",
            fixture.accountId(),
            fixture.tenantId()),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_membership_transition_receipt_stream_heads "
                + "WHERE account_id = ? AND tenant_id = ?",
            fixture.accountId(),
            fixture.tenantId()),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_membership_transition_receipts "
                + "WHERE account_id = ? AND tenant_id = ? ORDER BY receipt_sequence",
            fixture.accountId(),
            fixture.tenantId()),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_authority_outbox_streams "
                + "WHERE outbox_stream_key = ?",
            streamKey),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_authority_outbox_events "
                + "WHERE outbox_stream_key = ? ORDER BY outbox_sequence",
            streamKey));
  }

  private AccountConnectIssuanceFenceEvidence currentFenceCandidate(
      JoinFixture fixture,
      AccountConnectTokenIssuanceIdentity identity,
      byte[] requestDigest,
      ClaimResult claim) {
    return AccountConnectIssuanceFenceEvidence.capture(
        claim.operation().operationId(),
        fixture.accountUuid(),
        fixture.tenantUuid(),
        AccountJoinDigest.tokenHash(identity.connectScopeId()),
        identity.requestId(),
        requestDigest,
        accountIssuanceFence(fixture),
        accountIssuanceFenceSourceVersion(fixture));
  }

  private AccountEnvelopeBinding connectSourceBinding(
      ClaimResult claim,
      AccountConnectTokenIssuanceIdentity identity,
      byte[] requestDigest,
      byte[] issuanceFenceDigest) {
    return new AccountEnvelopeBinding(
        AccountEnvelopeBinding.OperationKind.CONNECT_TOKEN_ISSUANCE,
        claim.operation().operationId().toString(),
        identity.requestId(),
        Long.toString(identity.accountId()),
        identity.tenantId().toString(),
        identity.connectScopeId(),
        null,
        requestDigest,
        digest(127),
        digest(128),
        issuanceFenceDigest,
        digest(129));
  }

  private AccountConnectTokenAuthorityCaptureService captureService() {
    return new AccountConnectTokenAuthorityCaptureService(
        connectIssuanceRepository,
        accountRepository,
        joinOperationRepository,
        membershipAuthorityEventProducer,
        authorityGenerationRepository,
        dataSource);
  }

  private AccountConnectTokenIssuanceIdentity connectIdentity(JoinFixture fixture) {
    return new AccountConnectTokenIssuanceIdentity(
        fixture.accountId(),
        fixture.tenantUuid(),
        fixture.scope().connectScopeId(),
        "capture-connect-" + UUID.randomUUID());
  }

  private AccountEncryptedEnvelope syntheticEnvelope() {
    // Opaque fixture bytes exercise durable source binding; they are neither AEAD output nor a JWT.
    return new AccountEncryptedEnvelope(
        AccountEncryptedEnvelope.CURRENT_FORMAT_VERSION,
        "fixture_key",
        AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE,
        new byte[AccountEncryptedEnvelope.NONCE_LENGTH_BYTES],
        new byte[AccountEncryptedEnvelope.AUTHENTICATION_TAG_LENGTH_BYTES]);
  }

  private String syntheticTokenIdentity() {
    return "synthetic-fixture-not-jwt-" + UUID.randomUUID();
  }

  private byte[] digest(int seed) {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) {
      value[index] = (byte) (seed + index);
    }
    return value;
  }

  private List<List<String>> accountSourceFingerprint(JoinFixture fixture) {
    String account = accountStreamKey(fixture.accountUuid());
    String issuer = issuerStreamKey();
    String membership = membershipStreamKey(fixture);
    String tenant = tenantStreamKey(fixture.tenantUuid());
    Object[] streamKeys = {account, issuer, membership, tenant};
    return List.of(
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM accounts WHERE id = ?", fixture.accountId()),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_approved_legacy_tenant_associations "
                + "WHERE legacy_tenant_id = ?",
            fixture.tenantId()),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_canonical_tenant_identity_claims "
                + "WHERE canonical_tenant_id = ?",
            fixture.tenantUuid()),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_authority_generations "
                + "WHERE (scope_kind = 'ISSUER' AND issuer_id = ?) OR account_uuid = ? "
                + "OR tenant_uuid = ? ORDER BY scope_kind, issuer_id, account_uuid, tenant_uuid",
            AccountServiceImpl.ACCOUNT_JWT_ISSUER,
            fixture.accountUuid(),
            fixture.tenantUuid()),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_authority_issuance_fences "
                + "WHERE account_uuid = ?",
            fixture.accountUuid()),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_tenant_membership "
                + "WHERE account_id = ? AND tenant_id = ?",
            fixture.accountId(),
            fixture.tenantId()),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_membership_pair_authority "
                + "WHERE account_uuid = ? AND tenant_uuid = ?",
            fixture.accountUuid(),
            fixture.tenantUuid()),
        canonicalRows(
            "SELECT s.xmin::text AS snapshot_xmin, r.xmin::text AS role_xmin, "
                + "s.membership_id, s.snapshot_version, r.role_identifier "
                + "FROM account_tenant_membership_role_snapshots s "
                + "LEFT JOIN account_tenant_membership_role_snapshot_roles r "
                + "USING (membership_id, snapshot_version) WHERE s.membership_id = "
                + "(SELECT id FROM account_tenant_membership WHERE account_id = ? AND tenant_id = ?) "
                + "ORDER BY r.role_identifier",
            fixture.accountId(),
            fixture.tenantId()),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_membership_transition_receipt_stream_heads "
                + "WHERE account_id = ? AND tenant_id = ?",
            fixture.accountId(),
            fixture.tenantId()),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_membership_transition_receipts "
                + "WHERE account_id = ? AND tenant_id = ? ORDER BY receipt_sequence",
            fixture.accountId(),
            fixture.tenantId()),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_audit_outbox WHERE tenant_id = ? "
                + "ORDER BY created_at, audit_event_id",
            fixture.tenantId()),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_authority_outbox_streams "
                + "WHERE outbox_stream_key IN (?, ?, ?, ?) ORDER BY outbox_stream_key",
            streamKeys),
        canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_authority_outbox_events "
                + "WHERE outbox_stream_key IN (?, ?, ?, ?) ORDER BY outbox_stream_key, outbox_sequence",
            streamKeys));
  }

  private List<String> canonicalRows(String query, Object... bindValues) {
    return dsl.resultQuery(query, bindValues).fetch().stream()
        .map(row -> canonicalRow(row.intoMap()))
        .toList();
  }

  private String canonicalRow(Map<String, Object> row) {
    return row.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry ->
                entry.getKey()
                    + "="
                    + (entry.getValue() instanceof byte[] bytes
                        ? HexFormat.of().formatHex(bytes)
                        : String.valueOf(entry.getValue())))
        .toList()
        .toString();
  }

  private String operationFingerprint(UUID operationId) {
    return canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_connect_token_issuance_operations "
                + "WHERE operation_id = ?",
            operationId)
        .toString();
  }

  private String envelopeFingerprint(UUID operationId) {
    return canonicalRows(
            "SELECT xmin::text AS row_xmin, * FROM account_connect_token_response_envelopes "
                + "WHERE operation_id = ?",
            operationId)
        .toString();
  }

  private long countConnectEnvelopes(AccountConnectTokenIssuanceIdentity identity) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_connect_token_response_envelopes "
                    + "WHERE account_id = ? AND tenant_id = ? AND connect_scope_hash = ? "
                    + "AND request_id = ?",
                identity.accountId(),
                identity.tenantId(),
                AccountJoinDigest.tokenHash(identity.connectScopeId()),
                identity.requestId())
            .fetchOne(0, Long.class));
  }

  private byte[] independentlyRehashCapture(AccountConnectIssuanceFenceEvidence evidence) {
    ByteArrayOutputStream preimage = new ByteArrayOutputStream();
    appendFrame(preimage, evidence.schemaName());
    appendFrame(preimage, evidence.operationId().toString());
    appendFrame(preimage, evidence.accountUuid().toString());
    appendFrame(preimage, evidence.tenantUuid().toString());
    appendFrame(preimage, evidence.connectScopeHash());
    appendFrame(preimage, evidence.requestId());
    appendFrame(preimage, HexFormat.of().formatHex(evidence.requestDigest()));
    appendFrame(preimage, Long.toString(evidence.issuanceFence()));
    appendFrame(preimage, Long.toString(evidence.fenceSourceVersion()));
    try {
      return MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray());
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private void appendFrame(ByteArrayOutputStream output, String value) {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeBytes(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(encoded);
  }

  private <T> T ownerTransaction(Supplier<T> operation) {
    return new TransactionTemplate(transactionManager).execute(status -> operation.get());
  }

  private JoinFixture fixture() {
    String suffix = UUID.randomUUID().toString();
    long accountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                    "ca-" + suffix,
                    "current-authority-" + suffix + "@example.com",
                    "test-hash")
                .fetchOne(0, Long.class));
    UUID accountUuid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, UUID.class));
    seedAccountAuthorityState(accountUuid);

    long tenantId = positiveRandomLong();
    seedRetainedV26TenantEvidence(tenantId, suffix);
    dsl.execute(
        "INSERT INTO subscription (account_id, tenant_id, plan_id, status, entitlement_version) "
            + "VALUES (?, ?, 'current-authority-proof', 'active', 1)",
        accountId,
        tenantId);
    UUID tenantUuid = UUID.randomUUID();
    String evidenceDigest = legacyTenantSourceEvidence.digest(tenantId);
    tenantAssociationRepository.importApproved(
        tenantId, approvedTenantAssociation(tenantId, tenantUuid, evidenceDigest, suffix));
    var association = tenantAssociationRepository.findByLegacyTenantId(tenantId).orElseThrow();
    assertThat(association.canonicalTenantId()).isEqualTo(tenantUuid);

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
    when(gameSessionClient.listGameplayRealms(WORLD_SLUG)).thenReturn(List.of(realm));
    when(gameSessionClient.getAdmissionPointer(tenantId, WORLD_SLUG, REALM_SLUG))
        .thenReturn(pointer);

    String requestId = "current-authority-join-" + suffix;
    DirectTextCallerContext caller =
        new DirectTextCallerContext(
            accountId,
            tenantId,
            REALM_ID,
            NAMESPACE_ID,
            "SHARED",
            GAME_INSTANCE_ID,
            "current-authority-session-" + suffix,
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
    return new JoinFixture(accountId, accountUuid, tenantId, tenantUuid, requestId, caller, scope);
  }

  private JoinPublicProductionResult join(JoinFixture fixture) {
    return accountService.joinPublicProductionFromGameSession(
        fixture.caller(),
        new JoinPublicProductionRequest(fixture.scope().connectScopeId(), fixture.requestId()));
  }

  private JoinFixture fixtureForMembership(JoinFixture original) {
    String requestId = "current-authority-reactivate-" + UUID.randomUUID();
    DirectTextCallerContext caller =
        new DirectTextCallerContext(
            original.accountId(),
            original.tenantId(),
            REALM_ID,
            NAMESPACE_ID,
            "SHARED",
            GAME_INSTANCE_ID,
            "current-authority-reactivate-session-" + UUID.randomUUID(),
            requestId);
    DirectTextJoinTarget target =
        new DirectTextJoinTarget(
            original.tenantId(),
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
        original.accountId(),
        original.accountUuid(),
        original.tenantId(),
        original.tenantUuid(),
        requestId,
        caller,
        scope);
  }

  private AccountMembershipSourceReader membershipSourceReader() {
    return new AccountMembershipSourceReader(
        membershipAuthorityEventProducer, authorityGenerationRepository, transactionManager);
  }

  private RuntimeMembershipSnapshotDto readRuntimeMembershipSnapshot(JoinFixture fixture) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status ->
                membershipAuthorityEventProducer.readRuntimeMembershipSnapshot(
                    fixture.accountUuid(), fixture.tenantUuid()));
  }

  private ScopeState readAccountAuthority(UUID accountUuid) {
    return new TransactionTemplate(transactionManager)
        .execute(status -> authorityGenerationRepository.read(AuthorityScope.account(accountUuid)));
  }

  private ScopeState readTenantAuthority(UUID tenantUuid) {
    return new TransactionTemplate(transactionManager)
        .execute(status -> authorityGenerationRepository.read(AuthorityScope.tenant(tenantUuid)));
  }

  private Account readAccount(long accountId) {
    return new TransactionTemplate(transactionManager)
        .execute(status -> accountRepository.findById(accountId).orElseThrow());
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

  private byte[] membershipEventBytes(JoinFixture fixture) {
    return membershipEventBytes(fixture, 1L);
  }

  private byte[] membershipEventBytes(JoinFixture fixture, long sequence) {
    return Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT payload FROM account_authority_outbox_events WHERE outbox_stream_key = ? "
                        + "AND outbox_sequence = ?",
                    membershipStreamKey(fixture),
                    sequence)
                .fetchOne())
        .get("payload", byte[].class);
  }

  private long countMembershipRows(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = ? "
                    + "AND tenant_id = ?",
                fixture.accountId(),
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

  private Map<String, Object> membershipRow(JoinFixture fixture) {
    var row =
        dsl.resultQuery(
                "SELECT xmin::text AS row_xmin, * FROM account_tenant_membership "
                    + "WHERE account_id = ? AND tenant_id = ?",
                fixture.accountId(),
                fixture.tenantId())
            .fetchOne();
    assertThat(row).isNotNull();
    return row.intoMap();
  }

  private Map<String, Object> membershipPairAuthorityRow(JoinFixture fixture) {
    var row =
        dsl.resultQuery(
                "SELECT xmin::text AS row_xmin, * FROM account_membership_pair_authority "
                    + "WHERE account_uuid = ? AND tenant_uuid = ?",
                fixture.accountUuid(),
                fixture.tenantUuid())
            .fetchOne();
    assertThat(row).isNotNull();
    return row.intoMap();
  }

  private Map<String, Object> membershipGenerationRow(JoinFixture fixture) {
    var row =
        dsl.resultQuery(
                "SELECT xmin::text AS row_xmin, * FROM account_authority_generations "
                    + "WHERE scope_kind = 'MEMBERSHIP' AND account_uuid = ? AND tenant_uuid = ?",
                fixture.accountUuid(),
                fixture.tenantUuid())
            .fetchOne();
    assertThat(row).isNotNull();
    return row.intoMap();
  }

  private long accountIssuanceFence(JoinFixture fixture) {
    Long fence =
        dsl.resultQuery(
                "SELECT issuance_fence FROM account_authority_issuance_fences "
                    + "WHERE account_uuid = ?",
                fixture.accountUuid())
            .fetchOne(0, Long.class);
    assertThat(fence).isNotNull();
    return fence;
  }

  private long accountIssuanceFenceSourceVersion(JoinFixture fixture) {
    Long sourceVersion =
        dsl.resultQuery(
                "SELECT source_version FROM account_authority_issuance_fences "
                    + "WHERE account_uuid = ?",
                fixture.accountUuid())
            .fetchOne(0, Long.class);
    assertThat(sourceVersion).isNotNull();
    return sourceVersion;
  }

  private Map<String, Object> accountIssuanceFenceRow(JoinFixture fixture) {
    var row =
        dsl.resultQuery(
                "SELECT xmin::text AS row_xmin, * FROM account_authority_issuance_fences "
                    + "WHERE account_uuid = ?",
                fixture.accountUuid())
            .fetchOne();
    assertThat(row).isNotNull();
    return row.intoMap();
  }

  private long countStreamEvents(String streamKey) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
                streamKey)
            .fetchOne(0, Long.class));
  }

  private void seedRetainedV26TenantEvidence(long tenantId, String suffix) {
    long donorAccountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash, tenant_id) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                    "caspd-" + suffix,
                    "current-authority-donor-" + suffix + "@example.com",
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
            + "(scope_kind, account_uuid, generation, source_version) VALUES ('ACCOUNT', ?, 1, 1)",
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
        .setTargetNamespace(WORKLOAD_NAMESPACE)
        .setSignerKeyId("game-design-owner-test")
        .setApprovedBy("owner@example.test")
        .setApprovalReference("unit-1b-current-authority-proof")
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
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "membership/"
        + fixture.accountUuid()
        + "/"
        + fixture.tenantUuid();
  }

  private record JoinFixture(
      long accountId,
      UUID accountUuid,
      long tenantId,
      UUID tenantUuid,
      String requestId,
      DirectTextCallerContext caller,
      DirectTextJoinScope scope) {}

  private record ConnectClaimFixture(
      AccountConnectTokenIssuanceIdentity identity,
      byte[] requestDigest,
      ClaimResult claim,
      AccountConnectIssuanceFenceEvidence evidence) {
    private ConnectClaimFixture {
      requestDigest = requestDigest.clone();
    }

    @Override
    public byte[] requestDigest() {
      return requestDigest.clone();
    }
  }

  private record ConnectSourceFixture(
      AccountConnectTokenIssuanceIdentity identity,
      byte[] requestDigest,
      ClaimResult claim,
      AccountConnectIssuanceFenceEvidence evidence,
      AccountEnvelopeBinding binding,
      AccountEncryptedEnvelope envelope) {
    private ConnectSourceFixture {
      requestDigest = requestDigest.clone();
    }

    @Override
    public byte[] requestDigest() {
      return requestDigest.clone();
    }
  }

  private record CaptureReadback(
      AccountConnectIssuanceFenceEvidence evidence, AccountCommittedConnectSource source) {}

  private record ComposedConnectSourceFixture(
      AccountBareLoginCurrentAuthorityReader reader,
      AccountConnectTokenIssuanceIdentity identity,
      byte[] requestDigest,
      ClaimResult claim,
      AccountConnectIssuanceFenceEvidence capture,
      RuntimeMembershipSnapshotDto membershipSnapshot,
      AccountEnvelopeBinding binding,
      AccountEncryptedEnvelope envelope,
      String tokenIdentity,
      String compactJwt,
      String signedGatewayContext,
      Map<String, Object> sourceClaims,
      ProofClock clock) {
    private ComposedConnectSourceFixture {
      requestDigest = requestDigest.clone();
      sourceClaims = Map.copyOf(sourceClaims);
    }

    @Override
    public byte[] requestDigest() {
      return requestDigest.clone();
    }
  }

  private static final class ProofClock extends Clock {
    private final Instant initialInstant;
    private final AtomicInteger instantReads = new AtomicInteger();
    private volatile int expiresOnRead = Integer.MAX_VALUE;
    private volatile Instant expiredInstant;

    private ProofClock(Instant initialInstant) {
      this.initialInstant = initialInstant;
    }

    private void expireAfterReads(int readsUntilExpiry, Duration elapsed) {
      expiresOnRead = instantReads.get() + readsUntilExpiry;
      expiredInstant = initialInstant.plus(elapsed);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new ProofClock(initialInstant);
    }

    @Override
    public Instant instant() {
      int read = instantReads.incrementAndGet();
      Instant expired = expiredInstant;
      return expired != null && read >= expiresOnRead ? expired : initialInstant;
    }
  }
}
