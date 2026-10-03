package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.AccountServiceApplication;
import net.firedevops.firemud.accountservice.client.EntityManagementClient;
import net.firedevops.firemud.accountservice.client.GameSessionClient;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairTransition;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL storage/publisher proof for Account's canonical first-JOIN authority event.
 *
 * <p>Policy inputs and source associations are locally seeded owner fixtures. These cases prove
 * Account persistence composition only; they do not claim authenticated entitlement, caller, or
 * public JOIN flow proof.
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
class AccountCanonicalFirstJoinAuthorityEventIntegrationTest {
  private static final String TEST_NAMESPACE = "firemud-unit1b";

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
  @Autowired private AccountMembershipAuthorityEventProducer producer;
  @Autowired private AccountAuthorityGenerationRepository generations;
  @Autowired private AccountAuthorityOutboxRepository authorityOutbox;
  @Autowired private AccountConnectScopeRepository connectScopes;
  @Autowired private AccountJoinOperationRepository joinOperations;
  @Autowired private AccountMembershipPairAuthorityRepository pairAuthority;
  @Autowired private AccountRepository accounts;
  @Autowired private AccountTenantMembershipRepository memberships;
  @Autowired private AccountTenantMembershipRoleSnapshotRepository roleSnapshots;
  @Autowired private FreshTenantIdentityAssociationRepository freshAssociations;
  @Autowired private ApprovedLegacyTenantAssociationRepository approvedAssociations;
  @Autowired private LegacyTenantSourceEvidence retainedSourceEvidence;

  @MockitoSpyBean private AccountAuthorityOutboxRepository authorityOutboxSpy;
  @MockitoSpyBean private AccountMembershipPairAuthorityRepository pairAuthoritySpy;

  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private LoggingAdminClient loggingAdminClient;
  @MockitoBean private JavaMailSender mailSender;

  @Test
  void freshUuidFirstJoinCommitsClosedEventAndPairFromStoredMembershipAndPolicy() {
    JoinFixture fixture = freshFixture(true, 9L);
    long issuanceFenceBefore = issuanceFence(fixture.account().accountUuid());
    var baseline = fixture.baseline();

    var checkpoint = publishWithMembershipWrite(fixture);
    String streamKey = membershipStreamKey(fixture.account().accountUuid(), fixture.tenantUuid());
    Event persisted =
        inTransaction(
            () -> authorityOutbox.findEvent(streamKey, fixture.requestId()).orElseThrow());
    var event =
        MembershipAuthorityEventV1Codec.verify(
            new String(persisted.payload(), StandardCharsets.UTF_8));
    var pair =
        inTransaction(
            () ->
                pairAuthority
                    .readForUpdate(fixture.account().accountUuid(), fixture.tenantUuid())
                    .orElseThrow());

    assertThat(baseline.membershipVersion())
        .isEqualTo(java.util.Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(checkpoint.outboxSequence()).isEqualTo(1L);
    assertThat(persisted.outboxSequence()).isEqualTo(1L);
    assertThat(persisted.outboxStreamKey()).isEqualTo(streamKey);
    assertThat(event.accountId()).isEqualTo(fixture.account().accountUuid().toString());
    assertThat(event.tenantId()).isEqualTo(fixture.tenantUuid().toString());
    assertThat(event.sourceScope())
        .isEqualTo("membership/" + fixture.account().accountUuid() + "/" + fixture.tenantUuid());
    assertThat(event.membershipVersion())
        .isEqualTo(java.util.Map.of(fixture.tenantUuid().toString(), "2"));
    assertThat(event.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(event.authorityTuple().issuerAuthGeneration()).isEqualTo("1");
    assertThat(event.authorityTuple().accountAuthorityGeneration()).isEqualTo("1");
    assertThat(event.authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(java.util.Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(event.authorityTuple().membershipAuthorityGeneration())
        .isEqualTo(java.util.Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(event.authorityTuple().privateRealmGrantVersions()).isEmpty();
    assertThat(event.authorityTuple().accountSecurityCutoff()).isEmpty();
    assertThat(event.authorityTuple().tenantBillingCutoff()).isEmpty();
    assertThat(event.roles()).containsExactly("player");
    assertThat(event.gameplayAdmissionAllowed()).isTrue();
    assertThat(event.callerBoundAuthorityInvalidated()).isFalse();
    assertThat(event.issuanceFence()).isEqualTo(Long.toString(issuanceFenceBefore));
    assertThat(checkpoint.sourceEventId()).isEqualTo(event.eventId());
    assertThat(checkpoint.sourceEventDigest()).isEqualTo(event.eventDigest());
    assertThat(pair.membershipExists()).isTrue();
    assertThat(pair.membershipVersion()).isEqualTo(2L);
    assertThat(pair.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(pair.lastEventSequence()).isEqualTo(1L);
    assertThat(pair.lastEventId()).isEqualTo(event.eventId());
    assertThat(pair.lastEventDigest()).isEqualTo(event.eventDigest());
    assertThat(pair.lastTransitionInvalidated()).isFalse();
    assertThat(issuanceFence(fixture.account().accountUuid())).isEqualTo(issuanceFenceBefore);
    assertThat(countReceipts(fixture)).isZero();
    assertThat(generation("MEMBERSHIP", fixture.account().accountUuid(), fixture.tenantUuid()))
        .isEqualTo(1L);

    Record membershipRow =
        dsl.fetchOne(
            "SELECT tenant_id, tenant_uuid, tenant_provenance_kind, tenant_source_operation_id, "
                + "tenant_provenance_digest FROM account_tenant_membership "
                + "WHERE account_id = ? AND tenant_uuid = ?",
            fixture.account().accountId(),
            fixture.tenantUuid());
    assertThat(membershipRow).isNotNull();
    assertThat(membershipRow.get("tenant_id", Long.class)).isNull();
    assertThat(membershipRow.get("tenant_uuid", UUID.class)).isEqualTo(fixture.tenantUuid());
    assertThat(membershipRow.get("tenant_provenance_kind", String.class))
        .isEqualTo("FRESH_GAME_DESIGN");
    assertThat(membershipRow.get("tenant_source_operation_id", UUID.class))
        .isEqualTo(fixture.provenance().sourceOperationId());
    assertThat(membershipRow.get("tenant_provenance_digest", String.class))
        .isEqualTo(fixture.provenance().digest());

    Record operation =
        dsl.fetchOne(
            "SELECT tenant_id, game_instance_id, playable_state_namespace_id, "
                + "tenant_uuid, game_instance_uuid, playable_state_namespace_uuid, "
                + "status, entitlement_authority_availability, allow_public_join, request_digest "
                + "FROM account_join_operations WHERE request_id = ?",
            fixture.requestId());
    assertThat(operation).isNotNull();
    assertThat(operation.get("tenant_id", Long.class)).isNull();
    assertThat(operation.get("game_instance_id", Long.class)).isNull();
    assertThat(operation.get("playable_state_namespace_id", String.class)).isNull();
    assertThat(operation.get("tenant_uuid", UUID.class)).isEqualTo(fixture.tenantUuid());
    assertThat(operation.get("game_instance_uuid", UUID.class))
        .isEqualTo(fixture.scope().gameInstanceId());
    assertThat(operation.get("playable_state_namespace_uuid", UUID.class))
        .isEqualTo(fixture.scope().playableStateNamespaceId());
    assertThat(operation.get("status", String.class)).isEqualTo("PENDING");
    assertThat(operation.get("entitlement_authority_availability", String.class))
        .isEqualTo("AVAILABLE");
    assertThat(operation.get("allow_public_join", Boolean.class)).isTrue();
    assertThat(operation.get("request_digest", String.class))
        .isEqualTo(
            AccountJoinDigest.requestV2(
                fixture.scope(),
                fixture.callerBinding(),
                AccountJoinDigest.EntitlementAvailabilityV2.AVAILABLE,
                true,
                9L));
  }

  @Test
  void approvedRetainedUuidAssociationPublishesWithoutInventingTenantIdentity() {
    JoinFixture fixture = retainedFixture(true, 7L);
    var checkpoint = publishWithMembershipWrite(fixture);
    String streamKey = membershipStreamKey(fixture.account().accountUuid(), fixture.tenantUuid());
    Event persisted =
        inTransaction(
            () -> authorityOutbox.findEvent(streamKey, fixture.requestId()).orElseThrow());
    var event =
        MembershipAuthorityEventV1Codec.verify(
            new String(persisted.payload(), StandardCharsets.UTF_8));
    var pair =
        inTransaction(
            () ->
                pairAuthority
                    .readForUpdate(fixture.account().accountUuid(), fixture.tenantUuid())
                    .orElseThrow());

    assertThat(fixture.provenance().kind()).isEqualTo(TenantProvenanceKind.APPROVED_RETAINED);
    assertThat(checkpoint.outboxSequence()).isEqualTo(1L);
    assertThat(event.membershipVersion())
        .isEqualTo(java.util.Map.of(fixture.tenantUuid().toString(), "2"));
    assertThat(event.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(pair.provenance()).isEqualTo(fixture.provenance());
    assertThat(pair.membershipVersion()).isEqualTo(2L);
    assertThat(pair.lastEventSequence()).isEqualTo(1L);
    assertThat(event.tenantId()).isEqualTo(fixture.tenantUuid().toString());
    assertThat(
            dsl.resultQuery(
                    "SELECT COUNT(*) FROM account_tenant_membership "
                        + "WHERE account_id = ? AND tenant_uuid = ? AND tenant_id = ?",
                    fixture.account().accountId(),
                    fixture.tenantUuid(),
                    fixture.provenance().legacyTenantId())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void absentRequestChangedScopeUnavailablePolicyAndChangedDigestAreDenied() {
    JoinFixture notEvaluated = freshFixture(null, null);
    CanonicalJoinScopeV2 alteredScope =
        copyScope(notEvaluated.scope(), "different-world", notEvaluated.scope().tenantId());
    assertDeniedWithoutCommittedMembership(
        notEvaluated, alteredScope, notEvaluated.requestId(), "caller-bound-bootstrap");
    assertDeniedWithoutCommittedMembership(
        notEvaluated,
        copyScope(notEvaluated.scope(), notEvaluated.scope().worldSlug(), UUID.randomUUID()),
        notEvaluated.requestId(),
        notEvaluated.callerBinding());
    assertDeniedWithoutCommittedMembership(
        notEvaluated,
        notEvaluated.scope(),
        UUID.randomUUID().toString(),
        notEvaluated.callerBinding());
    assertDeniedWithoutCommittedMembership(
        notEvaluated, notEvaluated.scope(), notEvaluated.requestId(), notEvaluated.callerBinding());

    JoinFixture deniedPolicy = freshFixture(false, 12L);
    assertDeniedWithoutCommittedMembership(
        deniedPolicy, deniedPolicy.scope(), deniedPolicy.requestId(), deniedPolicy.callerBinding());

    JoinFixture changedDigest = freshFixture(true, 14L);
    dsl.execute(
        "UPDATE account_join_operations SET request_digest = ? WHERE request_id = ?",
        "sha256:" + "d".repeat(64),
        changedDigest.requestId());
    assertDeniedWithoutCommittedMembership(
        changedDigest,
        changedDigest.scope(),
        changedDigest.requestId(),
        changedDigest.callerBinding());
  }

  @Test
  void eventReadbackConflictRollsBackMembershipEventAndPairTogether() {
    JoinFixture fixture = freshFixture(true, 9L);
    MapSnapshot before = pairSnapshot(fixture);
    String streamKey = membershipStreamKey(fixture.account().accountUuid(), fixture.tenantUuid());
    doAnswer(
            invocation -> {
              Event exact = (Event) invocation.callRealMethod();
              return new Event(
                  exact.outboxStreamKey(),
                  exact.requestId(),
                  exact.outboxSequence(),
                  exact.eventId(),
                  "sha256:" + "e".repeat(64),
                  exact.payload());
            })
        .when(authorityOutboxSpy)
        .findEvent(eq(streamKey), eq(fixture.requestId()));

    assertThatThrownBy(() -> publishWithMembershipWrite(fixture))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("readback differs");

    assertThat(pairSnapshot(fixture)).isEqualTo(before);
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countEvents(streamKey)).isZero();
    assertThat(countStreams(streamKey)).isZero();
  }

  @Test
  void failureAfterPairAdvanceRollsBackMembershipEventAndPairTogether() {
    JoinFixture fixture = freshFixture(true, 9L);
    MapSnapshot before = pairSnapshot(fixture);
    String streamKey = membershipStreamKey(fixture.account().accountUuid(), fixture.tenantUuid());
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("simulated failure after pair advance");
            })
        .when(pairAuthoritySpy)
        .commitTransition(any(PairAuthority.class), any(PairTransition.class));

    assertThatThrownBy(() -> publishWithMembershipWrite(fixture))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated failure after pair advance");

    assertThat(pairSnapshot(fixture)).isEqualTo(before);
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countEvents(streamKey)).isZero();
    assertThat(countStreams(streamKey)).isZero();
  }

  @Test
  void retainedMembershipEventHistoryCannotBeResetOrReallocated() {
    JoinFixture fixture = freshFixture(true, 9L);
    String streamKey = membershipStreamKey(fixture.account().accountUuid(), fixture.tenantUuid());
    inTransaction(
        () -> {
          authorityOutbox.append(
              streamKey,
              "retained-history-" + UUID.randomUUID(),
              UUID.randomUUID().toString(),
              "sha256:" + "a".repeat(64),
              "{}".getBytes(StandardCharsets.UTF_8));
          return null;
        });
    MapSnapshot before = pairSnapshot(fixture);

    assertThatThrownBy(() -> publishWithMembershipWrite(fixture))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("retained membership-event history");

    assertThat(pairSnapshot(fixture)).isEqualTo(before);
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countEvents(streamKey)).isEqualTo(1L);
    assertThat(countStreams(streamKey)).isEqualTo(1L);
  }

  @Test
  void concurrentSameScopePublishersCommitOnlyOneSequenceOneEvent() throws Exception {
    JoinFixture fixture = freshFixture(true, 9L);
    inTransactionWithoutResult(() -> writeCanonicalMembershipAndRoles(fixture));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first =
          executor.submit(() -> publishConcurrently(fixture, ready, start, successes, rejected));
      Future<?> second =
          executor.submit(() -> publishConcurrently(fixture, ready, start, successes, rejected));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(30, TimeUnit.SECONDS);
      second.get(30, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    String streamKey = membershipStreamKey(fixture.account().accountUuid(), fixture.tenantUuid());
    assertThat(successes.get()).isEqualTo(1);
    assertThat(rejected.get()).isEqualTo(1);
    assertThat(countEvents(streamKey)).isEqualTo(1L);
    var pair =
        inTransaction(
            () ->
                pairAuthority
                    .readForUpdate(fixture.account().accountUuid(), fixture.tenantUuid())
                    .orElseThrow());
    assertThat(pair.membershipVersion()).isEqualTo(2L);
    assertThat(pair.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(pair.lastEventSequence()).isEqualTo(1L);
  }

  private JoinFixture freshFixture(Boolean allowPublicJoin, Long entitlementVersion) {
    AccountFixture account = accountFixture();
    UUID tenantUuid = UUID.randomUUID();
    FreshTenantCreationEvidence evidence = freshEvidence(tenantUuid);
    inTransaction(
        () -> {
          freshAssociations.importVerified(evidence);
          generations.initializeTenantIfAbsent(tenantUuid);
          generations.initializeIssuerIfAbsent(AccountServiceImpl.ACCOUNT_JWT_ISSUER);
          return null;
        });
    AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot baseline =
        inTransaction(
            () ->
                producer.readFreshNeverJoinedMembershipSnapshot(account.accountUuid(), tenantUuid));
    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            evidence.operationId(),
            evidence.evidenceDigest());
    return prepareOperation(
        account, tenantUuid, provenance, baseline, allowPublicJoin, entitlementVersion);
  }

  private JoinFixture retainedFixture(Boolean allowPublicJoin, Long entitlementVersion) {
    AccountFixture account = accountFixture();
    long legacyTenantId = positiveRandomLong();
    UUID tenantUuid = UUID.randomUUID();
    seedRetainedTenantSource(legacyTenantId);
    String sourceDigest = retainedSourceEvidence.digest(legacyTenantId);
    UUID associationOperation = UUID.randomUUID();
    ResolveLegacyAccountTenantAssociationResponse response =
        ResolveLegacyAccountTenantAssociationResponse.newBuilder()
            .setLegacyAccountTenantId(legacyTenantId)
            .setCanonicalTenantId(tenantUuid.toString())
            .setSourceLegacyGameTenantId("r-" + UUID.randomUUID().toString().replace("-", ""))
            .setSourceGameRowId(positiveRandomLong())
            .setAccountEvidenceDigest(sourceDigest)
            .setOperationId(associationOperation.toString())
            .setManifestDigest("sha256:" + "b".repeat(64))
            .setManifestSignature(Base64.getEncoder().encodeToString(new byte[64]))
            .setTargetNamespace(TEST_NAMESPACE)
            .setSignerKeyId("publisher-fixture")
            .setApprovedBy("fixture@example.test")
            .setApprovalReference("canonical-first-join-fixture")
            .setSignedAt(Instant.parse("2026-10-03T00:00:00Z").toString())
            .setOperationEntryCount(1)
            .setManifestSchemaVersion(1)
            .build();
    inTransaction(
        () -> {
          approvedAssociations.importApproved(legacyTenantId, response);
          generations.initializeIssuerIfAbsent(AccountServiceImpl.ACCOUNT_JWT_ISSUER);
          return null;
        });
    inTransactionWithoutResult(
        () -> producer.preparePairAuthorityForJoin(account.accountId(), legacyTenantId));
    var baseline =
        inTransaction(
            () -> pairAuthority.readForUpdate(account.accountUuid(), tenantUuid).orElseThrow());
    assertThat(baseline.membershipExists()).isFalse();
    assertThat(baseline.membershipVersion()).isEqualTo(1L);
    assertThat(baseline.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(baseline.lastEventSequence()).isZero();
    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            legacyTenantId,
            TenantProvenanceKind.APPROVED_RETAINED,
            associationOperation,
            response.getManifestDigest());
    return prepareOperation(
        account, tenantUuid, provenance, null, allowPublicJoin, entitlementVersion);
  }

  private JoinFixture prepareOperation(
      AccountFixture account,
      UUID tenantUuid,
      VerifiedTenantProvenance provenance,
      AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot baseline,
      Boolean allowPublicJoin,
      Long entitlementVersion) {
    CanonicalJoinScopeV2 scope = canonicalScope(account.accountUuid(), tenantUuid);
    String requestId = UUID.randomUUID().toString();
    String callerBinding = "fixture-bootstrap-" + UUID.randomUUID();
    inTransaction(
        () -> {
          connectScopes.insertCanonical(account.accountId(), scope, provenance);
          joinOperations.insertCanonicalIntent(requestId, scope, callerBinding);
          if (allowPublicJoin != null) {
            joinOperations.bindCanonicalPolicyEvidence(
                requestId, scope, callerBinding, allowPublicJoin, entitlementVersion);
          }
          return null;
        });
    return new JoinFixture(
        account, tenantUuid, provenance, scope, requestId, callerBinding, baseline);
  }

  private AccountAuthorityOutboxRepository.Checkpoint publishWithMembershipWrite(
      JoinFixture fixture) {
    return inTransaction(
        () -> {
          writeCanonicalMembershipAndRoles(fixture);
          return producer.publishCanonicalFirstJoinMembershipChange(
              fixture.scope(), fixture.requestId(), fixture.callerBinding());
        });
  }

  private void writeCanonicalMembershipAndRoles(JoinFixture fixture) {
    Account account = accounts.findByAccountUuid(fixture.account().accountUuid()).orElseThrow();
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
        membership, fixture.account().accountUuid(), fixture.tenantUuid(), fixture.provenance());
    roleSnapshots.replaceCanonical(
        membership,
        fixture.account().accountUuid(),
        fixture.tenantUuid(),
        fixture.provenance(),
        2L,
        java.util.List.of("player"));
  }

  private void assertDeniedWithoutCommittedMembership(
      JoinFixture fixture,
      CanonicalJoinScopeV2 requestScope,
      String requestId,
      String callerBinding) {
    String streamKey = membershipStreamKey(fixture.account().accountUuid(), fixture.tenantUuid());
    MapSnapshot before = pairSnapshot(fixture);
    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> {
                      writeCanonicalMembershipAndRoles(fixture);
                      return producer.publishCanonicalFirstJoinMembershipChange(
                          requestScope, requestId, callerBinding);
                    }))
        .isInstanceOf(RuntimeException.class);
    assertThat(pairSnapshot(fixture)).isEqualTo(before);
    assertThat(countMemberships(fixture)).isZero();
    assertThat(countEvents(streamKey)).isZero();
    assertThat(countStreams(streamKey)).isZero();
  }

  private void publishConcurrently(
      JoinFixture fixture,
      CountDownLatch ready,
      CountDownLatch start,
      AtomicInteger successes,
      AtomicInteger rejected) {
    ready.countDown();
    await(start);
    try {
      inTransaction(
          () ->
              producer.publishCanonicalFirstJoinMembershipChange(
                  fixture.scope(), fixture.requestId(), fixture.callerBinding()));
      successes.incrementAndGet();
    } catch (RuntimeException conflict) {
      rejected.incrementAndGet();
    }
  }

  private AccountFixture accountFixture() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    long accountId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                    "first-join-" + suffix,
                    "canonical-first-join-" + suffix + "@example.test",
                    "fixture-hash")
                .fetchOne(0, Long.class),
            "Account fixture insert returned no row ID");
    UUID accountUuid =
        Objects.requireNonNull(
            dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", accountId)
                .fetchOne(0, UUID.class),
            "Account fixture row has no canonical UUID");
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

  private FreshTenantCreationEvidence freshEvidence(UUID tenantUuid) {
    UUID creationRequestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String sourceTenantKey = "f-" + UUID.randomUUID().toString().replace("-", "");
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            TEST_NAMESPACE, creationRequestId, sourceTenantKey, "First join fixture", null);
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

  private CanonicalJoinScopeV2 canonicalScope(UUID accountUuid, UUID tenantUuid) {
    return new CanonicalJoinScopeV2(
        "first-join-scope-" + UUID.randomUUID(),
        accountUuid,
        tenantUuid,
        UUID.randomUUID(),
        "join-tenant-" + UUID.randomUUID().toString().substring(0, 8),
        "join-world-" + UUID.randomUUID().toString().substring(0, 8),
        "production",
        UUID.randomUUID(),
        "SHARED",
        UUID.randomUUID(),
        3L,
        2L,
        "2026-10-03T00:00:00Z",
        "2026-10-03T01:00:00Z");
  }

  private CanonicalJoinScopeV2 copyScope(
      CanonicalJoinScopeV2 source, String worldSlug, UUID tenantUuid) {
    return new CanonicalJoinScopeV2(
        source.connectScopeId(),
        source.accountId(),
        tenantUuid,
        source.realmId(),
        source.tenantSlug(),
        worldSlug,
        source.realmSlug(),
        source.playableStateNamespaceId(),
        source.playableStateScope(),
        source.gameInstanceId(),
        source.catalogRevision(),
        source.pointerVersion(),
        source.evaluatedAt(),
        source.connectScopeExpiresAt());
  }

  private void seedRetainedTenantSource(long tenantId) {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    Record donor =
        Objects.requireNonNull(
            dsl.fetchOne(
                "INSERT INTO accounts (username, email, password_hash, tenant_id) "
                    + "VALUES (?, ?, ?, ?) RETURNING id",
                "retained-source-" + suffix,
                "retained-source-" + suffix + "@example.test",
                "fixture-hash",
                tenantId),
            "Retained-tenant donor insert returned no row");
    long donorId =
        Objects.requireNonNull(
            donor.get("id", Long.class), "Retained-tenant donor row has no numeric ID");
    long membershipId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "INSERT INTO account_tenant_membership "
                        + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
                        + "membership_version, membership_authority_generation, authority_provenance) "
                        + "VALUES (?, ?, FALSE, 'LEGACY_UNVERIFIED', 1, 1, 'LEGACY_UNVERIFIED') "
                        + "RETURNING id",
                    donorId,
                    tenantId)
                .fetchOne(0, Long.class),
            "Retained-tenant donor membership insert returned no row ID");
    dsl.execute(
        "INSERT INTO account_legacy_tenant_sources "
            + "(account_id, legacy_tenant_id, matching_membership_id, "
            + "matching_membership_admission_allowed, profile_tenant_count, matching_profile_count, "
            + "disposition) VALUES (?, ?, ?, FALSE, 0, 0, 'UNVERIFIED')",
        donorId,
        tenantId,
        membershipId);
    dsl.execute(
        "INSERT INTO account_legacy_membership_sources "
            + "(membership_id, account_id, tenant_id, original_gameplay_admission_allowed, "
            + "matches_account_legacy_tenant, disposition) "
            + "VALUES (?, ?, ?, FALSE, TRUE, 'UNVERIFIED')",
        membershipId,
        donorId,
        tenantId);
  }

  private MapSnapshot pairSnapshot(JoinFixture fixture) {
    Record row =
        dsl.fetchOne(
            "SELECT membership_exists, membership_version, membership_authority_generation, "
                + "last_event_sequence, last_event_id, last_event_digest, "
                + "last_transition_invalidated FROM account_membership_pair_authority "
                + "WHERE account_uuid = ? AND tenant_uuid = ?",
            fixture.account().accountUuid(),
            fixture.tenantUuid());
    assertThat(row).isNotNull();
    return new MapSnapshot(row.intoMap());
  }

  private long countMemberships(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_tenant_membership "
                    + "WHERE account_id = ? AND tenant_uuid = ?",
                fixture.account().accountId(),
                fixture.tenantUuid())
            .fetchOne(0, Long.class),
        "Account membership count query returned no value");
  }

  private long countEvents(String streamKey) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_events WHERE outbox_stream_key = ?",
                streamKey)
            .fetchOne(0, Long.class),
        "Account authority event count query returned no value");
  }

  private long countStreams(String streamKey) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_authority_outbox_streams WHERE outbox_stream_key = ?",
                streamKey)
            .fetchOne(0, Long.class),
        "Account authority stream count query returned no value");
  }

  private long countReceipts(JoinFixture fixture) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT COUNT(*) FROM account_membership_transition_receipts "
                    + "WHERE account_uuid = ? AND tenant_uuid = ? AND receipt_version = 2",
                fixture.account().accountUuid(),
                fixture.tenantUuid())
            .fetchOne(0, Long.class),
        "Account transition receipt count query returned no value");
  }

  private long issuanceFence(UUID accountUuid) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT issuance_fence FROM account_authority_issuance_fences WHERE account_uuid = ?",
                accountUuid)
            .fetchOne(0, Long.class),
        "Account issuance fence row has no value");
  }

  private long generation(String kind, UUID accountUuid, UUID tenantUuid) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT generation FROM account_authority_generations WHERE scope_kind = ? "
                    + "AND account_uuid IS NOT DISTINCT FROM ? AND tenant_uuid IS NOT DISTINCT FROM ?",
                kind,
                accountUuid,
                tenantUuid)
            .fetchOne(0, Long.class),
        "Account authority generation row has no value");
  }

  private String membershipStreamKey(UUID accountUuid, UUID tenantUuid) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "membership/"
        + accountUuid
        + "/"
        + tenantUuid;
  }

  private <T> T inTransaction(java.util.function.Supplier<T> callback) {
    return new TransactionTemplate(transactionManager).execute(status -> callback.get());
  }

  private void inTransactionWithoutResult(Runnable callback) {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> callback.run());
  }

  private long positiveRandomLong() {
    long candidate = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    return candidate == 0L ? 1L : candidate;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent publisher barrier timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent publisher was interrupted", exception);
    }
  }

  private record AccountFixture(long accountId, UUID accountUuid) {}

  private record JoinFixture(
      AccountFixture account,
      UUID tenantUuid,
      VerifiedTenantProvenance provenance,
      CanonicalJoinScopeV2 scope,
      String requestId,
      String callerBinding,
      AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot baseline) {}

  private record MapSnapshot(java.util.Map<String, Object> values) {}
}
