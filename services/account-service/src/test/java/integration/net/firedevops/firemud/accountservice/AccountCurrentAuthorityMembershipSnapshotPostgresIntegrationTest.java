package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.AccountLogoutRequestDigest;
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
import org.springframework.transaction.PlatformTransactionManager;
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

  @Test
  void postJoinSnapshotCarriesCurrentCompositeSourcesWithoutRewritingMembershipEvent() {
    JoinFixture fixture = fixture();
    JoinPublicProductionResult joined = join(fixture);
    assertThat(joined.success()).isTrue();

    RuntimeMembershipSnapshotDto before = readRuntimeMembershipSnapshot(fixture);
    var immutableMembershipEvent = before.sourceEvent();
    byte[] immutableMembershipBytes = immutableMembershipEvent.canonicalJsonUtf8();
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
    return Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT payload FROM account_authority_outbox_events WHERE outbox_stream_key = ? "
                        + "AND outbox_sequence = 1",
                    membershipStreamKey(fixture))
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
}
