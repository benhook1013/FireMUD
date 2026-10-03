package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.CanonicalMembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceipt;
import net.firedevops.firemud.accountservice.dto.MembershipTransitionReceiptDigest;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairTransition;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository.TransitionReceiptEvidence;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL storage proof for V1/V2 provisional receipts; fixture evidence is not auth proof. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountMembershipTransitionReceiptCanonicalIntegrationTest {
  private static final String TEST_NAMESPACE = "account-service";
  private static final long RETAINED_TENANT_ID = 700L;
  private static final String MANIFEST_DIGEST = "sha256:" + "b".repeat(64);
  private static final String RECEIPT_V1_COLUMNS =
      "receipt_stream_key, receipt_sequence, account_id, tenant_id, evidence_status, "
          + "transition_type, request_id, membership_id, membership_lifecycle_state, "
          + "gameplay_admission_allowed, membership_version, membership_authority_generation, "
          + "authority_provenance, receipt_id, receipt_digest, created_at";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void retainedV1HistoryBindsOnceAndCanonicalAppendContinuesItsSequence() {
    Fixture fixture = fixture();
    RetainedPair pair = seedRetainedPair(fixture, "INACTIVE", false, 2L, 2L);
    MembershipTransitionReceipt v1Receipt = seedV47V1Receipt(fixture, pair, "retained-v1-left");
    Map<String, Object> v1RowBefore =
        v1ReceiptProjection(fixture.setupDsl(), pair.accountId(), RETAINED_TENANT_ID, 1L);
    Map<String, Object> v1HeadBefore =
        v1HeadProjection(fixture.setupDsl(), pair.accountId(), RETAINED_TENANT_ID);

    restoreMembership(fixture, pair, 3L, 3L);
    migrateToV48(fixture);

    assertThat(v1ReceiptProjection(fixture.setupDsl(), pair.accountId(), RETAINED_TENANT_ID, 1L))
        .containsExactlyEntriesOf(v1RowBefore);
    assertThat(v1HeadProjection(fixture.setupDsl(), pair.accountId(), RETAINED_TENANT_ID))
        .containsEntry("last_receipt_sequence", 1L)
        .containsEntry("receipt_stream_key", v1HeadBefore.get("receipt_stream_key"));
    assertThat(canonicalHeadProjection(fixture.setupDsl(), pair.accountId(), RETAINED_TENANT_ID))
        .containsEntry("account_uuid", null)
        .containsEntry("tenant_uuid", null)
        .containsEntry("tenant_provenance_kind", null)
        .containsEntry("tenant_source_operation_id", null)
        .containsEntry("tenant_provenance_digest", null);

    AccountMembershipTransitionReceiptRepository canonical = repository(fixture);
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status ->
                            canonical.appendCanonicalTransition(
                                pair.accountUuid(),
                                pair.tenantUuid(),
                                "MEMBERSHIP_REACTIVATED",
                                "retained-v1-left")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(v1HeadProjection(fixture.setupDsl(), pair.accountId(), RETAINED_TENANT_ID))
        .containsEntry("last_receipt_sequence", 1L);
    CanonicalMembershipTransitionReceipt v2Receipt =
        fixture
            .transaction()
            .execute(
                status ->
                    canonical.appendCanonicalTransition(
                        pair.accountUuid(),
                        pair.tenantUuid(),
                        "MEMBERSHIP_REACTIVATED",
                        "canonical-v2-join"));
    assertThat(v2Receipt.receiptSequence()).isEqualTo(2L);
    assertThat(v2Receipt.accountId()).isEqualTo(pair.accountUuid());
    assertThat(v2Receipt.tenantId()).isEqualTo(pair.tenantUuid());
    assertThat(v2Receipt.membershipVersion())
        .containsExactly(Map.entry(pair.tenantUuid().toString(), "3"));
    assertThat(v1ReceiptProjection(fixture.setupDsl(), pair.accountId(), RETAINED_TENANT_ID, 1L))
        .containsExactlyEntriesOf(v1RowBefore);
    assertThat(v1HeadProjection(fixture.setupDsl(), pair.accountId(), RETAINED_TENANT_ID))
        .containsEntry("last_receipt_sequence", 2L);
    assertThat(canonicalHeadProjection(fixture.setupDsl(), pair.accountId(), RETAINED_TENANT_ID))
        .containsEntry("account_uuid", pair.accountUuid())
        .containsEntry("tenant_uuid", pair.tenantUuid())
        .containsEntry("tenant_provenance_kind", "APPROVED_RETAINED")
        .containsEntry("tenant_source_operation_id", pair.sourceOperationId())
        .containsEntry("tenant_provenance_digest", MANIFEST_DIGEST);

    Optional<CanonicalMembershipTransitionReceipt> canonicalReadback =
        fixture
            .transaction()
            .execute(status -> canonical.findCanonicalByRequestId("canonical-v2-join"));
    assertThat(canonicalReadback).contains(v2Receipt);
    Optional<TransitionReceiptEvidence> retainedReadback =
        fixture.transaction().execute(status -> canonical.findByRequestId("retained-v1-left"));
    assertThat(retainedReadback).map(evidence -> evidence.receipt()).contains(v1Receipt);
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status ->
                            canonical.findLatestReceipt(pair.accountId(), RETAINED_TENANT_ID)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status ->
                            canonical.appendTransition(
                                pair.membership(), "MEMBERSHIP_LEFT", "unsupported-v1-latest")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(v1HeadProjection(fixture.setupDsl(), pair.accountId(), RETAINED_TENANT_ID))
        .containsEntry("last_receipt_sequence", 2L);
  }

  @Test
  void freshCanonicalHeadHasNoNumericTenantAliasAndExactRetryDoesNotAdvanceIt() {
    Fixture fixture = fixture();
    FreshPair pair = seedFreshPair(fixture, "ACTIVE", true, 2L, 1L, "fresh-v2-join");
    migrateToV48(fixture);
    AccountMembershipTransitionReceiptRepository repository = repository(fixture);
    assertFirstJoinSourceEvidence(fixture, pair, "fresh-v2-join");

    CanonicalMembershipTransitionReceipt first =
        fixture
            .transaction()
            .execute(
                status ->
                    repository.appendCanonicalTransition(
                        pair.accountUuid(),
                        pair.tenantUuid(),
                        "MEMBERSHIP_JOINED",
                        "fresh-v2-join"));
    CanonicalMembershipTransitionReceipt retry =
        fixture
            .transaction()
            .execute(
                status ->
                    repository.appendCanonicalTransition(
                        pair.accountUuid(),
                        pair.tenantUuid(),
                        "MEMBERSHIP_JOINED",
                        "fresh-v2-join"));

    assertThat(retry).isEqualTo(first);
    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status ->
                            repository.appendCanonicalTransition(
                                pair.accountUuid(),
                                pair.tenantUuid(),
                                "MEMBERSHIP_REACTIVATED",
                                "fresh-v2-join")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(first.receiptSequence()).isEqualTo(1L);
    assertThat(first.receiptStreamKey())
        .isEqualTo(
            "account:membership-transition-receipt:v2:membership/"
                + pair.accountUuid()
                + "/"
                + pair.tenantUuid());
    assertThat(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT tenant_id, tenant_uuid FROM account_tenant_membership "
                        + "WHERE account_id = ? AND tenant_uuid = ?",
                    pair.accountId(),
                    pair.tenantUuid()))
        .satisfies(
            row -> {
              assertThat(row.get("tenant_id", Long.class)).isNull();
              assertThat(row.get("tenant_uuid", UUID.class)).isEqualTo(pair.tenantUuid());
            });
    Record canonicalReceiptRow =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT tenant_id FROM account_membership_transition_receipts "
                        + "WHERE request_id = ?",
                    "fresh-v2-join"),
            "Expected canonical receipt row for fresh-v2-join");
    assertThat(canonicalReceiptRow.get("tenant_id", Long.class)).isNull();
    assertThat(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT tenant_id, tenant_uuid "
                        + "FROM account_membership_transition_receipt_stream_heads "
                        + "WHERE account_uuid = ? AND tenant_uuid = ?",
                    pair.accountUuid(),
                    pair.tenantUuid()))
        .satisfies(
            row -> {
              assertThat(row.get("tenant_id", Long.class)).isNull();
              assertThat(row.get("tenant_uuid", UUID.class)).isEqualTo(pair.tenantUuid());
            });
    assertThat(headSequence(fixture, pair.accountId(), pair.tenantUuid())).isEqualTo(1L);
    assertThat(receiptCount(fixture, pair.accountId(), pair.tenantUuid())).isEqualTo(1L);
  }

  @Test
  void freshKnownJoinHistoryWithMissingReceiptHeadCannotRestartForAnotherRequest() {
    Fixture fixture = fixture();
    FreshPair pair = seedFreshPair(fixture, "ACTIVE", true, 2L, 1L, "known-first-join");
    migrateToV48(fixture);
    AccountMembershipTransitionReceiptRepository repository = repository(fixture);
    assertFirstJoinSourceEvidence(fixture, pair, "known-first-join");

    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status ->
                            repository.appendCanonicalTransition(
                                pair.accountUuid(),
                                pair.tenantUuid(),
                                "MEMBERSHIP_JOINED",
                                "different-request-after-first-join")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(headCount(fixture, pair.accountId(), pair.tenantUuid())).isZero();
    assertThat(receiptCount(fixture, pair.accountId(), pair.tenantUuid())).isZero();
    assertFirstJoinSourceEvidence(fixture, pair, "known-first-join");
  }

  @Test
  void retainedMembershipHistoryWithoutReceiptHeadCannotStartCanonicalSequence() {
    Fixture fixture = fixture();
    RetainedPair pair = seedRetainedPair(fixture, "ACTIVE", true, 2L, 2L);
    migrateToV48(fixture);
    AccountMembershipTransitionReceiptRepository repository = repository(fixture);

    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status ->
                            repository.appendCanonicalTransition(
                                pair.accountUuid(),
                                pair.tenantUuid(),
                                "MEMBERSHIP_JOINED",
                                "must-not-reset-retained-history")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(headCount(fixture, pair.accountId(), pair.tenantUuid())).isZero();
    assertThat(receiptCount(fixture, pair.accountId(), pair.tenantUuid())).isZero();
  }

  @Test
  void concurrentMatchingRequestConvergesOnOneOriginalCanonicalReceipt() throws Exception {
    Fixture fixture = fixture();
    FreshPair pair = seedFreshPair(fixture, "ACTIVE", true, 2L, 1L, "concurrent-v2-join");
    migrateToV48(fixture);
    AccountMembershipTransitionReceiptRepository repository = repository(fixture);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<CanonicalMembershipTransitionReceipt> first =
          executor.submit(() -> appendAfterStart(fixture, repository, pair, ready, start));
      Future<CanonicalMembershipTransitionReceipt> second =
          executor.submit(() -> appendAfterStart(fixture, repository, pair, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      CanonicalMembershipTransitionReceipt one = first.get(30, TimeUnit.SECONDS);
      CanonicalMembershipTransitionReceipt two = second.get(30, TimeUnit.SECONDS);
      assertThat(one).isEqualTo(two);
      assertThat(headSequence(fixture, pair.accountId(), pair.tenantUuid())).isEqualTo(1L);
      assertThat(receiptCount(fixture, pair.accountId(), pair.tenantUuid())).isEqualTo(1L);
    }
  }

  @Test
  void rollbackAndUnprovedSourceLeaveNoCanonicalHeadOrReceipt() {
    Fixture fixture = fixture();
    FreshPair pair = seedFreshPair(fixture, "ACTIVE", true, 2L, 1L, "rolled-back-v2-join");
    migrateToV48(fixture);
    AccountMembershipTransitionReceiptRepository repository = repository(fixture);

    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status -> {
                          repository.appendCanonicalTransition(
                              pair.accountUuid(),
                              pair.tenantUuid(),
                              "MEMBERSHIP_JOINED",
                              "rolled-back-v2-join");
                          throw new IllegalStateException("rollback fixture");
                        }))
        .hasMessage("rollback fixture");
    assertThat(headCount(fixture, pair.accountId(), pair.tenantUuid())).isZero();
    assertThat(receiptCount(fixture, pair.accountId(), pair.tenantUuid())).isZero();

    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .execute(
                        status ->
                            repository.appendCanonicalTransition(
                                pair.accountUuid(),
                                UUID.randomUUID(),
                                "MEMBERSHIP_JOINED",
                                "unmapped-v2-join")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(headCount(fixture, pair.accountId(), pair.tenantUuid())).isZero();
    assertThat(receiptCount(fixture, pair.accountId(), pair.tenantUuid())).isZero();
  }

  @Test
  void canonicalAppendRequiresReadWriteOwnerTransactionAndHistoricalReadbackIsNotCurrent() {
    Fixture fixture = fixture();
    FreshPair pair = seedFreshPair(fixture, "ACTIVE", true, 2L, 1L, "historical-join");
    migrateToV48(fixture);
    AccountMembershipTransitionReceiptRepository repository = repository(fixture);

    assertThatThrownBy(
            () ->
                repository.appendCanonicalTransition(
                    pair.accountUuid(),
                    pair.tenantUuid(),
                    "MEMBERSHIP_JOINED",
                    "outside-transaction"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(new DataSourceTransactionManager(fixture.dataSource())) {
                  {
                    setReadOnly(true);
                  }
                }.execute(
                    status ->
                        repository.appendCanonicalTransition(
                            pair.accountUuid(),
                            pair.tenantUuid(),
                            "MEMBERSHIP_JOINED",
                            "read-only-transaction")))
        .isInstanceOf(IllegalStateException.class);

    CanonicalMembershipTransitionReceipt joined =
        fixture
            .transaction()
            .execute(
                status ->
                    repository.appendCanonicalTransition(
                        pair.accountUuid(),
                        pair.tenantUuid(),
                        "MEMBERSHIP_JOINED",
                        "historical-join"));
    moveFreshMembershipToLeft(fixture, pair);
    CanonicalMembershipTransitionReceipt left =
        fixture
            .transaction()
            .execute(
                status ->
                    repository.appendCanonicalTransition(
                        pair.accountUuid(),
                        pair.tenantUuid(),
                        "MEMBERSHIP_LEFT",
                        "historical-left"));
    assertThat(left.receiptSequence()).isEqualTo(2L);
    Optional<CanonicalMembershipTransitionReceipt> historicalJoin =
        fixture
            .transaction()
            .execute(status -> repository.findCanonicalByRequestId("historical-join"));
    assertThat(historicalJoin).contains(joined);
    Optional<CanonicalMembershipTransitionReceipt> latestCanonical =
        fixture
            .transaction()
            .execute(
                status ->
                    repository.findLatestCanonicalReceipt(pair.accountUuid(), pair.tenantUuid()));
    assertThat(latestCanonical).contains(left);
  }

  private static CanonicalMembershipTransitionReceipt appendAfterStart(
      Fixture fixture,
      AccountMembershipTransitionReceiptRepository repository,
      FreshPair pair,
      CountDownLatch ready,
      CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("concurrent canonical receipt start timed out");
    }
    return fixture
        .transaction()
        .execute(
            status ->
                repository.appendCanonicalTransition(
                    pair.accountUuid(),
                    pair.tenantUuid(),
                    "MEMBERSHIP_JOINED",
                    "concurrent-v2-join"));
  }

  private static RetainedPair seedRetainedPair(
      Fixture fixture,
      String lifecycleState,
      boolean admissionAllowed,
      long membershipVersion,
      long authorityGeneration) {
    long accountId = insertAccount(fixture.setupDsl(), "retained-receipt");
    UUID accountUuid = accountUuid(fixture.setupDsl(), accountId);
    seedRetainedTenantEvidence(fixture.setupDsl(), RETAINED_TENANT_ID);
    LegacyTenantSourceEvidence sourceEvidence =
        new LegacyTenantSourceEvidence(fixture.transactionDsl());
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(fixture.transactionDsl());
    ApprovedLegacyTenantAssociationRepository associations =
        new ApprovedLegacyTenantAssociationRepository(
            fixture.transactionDsl(), sourceEvidence, TEST_NAMESPACE, generations);
    UUID tenantUuid = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    String retainedDigest = sourceEvidence.digest(RETAINED_TENANT_ID);
    ResolveLegacyAccountTenantAssociationResponse ownerEvidence =
        ResolveLegacyAccountTenantAssociationResponse.newBuilder()
            .setLegacyAccountTenantId(RETAINED_TENANT_ID)
            .setCanonicalTenantId(tenantUuid.toString())
            .setSourceLegacyGameTenantId("r-" + UUID.randomUUID().toString().replace("-", ""))
            .setSourceGameRowId(positiveLong())
            .setAccountEvidenceDigest(retainedDigest)
            .setOperationId(sourceOperationId.toString())
            .setManifestDigest(MANIFEST_DIGEST)
            .setManifestSignature(Base64.getEncoder().encodeToString(new byte[64]))
            .setTargetNamespace(TEST_NAMESPACE)
            .setSignerKeyId("owner-fixture")
            .setApprovedBy("fixture@example.test")
            .setApprovalReference("canonical-receipt-test")
            .setSignedAt(Instant.parse("2026-10-03T00:00:00Z").toString())
            .setOperationEntryCount(1)
            .setManifestSchemaVersion(1)
            .build();
    var imported =
        fixture
            .transaction()
            .execute(status -> associations.importApproved(RETAINED_TENANT_ID, ownerEvidence));
    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            RETAINED_TENANT_ID,
            TenantProvenanceKind.APPROVED_RETAINED,
            imported.operationId(),
            MANIFEST_DIGEST);
    AccountTenantMembershipRepository memberships =
        membershipRepository(fixture, associations, sourceEvidence);
    var membership = new net.firedevops.firemud.accountservice.entity.AccountTenantMembership();
    var account = new net.firedevops.firemud.accountservice.entity.Account();
    account.setId(accountId);
    membership.setAccount(account);
    membership.setTenantId(RETAINED_TENANT_ID);
    membership.setLifecycleState(lifecycleState);
    membership.setGameplayAdmissionAllowed(admissionAllowed);
    membership.setMembershipVersion(membershipVersion);
    membership.setMembershipAuthorityGeneration(authorityGeneration);
    membership.setAuthorityProvenance("EXPLICIT_JOIN");
    var persisted =
        fixture
            .transaction()
            .execute(
                status ->
                    memberships.saveCanonical(membership, accountUuid, tenantUuid, provenance));
    return new RetainedPair(
        accountId, accountUuid, RETAINED_TENANT_ID, tenantUuid, sourceOperationId, persisted);
  }

  private static FreshPair seedFreshPair(
      Fixture fixture,
      String lifecycleState,
      boolean admissionAllowed,
      long membershipVersion,
      long authorityGeneration,
      String requestId) {
    long accountId = insertAccount(fixture.setupDsl(), "fresh-receipt");
    UUID accountUuid = accountUuid(fixture.setupDsl(), accountId);
    UUID tenantUuid = UUID.randomUUID();
    FreshTenantCreationEvidence evidence = freshTenantEvidence(tenantUuid);
    FreshTenantIdentityAssociationRepository fresh =
        new FreshTenantIdentityAssociationRepository(fixture.transactionDsl(), TEST_NAMESPACE);
    fixture.transaction().executeWithoutResult(status -> fresh.importVerified(evidence));
    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            evidence.operationId(),
            evidence.evidenceDigest());
    LegacyTenantSourceEvidence sourceEvidence =
        new LegacyTenantSourceEvidence(fixture.transactionDsl());
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(fixture.transactionDsl());
    ApprovedLegacyTenantAssociationRepository associations =
        new ApprovedLegacyTenantAssociationRepository(
            fixture.transactionDsl(), sourceEvidence, TEST_NAMESPACE, generations);
    AccountTenantMembershipRepository memberships =
        membershipRepository(fixture, associations, sourceEvidence);
    AccountTenantMembershipRoleSnapshotRepository roles =
        new AccountTenantMembershipRoleSnapshotRepository(fixture.transactionDsl());
    AccountMembershipPairAuthorityRepository pairAuthority =
        new AccountMembershipPairAuthorityRepository(fixture.transactionDsl());
    AccountAuthorityOutboxRepository authorityOutbox =
        new AccountAuthorityOutboxRepository(fixture.transactionDsl());
    AccountConnectScopeRepository connectScopes =
        new AccountConnectScopeRepository(
            fixture.transactionDsl(),
            new AccountRepository(fixture.transactionDsl()),
            new AccountTenantIdentityResolver(associations, sourceEvidence, TEST_NAMESPACE),
            fresh);
    AccountJoinOperationRepository joinOperations =
        new AccountJoinOperationRepository(fixture.transactionDsl(), connectScopes);
    CanonicalJoinScopeV2 scope = canonicalFreshJoinScope(accountUuid, tenantUuid);
    var membership = new net.firedevops.firemud.accountservice.entity.AccountTenantMembership();
    var account = new net.firedevops.firemud.accountservice.entity.Account();
    account.setId(accountId);
    membership.setAccount(account);
    membership.setLifecycleState(lifecycleState);
    membership.setGameplayAdmissionAllowed(admissionAllowed);
    membership.setMembershipVersion(membershipVersion);
    membership.setMembershipAuthorityGeneration(authorityGeneration);
    membership.setAuthorityProvenance("EXPLICIT_JOIN");
    var persisted =
        fixture
            .transaction()
            .execute(
                status -> {
                  joinOperations.lockAccount(accountId);
                  generations.initializeIssuerIfAbsent(AccountServiceImpl.ACCOUNT_JWT_ISSUER);
                  generations.initialize(AuthorityScope.account(accountUuid));
                  generations.initializeTenantIfAbsent(tenantUuid);
                  generations.initialize(AuthorityScope.membership(accountUuid, tenantUuid));
                  PairAuthority absence =
                      pairAuthority.enrollAbsence(accountUuid, tenantUuid, provenance);

                  connectScopes.insertCanonical(accountId, scope, provenance);
                  if (!joinOperations.insertCanonicalIntent(requestId, scope, "receipt-fixture")) {
                    throw new IllegalStateException("Canonical JOIN fixture intent already exists");
                  }
                  // Synthetic policy evidence satisfies the storage precondition only; it is not
                  // an authenticated entitlement result or production commit-gate proof.
                  joinOperations.bindCanonicalPolicyEvidence(
                      requestId, scope, "receipt-fixture", true, 9L);
                  var savedMembership =
                      memberships.saveCanonical(membership, accountUuid, tenantUuid, provenance);
                  roles.replaceCanonical(
                      savedMembership,
                      accountUuid,
                      tenantUuid,
                      provenance,
                      membershipVersion,
                      List.of("player"));

                  CompositeSnapshot authority =
                      generations.readCompositeSnapshot(
                          AccountServiceImpl.ACCOUNT_JWT_ISSUER,
                          accountUuid,
                          List.of(tenantUuid),
                          List.of(tenantUuid));
                  // This test-only producer composes the unchanged event codec with the durable
                  // outbox and pair repositories; it is not Account's missing UUID JOIN publisher.
                  MembershipEvent candidate =
                      firstMembershipEvent(
                          requestId, accountUuid, tenantUuid, savedMembership, authority);
                  String streamKey = candidate.outboxStreamKey();
                  Event appended =
                      authorityOutbox.append(
                          streamKey,
                          requestId,
                          candidate.eventId(),
                          candidate.eventDigest(),
                          candidate.canonicalJsonUtf8());
                  Event readback =
                      authorityOutbox
                          .findEvent(streamKey, requestId)
                          .orElseThrow(
                              () ->
                                  new IllegalStateException(
                                      "Fixture authority event readback is absent"));
                  Checkpoint checkpoint =
                      authorityOutbox
                          .readCheckpoint(streamKey)
                          .orElseThrow(
                              () ->
                                  new IllegalStateException(
                                      "Fixture authority event checkpoint is absent"));
                  if (!appended.equals(readback)
                      || appended.outboxSequence() != 1L
                      || checkpoint.outboxSequence() != 1L
                      || !checkpoint.sourceEventId().equals(candidate.eventId())
                      || !checkpoint.sourceEventDigest().equals(candidate.eventDigest())) {
                    throw new IllegalStateException(
                        "Fixture authority event/checkpoint differs from its canonical preimage");
                  }
                  PairAuthority committed =
                      pairAuthority.commitTransition(
                          absence,
                          new PairTransition(
                              true,
                              checkpoint.outboxSequence(),
                              checkpoint.sourceEventId(),
                              checkpoint.sourceEventDigest(),
                              false));
                  if (!committed.membershipExists()
                      || committed.membershipVersion() != membershipVersion
                      || committed.membershipAuthorityGeneration() != authorityGeneration
                      || committed.lastEventSequence() != checkpoint.outboxSequence()) {
                    throw new IllegalStateException(
                        "Fixture first-event pair readback differs from membership source");
                  }
                  return savedMembership;
                });
    return new FreshPair(accountId, accountUuid, tenantUuid, evidence, persisted);
  }

  private static CanonicalJoinScopeV2 canonicalFreshJoinScope(UUID accountUuid, UUID tenantUuid) {
    return new CanonicalJoinScopeV2(
        "canonical-receipt-scope-" + UUID.randomUUID(),
        accountUuid,
        tenantUuid,
        UUID.randomUUID(),
        "canonical-receipt-tenant",
        "canonical-receipt-world",
        "production",
        UUID.randomUUID(),
        "SHARED",
        UUID.randomUUID(),
        1L,
        1L,
        "2026-10-03T00:00:00Z",
        "2026-10-03T01:00:00Z");
  }

  private static MembershipEvent firstMembershipEvent(
      String requestId,
      UUID accountUuid,
      UUID tenantUuid,
      net.firedevops.firemud.accountservice.entity.AccountTenantMembership membership,
      CompositeSnapshot authority) {
    String streamKey =
        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
            + "membership/"
            + accountUuid
            + "/"
            + tenantUuid;
    String eventId =
        UUID.nameUUIDFromBytes(
                (MembershipAuthorityEventV1Codec.SCHEMA_VERSION + ":" + requestId)
                    .getBytes(StandardCharsets.UTF_8))
            .toString();
    ScopeState issuer = authority.issuer();
    ScopeState account = authority.account();
    ScopeState tenant = authority.tenants().get(0);
    ScopeState member = authority.memberships().get(0);
    Map<String, Object> authorityTuple =
        Map.of(
            "issuerAuthGeneration", Long.toString(issuer.generation()),
            "accountAuthorityGeneration", Long.toString(account.generation()),
            "tenantAuthorityGeneration",
                Map.of(tenantUuid.toString(), Long.toString(tenant.generation())),
            "membershipAuthorityGeneration",
                Map.of(tenantUuid.toString(), Long.toString(member.generation())),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> preimage = new LinkedHashMap<>();
    preimage.put("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION);
    preimage.put("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE);
    preimage.put("eventId", eventId);
    preimage.put("requestId", requestId);
    preimage.put("outboxStreamKey", streamKey);
    preimage.put("outboxSequence", "1");
    preimage.put(
        "sourceScope",
        streamKey.substring(MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX.length()));
    preimage.put("accountId", accountUuid.toString());
    preimage.put("tenantId", tenantUuid.toString());
    preimage.put("membershipExists", true);
    preimage.put("membershipLifecycleState", membership.getLifecycleState());
    preimage.put(
        "membershipVersion",
        Map.of(tenantUuid.toString(), Long.toString(membership.getMembershipVersion())));
    preimage.put(
        "membershipAuthorityGeneration",
        Long.toString(membership.getMembershipAuthorityGeneration()));
    preimage.put("authorityTuple", authorityTuple);
    preimage.put("issuanceFence", Long.toString(authority.issuanceFence().value()));
    preimage.put("roles", List.of("player"));
    preimage.put("gameplayAdmissionAllowed", membership.isGameplayAdmissionAllowed());
    preimage.put("callerBoundAuthorityInvalidated", false);
    return MembershipAuthorityEventV1Codec.seal(preimage);
  }

  private static void restoreMembership(
      Fixture fixture, RetainedPair pair, long membershipVersion, long authorityGeneration) {
    AccountTenantIdentityResolver resolver = identityResolver(fixture);
    AccountTenantMembershipRepository memberships =
        new AccountTenantMembershipRepository(
            fixture.transactionDsl(),
            new AccountRepository(fixture.transactionDsl()),
            resolver,
            new FreshTenantIdentityAssociationRepository(fixture.transactionDsl(), TEST_NAMESPACE));
    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            RETAINED_TENANT_ID,
            TenantProvenanceKind.APPROVED_RETAINED,
            pair.sourceOperationId(),
            MANIFEST_DIGEST);
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              var membership =
                  memberships
                      .findCanonicalMembershipForUpdate(pair.accountUuid(), pair.tenantUuid())
                      .orElseThrow();
              membership.setLifecycleState("ACTIVE");
              membership.setGameplayAdmissionAllowed(true);
              membership.setMembershipVersion(membershipVersion);
              membership.setMembershipAuthorityGeneration(authorityGeneration);
              memberships.saveCanonical(
                  membership, pair.accountUuid(), pair.tenantUuid(), provenance);
            });
  }

  private static void assertFirstJoinSourceEvidence(
      Fixture fixture, FreshPair pair, String requestId) {
    Record operation =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT status, operation_representation_version, account_id, tenant_id, "
                        + "account_uuid, tenant_uuid, "
                        + "intent_digest_version, request_digest_version, "
                        + "entitlement_authority_availability, allow_public_join, entitlement_version, "
                        + "request_digest "
                        + "FROM account_join_operations WHERE request_id = ?",
                    requestId));
    assertThat(operation.get("status", String.class)).isEqualTo("PENDING");
    assertThat(operation.get("operation_representation_version", Integer.class)).isEqualTo(2);
    assertThat(operation.get("account_id", Long.class)).isEqualTo(pair.accountId());
    assertThat(operation.get("tenant_id", Long.class)).isNull();
    assertThat(operation.get("account_uuid", UUID.class)).isEqualTo(pair.accountUuid());
    assertThat(operation.get("tenant_uuid", UUID.class)).isEqualTo(pair.tenantUuid());
    assertThat(operation.get("intent_digest_version", Integer.class)).isEqualTo(2);
    assertThat(operation.get("request_digest_version", Integer.class)).isEqualTo(2);
    assertThat(operation.get("entitlement_authority_availability", String.class))
        .isEqualTo("AVAILABLE");
    assertThat(operation.get("allow_public_join", Boolean.class)).isTrue();
    assertThat(operation.get("entitlement_version", Long.class)).isEqualTo(9L);
    assertThat(operation.get("request_digest", String.class)).matches("sha256:[0-9a-f]{64}");

    Record pairAuthority =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT legacy_tenant_id, membership_exists, membership_version, "
                        + "membership_authority_generation, last_event_sequence, last_event_id, "
                        + "last_event_digest, tenant_provenance_kind, tenant_source_operation_id, "
                        + "tenant_provenance_digest "
                        + "FROM account_membership_pair_authority "
                        + "WHERE account_uuid = ? AND tenant_uuid = ?",
                    pair.accountUuid(),
                    pair.tenantUuid()));
    assertThat(pairAuthority.get("membership_exists", Boolean.class)).isTrue();
    assertThat(pairAuthority.get("legacy_tenant_id", Long.class)).isNull();
    assertThat(pairAuthority.get("membership_version", Long.class))
        .isEqualTo(pair.membership().getMembershipVersion());
    assertThat(pairAuthority.get("membership_authority_generation", Long.class))
        .isEqualTo(pair.membership().getMembershipAuthorityGeneration());
    assertThat(pairAuthority.get("last_event_sequence", Long.class)).isEqualTo(1L);
    assertThat(pairAuthority.get("tenant_provenance_kind", String.class))
        .isEqualTo(TenantProvenanceKind.FRESH_GAME_DESIGN.name());
    assertThat(pairAuthority.get("tenant_source_operation_id", UUID.class))
        .isEqualTo(pair.sourceEvidence().operationId());
    assertThat(pairAuthority.get("tenant_provenance_digest", String.class))
        .isEqualTo(pair.sourceEvidence().evidenceDigest());

    String streamKey = membershipAuthorityStreamKey(pair.accountUuid(), pair.tenantUuid());
    Record checkpoint =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT last_sequence FROM account_authority_outbox_streams "
                        + "WHERE outbox_stream_key = ?",
                    streamKey));
    assertThat(checkpoint.get("last_sequence", Long.class)).isEqualTo(1L);
    Record eventRow =
        Objects.requireNonNull(
            fixture
                .setupDsl()
                .fetchOne(
                    "SELECT outbox_sequence, request_id, event_id, event_digest, payload "
                        + "FROM account_authority_outbox_events "
                        + "WHERE outbox_stream_key = ? AND outbox_sequence = 1",
                    streamKey));
    assertThat(eventRow.get("request_id", String.class)).isEqualTo(requestId);
    assertThat(eventRow.get("event_id", String.class))
        .isEqualTo(pairAuthority.get("last_event_id", String.class));
    assertThat(eventRow.get("event_digest", String.class))
        .isEqualTo(pairAuthority.get("last_event_digest", String.class));
    MembershipEvent event =
        MembershipAuthorityEventV1Codec.verify(
            new String(eventRow.get("payload", byte[].class), StandardCharsets.UTF_8));
    assertThat(event.outboxSequence()).isEqualTo("1");
    assertThat(event.requestId()).isEqualTo(requestId);
    assertThat(event.accountId()).isEqualTo(pair.accountUuid().toString());
    assertThat(event.tenantId()).isEqualTo(pair.tenantUuid().toString());
    assertThat(event.membershipVersion())
        .containsExactly(Map.entry(pair.tenantUuid().toString(), "2"));
  }

  private static String membershipAuthorityStreamKey(UUID accountUuid, UUID tenantUuid) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "membership/"
        + accountUuid
        + "/"
        + tenantUuid;
  }

  private static void moveFreshMembershipToLeft(Fixture fixture, FreshPair pair) {
    AccountTenantMembershipRepository memberships =
        new AccountTenantMembershipRepository(
            fixture.transactionDsl(),
            new AccountRepository(fixture.transactionDsl()),
            identityResolver(fixture),
            new FreshTenantIdentityAssociationRepository(fixture.transactionDsl(), TEST_NAMESPACE));
    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            pair.sourceEvidence().operationId(),
            pair.sourceEvidence().evidenceDigest());
    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              var membership =
                  memberships
                      .findCanonicalMembershipForUpdate(pair.accountUuid(), pair.tenantUuid())
                      .orElseThrow();
              membership.setLifecycleState("INACTIVE");
              membership.setGameplayAdmissionAllowed(false);
              membership.setMembershipVersion(membership.getMembershipVersion() + 1L);
              membership.setMembershipAuthorityGeneration(
                  membership.getMembershipAuthorityGeneration() + 1L);
              memberships.saveCanonical(
                  membership, pair.accountUuid(), pair.tenantUuid(), provenance);
            });
  }

  private static AccountTenantMembershipRepository membershipRepository(
      Fixture fixture,
      ApprovedLegacyTenantAssociationRepository associations,
      LegacyTenantSourceEvidence sourceEvidence) {
    return new AccountTenantMembershipRepository(
        fixture.transactionDsl(),
        new AccountRepository(fixture.transactionDsl()),
        new AccountTenantIdentityResolver(associations, sourceEvidence, TEST_NAMESPACE),
        new FreshTenantIdentityAssociationRepository(fixture.transactionDsl(), TEST_NAMESPACE));
  }

  private static AccountTenantIdentityResolver identityResolver(Fixture fixture) {
    LegacyTenantSourceEvidence source = new LegacyTenantSourceEvidence(fixture.transactionDsl());
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(fixture.transactionDsl());
    ApprovedLegacyTenantAssociationRepository associations =
        new ApprovedLegacyTenantAssociationRepository(
            fixture.transactionDsl(), source, TEST_NAMESPACE, generations);
    return new AccountTenantIdentityResolver(associations, source, TEST_NAMESPACE);
  }

  private static void seedRetainedTenantEvidence(DSLContext dsl, long tenantId) {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    Record donor =
        Objects.requireNonNull(
            dsl.fetchOne(
                "INSERT INTO accounts (username, email, password_hash, tenant_id) "
                    + "VALUES (?, ?, ?, ?) RETURNING id",
                "receipt-donor-" + suffix,
                "receipt-donor-" + suffix + "@example.test",
                "fixture-hash",
                tenantId));
    long donorId = donor.get("id", Long.class);
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
                .fetchOne(0, Long.class));
    dsl.execute(
        "INSERT INTO account_legacy_tenant_sources "
            + "(account_id, legacy_tenant_id, matching_membership_id, "
            + "matching_membership_admission_allowed, profile_tenant_count, "
            + "matching_profile_count, disposition) VALUES (?, ?, ?, FALSE, 0, 0, 'UNVERIFIED')",
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

  private static MembershipTransitionReceipt seedV47V1Receipt(
      Fixture fixture, RetainedPair pair, String requestId) {
    String streamKey =
        MembershipTransitionReceiptDigest.receiptStreamKey(pair.accountId(), pair.tenantId());
    UUID receiptId = MembershipTransitionReceiptDigest.receiptIdForRequest(requestId);
    String digest =
        MembershipTransitionReceiptDigest.transitionDigest(
            streamKey,
            1L,
            receiptId,
            "MEMBERSHIP_LEFT",
            requestId,
            pair.accountId(),
            pair.tenantId(),
            pair.membership().getId(),
            pair.membership().getLifecycleState(),
            pair.membership().isGameplayAdmissionAllowed(),
            pair.membership().getMembershipVersion(),
            pair.membership().getMembershipAuthorityGeneration(),
            pair.membership().getAuthorityProvenance());
    return fixture
        .transaction()
        .execute(
            status -> {
              DSLContext dsl = fixture.transactionDsl();
              int insertedHead =
                  dsl.execute(
                      "INSERT INTO account_membership_transition_receipt_stream_heads "
                          + "(account_id, tenant_id, receipt_stream_key, last_receipt_sequence) "
                          + "VALUES (?, ?, ?, 1)",
                      pair.accountId(),
                      pair.tenantId(),
                      streamKey);
              int insertedReceipt =
                  dsl.execute(
                      "INSERT INTO account_membership_transition_receipts "
                          + "(receipt_stream_key, receipt_sequence, account_id, tenant_id, "
                          + "evidence_status, transition_type, request_id, membership_id, "
                          + "membership_lifecycle_state, gameplay_admission_allowed, "
                          + "membership_version, membership_authority_generation, "
                          + "authority_provenance, receipt_id, receipt_digest) "
                          + "VALUES (?, 1, ?, ?, ?, 'MEMBERSHIP_LEFT', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                      streamKey,
                      pair.accountId(),
                      pair.tenantId(),
                      MembershipTransitionReceiptDigest.EVIDENCE_STATUS,
                      requestId,
                      pair.membership().getId(),
                      pair.membership().getLifecycleState(),
                      pair.membership().isGameplayAdmissionAllowed(),
                      pair.membership().getMembershipVersion(),
                      pair.membership().getMembershipAuthorityGeneration(),
                      pair.membership().getAuthorityProvenance(),
                      receiptId,
                      digest);
              if (insertedHead != 1 || insertedReceipt != 1) {
                throw new IllegalStateException(
                    "V47 V1 receipt fixture was not inserted exactly once");
              }
              return new MembershipTransitionReceipt(
                  streamKey,
                  1L,
                  receiptId,
                  digest,
                  MembershipTransitionReceiptDigest.EVIDENCE_STATUS,
                  "MEMBERSHIP_LEFT",
                  requestId,
                  pair.membership().getId());
            });
  }

  private static FreshTenantCreationEvidence freshTenantEvidence(UUID canonicalTenantId) {
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String sourceTenantKey = "f-" + UUID.randomUUID().toString().replace("-", "");
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            TEST_NAMESPACE, requestId, sourceTenantKey, "Canonical receipt fixture", null);
    long sourceGameRowId = positiveLong();
    String sourceKind = "NEW_GAME_ROW";
    return new FreshTenantCreationEvidence(
        1,
        TEST_NAMESPACE,
        requestId,
        operationId,
        requestDigest,
        canonicalTenantId,
        sourceGameRowId,
        sourceTenantKey,
        sourceKind,
        GameTenantCreationDigest.evidenceDigest(
            TEST_NAMESPACE,
            requestId,
            operationId,
            requestDigest,
            canonicalTenantId,
            sourceGameRowId,
            sourceTenantKey,
            sourceKind));
  }

  private static long insertAccount(DSLContext dsl, String prefix) {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    return Objects.requireNonNull(
        dsl.resultQuery(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                prefix + "-" + suffix,
                prefix + "-" + suffix + "@example.test",
                "fixture-hash")
            .fetchOne(0, Long.class));
  }

  private static UUID accountUuid(DSLContext dsl, long accountId) {
    return Objects.requireNonNull(
        dsl.resultQuery("SELECT account_uuid FROM accounts WHERE id = ?", accountId)
            .fetchOne(0, UUID.class));
  }

  private static void migrateToV48(Fixture fixture) {
    flyway(fixture.dataSource(), fixture.schema(), "48").migrate();
  }

  private static AccountMembershipTransitionReceiptRepository repository(Fixture fixture) {
    return new AccountMembershipTransitionReceiptRepository(fixture.transactionDsl());
  }

  private static Map<String, Object> v1ReceiptProjection(
      DSLContext dsl, long accountId, long tenantId, long sequence) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT "
                    + RECEIPT_V1_COLUMNS
                    + " FROM account_membership_transition_receipts "
                    + "WHERE account_id = ? AND tenant_id = ? AND receipt_sequence = ?",
                accountId,
                tenantId,
                sequence))
        .intoMap();
  }

  private static Map<String, Object> v1HeadProjection(
      DSLContext dsl, long accountId, long tenantId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT account_id, tenant_id, receipt_stream_key, last_receipt_sequence "
                    + "FROM account_membership_transition_receipt_stream_heads "
                    + "WHERE account_id = ? AND tenant_id = ?",
                accountId,
                tenantId))
        .intoMap();
  }

  private static Map<String, Object> canonicalHeadProjection(
      DSLContext dsl, long accountId, long tenantId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT account_uuid, tenant_uuid, tenant_provenance_kind, "
                    + "tenant_source_operation_id, tenant_provenance_digest "
                    + "FROM account_membership_transition_receipt_stream_heads "
                    + "WHERE account_id = ? AND tenant_id = ?",
                accountId,
                tenantId))
        .intoMap();
  }

  private static long headSequence(Fixture fixture, long accountId, UUID tenantUuid) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT last_receipt_sequence FROM account_membership_transition_receipt_stream_heads "
                    + "WHERE account_uuid = ? AND tenant_uuid = ?",
                accountUuid(fixture.setupDsl(), accountId),
                tenantUuid)
            .fetchOne(0, Long.class));
  }

  private static long headCount(Fixture fixture, long accountId, UUID tenantUuid) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_membership_transition_receipt_stream_heads "
                    + "WHERE account_uuid = ? AND tenant_uuid = ?",
                accountUuid(fixture.setupDsl(), accountId),
                tenantUuid)
            .fetchOne(0, Long.class));
  }

  private static long receiptCount(Fixture fixture, long accountId, UUID tenantUuid) {
    return Objects.requireNonNull(
        fixture
            .setupDsl()
            .resultQuery(
                "SELECT COUNT(*) FROM account_membership_transition_receipts "
                    + "WHERE account_uuid = ? AND tenant_uuid = ?",
                accountUuid(fixture.setupDsl(), accountId),
                tenantUuid)
            .fetchOne(0, Long.class));
  }

  private static long positiveLong() {
    long value = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    return value == 0L ? 1L : value;
  }

  private static Fixture fixture() {
    String schema = "canonical_receipt_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    flyway(dataSource, schema, "47").migrate();

    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    return new Fixture(schema, dataSource, setupDsl, transactionDsl, transaction);
  }

  private static Flyway flyway(
      DriverManagerDataSource dataSource, String schema, String targetVersion) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .target(targetVersion)
        .load();
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext setupDsl,
      DSLContext transactionDsl,
      TransactionTemplate transaction) {}

  private record RetainedPair(
      long accountId,
      UUID accountUuid,
      long tenantId,
      UUID tenantUuid,
      UUID sourceOperationId,
      net.firedevops.firemud.accountservice.entity.AccountTenantMembership membership) {}

  private record FreshPair(
      long accountId,
      UUID accountUuid,
      UUID tenantUuid,
      FreshTenantCreationEvidence sourceEvidence,
      net.firedevops.firemud.accountservice.entity.AccountTenantMembership membership) {}
}
