package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
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
      "firemud.grpc.workload-namespace=firemud-unit1b"
    })
class AccountFreshUuidMembershipSnapshotIntegrationTest {
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
  @Autowired private AccountMembershipAuthorityEventProducer producer;
  @Autowired private AccountAuthorityGenerationRepository authorityGenerationRepository;
  @Autowired private AccountAuthorityOutboxRepository authorityOutboxRepository;
  @Autowired private FreshTenantIdentityAssociationRepository freshAssociationRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoSpyBean private AccountJoinOperationRepository joinOperationRepository;
  @MockitoSpyBean private AccountMembershipPairAuthorityRepository pairAuthorityRepository;

  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private LoggingAdminClient loggingAdminClient;
  @MockitoBean private JavaMailSender mailSender;

  @Test
  void freshUuidSnapshotEnrollsExactNonAdmittingBaselineAndRetriesWithoutSideEffects() {
    AccountFixture account = accountFixture();
    UUID tenantUuid = UUID.randomUUID();
    FreshTenantCreationEvidence evidence = importFreshTenantAssociation(tenantUuid);
    long originalIssuanceFence = issuanceFence(account.accountUuid());

    var first = readFreshNeverJoinedMembershipSnapshot(account.accountUuid(), tenantUuid);
    Map<String, Object> pairRow = membershipPairAuthorityRow(account.accountUuid(), tenantUuid);

    assertThat(first.accountId()).isEqualTo(account.accountUuid().toString());
    assertThat(first.tenantId()).isEqualTo(tenantUuid.toString());
    assertThat(first.membershipExists()).isFalse();
    assertThat(first.membershipLifecycleState()).isEqualTo("MISSING");
    assertThat(first.gameplayAdmissionAllowed()).isFalse();
    assertThat(first.roles()).isEmpty();
    assertThat(first.membershipVersion()).isEqualTo(Map.of(tenantUuid.toString(), "1"));
    assertThat(first.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(first.authorityTuple().issuerAuthGeneration()).isEqualTo("1");
    assertThat(first.authorityTuple().accountAuthorityGeneration()).isEqualTo("1");
    assertThat(first.authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(Map.of(tenantUuid.toString(), "1"));
    assertThat(first.authorityTuple().membershipAuthorityGeneration())
        .isEqualTo(Map.of(tenantUuid.toString(), "1"));
    assertThat(first.authorityTuple().privateRealmGrantVersions()).isEmpty();
    assertThat(first.authorityTuple().accountSecurityCutoff()).isEmpty();
    assertThat(first.authorityTuple().tenantBillingCutoff()).isEmpty();
    assertThat(first.issuanceFence()).isEqualTo(Long.toString(originalIssuanceFence));
    assertThat(first.evaluatedAt()).isNotNull();
    assertThat(first.outboxStreamKey())
        .isEqualTo(membershipStreamKey(account.accountUuid(), tenantUuid));
    assertThat(first.outboxCheckpoints())
        .containsExactlyElementsOf(expectedCheckpoints(account.accountUuid(), tenantUuid));
    assertThat(first.outboxSourceEvidence()).isEmpty();
    assertThat(pairRow)
        .containsEntry("account_uuid", account.accountUuid())
        .containsEntry("tenant_uuid", tenantUuid)
        .containsEntry("legacy_tenant_id", null)
        .containsEntry("tenant_provenance_kind", "FRESH_GAME_DESIGN")
        .containsEntry("tenant_source_operation_id", evidence.operationId())
        .containsEntry("tenant_provenance_digest", evidence.evidenceDigest())
        .containsEntry("membership_exists", false)
        .containsEntry("membership_version", 1L)
        .containsEntry("membership_authority_generation", 1L)
        .containsEntry("last_event_sequence", 0L)
        .containsEntry("last_event_id", null)
        .containsEntry("last_event_digest", null);
    assertThat(authorityGeneration("MEMBERSHIP", account.accountUuid(), tenantUuid)).isEqualTo(1L);
    assertThat(issuanceFence(account.accountUuid())).isEqualTo(originalIssuanceFence);
    assertThat(countMembershipsForAccount(account.accountId())).isZero();
    assertThat(countMembershipTransitionReceiptsForAccount(account.accountId())).isZero();
    assertThat(countAuthorityEvents(membershipStreamKey(account.accountUuid(), tenantUuid)))
        .isZero();
    assertThat(countAuthorityStreams(membershipStreamKey(account.accountUuid(), tenantUuid)))
        .isZero();
    assertThat(countFreshCanonicalClaim(tenantUuid)).isEqualTo(1L);
    assertThat(countApprovedAssociationForCanonicalTenant(tenantUuid)).isZero();

    var retry = readFreshNeverJoinedMembershipSnapshot(account.accountUuid(), tenantUuid);
    assertThat(retry.accountId()).isEqualTo(first.accountId());
    assertThat(retry.tenantId()).isEqualTo(first.tenantId());
    assertThat(retry.membershipVersion()).isEqualTo(first.membershipVersion());
    assertThat(retry.membershipAuthorityGeneration())
        .isEqualTo(first.membershipAuthorityGeneration());
    assertThat(retry.authorityTuple()).isEqualTo(first.authorityTuple());
    assertThat(retry.issuanceFence()).isEqualTo(first.issuanceFence());
    assertThat(retry.outboxCheckpoints()).isEqualTo(first.outboxCheckpoints());
    assertThat(retry.outboxSourceEvidence()).isEmpty();
    assertThat(membershipPairAuthorityRow(account.accountUuid(), tenantUuid)).isEqualTo(pairRow);
    assertThat(countMembershipAuthorityGenerations(account.accountUuid(), tenantUuid))
        .isEqualTo(1L);
    assertThat(countMembershipsForAccount(account.accountId())).isZero();
    assertThat(countMembershipTransitionReceiptsForAccount(account.accountId())).isZero();
    assertThat(countAuthorityEvents(membershipStreamKey(account.accountUuid(), tenantUuid)))
        .isZero();
    assertThat(countAuthorityStreams(membershipStreamKey(account.accountUuid(), tenantUuid)))
        .isZero();
    assertThat(issuanceFence(account.accountUuid())).isEqualTo(originalIssuanceFence);
  }

  @Test
  void strictExistingMembershipReadDeniesFreshAssociationWithoutEnrollmentOrMutation() {
    AccountFixture account = accountFixture();
    UUID tenantUuid = UUID.randomUUID();
    FreshTenantCreationEvidence evidence = importFreshTenantAssociation(tenantUuid);
    long originalIssuanceFence = issuanceFence(account.accountUuid());

    assertThatThrownBy(
            () ->
                readExistingPairBoundPositiveMembershipSnapshot(account.accountUuid(), tenantUuid))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Fresh tenant membership has no positive current Account");
    assertThat(countMembershipPairAuthorities(account.accountUuid(), tenantUuid)).isZero();
    assertThat(countMembershipAuthorityGenerations(account.accountUuid(), tenantUuid)).isZero();
    assertThat(countMembershipsForAccount(account.accountId())).isZero();
    assertThat(countMembershipTransitionReceiptsForAccount(account.accountId())).isZero();
    assertThat(countAuthorityEvents(membershipStreamKey(account.accountUuid(), tenantUuid)))
        .isZero();
    assertThat(countAuthorityStreams(membershipStreamKey(account.accountUuid(), tenantUuid)))
        .isZero();

    // A separately established V38 sequence-zero pair remains non-admitting, and the strict
    // existing-positive reader must neither promote it nor alter its counters.
    readFreshNeverJoinedMembershipSnapshot(account.accountUuid(), tenantUuid);
    Map<String, Object> pairBeforeRead =
        membershipPairAuthorityRow(account.accountUuid(), tenantUuid);
    long tenantGeneration = authorityGeneration("TENANT", null, tenantUuid);
    assertThatThrownBy(
            () ->
                readExistingPairBoundPositiveMembershipSnapshot(account.accountUuid(), tenantUuid))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Fresh tenant membership has no positive current Account");

    Optional<FreshTenantCreationEvidence> associationReadback =
        new TransactionTemplate(transactionManager)
            .execute(status -> freshAssociationRepository.read(tenantUuid));
    assertThat(associationReadback).contains(evidence);
    assertThat(membershipPairAuthorityRow(account.accountUuid(), tenantUuid))
        .isEqualTo(pairBeforeRead);
    assertThat(authorityGeneration("TENANT", null, tenantUuid)).isEqualTo(tenantGeneration);
    assertThat(issuanceFence(account.accountUuid())).isEqualTo(originalIssuanceFence);
    assertThat(countMembershipPairAuthorities(account.accountUuid(), tenantUuid)).isEqualTo(1L);
    assertThat(countMembershipAuthorityGenerations(account.accountUuid(), tenantUuid))
        .isEqualTo(1L);
    assertThat(countMembershipsForAccount(account.accountId())).isZero();
    assertThat(countMembershipTransitionReceiptsForAccount(account.accountId())).isZero();
    assertThat(countAuthorityEvents(membershipStreamKey(account.accountUuid(), tenantUuid)))
        .isZero();
    assertThat(countAuthorityStreams(membershipStreamKey(account.accountUuid(), tenantUuid)))
        .isZero();
  }

  @Test
  void canonicalRuntimeReadUsesFreshUuidAssociationWithoutInventingRetainedAlias() {
    AccountFixture account = accountFixture();
    UUID tenantUuid = UUID.randomUUID();
    FreshTenantCreationEvidence evidence = importFreshTenantAssociation(tenantUuid);
    long originalIssuanceFence = issuanceFence(account.accountUuid());

    RuntimeMembershipSnapshotDto first =
        readRuntimeMembershipSnapshot(account.accountUuid(), tenantUuid);

    assertThat(first.requestAccountUuid()).isEqualTo(account.accountUuid().toString());
    assertThat(first.requestTenantUuid()).isEqualTo(tenantUuid.toString());
    assertThat(first.accountUuid()).isEqualTo(account.accountUuid().toString());
    assertThat(first.tenantUuid()).isEqualTo(tenantUuid.toString());
    assertThat(first.membershipExists()).isFalse();
    assertThat(first.gameplayAdmissionAllowed()).isFalse();
    assertThat(first.membershipBaseline().membershipLifecycleState()).isEqualTo("MISSING");
    assertThat(first.membershipBaseline().membershipVersion())
        .isEqualTo(Map.of(tenantUuid.toString(), "1"));
    assertThat(first.membershipBaseline().membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(first.authorityTuple().issuerAuthGeneration()).isEqualTo("1");
    assertThat(first.authorityTuple().accountAuthorityGeneration()).isEqualTo("1");
    assertThat(first.authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(Map.of(tenantUuid.toString(), "1"));
    assertThat(first.authorityTuple().membershipAuthorityGeneration())
        .isEqualTo(Map.of(tenantUuid.toString(), "1"));
    assertThat(first.authorityTuple().privateRealmGrantVersions()).isEmpty();
    assertThat(first.issuanceFence()).isEqualTo(Long.toString(originalIssuanceFence));
    assertThat(first.evaluatedAt()).isNotNull();
    assertThat(first.outboxCheckpoints())
        .containsExactlyElementsOf(expectedCheckpoints(account.accountUuid(), tenantUuid));
    assertThat(first.outboxSourceEvidence()).isEmpty();
    assertThat(first.sourceEvent()).isNull();
    assertThat(first.roles()).isEmpty();
    assertThat(membershipPairAuthorityRow(account.accountUuid(), tenantUuid))
        .containsEntry("legacy_tenant_id", null)
        .containsEntry("tenant_provenance_kind", "FRESH_GAME_DESIGN")
        .containsEntry("tenant_source_operation_id", evidence.operationId())
        .containsEntry("tenant_provenance_digest", evidence.evidenceDigest())
        .containsEntry("membership_exists", false)
        .containsEntry("membership_version", 1L)
        .containsEntry("membership_authority_generation", 1L)
        .containsEntry("last_event_sequence", 0L);
    assertThat(countApprovedAssociationForCanonicalTenant(tenantUuid)).isZero();
    assertThat(countMembershipsForAccount(account.accountId())).isZero();
    assertThat(countMembershipTransitionReceiptsForAccount(account.accountId())).isZero();
    assertThat(countAuthorityEvents(membershipStreamKey(account.accountUuid(), tenantUuid)))
        .isZero();
    assertThat(countAuthorityStreams(membershipStreamKey(account.accountUuid(), tenantUuid)))
        .isZero();
    assertThat(issuanceFence(account.accountUuid())).isEqualTo(originalIssuanceFence);

    RuntimeMembershipSnapshotDto retry =
        readRuntimeMembershipSnapshot(account.accountUuid(), tenantUuid);
    assertThat(retry.requestAccountUuid()).isEqualTo(first.requestAccountUuid());
    assertThat(retry.requestTenantUuid()).isEqualTo(first.requestTenantUuid());
    assertThat(retry.membershipBaseline()).isEqualTo(first.membershipBaseline());
    assertThat(retry.authorityTuple()).isEqualTo(first.authorityTuple());
    assertThat(retry.issuanceFence()).isEqualTo(first.issuanceFence());
    assertThat(retry.outboxCheckpoints()).isEqualTo(first.outboxCheckpoints());
    assertThat(retry.outboxSourceEvidence()).isEmpty();
    assertThat(countMembershipPairAuthorities(account.accountUuid(), tenantUuid)).isEqualTo(1L);
    assertThat(countMembershipAuthorityGenerations(account.accountUuid(), tenantUuid))
        .isEqualTo(1L);
  }

  @Test
  void canonicalRuntimeReadRejectsUnmappedTenantUuidWithoutEnrollment() {
    AccountFixture account = accountFixture();
    UUID unmappedTenantUuid = UUID.randomUUID();

    assertThatThrownBy(
            () -> readRuntimeMembershipSnapshot(account.accountUuid(), unmappedTenantUuid))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("approved Account tenant association is absent");
    assertThat(countMembershipPairAuthorities(account.accountUuid(), unmappedTenantUuid)).isZero();
    assertThat(countMembershipAuthorityGenerations(account.accountUuid(), unmappedTenantUuid))
        .isZero();
    assertThat(countMembershipsForAccount(account.accountId())).isZero();
    assertThat(countMembershipTransitionReceiptsForAccount(account.accountId())).isZero();
  }

  @Test
  void freshUuidSnapshotRejectsUnmappedTenantWithoutEnrollment() {
    AccountFixture account = accountFixture();
    UUID unmappedTenantUuid = UUID.randomUUID();

    assertThatThrownBy(
            () -> readFreshNeverJoinedMembershipSnapshot(account.accountUuid(), unmappedTenantUuid))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("association is absent");
    assertThat(countMembershipPairAuthorities(account.accountUuid(), unmappedTenantUuid)).isZero();
    assertThat(countMembershipAuthorityGenerations(account.accountUuid(), unmappedTenantUuid))
        .isZero();
    assertThat(countMembershipsForAccount(account.accountId())).isZero();
    assertThat(countMembershipTransitionReceiptsForAccount(account.accountId())).isZero();
  }

  @Test
  void freshUuidSnapshotRejectsPairProvenanceThatConflictsWithSourceReadback() {
    AccountFixture account = accountFixture();
    UUID tenantUuid = UUID.randomUUID();
    FreshTenantCreationEvidence evidence = importFreshTenantAssociation(tenantUuid);
    dsl.execute(
        "INSERT INTO account_membership_pair_authority "
            + "(account_uuid, tenant_uuid, legacy_tenant_id, tenant_provenance_kind, "
            + "tenant_source_operation_id, tenant_provenance_digest, membership_exists, "
            + "membership_version, membership_authority_generation, last_event_sequence, "
            + "last_event_id, last_event_digest, last_transition_invalidated) "
            + "VALUES (?, ?, NULL, 'FRESH_GAME_DESIGN', ?, ?, FALSE, 1, 1, 0, NULL, NULL, FALSE)",
        account.accountUuid(),
        tenantUuid,
        UUID.randomUUID(),
        evidence.evidenceDigest());
    Map<String, Object> conflictingPair =
        membershipPairAuthorityRow(account.accountUuid(), tenantUuid);

    assertThatThrownBy(
            () -> readFreshNeverJoinedMembershipSnapshot(account.accountUuid(), tenantUuid))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts with verified association provenance");
    assertThat(membershipPairAuthorityRow(account.accountUuid(), tenantUuid))
        .isEqualTo(conflictingPair);
    assertThat(countMembershipAuthorityGenerations(account.accountUuid(), tenantUuid)).isZero();
    assertThat(countMembershipsForAccount(account.accountId())).isZero();
    assertThat(countMembershipTransitionReceiptsForAccount(account.accountId())).isZero();
    assertThat(countAuthorityEvents(membershipStreamKey(account.accountUuid(), tenantUuid)))
        .isZero();
  }

  @Test
  void freshUuidSnapshotRollsBackGenerationAndPairWhenEnrollmentCompositionFails() {
    AccountFixture account = accountFixture();
    UUID tenantUuid = UUID.randomUUID();
    importFreshTenantAssociation(tenantUuid);
    AccountMembershipPairAuthorityRepository pairAuthorityTarget =
        AopTestUtils.getUltimateTargetObject(pairAuthorityRepository);
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("simulated failure after pair enrollment");
            })
        .when(pairAuthorityTarget)
        .enrollAbsence(eq(account.accountUuid()), eq(tenantUuid), any());

    assertThatThrownBy(
            () -> readFreshNeverJoinedMembershipSnapshot(account.accountUuid(), tenantUuid))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated failure after pair enrollment");
    assertThat(countMembershipPairAuthorities(account.accountUuid(), tenantUuid)).isZero();
    assertThat(countMembershipAuthorityGenerations(account.accountUuid(), tenantUuid)).isZero();
    assertThat(countMembershipsForAccount(account.accountId())).isZero();
    assertThat(countMembershipTransitionReceiptsForAccount(account.accountId())).isZero();
    assertThat(countAuthorityEvents(membershipStreamKey(account.accountUuid(), tenantUuid)))
        .isZero();
    assertThat(countAuthorityStreams(membershipStreamKey(account.accountUuid(), tenantUuid)))
        .isZero();
  }

  @Test
  void freshUuidSnapshotRejectsCommittedMembershipHistoryBeforeBaselineEnrollment() {
    AccountFixture account = accountFixture();
    UUID tenantUuid = UUID.randomUUID();
    importFreshTenantAssociation(tenantUuid);
    String streamKey = membershipStreamKey(account.accountUuid(), tenantUuid);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                authorityOutboxRepository.append(
                    streamKey,
                    "fresh-history-" + UUID.randomUUID(),
                    UUID.randomUUID().toString(),
                    "sha256:" + "a".repeat(64),
                    "{}".getBytes(StandardCharsets.UTF_8)));

    assertThatThrownBy(
            () -> readFreshNeverJoinedMembershipSnapshot(account.accountUuid(), tenantUuid))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("retained authority-event history");
    assertThat(countMembershipPairAuthorities(account.accountUuid(), tenantUuid)).isZero();
    assertThat(countMembershipAuthorityGenerations(account.accountUuid(), tenantUuid)).isZero();
    assertThat(countMembershipsForAccount(account.accountId())).isZero();
    assertThat(countMembershipTransitionReceiptsForAccount(account.accountId())).isZero();
    assertThat(countAuthorityEvents(streamKey)).isEqualTo(1L);
    assertThat(countAuthorityStreams(streamKey)).isEqualTo(1L);
  }

  @Test
  void freshUuidSnapshotRejectsAdvancedPairAndGenerationWithoutResettingEither() {
    AccountFixture account = accountFixture();
    UUID tenantUuid = UUID.randomUUID();
    importFreshTenantAssociation(tenantUuid);
    var original = readFreshNeverJoinedMembershipSnapshot(account.accountUuid(), tenantUuid);
    Map<String, Object> pairRow = membershipPairAuthorityRow(account.accountUuid(), tenantUuid);
    dsl.execute(
        "UPDATE account_authority_generations SET generation = 2, source_version = 2 "
            + "WHERE scope_kind = 'MEMBERSHIP' AND account_uuid = ? AND tenant_uuid = ?",
        account.accountUuid(),
        tenantUuid);

    assertThatThrownBy(
            () -> readFreshNeverJoinedMembershipSnapshot(account.accountUuid(), tenantUuid))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "membership authority generation cannot prove its sequence-zero baseline");
    assertThat(membershipPairAuthorityRow(account.accountUuid(), tenantUuid)).isEqualTo(pairRow);
    assertThat(authorityGeneration("MEMBERSHIP", account.accountUuid(), tenantUuid)).isEqualTo(2L);
    assertThat(countAuthorityEvents(original.outboxStreamKey())).isZero();

    dsl.execute(
        "UPDATE account_membership_pair_authority SET membership_exists = TRUE, "
            + "membership_version = 2, membership_authority_generation = 2, "
            + "last_event_sequence = 1, last_event_id = ?, last_event_digest = ?, "
            + "last_transition_invalidated = TRUE WHERE account_uuid = ? AND tenant_uuid = ?",
        UUID.randomUUID().toString(),
        "sha256:" + "b".repeat(64),
        account.accountUuid(),
        tenantUuid);
    Map<String, Object> progressedPair =
        membershipPairAuthorityRow(account.accountUuid(), tenantUuid);

    assertThatThrownBy(
            () -> readFreshNeverJoinedMembershipSnapshot(account.accountUuid(), tenantUuid))
        .isInstanceOf(IllegalStateException.class);
    assertThat(membershipPairAuthorityRow(account.accountUuid(), tenantUuid))
        .isEqualTo(progressedPair);
    assertThat(countAuthorityEvents(original.outboxStreamKey())).isZero();
  }

  @Test
  void freshUuidSnapshotRejectsAdvancedTenantGenerationAndRollsBackBaselineWrites() {
    AccountFixture account = accountFixture();
    UUID tenantUuid = UUID.randomUUID();
    importFreshTenantAssociation(tenantUuid);
    dsl.execute(
        "UPDATE account_authority_generations SET generation = 2, source_version = 2 "
            + "WHERE scope_kind = 'TENANT' AND tenant_uuid = ?",
        tenantUuid);

    assertThatThrownBy(
            () -> readFreshNeverJoinedMembershipSnapshot(account.accountUuid(), tenantUuid))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tenant source history is missing");
    assertThat(authorityGeneration("TENANT", null, tenantUuid)).isEqualTo(2L);
    assertThat(countMembershipPairAuthorities(account.accountUuid(), tenantUuid)).isZero();
    assertThat(countMembershipAuthorityGenerations(account.accountUuid(), tenantUuid)).isZero();
    assertThat(countMembershipsForAccount(account.accountId())).isZero();
    assertThat(countMembershipTransitionReceiptsForAccount(account.accountId())).isZero();
    assertThat(countAuthorityEvents(membershipStreamKey(account.accountUuid(), tenantUuid)))
        .isZero();
    assertThat(countAuthorityStreams(membershipStreamKey(account.accountUuid(), tenantUuid)))
        .isZero();
  }

  @Test
  void concurrentFreshUuidSnapshotCallsInitializeOneExactPairBaseline() throws Exception {
    AccountFixture account = accountFixture();
    UUID tenantUuid = UUID.randomUUID();
    importFreshTenantAssociation(tenantUuid);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch firstSnapshotReady = new CountDownLatch(1);
    CountDownLatch releaseFirstSnapshot = new CountDownLatch(1);
    CountDownLatch secondLockAttempted = new CountDownLatch(1);
    AtomicReference<Integer> firstBackendPid = new AtomicReference<>();
    AtomicReference<Integer> secondBackendPid = new AtomicReference<>();
    AtomicInteger lockInvocations = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (lockInvocations.incrementAndGet() == 2) {
                secondBackendPid.set(currentBackendPid());
                secondLockAttempted.countDown();
              }
              return invocation.callRealMethod();
            })
        .when(joinOperationRepository)
        .lockAccount(anyLong());
    try {
      Future<AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot> first =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .execute(
                          status -> {
                            var snapshot =
                                producer.readFreshNeverJoinedMembershipSnapshot(
                                    account.accountUuid(), tenantUuid);
                            firstBackendPid.set(currentBackendPid());
                            firstSnapshotReady.countDown();
                            await(releaseFirstSnapshot);
                            return snapshot;
                          }));
      assertThat(firstSnapshotReady.await(10, TimeUnit.SECONDS)).isTrue();

      Future<AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot> second =
          executor.submit(
              () -> readFreshNeverJoinedMembershipSnapshot(account.accountUuid(), tenantUuid));
      assertThat(secondLockAttempted.await(10, TimeUnit.SECONDS)).isTrue();
      awaitAccountFenceLockWait(firstBackendPid.get(), secondBackendPid.get());
      assertThat(second.isDone()).isFalse();

      releaseFirstSnapshot.countDown();
      var firstSnapshot = first.get(20, TimeUnit.SECONDS);
      var secondSnapshot = second.get(20, TimeUnit.SECONDS);
      assertThat(firstSnapshot.membershipVersion()).isEqualTo(Map.of(tenantUuid.toString(), "1"));
      assertThat(secondSnapshot.membershipVersion()).isEqualTo(firstSnapshot.membershipVersion());
      assertThat(secondSnapshot.authorityTuple()).isEqualTo(firstSnapshot.authorityTuple());
      assertThat(secondSnapshot.outboxCheckpoints()).isEqualTo(firstSnapshot.outboxCheckpoints());
      assertThat(countMembershipPairAuthorities(account.accountUuid(), tenantUuid)).isEqualTo(1L);
      assertThat(countMembershipAuthorityGenerations(account.accountUuid(), tenantUuid))
          .isEqualTo(1L);
      assertThat(countMembershipsForAccount(account.accountId())).isZero();
      assertThat(countMembershipTransitionReceiptsForAccount(account.accountId())).isZero();
      assertThat(countAuthorityEvents(membershipStreamKey(account.accountUuid(), tenantUuid)))
          .isZero();
    } catch (ExecutionException exception) {
      throw new AssertionError("Concurrent fresh snapshot read failed", exception.getCause());
    } finally {
      releaseFirstSnapshot.countDown();
      executor.shutdownNow();
    }
  }

  private AccountFixture accountFixture() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    long accountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash) "
                        + "VALUES (?, ?, ?) RETURNING id",
                    "fresh-snapshot-" + suffix,
                    "fresh-snapshot-" + suffix + "@example.com",
                    "test-hash")
                .fetchOne(0, Long.class));
    UUID accountUuid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, UUID.class));
    dsl.execute(
        "INSERT INTO account_authority_generations "
            + "(scope_kind, account_uuid, generation, source_version) "
            + "VALUES ('ACCOUNT', ?, 1, 1)",
        accountUuid);
    dsl.execute(
        "INSERT INTO account_authority_issuance_fences "
            + "(account_uuid, issuance_fence, source_version) VALUES (?, 1, 1)",
        accountUuid);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                authorityGenerationRepository.initializeIssuerIfAbsent(
                    AccountServiceImpl.ACCOUNT_JWT_ISSUER));
    return new AccountFixture(accountId, accountUuid);
  }

  private FreshTenantCreationEvidence importFreshTenantAssociation(UUID canonicalTenantUuid) {
    UUID creationRequestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String targetNamespace = "firemud-unit1b";
    String sourceGameTenantKey =
        "fresh-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            targetNamespace, creationRequestId, sourceGameTenantKey, "Fresh tenant", null);
    long sourceGameRowId = positiveRandomLong();
    String provenanceKind = "NEW_GAME_ROW";
    FreshTenantCreationEvidence evidence =
        new FreshTenantCreationEvidence(
            1,
            targetNamespace,
            creationRequestId,
            operationId,
            requestDigest,
            canonicalTenantUuid,
            sourceGameRowId,
            sourceGameTenantKey,
            provenanceKind,
            GameTenantCreationDigest.evidenceDigest(
                targetNamespace,
                creationRequestId,
                operationId,
                requestDigest,
                canonicalTenantUuid,
                sourceGameRowId,
                sourceGameTenantKey,
                provenanceKind));
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              freshAssociationRepository.importVerified(evidence);
              authorityGenerationRepository.initializeTenantIfAbsent(canonicalTenantUuid);
            });
    return evidence;
  }

  private AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot
      readFreshNeverJoinedMembershipSnapshot(UUID accountUuid, UUID tenantUuid) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status -> producer.readFreshNeverJoinedMembershipSnapshot(accountUuid, tenantUuid));
  }

  private RuntimeMembershipSnapshotDto readRuntimeMembershipSnapshot(
      UUID accountUuid, UUID tenantUuid) {
    return new TransactionTemplate(transactionManager)
        .execute(status -> producer.readRuntimeMembershipSnapshot(accountUuid, tenantUuid));
  }

  private AccountMembershipAuthorityEventProducer.PositiveMembershipSnapshot
      readExistingPairBoundPositiveMembershipSnapshot(UUID accountUuid, UUID tenantUuid) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status ->
                producer.readExistingPairBoundPositiveMembershipSnapshot(accountUuid, tenantUuid));
  }

  private Map<String, Object> membershipPairAuthorityRow(UUID accountUuid, UUID tenantUuid) {
    var row =
        dsl.resultQuery(
                "SELECT xmin::text AS row_xmin, * FROM account_membership_pair_authority "
                    + "WHERE account_uuid = ? AND tenant_uuid = ?",
                accountUuid,
                tenantUuid)
            .fetchOne();
    assertThat(row).isNotNull();
    return row.intoMap();
  }

  private long countMembershipPairAuthorities(UUID accountUuid, UUID tenantUuid) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_membership_pair_authority "
                    + "WHERE account_uuid = ? AND tenant_uuid = ?",
                accountUuid,
                tenantUuid)
            .fetchOne(0, Long.class));
  }

  private long countMembershipAuthorityGenerations(UUID accountUuid, UUID tenantUuid) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_authority_generations "
                    + "WHERE scope_kind = 'MEMBERSHIP' AND account_uuid = ? "
                    + "AND tenant_uuid = ?",
                accountUuid,
                tenantUuid)
            .fetchOne(0, Long.class));
  }

  private long authorityGeneration(String scopeKind, UUID accountUuid, UUID tenantUuid) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT generation FROM account_authority_generations "
                    + "WHERE scope_kind = ? AND account_uuid IS NOT DISTINCT FROM ? "
                    + "AND tenant_uuid IS NOT DISTINCT FROM ?",
                scopeKind,
                accountUuid,
                tenantUuid)
            .fetchOne(0, Long.class));
  }

  private long issuanceFence(UUID accountUuid) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT issuance_fence FROM account_authority_issuance_fences "
                    + "WHERE account_uuid = ?",
                accountUuid)
            .fetchOne(0, Long.class));
  }

  private long countMembershipsForAccount(long accountId) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_tenant_membership WHERE account_id = ?", accountId)
            .fetchOne(0, Long.class));
  }

  private long countMembershipTransitionReceiptsForAccount(long accountId) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_membership_transition_receipts WHERE account_id = ?",
                accountId)
            .fetchOne(0, Long.class));
  }

  private long countAuthorityEvents(String streamKey) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_events "
                    + "WHERE outbox_stream_key = ?",
                streamKey)
            .fetchOne(0, Long.class));
  }

  private long countAuthorityStreams(String streamKey) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_streams "
                    + "WHERE outbox_stream_key = ?",
                streamKey)
            .fetchOne(0, Long.class));
  }

  private long countFreshCanonicalClaim(UUID tenantUuid) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_canonical_tenant_identity_claims "
                    + "WHERE canonical_tenant_id = ? AND identity_kind = 'FRESH_GAME_DESIGN'",
                tenantUuid)
            .fetchOne(0, Long.class));
  }

  private long countApprovedAssociationForCanonicalTenant(UUID tenantUuid) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_approved_legacy_tenant_associations "
                    + "WHERE canonical_tenant_id = ?",
                tenantUuid)
            .fetchOne(0, Long.class));
  }

  private String membershipStreamKey(UUID accountUuid, UUID tenantUuid) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "membership/"
        + accountUuid
        + "/"
        + tenantUuid;
  }

  private List<OutboxCheckpointEntry> expectedCheckpoints(UUID accountUuid, UUID tenantUuid) {
    return List.of(
        new OutboxCheckpointEntry(
            MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "account/" + accountUuid, "0"),
        new OutboxCheckpointEntry(
            MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
                + "issuer/"
                + AccountServiceImpl.ACCOUNT_JWT_ISSUER,
            "0"),
        new OutboxCheckpointEntry(membershipStreamKey(accountUuid, tenantUuid), "0"),
        new OutboxCheckpointEntry(
            MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "tenant/" + tenantUuid, "0"));
  }

  private long positiveRandomLong() {
    long candidate = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    return candidate == 0L ? 1L : candidate;
  }

  private Integer currentBackendPid() {
    return dsl.resultQuery("SELECT pg_backend_pid()").fetchOne(0, Integer.class);
  }

  private void awaitAccountFenceLockWait(Integer snapshotBackendPid, Integer waitingBackendPid)
      throws InterruptedException {
    assertThat(snapshotBackendPid).isNotNull();
    assertThat(waitingBackendPid).isNotNull();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Boolean blockedBySnapshot =
          dsl.resultQuery(
                  "SELECT CAST(? AS integer) = ANY(pg_blocking_pids(CAST(? AS integer)))",
                  snapshotBackendPid,
                  waitingBackendPid)
              .fetchOne(0, Boolean.class);
      if (Boolean.TRUE.equals(blockedBySnapshot)) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError(
        "second fresh snapshot transaction did not block on the Account-row fence");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(60, TimeUnit.SECONDS)) {
        throw new IllegalStateException("fresh snapshot concurrency test timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("fresh snapshot concurrency test was interrupted", exception);
    }
  }

  private record AccountFixture(long accountId, UUID accountUuid) {}
}
