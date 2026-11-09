package integration.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.dto.AccountSecurityStateMutationRequest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthModes;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationConflictException;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository.Capture;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantEntitlementOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCanonicalFirstJoinTerminalCoordinator;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec.AccountState;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL proof fixture for the unwired canonical first-JOIN event/first-pair composition. */
class AccountCanonicalFirstJoinEventIntegrationTest {
  private static final String SCHEMA_PREFIX = "canonical_join_event_";
  private static final String TEST_NAMESPACE = "canonical-join-event-proof";
  private static final String ACCOUNT_ISSUER = "firemud-account-service";

  private static final AccountPostgresIntegrationFixture postgres =
      new AccountPostgresIntegrationFixture();
  private final Set<String> runOwnedSchemas = ConcurrentHashMap.newKeySet();

  @BeforeAll
  static void startPostgres() {
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @AfterEach
  void dropRunOwnedSchemas() {
    JdbcTemplate rootJdbc = new JdbcTemplate(postgres.dataSource());
    for (String schema : runOwnedSchemas) {
      if (!schema.startsWith(SCHEMA_PREFIX) || !schema.matches("[a-z][a-z0-9_]{0,62}")) {
        throw new IllegalStateException("Refusing to clean an unowned PostgreSQL schema");
      }
      rootJdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
    runOwnedSchemas.clear();
  }

  @Test
  void commitsExactEventAndPairAndReplaysOnlyTheSameCallerOperation() {
    Fixture fixture = newFixture();
    var first = fixture.commitFirstJoin();
    var replay = fixture.commitFirstJoin();

    String stream = fixture.membershipStream();
    AccountAuthorityOutboxRepository.Event event =
        fixture.inTransaction(
            () -> fixture.outbox.findEvent(stream, fixture.requestId).orElseThrow());
    var decoded =
        MembershipAuthorityEventV1Codec.verify(new String(event.payload(), StandardCharsets.UTF_8));
    PairAuthority pair =
        fixture.inTransaction(
            () ->
                fixture
                    .pairs
                    .readForUpdate(fixture.account.getAccountUuid(), fixture.tenantUuid)
                    .orElseThrow());

    assertThat(first.eventSequence()).isEqualTo(1L);
    assertThat(replay).isEqualTo(first);
    assertThat(decoded.requestId()).isEqualTo(fixture.requestId);
    assertThat(decoded.accountId()).isEqualTo(fixture.account.getAccountUuid().toString());
    assertThat(decoded.tenantId()).isEqualTo(fixture.tenantUuid.toString());
    assertThat(decoded.membershipVersion()).isEqualTo(Map.of(fixture.tenantUuid.toString(), "2"));
    assertThat(decoded.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(decoded.authorityTuple().privateRealmGrantVersions()).isEmpty();
    assertThat(decoded.authorityTuple().accountSecurityCutoff()).isEmpty();
    assertThat(decoded.authorityTuple().tenantBillingCutoff())
        .contains(
            Map.of(
                fixture.tenantUuid.toString(),
                new MembershipAuthorityEventV1Codec.TenantBillingCutoff(
                    "2", "1", "account:auth-authority:v1:tenant/" + fixture.tenantUuid, "1")));
    assertThat(decoded.roles()).containsExactly("player");
    assertThat(pair.membershipExists()).isTrue();
    assertThat(pair.membershipVersion()).isEqualTo(2L);
    assertThat(pair.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(pair.eventSequence()).isEqualTo(1L);
    assertThat(pair.eventId()).isEqualTo(decoded.eventId());
    assertThat(pair.eventDigest()).isEqualTo(decoded.eventDigest());
    assertThat(fixture.count("account_authority_outbox_events", "outbox_stream_key", stream))
        .isEqualTo(1L);
    assertThat(fixture.count("account_audit_outbox", "audit_event_id", first.auditEventId()))
        .isEqualTo(1L);

    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.terminalCoordinator.commitCanonicalFirstJoin(
                            fixture.scope, fixture.requestId, "another-caller")))
        .isInstanceOf(CanonicalJoinOperationConflictException.class)
        .hasMessage("Canonical JOIN intent conflicts");
    assertThat(fixture.count("account_authority_outbox_events", "outbox_stream_key", stream))
        .isEqualTo(1L);
    assertThat(fixture.count("account_tenant_membership", "tenant_uuid", fixture.tenantUuid))
        .isEqualTo(1L);
  }

  @Test
  void changedEntitlementPreventsAStalePendingJoinFromCommittingMembership() {
    Fixture fixture = newFixture();
    DemoTenantEntitlementSnapshot entitlement = fixture.provisionCurrentTenantEntitlement();

    assertThatThrownBy(fixture::commitFirstJoin)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact current public-join entitlement evidence");
    String stream = fixture.membershipStream();
    var currentTenantEvent =
        fixture.inTransaction(
            () -> fixture.tenantAuthorityEvents.readCurrentByTenant(fixture.tenantUuid));
    var pendingOperation =
        fixture.inTransaction(
            () -> {
              fixture.operations.lockAccount(fixture.account.getId());
              return fixture.operations.findCanonicalEvidenceForUpdateByRequestId(
                  fixture.requestId);
            });

    assertThat(entitlement.entitlementVersion()).isEqualTo(2L);
    assertThat(pendingOperation).isPresent();
    assertThat(pendingOperation.orElseThrow().status()).isEqualTo("PENDING");
    assertThat(pendingOperation.orElseThrow().entitlementVersion()).isEqualTo(1L);
    assertThat(entitlement.tenantAuthorityGeneration()).isEqualTo(3L);
    assertThat(entitlement.tenantAuthoritySourceVersion()).isEqualTo(3L);
    assertThat(entitlement.tenantBillingSequence()).isEqualTo(2L);
    assertThat(entitlement.tenantAuthorityOutboxSequence()).isEqualTo(2L);
    assertThat(currentTenantEvent.tenantBillingEventId()).isEqualTo(entitlement.eventId());
    assertThat(currentTenantEvent.tenantBillingEventDigest()).isEqualTo(entitlement.eventDigest());
    assertThat(fixture.count("account_tenant_membership", "tenant_uuid", fixture.tenantUuid))
        .isZero();
    assertThat(fixture.count("account_authority_outbox_events", "outbox_stream_key", stream))
        .isZero();
  }

  @Test
  void pairCasFailureRollsBackMembershipAndTheActualOutboxAppend() {
    Fixture fixture = newFixture();
    fixture.dsl.execute(
        "CREATE FUNCTION fail_first_join_pair_advance() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN RAISE EXCEPTION 'first-join pair CAS failure'; END $$");
    fixture.dsl.execute(
        "CREATE TRIGGER fail_first_join_pair_advance BEFORE UPDATE ON "
            + "account_membership_pair_authority FOR EACH ROW "
            + "EXECUTE FUNCTION fail_first_join_pair_advance()");

    assertThatThrownBy(fixture::writeAndPublish)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("first-join pair CAS failure");

    String stream = fixture.membershipStream();
    assertThat(fixture.count("account_tenant_membership", "tenant_uuid", fixture.tenantUuid))
        .isZero();
    assertThat(
            fixture.count(
                "account_tenant_membership_role_snapshots",
                "membership_id",
                fixture.membershipId()))
        .isZero();
    assertThat(
            fixture.count("account_membership_pair_authority", "tenant_uuid", fixture.tenantUuid))
        .isZero();
    assertThat(fixture.count("account_authority_outbox_streams", "outbox_stream_key", stream))
        .isZero();
    assertThat(fixture.count("account_authority_outbox_events", "outbox_stream_key", stream))
        .isZero();
  }

  @Test
  void terminalCoordinatorCommitsAuditAndOperationWithTheFirstEventAndReplaysExactly() {
    Fixture fixture = newFixture();
    var proof = fixture.commitFirstJoin();
    var replay = fixture.commitFirstJoin();
    String stream = fixture.membershipStream();
    var operation =
        fixture.inTransaction(
            () ->
                fixture
                    .operations
                    .findCanonicalEvidenceForUpdateByRequestId(fixture.requestId)
                    .orElseThrow());

    assertThat(replay).isEqualTo(proof);
    assertThat(operation.status()).isEqualTo("COMMITTED");
    assertThat(operation.outcome()).isEqualTo("JOINED");
    assertThat(operation.terminalProof()).isEqualTo(proof);
    assertThat(fixture.count("account_tenant_membership", "tenant_uuid", fixture.tenantUuid))
        .isEqualTo(1L);
    assertThat(
            fixture.count(
                "account_tenant_membership_role_snapshots", "membership_id", proof.membershipId()))
        .isEqualTo(1L);
    assertThat(fixture.count("account_authority_outbox_events", "outbox_stream_key", stream))
        .isEqualTo(1L);
    assertThat(fixture.count("account_audit_outbox", "audit_event_id", proof.auditEventId()))
        .isEqualTo(1L);

    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.terminalCoordinator.commitCanonicalFirstJoin(
                            fixture.scope, fixture.requestId, "another-caller")))
        .isInstanceOf(CanonicalJoinOperationConflictException.class);
    assertThat(fixture.count("account_authority_outbox_events", "outbox_stream_key", stream))
        .isEqualTo(1L);
    assertThat(fixture.count("account_audit_outbox", "audit_event_id", proof.auditEventId()))
        .isEqualTo(1L);
  }

  @Test
  void terminalJournalFailureRollsBackMembershipEventPairAndAuditTogether() {
    Fixture fixture = newFixture();
    fixture.dsl.execute(
        "CREATE FUNCTION fail_first_join_terminal_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN IF NEW.status = 'COMMITTED' THEN "
            + "RAISE EXCEPTION 'first-join terminal receipt failure'; END IF; RETURN NEW; END $$");
    fixture.dsl.execute(
        "CREATE TRIGGER fail_first_join_terminal_receipt BEFORE UPDATE ON "
            + "account_join_operations FOR EACH ROW "
            + "EXECUTE FUNCTION fail_first_join_terminal_receipt()");

    assertThatThrownBy(fixture::commitFirstJoin)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("first-join terminal receipt failure");

    String stream = fixture.membershipStream();
    UUID auditEventId =
        UUID.nameUUIDFromBytes(
            ("account-join-audit/v1:" + fixture.requestId).getBytes(StandardCharsets.UTF_8));
    assertThat(fixture.count("account_tenant_membership", "tenant_uuid", fixture.tenantUuid))
        .isZero();
    assertThat(fixture.countAll("account_tenant_membership_role_snapshots")).isZero();
    assertThat(fixture.countAll("account_tenant_membership_role_snapshot_roles")).isZero();
    assertThat(
            fixture.count("account_membership_pair_authority", "tenant_uuid", fixture.tenantUuid))
        .isZero();
    assertThat(fixture.count("account_authority_outbox_streams", "outbox_stream_key", stream))
        .isZero();
    assertThat(fixture.count("account_authority_outbox_events", "outbox_stream_key", stream))
        .isZero();
    assertThat(fixture.count("account_audit_outbox", "audit_event_id", auditEventId)).isZero();
    var operation =
        fixture.inTransaction(
            () ->
                fixture
                    .operations
                    .findCanonicalEvidenceForUpdateByRequestId(fixture.requestId)
                    .orElseThrow());
    assertThat(operation.status()).isEqualTo("PENDING");
    assertThat(operation.terminalProof()).isNull();
  }

  @Test
  void activeJoinRetainsExactSnapshotWithoutAnotherEventOrAuditAndReplays() {
    Fixture fixture = newFixture();
    var original = fixture.commitFirstJoin();
    String nextRequest = fixture.prepareAnotherJoin();
    var result = fixture.commitExistingJoin(nextRequest);
    var replay = fixture.commitExistingJoin(nextRequest);

    assertThat(result.outcome()).isEqualTo("ALREADY_ACTIVE");
    assertThat(result.membershipId()).isEqualTo(original.membershipId());
    assertThat(result.membershipVersion()).isEqualTo(2L);
    assertThat(result.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(result.terminalProof().eventSequence()).isEqualTo(1L);
    assertThat(result.terminalProof().auditEventId()).isNull();
    assertThat(replay).isEqualTo(result);
    assertThat(
            fixture.count(
                "account_authority_outbox_events", "outbox_stream_key", fixture.membershipStream()))
        .isEqualTo(1L);
    assertThat(fixture.countAll("account_audit_outbox")).isEqualTo(1L);
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.dsl.execute(
                            "UPDATE account_join_operations SET outcome_membership_version = 3 WHERE request_id = ?",
                            nextRequest)))
        .isInstanceOf(RuntimeException.class)
        .rootCause()
        .isInstanceOfSatisfying(
            java.sql.SQLException.class,
            failure -> {
              assertThat(failure.getSQLState()).isEqualTo("23514");
              assertThat(failure.getMessage())
                  .contains("Terminal canonical JOIN receipt is immutable");
            });
  }

  @Test
  void nullCommittedOutcomeIsRejectedByTerminalReceiptShapeGuard() {
    Fixture fixture = newFixture();
    fixture.commitFirstJoin();
    String nextRequest = fixture.prepareAnotherJoin();

    // Retain a valid current membership witness but omit only the required committed outcome.
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.dsl.execute(
                            """
                            UPDATE account_join_operations pending
                            SET status = 'COMMITTED', outcome = NULL,
                                last_attempt_authority_availability = 'AVAILABLE',
                                membership_id = original.membership_id,
                                outcome_membership_version = original.outcome_membership_version,
                                outcome_membership_authority_generation = original.outcome_membership_authority_generation,
                                membership_authority_outbox_stream_key = original.membership_authority_outbox_stream_key,
                                membership_authority_outbox_sequence = original.membership_authority_outbox_sequence,
                                membership_authority_event_id = original.membership_authority_event_id,
                                membership_authority_event_digest = original.membership_authority_event_digest
                            FROM account_join_operations original
                            WHERE pending.request_id = ? AND original.request_id = ?
                            """,
                            nextRequest,
                            fixture.requestId)))
        .isInstanceOf(RuntimeException.class)
        .rootCause()
        .isInstanceOfSatisfying(
            java.sql.SQLException.class,
            failure -> {
              assertThat(failure.getSQLState()).isEqualTo("23514");
              assertThat(failure.getMessage())
                  .contains("Canonical JOIN terminal receipt is incomplete or noncommitting");
            });

    var committed = fixture.commitExistingJoin(nextRequest);
    assertThat(committed.status()).isEqualTo("COMMITTED");
    assertThat(committed.outcome()).isEqualTo("ALREADY_ACTIVE");
    assertThat(fixture.countAll("account_audit_outbox")).isEqualTo(1L);
  }

  @Test
  void closingEnrollmentPreservesAnActiveMembersEventFreeJoinSnapshot() {
    Fixture fixture = newFixture();
    fixture.commitFirstJoin();
    fixture.inTransaction(
        () -> {
          var current = fixture.demoEntitlements.readCurrent(fixture.tenantUuid);
          fixture.demoEntitlements.provision(
              fixture.entitlementRequest(UUID.randomUUID(), current, false),
              fixture.tenantEvidence);
          return null;
        });
    String requestId = fixture.prepareAnotherJoin();
    var result = fixture.commitExistingJoin(requestId);
    assertThat(result.outcome()).isEqualTo("ALREADY_ACTIVE");
    assertThat(result.allowPublicJoin()).isFalse();
    assertThat(result.membershipVersion()).isEqualTo(2L);
    assertThat(result.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(
            fixture.count(
                "account_authority_outbox_events", "outbox_stream_key", fixture.membershipStream()))
        .isEqualTo(1L);
    assertThat(fixture.countAll("account_audit_outbox")).isEqualTo(1L);
  }

  @Test
  void activeNonAdmittingSnapshotWithoutPlayerRoleRemainsSuccessfulAndUnchanged() {
    Fixture fixture = newFixture();
    fixture.commitFirstJoin();
    fixture.seedControlledMembershipSnapshot("ACTIVE", java.util.List.of());
    BrowserFacade browser = fixture.browserFacade();
    var selected =
        browser
            .service()
            .listBootstrapRealms(browser.bootstrapToken(), "tenant", "world")
            .getFirst();
    var request =
        new net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest(
            selected.connectScopeId(), UUID.randomUUID().toString());
    var result = browser.service().joinPublicProduction(browser.bootstrapToken(), request);
    var replay = browser.service().joinPublicProduction(browser.bootstrapToken(), request);
    assertThat(result.success()).isTrue();
    assertThat(result.outcomeCode()).isEqualTo("ALREADY_ACTIVE");
    assertThat(result.gameplayAdmissionAllowed()).isFalse();
    assertThat(result.membershipVersion()).isEqualTo(3L);
    assertThat(result.membershipAuthorityGeneration()).isEqualTo(2L);
    assertThat(replay.gameplayAdmissionAllowed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    var member =
        fixture.inTransaction(
            () ->
                fixture
                    .memberships
                    .findFreshMembershipForUpdate(
                        fixture.account.getAccountUuid(), fixture.tenantUuid)
                    .orElseThrow());
    var roles =
        fixture.inTransaction(
            () ->
                fixture
                    .roles
                    .findForCanonicalUpdate(
                        fixture.account.getAccountUuid(),
                        fixture.tenantUuid,
                        fixture.provenance,
                        member.getId(),
                        3L)
                    .orElseThrow());
    assertThat(roles.roles()).isEmpty();
    assertThat(member.isGameplayAdmissionAllowed()).isFalse();
    assertThat(
            fixture.count(
                "account_authority_outbox_events", "outbox_stream_key", fixture.membershipStream()))
        .isEqualTo(2L);
    assertThat(fixture.countAll("account_audit_outbox")).isEqualTo(1L);
  }

  @Test
  void pendingSplitTransitionCannotBeReclassifiedAsAnEventFreeActiveJoin() {
    Fixture fixture = newFixture();
    var checkpoint = fixture.writeAndPublish();
    assertThatThrownBy(() -> fixture.commitExistingJoin(fixture.requestId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("precommitted transition");
    var member =
        fixture.inTransaction(
            () ->
                fixture
                    .memberships
                    .findFreshMembershipForUpdate(
                        fixture.account.getAccountUuid(), fixture.tenantUuid)
                    .orElseThrow());
    var proof =
        new AccountJoinOperationRepository.CanonicalJoinTerminalProof(
            checkpoint.outboxStreamKey(),
            checkpoint.outboxSequence(),
            checkpoint.sourceEventId(),
            checkpoint.sourceEventDigest(),
            null,
            null,
            null,
            1L,
            member.getId(),
            member.getMembershipVersion(),
            member.getMembershipAuthorityGeneration());
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.operations.commitCanonicalJoin(
                            fixture.requestId,
                            fixture.scope,
                            fixture.callerBinding,
                            "ALREADY_ACTIVE",
                            proof)))
        .isInstanceOf(RuntimeException.class)
        .rootCause()
        .isInstanceOfSatisfying(
            java.sql.SQLException.class,
            failure -> {
              assertThat(failure.getSQLState()).isEqualTo("23514");
              assertThat(failure.getMessage())
                  .contains(
                      "Canonical JOIN terminal receipt does not match committed owner evidence");
            });
    assertThat(fixture.countAll("account_audit_outbox")).isZero();
    assertThat(
            fixture
                .inTransaction(
                    () ->
                        fixture
                            .operations
                            .findCanonicalEvidenceForUpdateByRequestId(fixture.requestId)
                            .orElseThrow())
                .status())
        .isEqualTo("PENDING");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void committedJoinReplaysStoredEvidenceAfterLaterMembershipChanges(boolean alreadyActive) {
    Fixture fixture = newFixture();
    fixture.commitFirstJoin();
    String originalRequest = alreadyActive ? fixture.prepareAnotherJoin() : fixture.requestId;
    var committed = fixture.commitExistingJoin(originalRequest);
    assertThat(committed.outcome()).isEqualTo(alreadyActive ? "ALREADY_ACTIVE" : "JOINED");

    // Controlled historical owner input, not proof of a production leave API.
    fixture.seedControlledInactiveOwnerSnapshot();
    var tables =
        java.util.List.of(
            "account_join_operations",
            "account_tenant_membership",
            "account_tenant_membership_role_snapshots",
            "account_membership_pair_authority",
            "account_authority_outbox_events",
            "account_authority_outbox_streams",
            "account_audit_outbox");
    java.util.function.Supplier<Object> storedRows =
        () ->
            tables.stream()
                .map(
                    table ->
                        fixture
                            .dsl
                            .fetch(
                                "SELECT xmin::text AS row_version, to_jsonb(t)::text AS row_data FROM "
                                    + table
                                    + " t ORDER BY to_jsonb(t)::text")
                            .intoMaps())
                .toList();
    var inactiveRows = storedRows.get();
    assertThat(fixture.commitExistingJoin(originalRequest)).isEqualTo(committed);
    assertThat(storedRows.get()).isEqualTo(inactiveRows);

    // A real subsequent JOIN owner operation advances the event and restores membership.
    var restored = fixture.commitExistingJoin(fixture.prepareAnotherJoin());
    assertThat(restored.terminalProof().eventSequence()).isEqualTo(3L);
    assertThat(restored.membershipVersion()).isEqualTo(4L);
    var restoredRows = storedRows.get();
    assertThat(fixture.commitExistingJoin(originalRequest)).isEqualTo(committed);
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.terminalCoordinator.commitCanonicalJoin(
                            fixture.scope, originalRequest, "different-caller", null, null, null)))
        .isInstanceOf(CanonicalJoinOperationConflictException.class);
    assertThat(storedRows.get()).isEqualTo(restoredRows);
  }

  @Test
  void inactiveJoinRestoresOneVersionAndEventWithoutChangingIndependentGeneration() {
    Fixture fixture = newFixture();
    var first = fixture.commitFirstJoin();
    fixture.seedControlledInactiveOwnerSnapshot();
    String restoreRequest = fixture.prepareAnotherJoin();
    var result = fixture.commitExistingJoin(restoreRequest);
    var replay = fixture.commitExistingJoin(restoreRequest);

    assertThat(result.outcome()).isEqualTo("JOINED");
    assertThat(result.membershipId()).isEqualTo(first.membershipId());
    assertThat(result.membershipVersion()).isEqualTo(4L);
    assertThat(result.membershipAuthorityGeneration()).isEqualTo(2L);
    assertThat(result.terminalProof().eventSequence()).isEqualTo(3L);
    assertThat(result.terminalProof().auditEventId())
        .isNotNull()
        .isNotEqualTo(first.auditEventId());
    assertThat(replay).isEqualTo(result);
    assertThat(
            fixture.count(
                "account_authority_outbox_events", "outbox_stream_key", fixture.membershipStream()))
        .isEqualTo(3L);
    assertThat(fixture.countAll("account_audit_outbox")).isEqualTo(2L);
    var member =
        fixture.inTransaction(
            () ->
                fixture
                    .memberships
                    .findFreshMembershipForUpdate(
                        fixture.account.getAccountUuid(), fixture.tenantUuid)
                    .orElseThrow());
    assertThat(member.getLifecycleState()).isEqualTo("ACTIVE");
    assertThat(member.isGameplayAdmissionAllowed()).isTrue();
  }

  @Test
  void restoreTerminalFailureRollsBackMemberRolesPairEventAndAudit() {
    Fixture fixture = newFixture();
    fixture.commitFirstJoin();
    fixture.seedControlledInactiveOwnerSnapshot();
    String restoreRequest = fixture.prepareAnotherJoin();
    fixture.dsl.execute(
        "CREATE FUNCTION fail_restore_terminal() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN IF NEW.status = 'COMMITTED' THEN RAISE EXCEPTION 'restore terminal failure'; "
            + "END IF; RETURN NEW; END $$");
    fixture.dsl.execute(
        "CREATE TRIGGER fail_restore_terminal BEFORE UPDATE ON account_join_operations "
            + "FOR EACH ROW EXECUTE FUNCTION fail_restore_terminal()");

    assertThatThrownBy(() -> fixture.commitExistingJoin(restoreRequest))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("restore terminal failure");
    var member =
        fixture.inTransaction(
            () ->
                fixture
                    .memberships
                    .findFreshMembershipForUpdate(
                        fixture.account.getAccountUuid(), fixture.tenantUuid)
                    .orElseThrow());
    assertThat(member.getLifecycleState()).isEqualTo("INACTIVE");
    assertThat(member.getMembershipVersion()).isEqualTo(3L);
    assertThat(member.getMembershipAuthorityGeneration()).isEqualTo(2L);
    assertThat(
            fixture.count(
                "account_authority_outbox_events", "outbox_stream_key", fixture.membershipStream()))
        .isEqualTo(2L);
    assertThat(fixture.countAll("account_audit_outbox")).isEqualTo(1L);
    var operation =
        fixture.inTransaction(
            () ->
                fixture
                    .operations
                    .findCanonicalEvidenceForUpdateByRequestId(restoreRequest)
                    .orElseThrow());
    assertThat(operation.status()).isEqualTo("PENDING");
    assertThat(operation.terminalProof()).isNull();
  }

  @Test
  void browserFacadeComposesRealV2ProducerReaderAndTerminalOwnersAndReplaysCommittedReceipt() {
    Fixture fixture = newFixture();
    BrowserFacade browser = fixture.browserFacade();
    var realms = browser.service().listBootstrapRealms(browser.bootstrapToken(), "tenant", "world");
    var selected = realms.getFirst();
    String requestId = UUID.randomUUID().toString();
    var request =
        new net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest(
            selected.connectScopeId(), requestId);

    var first = browser.service().joinPublicProduction(browser.bootstrapToken(), request);
    var replay = browser.service().joinPublicProduction(browser.bootstrapToken(), request);

    assertThat(first.success()).isTrue();
    assertThat(first.outcomeCode()).isEqualTo("JOINED");
    assertThat(first.accountId()).isEqualTo(fixture.account.getAccountUuid().toString());
    assertThat(first.tenantId()).isEqualTo(fixture.tenantUuid);
    assertThat(first.membershipVersion()).isEqualTo(2L);
    assertThat(first.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(first.gameplayAdmissionAllowed()).isTrue();
    assertThat(first.replayed()).isFalse();
    assertThat(replay.success()).isTrue();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.membershipId()).isEqualTo(first.membershipId());
    assertThat(
            fixture.count(
                "account_authority_outbox_events", "outbox_stream_key", fixture.membershipStream()))
        .isEqualTo(1L);
    assertThat(fixture.countAll("account_audit_outbox")).isEqualTo(1L);
    var stored =
        fixture.inTransaction(
            () ->
                fixture
                    .operations
                    .findCanonicalEvidenceForUpdateByRequestId(requestId)
                    .orElseThrow());
    assertThat(stored.status()).isEqualTo("COMMITTED");
    assertThat(stored.operationRepresentationVersion()).isEqualTo(2);
    assertThat(stored.scopeEvidence().tenantUuid()).isEqualTo(fixture.tenantUuid);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"COMMITTED", "FAILED_UNBOUND", "FAILED_BOUND"})
  void browserFacadeReplaysOperationFinishedAfterPreparation(String terminalState) {
    Fixture fixture = newFixture();
    BrowserFacade browser = fixture.browserFacade();
    var selected =
        browser
            .service()
            .listBootstrapRealms(browser.bootstrapToken(), "tenant", "world")
            .getFirst();
    String requestId = UUID.randomUUID().toString();
    var request =
        new net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest(
            selected.connectScopeId(), requestId);
    var callerReads = new java.util.concurrent.atomic.AtomicInteger();
    var retained =
        new java.util.concurrent.atomic.AtomicReference<
            AccountJoinOperationRepository.CanonicalJoinOperationEvidence>();
    org.mockito.Mockito.when(
            browser
                .sessions()
                .isAccountSessionActive(fixture.account.getId(), browser.bootstrapToken()))
        .thenAnswer(
            invocation -> {
              // The second caller read occurs outside SQL, after preparation and immediately before
              // the final transaction. Finish another exact attempt in this deterministic gap.
              if (callerReads.incrementAndGet() == 2) {
                if ("COMMITTED".equals(terminalState)) {
                  var first =
                      browser.service().joinPublicProduction(browser.bootstrapToken(), request);
                  assertThat(first.success()).isTrue();
                  assertThat(first.replayed()).isFalse();
                } else {
                  fixture.inTransaction(
                      () -> {
                        fixture.operations.lockAccount(fixture.account.getId());
                        var scope =
                            fixture
                                .connectScopes
                                .findCanonical(selected.connectScopeId())
                                .orElseThrow();
                        var pending =
                            fixture
                                .operations
                                .findCanonicalEvidenceForUpdateByRequestId(requestId)
                                .orElseThrow();
                        assertThat(pending.status()).isEqualTo("PENDING");
                        if ("FAILED_BOUND".equals(terminalState)) {
                          var policy = fixture.demoEntitlements.readCurrent(fixture.tenantUuid);
                          fixture.operations.bindCanonicalPolicyEvidence(
                              requestId,
                              scope,
                              pending.callerBinding(),
                              policy.allowPublicJoin(),
                              policy.entitlementVersion());
                        }
                        fixture.operations.finishCanonicalFailure(
                            requestId, scope, pending.callerBinding(), "CONNECT_SCOPE_MISMATCH");
                        return null;
                      });
                }
                retained.set(
                    fixture.inTransaction(
                        () ->
                            fixture
                                .operations
                                .findCanonicalEvidenceForUpdateByRequestId(requestId)
                                .orElseThrow()));
              }
              return true;
            });

    var replay = browser.service().joinPublicProduction(browser.bootstrapToken(), request);

    assertThat(retained.get()).isNotNull();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.success()).isEqualTo("COMMITTED".equals(terminalState));
    assertThat(replay.outcomeCode()).isEqualTo(retained.get().outcome());
    assertThat(
            fixture.inTransaction(
                () ->
                    fixture
                        .operations
                        .findCanonicalEvidenceForUpdateByRequestId(requestId)
                        .orElseThrow()))
        .isEqualTo(retained.get());
    assertThat(fixture.countAll("account_audit_outbox"))
        .isEqualTo("COMMITTED".equals(terminalState) ? 1L : 0L);
  }

  @Test
  void browserFacadeReplaysCommittedJoinProofAfterMembershipBecomesInactive() {
    Fixture fixture = newFixture();
    BrowserFacade browser = fixture.browserFacade();
    var selected =
        browser
            .service()
            .listBootstrapRealms(browser.bootstrapToken(), "tenant", "world")
            .getFirst();
    String requestId = UUID.randomUUID().toString();
    var request =
        new net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest(
            selected.connectScopeId(), requestId);

    var original = browser.service().joinPublicProduction(browser.bootstrapToken(), request);
    fixture.seedControlledInactiveOwnerSnapshot();
    var replay = browser.service().joinPublicProduction(browser.bootstrapToken(), request);

    assertThat(original.success()).isTrue();
    assertThat(original.outcomeCode()).isEqualTo("JOINED");
    assertThat(original.membershipVersion()).isEqualTo(2L);
    assertThat(original.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(original.gameplayAdmissionAllowed()).isTrue();
    assertThat(replay.success()).isTrue();
    assertThat(replay.outcomeCode()).isEqualTo(original.outcomeCode());
    assertThat(replay.membershipId()).isEqualTo(original.membershipId());
    assertThat(replay.membershipVersion()).isEqualTo(original.membershipVersion());
    assertThat(replay.membershipAuthorityGeneration())
        .isEqualTo(original.membershipAuthorityGeneration());
    assertThat(replay.gameplayAdmissionAllowed()).isFalse();
    assertThat(replay.replayed()).isTrue();

    var currentMembership =
        fixture.inTransaction(
            () ->
                fixture
                    .memberships
                    .findFreshMembershipForUpdate(
                        fixture.account.getAccountUuid(), fixture.tenantUuid)
                    .orElseThrow());
    assertThat(currentMembership.getLifecycleState()).isEqualTo("INACTIVE");
    assertThat(currentMembership.getMembershipVersion()).isEqualTo(3L);
    assertThat(currentMembership.getMembershipAuthorityGeneration()).isEqualTo(2L);
    assertThat(currentMembership.isGameplayAdmissionAllowed()).isFalse();
  }

  @Test
  void directFacadeComposesItsOwnAuthenticatedProducerWithRealV2ReaderAndJoinOwners() {
    Fixture fixture = newFixture();
    BrowserFacade composed = fixture.browserFacade();
    var target = composed.target();
    String requestId = UUID.randomUUID().toString();
    var caller =
        new net.firedevops.firemud.accountservice.dto.DirectTextCallerContext(
            fixture.account.getAccountUuid(),
            fixture.tenantUuid,
            target.realmId(),
            target.playableStateNamespaceId(),
            target.playableStateScope(),
            target.canonicalGameInstanceId(),
            "2001",
            requestId);
    var selected =
        new net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget(
            fixture.tenantUuid,
            target.realmId(),
            target.worldSlug(),
            target.realmSlug(),
            target.playableStateNamespaceId(),
            target.playableStateScope(),
            target.canonicalGameInstanceId(),
            target.catalogRevision(),
            target.pointerVersion());
    var peer =
        new net.firedevops.firemud.common.grpc.GrpcPeerIdentity(
            "spiffe://firemud/ns/" + TEST_NAMESPACE + "/sa/game-session-service",
            TEST_NAMESPACE,
            "game-session-service");
    var context =
        io.grpc.Context.ROOT.withValue(
            net.firedevops.firemud.common.grpc.GrpcPeerIdentity.CONTEXT_KEY, peer);
    context.run(
        () -> {
          var scope = composed.service().issueDirectTextConnectScope(caller, selected);
          var request =
              new net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest(
                  scope.connectScopeId(), requestId);
          var first = composed.service().joinPublicProductionFromGameSession(caller, request);
          var replay = composed.service().joinPublicProductionFromGameSession(caller, request);
          assertThat(first.success()).isTrue();
          assertThat(first.tenantId()).isEqualTo(fixture.tenantUuid);
          assertThat(first.membershipVersion()).isEqualTo(2L);
          assertThat(first.gameplayAdmissionAllowed()).isTrue();
          assertThat(replay.success()).isTrue();
          assertThat(replay.replayed()).isTrue();
          assertThat(replay.membershipId()).isEqualTo(first.membershipId());
        });
    org.mockito.Mockito.verifyNoInteractions(composed.sessions());
    assertThat(
            fixture.count(
                "account_authority_outbox_events", "outbox_stream_key", fixture.membershipStream()))
        .isEqualTo(1L);
    assertThat(fixture.countAll("account_audit_outbox")).isEqualTo(1L);
  }

  private Fixture newFixture() {
    return new Fixture(newTestContext());
  }

  private TestContext newTestContext() {
    String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    runOwnedSchemas.add(schema);
    DriverManagerDataSource dataSource = postgres.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new TestContext(
        dsl, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

  private static final class Fixture {
    private final DSLContext dsl;
    private final TransactionTemplate transaction;
    private final AccountRepository accounts;
    private final FreshTenantIdentityAssociationRepository freshTenants;
    private final AccountMembershipPairAuthorityRepository pairs;
    private final AccountTenantMembershipRepository memberships;
    private final AccountTenantMembershipRoleSnapshotRepository roles;
    private final AccountAuthorityGenerationRepository generations;
    private final AccountAuthorityOutboxRepository outbox;
    private final AccountAuthoritySourceEvidenceRepository sourceEvidence;
    private final AccountTenantAuthorityEventRepository tenantAuthorityEvents;
    private final AccountDemoTenantEntitlementRepository demoEntitlements;
    private final AccountAuditOutboxRepository auditOutbox;
    private final AccountJoinOperationRepository operations;
    private final AccountMembershipAuthorityEventProducer producer;
    private final AccountCanonicalFirstJoinTerminalCoordinator terminalCoordinator;
    private final AccountConnectScopeRepository connectScopes;
    private final Account account;
    private final UUID tenantUuid = UUID.randomUUID();
    private final FreshTenantCreationEvidence tenantEvidence;
    private final VerifiedTenantProvenance provenance;
    private final CanonicalJoinScopeV2 scope;
    private final String requestId = UUID.randomUUID().toString();
    private final String callerBinding = "owner-bound-caller-" + UUID.randomUUID();
    private Long membershipId;

    private Fixture(TestContext context) {
      this.dsl = context.dsl();
      this.transaction = context.transaction();
      this.accounts = new AccountRepository(dsl);
      this.freshTenants = new FreshTenantIdentityAssociationRepository(dsl, TEST_NAMESPACE);
      this.pairs = new AccountMembershipPairAuthorityRepository(dsl);
      this.memberships = new AccountTenantMembershipRepository(dsl, accounts, freshTenants, pairs);
      this.roles = new AccountTenantMembershipRoleSnapshotRepository(dsl);
      this.generations = new AccountAuthorityGenerationRepository(dsl);
      this.outbox = new AccountAuthorityOutboxRepository(dsl);
      this.sourceEvidence = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
      var billingOutbox = new AccountTenantEntitlementOutboxRepository(dsl);
      this.tenantAuthorityEvents =
          new AccountTenantAuthorityEventRepository(
              dsl, outbox, billingOutbox, freshTenants, generations);
      this.demoEntitlements =
          new AccountDemoTenantEntitlementRepository(
              dsl, freshTenants, generations, billingOutbox, tenantAuthorityEvents);
      this.auditOutbox = new AccountAuditOutboxRepository(dsl);
      var legacyAssociationRepository =
          org.mockito.Mockito.mock(ApprovedLegacyTenantAssociationRepository.class);
      var retainedIdentityResolver =
          new AccountTenantIdentityResolver(legacyAssociationRepository, TEST_NAMESPACE);
      this.connectScopes =
          new AccountConnectScopeRepository(dsl, retainedIdentityResolver, freshTenants);
      this.operations = new AccountJoinOperationRepository(dsl, connectScopes);
      this.producer =
          new AccountMembershipAuthorityEventProducer(
              operations,
              accounts,
              pairs,
              memberships,
              roles,
              generations,
              outbox,
              sourceEvidence,
              tenantAuthorityEvents,
              demoEntitlements);
      this.terminalCoordinator =
          new AccountCanonicalFirstJoinTerminalCoordinator(
              accounts, operations, memberships, roles, outbox, pairs, auditOutbox, producer);
      this.account = createAccount();
      this.tenantEvidence = freshTenantEvidence(tenantUuid);
      this.provenance =
          new VerifiedTenantProvenance(
              null,
              TenantProvenanceKind.FRESH_GAME_DESIGN,
              tenantEvidence.operationId(),
              tenantEvidence.evidenceDigest());
      this.scope = canonicalScope(account.getAccountUuid(), tenantUuid);
      seedOwnerRowsAndOperation();
    }

    private Account createAccount() {
      Account input = new Account();
      String suffix = UUID.randomUUID().toString().replace("-", "");
      input.setUsername("canonical-join-" + suffix);
      input.setEmail("canonical-join-" + suffix + "@example.test");
      input.setPasswordHash("integration-fixture-hash");
      // Player role belongs to the canonical tenant membership, not global Account authority.
      return inTransaction(() -> accounts.save(input));
    }

    private void seedOwnerRowsAndOperation() {
      inTransaction(
          () -> {
            freshTenants.importVerified(tenantEvidence);
            sourceEvidence.initializeIssuerIfAbsent(ACCOUNT_ISSUER);
            generations.initializeTenantIfAbsent(tenantUuid);
            generations.initialize(
                AccountAuthorityGenerationRepository.AuthorityScope.membership(
                    account.getAccountUuid(), tenantUuid));
            connectScopes.insertCanonical(account.getId(), scope, provenance);
            operations.insertCanonicalIntent(requestId, scope, callerBinding);
            return null;
          });
      DemoTenantEntitlementSnapshot currentEntitlement =
          inTransaction(
              () ->
                  demoEntitlements.provision(
                      entitlementRequest(UUID.randomUUID(), null), tenantEvidence));
      inTransaction(
          () -> {
            operations.bindCanonicalPolicyEvidence(
                requestId,
                scope,
                callerBinding,
                currentEntitlement.allowPublicJoin(),
                currentEntitlement.entitlementVersion());
            return null;
          });
    }

    private DemoTenantEntitlementSnapshot provisionCurrentTenantEntitlement() {
      DemoTenantEntitlementSnapshot current =
          inTransaction(() -> demoEntitlements.readCurrent(tenantUuid));
      return inTransaction(
          () ->
              demoEntitlements.provision(
                  entitlementRequest(UUID.randomUUID(), current), tenantEvidence));
    }

    private DemoTenantEntitlementRequest entitlementRequest(
        UUID requestId, DemoTenantEntitlementSnapshot current) {
      return entitlementRequest(requestId, current, true);
    }

    private DemoTenantEntitlementRequest entitlementRequest(
        UUID requestId, DemoTenantEntitlementSnapshot current, boolean allowPublicJoin) {
      return new DemoTenantEntitlementRequest(
          requestId,
          tenantUuid,
          tenantEvidence.creationRequestId(),
          tenantEvidence.requestDigest(),
          current == null ? null : current.entitlementVersion(),
          current == null ? null : current.tenantAuthorityGeneration(),
          current == null ? null : current.tenantAuthoritySourceVersion(),
          true,
          allowPublicJoin,
          true,
          true,
          new DemoTenantEntitlementRequest.Quotas(3L, 2L, 4096L));
    }

    private AccountAuthorityOutboxRepository.Checkpoint writeAndPublish() {
      return inTransaction(
          () -> {
            var membership =
                memberships.createFreshMembershipForJoin(account.getAccountUuid(), tenantUuid);
            membershipId = membership.getId();
            roles.replaceCanonical(
                membership,
                account.getAccountUuid(),
                tenantUuid,
                provenance,
                2L,
                java.util.List.of("player"));
            return producer.publishCanonicalFirstJoinMembershipChange(
                scope, requestId, callerBinding);
          });
    }

    private AccountJoinOperationRepository.CanonicalJoinTerminalProof commitFirstJoin() {
      return inTransaction(
          () -> terminalCoordinator.commitCanonicalFirstJoin(scope, requestId, callerBinding));
    }

    private String prepareAnotherJoin() {
      String nextRequest = UUID.randomUUID().toString();
      inTransaction(
          () -> {
            operations.lockAccount(account.getId());
            operations.insertCanonicalIntent(nextRequest, scope, callerBinding);
            var policy = demoEntitlements.readCurrent(tenantUuid);
            operations.bindCanonicalPolicyEvidence(
                nextRequest,
                scope,
                callerBinding,
                policy.allowPublicJoin(),
                policy.entitlementVersion());
            return null;
          });
      return nextRequest;
    }

    private AccountJoinOperationRepository.CanonicalJoinOperationEvidence commitExistingJoin(
        String nextRequest) {
      return inTransaction(
          () ->
              terminalCoordinator.commitCanonicalJoin(
                  scope, nextRequest, callerBinding, null, null, null));
    }

    private BrowserFacade browserFacade() {
      var jwt =
          new net.firedevops.firemud.common.security.JwtUtil(
              "controlled-browser-facade-integration-signing-key-0123456789", 300000L);
      var sessions =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.accountservice.service.session.SessionService.class);
      String bootstrap =
          jwt.generateToken(
              account.getAccountUuid().toString(),
              300000L,
              Map.of(
                  "aud",
                  "player-bootstrap",
                  "accountId",
                  account.getAccountUuid().toString(),
                  "jti",
                  UUID.randomUUID().toString()));
      org.mockito.Mockito.when(sessions.isAccountSessionActive(account.getId(), bootstrap))
          .thenReturn(true);
      var targets =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.accountservice.client
                  .CanonicalGameplayAdmissionDecisionReadClient.class);
      var target = controlledPublishedTarget();
      org.mockito.Mockito.when(
              targets.listVisiblePublicProductionAdmissionTargets(
                  org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              invocation -> {
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive())
                    .isFalse();
                var request =
                    new net.firedevops.firemud.common.gamesession
                        .CanonicalGameplayAdmissionDecisionReadGrpcCodec.DiscoveryRequest(
                        invocation.getArgument(0, UUID.class), TEST_NAMESPACE);
                return net.firedevops.firemud.common.gamesession
                    .CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromDiscoveryResponse(
                    request,
                    net.firedevops.firemud.common.gamesession
                        .CanonicalGameplayAdmissionDecisionReadGrpcCodec.toDiscoveryResponse(
                        request, java.util.List.of(target)));
              });
      org.mockito.Mockito.when(
              targets.resolvePublishedRealmAdmissionTarget(org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              invocation -> {
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive())
                    .isFalse();
                var selector =
                    invocation.getArgument(
                        0,
                        net.firedevops.firemud.common.gamesession
                            .CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector
                            .class);
                return net.firedevops.firemud.common.gamesession
                    .CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromTargetResolutionResponse(
                    selector,
                    net.firedevops.firemud.common.gamesession
                        .CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetResolutionResponse(
                        selector, target));
              });
      // The protected client is the controlled upstream boundary in this Account SQL composition;
      // this fixture does not claim physical GS publication or World lifecycle validation.
      org.mockito.Mockito.when(
              targets.readPublishedRealmAdmissionOwner(org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              invocation -> {
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive())
                    .isFalse();
                var selector =
                    invocation.getArgument(
                        0,
                        net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence
                            .Request.class);
                var published =
                    org.mockito.Mockito.mock(
                        net.firedevops.firemud.common.gamesession
                            .CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence
                            .class);
                org.mockito.Mockito.when(published.selector()).thenReturn(selector);
                org.mockito.Mockito.when(published.currentTarget()).thenReturn(target);
                return published;
              });
      var hosting =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.accountservice.repository
                  .AccountHostingSubscriptionLifecycleRepository.class);
      var observer =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver.class);
      var manager = Objects.requireNonNull(transaction.getTransactionManager());
      var clock = java.time.Clock.systemUTC();
      var properties = new net.firedevops.firemud.accountservice.config.AccountTokenProperties();
      properties.setConnectScopeExpirationMs(120000L);
      var entitlementOwner =
          new net.firedevops.firemud.accountservice.service.AccountPublicBootstrapEntitlementOwner(
              tenantAuthorityEvents,
              new net.firedevops.firemud.accountservice.service
                  .AccountHostingSubscriptionLifecycleService(hosting, observer, manager),
              demoEntitlements,
              manager,
              clock);
      var producerOwner =
          new net.firedevops.firemud.accountservice.service.AccountCanonicalPublicRealmScopeOwner(
              new net.firedevops.firemud.accountservice.service.session
                  .AccountPlayerBootstrapCallerVerifier(jwt, accounts, sessions),
              targets,
              entitlementOwner,
              accounts,
              memberships,
              freshTenants,
              tenantAuthorityEvents,
              connectScopes,
              jwt,
              properties,
              clock,
              manager,
              TEST_NAMESPACE);
      var service =
          new net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl(
              accounts,
              generations,
              sourceEvidence,
              outbox,
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.accountservice.repository
                      .AccountPasswordResetOperationRepository.class),
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.accountservice.repository
                      .AccountLogoutAllOperationRepository.class),
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.accountservice.repository
                      .AccountSecurityStateOperationRepository.class),
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.accountservice.service
                      .AccountPasswordResetDraftSourceChangeRepository.class),
              auditOutbox,
              operations,
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.accountservice.repository
                      .AccountEmailLoginChallengeRepository.class),
              org.mockito.Mockito.mock(
                  net.firedevops.firemud.accountservice.repository.AccountRealmAccessGrantRepository
                      .class),
              memberships,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              new net.firedevops.firemud.accountservice.config.MailProperties(),
              properties,
              new net.firedevops.firemud.common.security.JwtAuthProperties(),
              jwt,
              sessions,
              manager);
      service.setCanonicalPublicRealmScopeOwner(producerOwner);
      service.setCanonicalScopeConsumers(
          new net.firedevops.firemud.accountservice.service.AccountCanonicalConnectScopeReader(
              jwt, connectScopes, manager, clock),
          targets,
          tenantAuthorityEvents,
          demoEntitlements,
          hosting,
          observer,
          terminalCoordinator,
          TEST_NAMESPACE,
          clock);
      return new BrowserFacade(service, bootstrap, target, sessions);
    }

    /**
     * Protected GS transport remains a controlled owner response; Account SQL/JWT owners are real.
     */
    private net.firedevops.firemud.common.gamesession
            .CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target
        controlledPublishedTarget() {
      UUID version = UUID.randomUUID();
      String digest = "a".repeat(64);
      String initial = UUID.randomUUID().toString();
      var holdRequest =
          new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request(
              TEST_NAMESPACE,
              tenantUuid,
              scope.worldSlug(),
              scope.realmId(),
              scope.playableStateNamespaceId(),
              scope.playableStateScope(),
              scope.gameInstanceId(),
              version,
              1L,
              initial,
              digest,
              net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold
                  .InitialAdmissionOrigin.NO_PRIOR_POINTER,
              scope.catalogRevision(),
              null);
      var hold =
          new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity(
              holdRequest, UUID.randomUUID(), UUID.randomUUID());
      return new net.firedevops.firemud.common.gamesession
          .CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target(
          TEST_NAMESPACE,
          "tenant",
          tenantUuid,
          scope.worldSlug(),
          "World",
          scope.realmId(),
          scope.realmSlug(),
          "Production",
          11L,
          44L,
          scope.playableStateNamespaceId(),
          "SHARED",
          scope.gameInstanceId(),
          version,
          1L,
          scope.catalogRevision(),
          scope.pointerVersion(),
          digest,
          1L,
          initial,
          digest,
          net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec
              .OriginKind.NO_PRIOR_POINTER,
          null,
          hold.holdId(),
          hold.holdFence(),
          hold.holdBindingDigest(),
          1L,
          "sha256:" + digest,
          net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec
              .OwnerProofOutcome.COMMITTED,
          false,
          java.time.Instant.now(),
          "PRESEEDED_ONLY");
    }

    /** Controlled retained INACTIVE input; this fixture does not claim a production leave owner. */
    private void seedControlledInactiveOwnerSnapshot() {
      seedControlledMembershipSnapshot("INACTIVE", java.util.List.of("player"));
    }

    /** Controlled owner snapshot; no leave, creator grant or lifecycle mutation API is claimed. */
    @SuppressWarnings("unchecked")
    private void seedControlledMembershipSnapshot(
        String lifecycle, java.util.List<String> currentRoles) {
      // The existing draft source-change owner requires a physical READ_COMMITTED connection.
      var historicalTransaction = new TransactionTemplate(transaction.getTransactionManager());
      historicalTransaction.setPropagationBehavior(
          org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      historicalTransaction.setIsolationLevel(
          org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
      historicalTransaction.execute(
          status -> {
            operations.lockAccount(account.getId());
            var member =
                memberships
                    .findFreshMembershipForUpdate(account.getAccountUuid(), tenantUuid)
                    .orElseThrow();
            var generation =
                generations.read(
                    AccountAuthorityGenerationRepository.AuthorityScope.membership(
                        account.getAccountUuid(), tenantUuid));
            // This is controlled historical input, not an implemented leave/role-removal owner.
            // One atomic invalidating transaction advances the closed Account source and fence;
            // the member generation then advances under that same fence without inventing an
            // Account event or weakening the V40 deferred source/fence checks.
            var accountBefore =
                sourceEvidence.readCurrentIssuerAccountSources(
                    ACCOUNT_ISSUER, account.getAccountUuid());
            commitControlledClosedAccountSecurityChange(accountBefore.account());
            assertThat(
                    dsl.execute(
                        "UPDATE account_authority_generations SET generation = generation + 1, "
                            + "source_version = source_version + 1, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE scope_kind = 'MEMBERSHIP' AND account_uuid = ? AND tenant_uuid = ? "
                            + "AND generation = ? AND source_version = ?",
                        account.getAccountUuid(),
                        tenantUuid,
                        generation.generation(),
                        generation.sourceVersion()))
                .isEqualTo(1);
            var next = generations.read(generation.scope());
            var authority =
                generations.readCompositeSnapshot(
                    ACCOUNT_ISSUER,
                    account.getAccountUuid(),
                    java.util.List.of(tenantUuid),
                    java.util.List.of(tenantUuid));
            var accountSource =
                sourceEvidence.readCurrentIssuerAccountSources(
                    ACCOUNT_ISSUER, account.getAccountUuid());
            assertThat(next.generation()).isEqualTo(generation.generation() + 1L);
            assertThat(next.sourceVersion()).isEqualTo(generation.sourceVersion() + 1L);
            assertThat(next.issuanceFence().value())
                .isEqualTo(generation.issuanceFence().value() + 1L);
            assertThat(next.issuanceFence().sourceVersion())
                .isEqualTo(generation.issuanceFence().sourceVersion() + 1L);
            assertThat(accountSource.issuanceFence()).isEqualTo(next.issuanceFence());
            assertThat(accountSource.account().generation())
                .isEqualTo(accountBefore.account().generation() + 1L);
            assertThat(accountSource.account().sourceVersion())
                .isEqualTo(accountBefore.account().sourceVersion() + 1L);
            assertThat(accountSource.account().checkpoint().sequence())
                .isEqualTo(accountBefore.account().checkpoint().sequence() + 1L);
            var tenantSource = tenantAuthorityEvents.readCurrentByTenant(tenantUuid);
            var prior = pairs.readForUpdate(account.getAccountUuid(), tenantUuid).orElseThrow();
            long nextMembershipVersion = member.getMembershipVersion() + 1L;
            long nextEventSequence = prior.eventSequence() + 1L;
            var previousEvent =
                outbox.findEvent(membershipStream(), prior.eventSequence()).orElseThrow();
            var json = tools.jackson.databind.json.JsonMapper.builder().build();
            Map<String, Object> event =
                json.readValue(
                    new String(previousEvent.payload(), StandardCharsets.UTF_8), Map.class);
            String inactiveRequest = "controlled-inactive-" + UUID.randomUUID();
            event.remove("eventDigest");
            event.put("eventId", UUID.randomUUID().toString());
            event.put("requestId", inactiveRequest);
            event.put("outboxSequence", Long.toString(nextEventSequence));
            event.put("membershipLifecycleState", lifecycle);
            event.put("roles", currentRoles);
            event.put(
                "membershipVersion",
                Map.of(tenantUuid.toString(), Long.toString(nextMembershipVersion)));
            event.put("membershipAuthorityGeneration", Long.toString(next.generation()));
            Map<String, Object> tuple = new java.util.LinkedHashMap<>();
            tuple.put("issuerAuthGeneration", Long.toString(authority.issuer().generation()));
            tuple.put(
                "accountAuthorityGeneration", Long.toString(authority.account().generation()));
            tuple.put(
                "tenantAuthorityGeneration",
                Map.of(
                    tenantUuid.toString(),
                    Long.toString(authority.tenants().getFirst().generation())));
            tuple.put(
                "membershipAuthorityGeneration",
                Map.of(tenantUuid.toString(), Long.toString(next.generation())));
            tuple.put("privateRealmGrantVersions", java.util.List.of());
            var cutoff = accountSource.account().accountSecurityCutoff().orElseThrow();
            tuple.put(
                "accountSecurityCutoff",
                Map.of(
                    "accountAuthorityGeneration", cutoff.accountAuthorityGeneration(),
                    "outboxStreamKey", cutoff.outboxStreamKey(),
                    "outboxSequence", cutoff.outboxSequence()));
            tuple.put(
                "tenantBillingCutoff",
                Map.of(
                    tenantUuid.toString(),
                    Map.of(
                        "tenantAuthorityGeneration",
                            Long.toString(tenantSource.tenantAuthorityGeneration()),
                        "tenantBillingSequence",
                            Long.toString(tenantSource.tenantBillingSequence()),
                        "outboxStreamKey", tenantSource.outboxStreamKey(),
                        "outboxSequence", Long.toString(tenantSource.outboxSequence()))));
            event.put("authorityTuple", tuple);
            event.put("issuanceFence", Long.toString(next.issuanceFence().value()));
            event.put("gameplayAdmissionAllowed", false);
            event.put("callerBoundAuthorityInvalidated", true);
            var sealed = MembershipAuthorityEventV1Codec.seal(event);
            var appended =
                outbox.append(
                    membershipStream(),
                    inactiveRequest,
                    sequence -> {
                      if (sequence != nextEventSequence)
                        throw new IllegalStateException("Controlled inactive sequence changed");
                      return new AccountAuthorityOutboxRepository.EventEvidence(
                          sealed.eventId(), sealed.eventDigest(), sealed.canonicalJsonUtf8());
                    });
            assertThat(
                    dsl.execute(
                        "UPDATE account_tenant_membership SET lifecycle_state = ?, gameplay_admission_allowed = FALSE, "
                            + "membership_version = ?, membership_authority_generation = ? WHERE id = ?",
                        lifecycle,
                        nextMembershipVersion,
                        next.generation(),
                        member.getId()))
                .isEqualTo(1);
            var inactive =
                memberships
                    .findCanonicalMembershipForUpdate(account.getAccountUuid(), tenantUuid)
                    .orElseThrow();
            roles.replaceCanonical(
                inactive,
                account.getAccountUuid(),
                tenantUuid,
                provenance,
                nextMembershipVersion,
                currentRoles);
            assertThat(
                    dsl.execute(
                        "UPDATE account_membership_pair_authority SET membership_version = ?, membership_authority_generation = ?, "
                            + "last_event_sequence = ?, last_event_id = ?, last_event_digest = ?, last_transition_invalidated = TRUE "
                            + "WHERE account_uuid = ? AND tenant_uuid = ?",
                        nextMembershipVersion,
                        next.generation(),
                        appended.outboxSequence(),
                        appended.eventId(),
                        appended.eventDigest(),
                        account.getAccountUuid(),
                        tenantUuid))
                .isEqualTo(1);
            return null;
          });
    }

    /**
     * Controlled sole-writer composition of the existing closed security-state operation. The
     * correlation bytes are fixture input, not authenticated permission or a runtime producer. Keep
     * the source change, exact receipt, event and head in the enclosing historical transaction.
     */
    private void commitControlledClosedAccountSecurityChange(
        AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence currentSource) {
      var current = accounts.findByIdForUpdate(account.getId()).orElseThrow();
      var roleSource =
          Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT global_roles, global_role_source_version FROM account_global_role_sources "
                      + "WHERE account_uuid = ? FOR SHARE",
                  account.getAccountUuid()));
      long roleVersion =
          Objects.requireNonNull(roleSource.get("global_role_source_version", Long.class));
      var before =
          new AccountState(
              current.isEmailVerified(),
              AccountLoginAuthModes.read(current.getLoginAuthModes()).stream()
                  .map(Enum::name)
                  .sorted()
                  .toList(),
              java.util.Arrays.stream(roleSource.get("global_roles", String[].class))
                  .sorted()
                  .toList(),
              current.getLifecycleState().name());
      var after =
          new AccountState(
              !before.emailVerified(),
              before.loginAuthModes(),
              before.globalRoles(),
              before.lifecycleState());
      var sourceState = generations.read(currentSource.scope());
      String stream = currentSource.checkpoint().outboxStreamKey();
      long checkpointSequence = currentSource.checkpoint().sequence();
      byte[] checkpointPayload =
          checkpointSequence == 0L
              ? new byte[0]
              : outbox.findEvent(stream, checkpointSequence).orElseThrow().payload();
      UUID securityRequest = UUID.randomUUID();
      byte[] correlation =
          ("{\"actorAccountUuid\":\""
                  + account.getAccountUuid()
                  + "\",\"ownerEvidenceDigest\":\"sha256:"
                  + "a".repeat(64)
                  + "\",\"ownerOperationId\":\""
                  + securityRequest
                  + "\",\"schemaVersion\":\"account-security-state-caller-correlation/v1\"}")
              .getBytes(StandardCharsets.UTF_8);
      var request =
          new AccountSecurityStateMutationRequest(
              securityRequest,
              account.getAccountUuid(),
              correlation,
              sourceState.generation(),
              sourceState.sourceVersion(),
              AccountSecurityStateOperationRepository.detectedKinds(before, after),
              after);
      var draft =
          new Capture(
              current.getId(),
              current.getAccountUuidProvenance(),
              before,
              sourceState,
              checkpointSequence,
              checkpointPayload,
              roleVersion,
              null);
      byte[] captureBytes = AccountSecurityStateOperationRepository.captureBytes(request, draft);
      var source =
          new SourceEvidence(
              SourceKind.ACCOUNT,
              account.getAccountUuid().toString(),
              Long.toString(sourceState.generation()),
              Long.toString(sourceState.sourceVersion()),
              stream,
              Long.toString(checkpointSequence),
              captureBytes);
      var change = new SourceChange(UUID.randomUUID(), java.util.List.of(source), captureBytes);
      var capture =
          new Capture(
              draft.accountId(),
              draft.provenance(),
              before,
              sourceState,
              checkpointSequence,
              checkpointPayload,
              roleVersion,
              change);
      var fences = new DraftAuthorizationFenceRepository(dsl);
      var securityOperations = new AccountSecurityStateOperationRepository(dsl);
      assertThat(fences.requestSourceChange(change)).isTrue();
      assertThat(securityOperations.claim(request, capture).claimed()).isTrue();
      assertThat(fences.sourceMutationPermitted(change)).isTrue();
      var advanced =
          sourceEvidence.prepareClosedAccountAdvance(account.getAccountUuid(), currentSource);
      assertThat(
              dsl.execute(
                  "UPDATE accounts SET email_verified = ? WHERE account_uuid = ?",
                  after.emailVerified(),
                  account.getAccountUuid()))
          .isEqualTo(1);
      var event =
          outbox.append(
              stream,
              securityRequest.toString(),
              sequence -> {
                var sealed =
                    AccountSecurityStateAuthorityEventV1Codec.seal(
                        AccountSecurityStateMutationRequest.eventPreimage(
                            securityRequest,
                            account.getAccountUuid(),
                            Long.toString(advanced.generation()),
                            Long.toString(advanced.sourceVersion()),
                            Long.toString(sequence),
                            request.mutationKinds(),
                            after));
                return new AccountAuthorityOutboxRepository.EventEvidence(
                    sealed.eventId(), sealed.eventDigest(), sealed.canonicalJsonUtf8());
              });
      fences.markSourceCommitted(change);
      var committed = securityOperations.complete(request, event, advanced, roleVersion);
      assertThat(committed.receipt().orElseThrow().event()).isEqualTo(event);
      assertThat(committed.capture().sourceChange().canonicalBytes())
          .isEqualTo(change.canonicalBytes());
      assertThat(fences.readSourceChange(change).status()).isEqualTo("SOURCE_COMMITTED");
    }

    private long membershipId() {
      return membershipId == null ? -1L : membershipId;
    }

    private String membershipStream() {
      return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
          + "membership/"
          + account.getAccountUuid()
          + "/"
          + tenantUuid;
    }

    private long count(String table, String keyColumn, Object value) {
      return Objects.requireNonNull(
              dsl.fetchOne("SELECT count(*) FROM " + table + " WHERE " + keyColumn + " = ?", value))
          .get(0, Long.class);
    }

    private long countAll(String table) {
      return Objects.requireNonNull(dsl.fetchOne("SELECT count(*) FROM " + table))
          .get(0, Long.class);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> callback) {
      return transaction.execute(status -> callback.get());
    }
  }

  private static FreshTenantCreationEvidence freshTenantEvidence(UUID tenantUuid) {
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String sourceTenantKey =
        "fresh-" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            TEST_NAMESPACE, requestId, sourceTenantKey, "Canonical JOIN event test", null);
    long sourceGameRowId = positiveRandomLong();
    String provenanceKind = "NEW_GAME_ROW";
    return new FreshTenantCreationEvidence(
        1,
        TEST_NAMESPACE,
        requestId,
        operationId,
        requestDigest,
        tenantUuid,
        sourceGameRowId,
        sourceTenantKey,
        provenanceKind,
        GameTenantCreationDigest.evidenceDigest(
            TEST_NAMESPACE,
            requestId,
            operationId,
            requestDigest,
            tenantUuid,
            sourceGameRowId,
            sourceTenantKey,
            provenanceKind));
  }

  private static CanonicalJoinScopeV2 canonicalScope(UUID accountUuid, UUID tenantUuid) {
    return new CanonicalJoinScopeV2(
        "canonical-scope-" + UUID.randomUUID(),
        accountUuid,
        tenantUuid,
        UUID.randomUUID(),
        "tenant",
        "world",
        "production",
        UUID.randomUUID(),
        "SHARED",
        UUID.randomUUID(),
        7L,
        3L,
        "2026-10-04T00:00:00Z",
        "2026-10-04T00:02:00Z");
  }

  private static long positiveRandomLong() {
    long value = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    return value == 0L ? 1L : value;
  }

  private record TestContext(DSLContext dsl, TransactionTemplate transaction) {}

  private record BrowserFacade(
      net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl service,
      String bootstrapToken,
      net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec
              .Target
          target,
      net.firedevops.firemud.accountservice.service.session.SessionService sessions) {}
}
