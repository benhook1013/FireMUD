package integration.net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import net.firedevops.firemud.accountservice.dto.AccountTenantCreationBootstrapResult;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapDigest;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.Completion;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.StoredOperation;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCreatorMembershipSourceReader;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapAuthorizationSource;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapService;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Account transaction-composition proof using a synthetic current-source participant. The
 * participant is test-only and does not establish production authentication or authorization.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountTenantCreationBootstrapServicePostgresIntegrationTest {
  private static final String TEST_NAMESPACE = "creator-bootstrap-proof";
  private static final String AUDIT_EVENT_TYPE = "ACCOUNT_TENANT_CREATOR_BOOTSTRAPPED";
  private static final String SCHEMA_PREFIX = "creator_bootstrap_service_";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void provenanceMigrationPreservesRetainedRowsAndRejectsUnknownValues() {
    String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    migrateSchema(dataSource, schema, "97");
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);

    UUID accountUuid = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO accounts (account_uuid, username, email, password_hash) "
            + "VALUES (?, ?, ?, 'migration-fixture-hash')",
        accountUuid,
        "creator-migration-" + accountUuid.toString().substring(0, 8),
        accountUuid + "@example.test");
    Long accountId =
        java.util.Objects.requireNonNull(
                dsl.fetchOne("SELECT id FROM accounts WHERE account_uuid = ?", accountUuid))
            .get("id", Long.class);
    dsl.execute(
        "INSERT INTO account_tenant_membership "
            + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
            + "membership_version, membership_authority_generation, authority_provenance) "
            + "VALUES (?, 42, FALSE, 'LEGACY_UNVERIFIED', 1, 1, 'LEGACY_UNVERIFIED'), "
            + "(?, 43, FALSE, 'ACTIVE', 1, 1, 'SEEDED_DEMO'), "
            + "(?, 44, TRUE, 'ACTIVE', 1, 1, 'EXPLICIT_JOIN')",
        accountId,
        accountId,
        accountId);
    List<Map<String, Object>> retainedBefore =
        dsl.fetch("SELECT * FROM account_tenant_membership ORDER BY tenant_id").intoMaps();

    migrateSchema(dataSource, schema, "98");

    assertThat(dsl.fetch("SELECT * FROM account_tenant_membership ORDER BY tenant_id").intoMaps())
        .containsExactlyElementsOf(retainedBefore);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO account_tenant_membership "
                        + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
                        + "membership_version, membership_authority_generation, authority_provenance) "
                        + "VALUES (?, 45, FALSE, 'ACTIVE', 1, 1, 'UNRECOGNIZED')",
                    accountId))
        .hasMessageContaining("account_membership_provenance_check");
    assertThat(
            java.util.Objects.requireNonNull(
                    dsl.fetchOne("SELECT count(*) FROM account_tenant_membership"))
                .get(0, Long.class))
        .isEqualTo(3L);
  }

  @Test
  void commitIsControlOnlyAndHistoricalRetryDoesNotOverwriteLaterMembershipAdvance() {
    Fixture fixture = fixture(false);
    AccountTenantCreationBootstrapResult original = bootstrap(fixture);
    assertThat(original.membershipVersion())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "2"));
    assertThat(original.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(original.roles()).containsExactly("tenantAdmin");
    assertThat(original.gameplayAdmissionAllowed()).isFalse();
    String streamKey = membershipStreamKey(fixture.accountUuid(), fixture.tenantUuid());
    AccountAuthorityOutboxRepository.Event originalOutboxEvent =
        inTransaction(
            fixture,
            () ->
                fixture
                    .outbox()
                    .findEvent(
                        streamKey, fixture.evidence().accountAuthorizationOperationId().toString())
                    .orElseThrow());
    MembershipEvent originalEvent =
        MembershipAuthorityEventV1Codec.verify(
            new String(originalOutboxEvent.payload(), StandardCharsets.UTF_8));
    assertThat(originalEvent.outboxSequence()).isEqualTo("1");
    assertThat(originalEvent.membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(originalEvent.membershipVersion())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "2"));
    assertThat(originalEvent.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(originalEvent.authorityTuple().issuerAuthGeneration()).isEqualTo("1");
    assertThat(originalEvent.authorityTuple().accountAuthorityGeneration()).isEqualTo("1");
    assertThat(originalEvent.authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(originalEvent.authorityTuple().membershipAuthorityGeneration())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(originalEvent.authorityTuple().privateRealmGrantVersions()).isEmpty();
    assertThat(originalEvent.authorityTuple().accountSecurityCutoff()).isEmpty();
    assertThat(originalEvent.authorityTuple().tenantBillingCutoff()).isEmpty();
    assertThat(originalEvent.roles()).containsExactly("tenantAdmin");
    assertThat(originalEvent.gameplayAdmissionAllowed()).isFalse();
    assertThat(originalEvent.callerBoundAuthorityInvalidated()).isFalse();
    assertThat(originalOutboxEvent.outboxSequence()).isEqualTo(1L);
    assertThat(originalOutboxEvent.eventId()).isEqualTo(originalEvent.eventId());
    assertThat(originalOutboxEvent.eventDigest()).isEqualTo(originalEvent.eventDigest());

    AccountTenantMembership committedMembership =
        inTransaction(
            fixture,
            () ->
                fixture
                    .memberships()
                    .findCanonicalMembershipForUpdate(fixture.accountUuid(), fixture.tenantUuid())
                    .orElseThrow());
    assertThat(committedMembership.getLifecycleState()).isEqualTo("ACTIVE");
    assertThat(committedMembership.getMembershipVersion()).isEqualTo(2L);
    assertThat(committedMembership.getMembershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(committedMembership.isGameplayAdmissionAllowed()).isFalse();
    assertThat(
            countQuery(
                fixture.setupDsl(),
                "SELECT count(*) FROM account_audit_outbox "
                    + "WHERE audit_event_id = ? AND event_type = ?",
                auditEventId(fixture.evidence().accountAuthorizationOperationId()),
                AUDIT_EVENT_TYPE))
        .isEqualTo(1L);
    StoredOperation receipt =
        inTransaction(
            fixture,
            () ->
                fixture
                    .operationRepository()
                    .findForUpdate(fixture.evidence().accountAuthorizationOperationId())
                    .orElseThrow());
    assertThat(receipt.status()).isEqualTo("COMMITTED");
    assertThat(receipt.result()).isEqualTo(original);
    PairAuthority initialPair =
        inTransaction(
            fixture,
            () ->
                fixture
                    .pairAuthority()
                    .readForUpdate(fixture.accountUuid(), fixture.tenantUuid())
                    .orElseThrow());
    assertThat(initialPair.membershipExists()).isTrue();
    assertThat(initialPair.membershipVersion()).isEqualTo(2L);
    assertThat(initialPair.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(initialPair.eventSequence()).isEqualTo(1L);
    assertThat(initialPair.lastTransitionInvalidated()).isFalse();

    advanceMembershipForLaterJoin(fixture, originalEvent);
    AccountTenantCreationBootstrapResult replay = bootstrap(fixture);
    assertThat(replay).isEqualTo(original);

    CurrentState current = currentState(fixture);
    assertThat(current.membership().getMembershipVersion()).isEqualTo(3L);
    assertThat(current.membership().getMembershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(current.membership().isGameplayAdmissionAllowed()).isTrue();
    assertThat(current.roles()).containsExactly("player", "tenantAdmin");
    assertThat(current.pair().membershipVersion()).isEqualTo(3L);
    assertThat(current.pair().eventSequence()).isEqualTo(2L);
  }

  @Test
  void lateReceiptFailureRollsBackMembershipRolesPairEventAuditAndClaimTogether() {
    Fixture fixture = fixture(true);
    assertThatThrownBy(() -> bootstrap(fixture))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("injected after Account audit append");

    String streamKey = membershipStreamKey(fixture.accountUuid(), fixture.tenantUuid());
    UUID auditId = auditEventId(fixture.evidence().accountAuthorizationOperationId());
    assertThat(
            count(
                fixture.setupDsl(),
                "account_tenant_membership",
                "tenant_uuid",
                fixture.tenantUuid()))
        .isEqualTo(0L);
    assertThat(
            countQuery(
                fixture.setupDsl(),
                "SELECT count(*) FROM account_tenant_membership_role_snapshots s "
                    + "JOIN account_tenant_membership m ON m.id = s.membership_id "
                    + "WHERE m.tenant_uuid = ?",
                fixture.tenantUuid()))
        .isEqualTo(0L);
    assertThat(
            countQuery(
                fixture.setupDsl(),
                "SELECT count(*) FROM account_membership_pair_authority "
                    + "WHERE account_uuid = ? AND tenant_uuid = ?",
                fixture.accountUuid(),
                fixture.tenantUuid()))
        .isEqualTo(0L);
    assertThat(
            countQuery(
                fixture.setupDsl(),
                "SELECT count(*) FROM account_authority_outbox_events "
                    + "WHERE outbox_stream_key = ?",
                streamKey))
        .isEqualTo(0L);
    assertThat(
            countQuery(
                fixture.setupDsl(),
                "SELECT count(*) FROM account_authority_outbox_streams "
                    + "WHERE outbox_stream_key = ?",
                streamKey))
        .isEqualTo(0L);
    assertThat(
            countQuery(
                fixture.setupDsl(),
                "SELECT count(*) FROM account_audit_outbox "
                    + "WHERE audit_event_id = ? AND event_type = ?",
                auditId,
                AUDIT_EVENT_TYPE))
        .isEqualTo(0L);
    assertThat(
            countQuery(
                fixture.setupDsl(),
                "SELECT count(*) FROM account_tenant_creation_bootstrap_operations "
                    + "WHERE request_id = ?",
                fixture.evidence().accountAuthorizationOperationId()))
        .isEqualTo(0L);
    assertThat(
            countQuery(
                fixture.setupDsl(),
                "SELECT count(*) FROM account_authority_generations "
                    + "WHERE scope_kind = 'MEMBERSHIP' AND account_uuid = ? AND tenant_uuid = ?",
                fixture.accountUuid(),
                fixture.tenantUuid()))
        .isEqualTo(0L);
  }

  @Test
  void committedCreatorControlCaptureRetainsExactSourcesWithoutGameplayAdmission() {
    Fixture f = fixture(false);
    AccountTenantCreationBootstrapResult committed = bootstrap(f);
    var capture =
        inTransaction(
            f,
            () ->
                f.service()
                    .readExistingCreatorControlCaptureSources(f.accountUuid(), f.tenantUuid()));
    assertThat(capture.accountUuid()).isEqualTo(f.accountUuid());
    assertThat(capture.tenantUuid()).isEqualTo(f.tenantUuid());
    assertThat(capture.membershipVersion()).isEqualTo(committed.membershipVersion());
    assertThat(capture.roleSource().roles()).containsExactly("tenantAdmin");
    assertThat(capture.roleSource().tenantProvenance().sourceOperationId())
        .isEqualTo(f.evidence().creationEvidence().operationId());
    assertThat(capture.creationSource()).isEqualTo(f.evidence().creationEvidence());
    assertThat(capture.bootstrapReceipt().result()).isEqualTo(committed);
    assertThat(capture.pairSource())
        .isEqualTo(
            inTransaction(
                f,
                () ->
                    f.pairAuthority()
                        .readForUpdate(f.accountUuid(), f.tenantUuid())
                        .orElseThrow()));
    assertThat(capture.sourceEvent().membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(capture.sourceEvent().gameplayAdmissionAllowed()).isFalse();
    assertThat(capture.sourceEvent().roles()).containsExactly("tenantAdmin");
    assertThat(capture.sourceEvent().eventId()).isEqualTo(committed.eventId());
    assertThat(capture.sourceEvent().eventDigest()).isEqualTo(committed.eventDigest());
    assertThat(capture.sourceEvent().membershipVersion()).isEqualTo(capture.membershipVersion());
    assertThat(capture.authorityTuple().membershipAuthorityGeneration())
        .isEqualTo(Map.of(f.tenantUuid().toString(), "1"));
    assertThat(capture.issuanceFence())
        .isEqualTo(Long.toString(capture.authoritySnapshot().issuanceFence().value()));
    assertThat(capture.outboxCheckpoints()).hasSize(4);
    assertThat(capture.outboxSourceEvidence()).hasSize(1);
    assertThat(capture.outboxSourceEvidence().getFirst().eventDigest())
        .isEqualTo(committed.eventDigest());
    assertThat(currentState(f).membership().isGameplayAdmissionAllowed()).isFalse();
  }

  @Test
  void creatorControlCaptureDeniesLaterJoinedMembershipWithoutRewritingOriginalReceipt() {
    Fixture f = fixture(false);
    var committed = bootstrap(f);
    MembershipEvent event =
        inTransaction(
            f,
            () ->
                MembershipAuthorityEventV1Codec.verify(
                    new String(
                        f.outbox()
                            .findEvent(
                                membershipStreamKey(f.accountUuid(), f.tenantUuid()),
                                committed.eventRequestId())
                            .orElseThrow()
                            .payload(),
                        StandardCharsets.UTF_8)));
    advanceMembershipForLaterJoin(f, event);
    assertThatThrownBy(
            () ->
                inTransaction(
                    f,
                    () ->
                        f.service()
                            .readExistingCreatorControlCaptureSources(
                                f.accountUuid(), f.tenantUuid())))
        .isInstanceOf(IllegalStateException.class);
    assertThat(currentState(f).membership().getMembershipVersion()).isEqualTo(3L);
    assertThat(
            inTransaction(
                f,
                () ->
                    f.operationRepository()
                        .findForUpdate(committed.operationId())
                        .orElseThrow()
                        .result()))
        .isEqualTo(committed);
  }

  @Test
  void creatorControlCaptureDeniesMissingCommittedReceiptReadbackRatherThanInferringFromRoles() {
    Fixture f = fixture(false);
    var committed = bootstrap(f);
    // A synthetic unavailable owner read overlays the real committed database fixture.
    doReturn(Optional.empty()).when(f.operationRepository()).findForUpdate(committed.operationId());
    assertThatThrownBy(
            () ->
                inTransaction(
                    f,
                    () ->
                        f.service()
                            .readExistingCreatorControlCaptureSources(
                                f.accountUuid(), f.tenantUuid())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("receipt is absent");
    assertThat(currentState(f).roles()).containsExactly("tenantAdmin");
    assertThat(currentState(f).membership().isGameplayAdmissionAllowed()).isFalse();
  }

  @Test
  void creatorControlCaptureDeniesCorruptedOriginalReceiptBytes() {
    Fixture f = fixture(false);
    var committed = bootstrap(f);
    StoredOperation original =
        inTransaction(
            f, () -> f.operationRepository().findForUpdate(committed.operationId()).orElseThrow());
    StoredOperation corrupt = spy(original);
    doReturn(new byte[] {1}).when(corrupt).creatorEvidencePayload();
    // Keep production immutability guards intact; inject corrupt read evidence, not SQL rewrites.
    doReturn(Optional.of(corrupt))
        .when(f.operationRepository())
        .findForUpdate(committed.operationId());
    assertThatThrownBy(
            () ->
                inTransaction(
                    f,
                    () ->
                        f.service()
                            .readExistingCreatorControlCaptureSources(
                                f.accountUuid(), f.tenantUuid())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed creator");
    assertThat(currentState(f).membership().isGameplayAdmissionAllowed()).isFalse();
  }

  @Test
  void creatorControlCaptureDoesNotCreateMissingMembershipOrReceiptHistory() {
    Fixture f = fixture(false);
    assertThatThrownBy(
            () ->
                inTransaction(
                    f,
                    () ->
                        f.service()
                            .readExistingCreatorControlCaptureSources(
                                f.accountUuid(), f.tenantUuid())))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            inTransaction(
                f,
                () ->
                    f.memberships()
                        .findCanonicalMembershipForUpdate(f.accountUuid(), f.tenantUuid())))
        .isEmpty();
    assertThat(
            inTransaction(
                f, () -> f.operationRepository().findRequestIdByTenantForUpdate(f.tenantUuid())))
        .isEmpty();
  }

  @Test
  void creatorCaptureRejectsGenericAccountSaveWithoutRewritingBootstrapEventOrReceipt() {
    Fixture f = fixture(false);
    var committed = bootstrap(f);
    StoredOperation originalReceipt =
        inTransaction(
            f, () -> f.operationRepository().findForUpdate(committed.operationId()).orElseThrow());
    AccountAuthorityOutboxRepository.Event originalBootstrapEvent =
        inTransaction(
            f,
            () ->
                f.outbox()
                    .findEvent(originalReceipt.eventStreamKey(), originalReceipt.eventRequestId())
                    .orElseThrow());
    assertThat(originalBootstrapEvent.payload()).containsExactly(originalReceipt.eventPayload());
    inTransaction(
        f,
        () -> {
          AccountRepository accounts = new AccountRepository(f.setupDsl());
          Account account = accounts.findByAccountUuidForUpdate(f.accountUuid()).orElseThrow();
          account.setRole("admin");
          accounts.save(account);
          return null;
        });
    assertThatThrownBy(
            () ->
                inTransaction(
                    f,
                    () ->
                        f.service()
                            .readExistingCreatorControlCaptureSources(
                                f.accountUuid(), f.tenantUuid())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Account source event schema is unsupported");

    StoredOperation receiptAfterRejectedCapture =
        inTransaction(
            f, () -> f.operationRepository().findForUpdate(committed.operationId()).orElseThrow());
    AccountAuthorityOutboxRepository.Event bootstrapEventAfterRejectedCapture =
        inTransaction(
            f,
            () ->
                f.outbox()
                    .findEvent(originalReceipt.eventStreamKey(), originalReceipt.eventRequestId())
                    .orElseThrow());
    assertThat(receiptAfterRejectedCapture).usingRecursiveComparison().isEqualTo(originalReceipt);
    assertThat(bootstrapEventAfterRejectedCapture).isEqualTo(originalBootstrapEvent);
    MembershipEvent originalEvent =
        MembershipAuthorityEventV1Codec.verify(
            new String(originalBootstrapEvent.payload(), StandardCharsets.UTF_8));
    assertThat(originalEvent.eventDigest()).isEqualTo(committed.eventDigest());
    assertThat(originalEvent.gameplayAdmissionAllowed()).isFalse();
  }

  private static AccountTenantCreationBootstrapResult bootstrap(Fixture fixture) {
    return fixture.transaction().execute(status -> fixture.service().bootstrap(fixture.evidence()));
  }

  private static void advanceMembershipForLaterJoin(
      Fixture fixture, MembershipEvent originalEvent) {
    inTransaction(
        fixture,
        () -> {
          VerifiedTenantProvenance provenance = provenance(fixture.evidence().creationEvidence());
          AccountTenantMembership membership =
              fixture
                  .memberships()
                  .findCanonicalMembershipForUpdate(fixture.accountUuid(), fixture.tenantUuid())
                  .orElseThrow();
          membership.setLifecycleState("ACTIVE");
          membership.setGameplayAdmissionAllowed(true);
          membership.setMembershipVersion(3L);
          membership.setMembershipAuthorityGeneration(1L);
          membership.setAuthorityProvenance("EXPLICIT_JOIN");
          // Model a later committed owner state directly in this isolated schema. The target
          // first-JOIN producer intentionally has no creator-to-player transition entrypoint.
          fixture
              .setupDsl()
              .execute(
                  "UPDATE account_tenant_membership SET gameplay_admission_allowed = TRUE, "
                      + "membership_version = 3, authority_provenance = 'EXPLICIT_JOIN' WHERE id = ?",
                  membership.getId());
          AccountTenantMembership persisted =
              fixture
                  .memberships()
                  .findCanonicalMembershipForUpdate(fixture.accountUuid(), fixture.tenantUuid())
                  .orElseThrow();
          fixture
              .roles()
              .replaceCanonical(
                  persisted,
                  fixture.accountUuid(),
                  fixture.tenantUuid(),
                  provenance,
                  3L,
                  List.of("player", "tenantAdmin"));

          PairAuthority priorPair =
              fixture
                  .pairAuthority()
                  .readForUpdate(fixture.accountUuid(), fixture.tenantUuid())
                  .orElseThrow();
          String requestId = UUID.randomUUID().toString();
          String eventId =
              UUID.nameUUIDFromBytes(
                      (MembershipAuthorityEventV1Codec.SCHEMA_VERSION + ":" + requestId)
                          .getBytes(StandardCharsets.UTF_8))
                  .toString();
          String streamKey = membershipStreamKey(fixture.accountUuid(), fixture.tenantUuid());
          Map<String, Object> preimage = new LinkedHashMap<>();
          preimage.put("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION);
          preimage.put("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE);
          preimage.put("eventId", eventId);
          preimage.put("requestId", requestId);
          preimage.put("outboxStreamKey", streamKey);
          preimage.put("outboxSequence", "2");
          preimage.put(
              "sourceScope", "membership/" + fixture.accountUuid() + "/" + fixture.tenantUuid());
          preimage.put("accountId", fixture.accountUuid().toString());
          preimage.put("tenantId", fixture.tenantUuid().toString());
          preimage.put("membershipExists", true);
          preimage.put("membershipLifecycleState", "ACTIVE");
          preimage.put("membershipVersion", Map.of(fixture.tenantUuid().toString(), "3"));
          preimage.put("membershipAuthorityGeneration", "1");
          preimage.put(
              "authorityTuple",
              AccountTenantCreationBootstrapDigest.authorityTupleMap(
                  originalEvent.authorityTuple()));
          preimage.put("issuanceFence", originalEvent.issuanceFence());
          preimage.put("roles", List.of("player", "tenantAdmin"));
          preimage.put("gameplayAdmissionAllowed", true);
          preimage.put("callerBoundAuthorityInvalidated", false);
          MembershipEvent laterEvent = MembershipAuthorityEventV1Codec.seal(preimage);
          AccountAuthorityOutboxRepository.Event laterOutboxEvent =
              fixture
                  .outbox()
                  .append(
                      streamKey,
                      requestId,
                      sequence -> {
                        if (sequence != 2L) {
                          throw new IllegalStateException(
                              "Later membership event sequence differs");
                        }
                        return new AccountAuthorityOutboxRepository.EventEvidence(
                            laterEvent.eventId(),
                            laterEvent.eventDigest(),
                            laterEvent.canonicalJsonUtf8());
                      });
          fixture
              .setupDsl()
              .execute(
                  "UPDATE account_membership_pair_authority SET membership_version = 3, "
                      + "last_event_sequence = 2, last_event_id = ?, last_event_digest = ? "
                      + "WHERE account_uuid = ? AND tenant_uuid = ? AND membership_version = ?",
                  laterOutboxEvent.eventId(),
                  laterOutboxEvent.eventDigest(),
                  fixture.accountUuid(),
                  fixture.tenantUuid(),
                  priorPair.membershipVersion());
          return null;
        });
  }

  private static CurrentState currentState(Fixture fixture) {
    return inTransaction(
        fixture,
        () -> {
          AccountTenantMembership membership =
              fixture
                  .memberships()
                  .findCanonicalMembershipForUpdate(fixture.accountUuid(), fixture.tenantUuid())
                  .orElseThrow();
          VerifiedTenantProvenance provenance = provenance(fixture.evidence().creationEvidence());
          var roles =
              fixture
                  .roles()
                  .findForCanonicalUpdate(
                      fixture.accountUuid(),
                      fixture.tenantUuid(),
                      provenance,
                      membership.getId(),
                      membership.getMembershipVersion())
                  .orElseThrow();
          PairAuthority pair =
              fixture
                  .pairAuthority()
                  .readForUpdate(fixture.accountUuid(), fixture.tenantUuid())
                  .orElseThrow();
          return new CurrentState(membership, roles.roles(), pair);
        });
  }

  private static Fixture fixture(boolean failAfterAudit) {
    String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext txDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    String suffix = UUID.randomUUID().toString().replace("-", "");
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(txDsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(txDsl);
    AccountAuthoritySourceEvidenceRepository sources =
        new AccountAuthoritySourceEvidenceRepository(txDsl, generations, outbox);
    AccountRepository accounts = new AccountRepository(txDsl, sources);
    Account inserted =
        transaction.execute(
            status -> {
              Account candidate = new Account();
              candidate.setUsername("creator-bootstrap-" + suffix.substring(0, 16));
              candidate.setEmail("creator-bootstrap-" + suffix + "@example.test");
              candidate.setPasswordHash("test-hash");
              return accounts.save(candidate);
            });
    UUID accountUuid = java.util.Objects.requireNonNull(inserted).getAccountUuid();
    UUID tenantUuid = UUID.randomUUID();
    FreshTenantIdentityAssociationRepository fresh =
        new FreshTenantIdentityAssociationRepository(txDsl, TEST_NAMESPACE);
    AccountMembershipPairAuthorityRepository pairs =
        new AccountMembershipPairAuthorityRepository(txDsl);
    AccountTenantMembershipRepository memberships =
        new AccountTenantMembershipRepository(txDsl, accounts, fresh, pairs);
    AccountTenantMembershipRoleSnapshotRepository roles =
        new AccountTenantMembershipRoleSnapshotRepository(txDsl);
    AccountAuditOutboxRepository audit = new AccountAuditOutboxRepository(txDsl);
    AccountJoinOperationRepository joinOperations = new AccountJoinOperationRepository(txDsl);
    AccountTenantCreationBootstrapOperationRepository operationRepository =
        spy(
            failAfterAudit
                ? new FailingAfterAuditOperationRepository(txDsl)
                : new AccountTenantCreationBootstrapOperationRepository(txDsl));
    AccountCreatorMembershipSourceReader producer =
        new AccountCreatorMembershipSourceReader(
            accounts,
            joinOperations,
            memberships,
            pairs,
            roles,
            fresh,
            generations,
            outbox,
            sources,
            operationRepository);

    FreshTenantCreatorEvidence evidence = creatorEvidence(accountUuid, tenantUuid);
    transaction.executeWithoutResult(
        status -> {
          fresh.importVerified(evidence.creationEvidence());
          generations.initializeTenantIfAbsent(tenantUuid);
        });

    AccountTenantCreationBootstrapAuthorizationSource syntheticParticipant =
        syntheticParticipant(evidence);
    @SuppressWarnings("unchecked")
    ObjectProvider<AccountTenantCreationBootstrapAuthorizationSource> provider =
        mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(syntheticParticipant);
    AccountTenantCreationBootstrapService service =
        new AccountTenantCreationBootstrapService(
            accounts,
            fresh,
            producer,
            pairs,
            memberships,
            roles,
            outbox,
            audit,
            operationRepository,
            provider);
    return new Fixture(
        txDsl,
        transaction,
        accountUuid,
        tenantUuid,
        evidence,
        service,
        memberships,
        roles,
        pairs,
        outbox,
        operationRepository,
        producer);
  }

  private static void migrateSchema(
      DriverManagerDataSource dataSource, String schema, String target) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target(target)
        .load()
        .migrate();
  }

  private static FreshTenantCreatorEvidence creatorEvidence(UUID accountUuid, UUID tenantUuid) {
    UUID creationRequestId = UUID.randomUUID();
    UUID creationOperationId = UUID.randomUUID();
    UUID authorizationOperationId = UUID.randomUUID();
    String tenantKey = "creator-game-row-17";
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            TEST_NAMESPACE, creationRequestId, tenantKey, "Creator fixture", null);
    FreshTenantCreationEvidence creation =
        new FreshTenantCreationEvidence(
            1,
            TEST_NAMESPACE,
            creationRequestId,
            creationOperationId,
            requestDigest,
            tenantUuid,
            17L,
            tenantKey,
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                TEST_NAMESPACE,
                creationRequestId,
                creationOperationId,
                requestDigest,
                tenantUuid,
                17L,
                tenantKey,
                "NEW_GAME_ROW"));
    String authorizationDigest = "sha256:" + "8".repeat(64);
    return new FreshTenantCreatorEvidence(
        1,
        creation,
        accountUuid,
        authorizationOperationId,
        authorizationDigest,
        FreshTenantCreatorDigest.evidenceDigest(
            1, creation, accountUuid, authorizationOperationId, authorizationDigest));
  }

  private static AccountTenantCreationBootstrapAuthorizationSource syntheticParticipant(
      FreshTenantCreatorEvidence expectedEvidence) {
    ReentrantLock syntheticSourceFence = new ReentrantLock();
    return (supplied, mutation) -> {
      if (!expectedEvidence.equals(supplied)) {
        throw new IllegalStateException("Synthetic component fixture source differs");
      }
      if (!TransactionSynchronizationManager.isActualTransactionActive()
          || !TransactionSynchronizationManager.isSynchronizationActive()) {
        throw new IllegalStateException(
            "Synthetic source participant requires the outer Account transaction");
      }
      syntheticSourceFence.lock();
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              syntheticSourceFence.unlock();
            }
          });
      AccountTenantCreationBootstrapResult result = mutation.get();
      if (!syntheticSourceFence.isHeldByCurrentThread()) {
        throw new IllegalStateException(
            "Synthetic source participant released its fence before Account completion");
      }
      return result;
    };
  }

  private static VerifiedTenantProvenance provenance(FreshTenantCreationEvidence source) {
    return new VerifiedTenantProvenance(
        null,
        TenantProvenanceKind.FRESH_GAME_DESIGN,
        source.operationId(),
        source.evidenceDigest());
  }

  private static String membershipStreamKey(UUID accountUuid, UUID tenantUuid) {
    return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
        + "membership/"
        + accountUuid
        + "/"
        + tenantUuid;
  }

  private static UUID auditEventId(UUID authorizationOperationId) {
    return UUID.nameUUIDFromBytes(
        ("account-tenant-creation-bootstrap-audit/v1:" + authorizationOperationId)
            .getBytes(StandardCharsets.UTF_8));
  }

  private static long count(DSLContext dsl, String table, String column, UUID value) {
    return countQuery(dsl, "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", value);
  }

  private static long countQuery(DSLContext dsl, String query, Object... bindings) {
    return java.util.Objects.requireNonNull(
        java.util.Objects.requireNonNull(
                dsl.fetchOne(query, bindings), "Count query returned no row")
            .get(0, Long.class),
        "Count query returned no value");
  }

  private static <T> T inTransaction(Fixture fixture, java.util.function.Supplier<T> work) {
    return fixture.transaction().execute(status -> work.get());
  }

  private record CurrentState(
      AccountTenantMembership membership, List<String> roles, PairAuthority pair) {}

  private record Fixture(
      DSLContext setupDsl,
      TransactionTemplate transaction,
      UUID accountUuid,
      UUID tenantUuid,
      FreshTenantCreatorEvidence evidence,
      AccountTenantCreationBootstrapService service,
      AccountTenantMembershipRepository memberships,
      AccountTenantMembershipRoleSnapshotRepository roles,
      AccountMembershipPairAuthorityRepository pairAuthority,
      AccountAuthorityOutboxRepository outbox,
      AccountTenantCreationBootstrapOperationRepository operationRepository,
      AccountCreatorMembershipSourceReader producer) {}

  private static final class FailingAfterAuditOperationRepository
      extends AccountTenantCreationBootstrapOperationRepository {
    private FailingAfterAuditOperationRepository(DSLContext dsl) {
      super(dsl);
    }

    @Override
    public StoredOperation complete(UUID requestId, Completion completion) {
      throw new IllegalStateException("injected after Account audit append");
    }
  }
}
