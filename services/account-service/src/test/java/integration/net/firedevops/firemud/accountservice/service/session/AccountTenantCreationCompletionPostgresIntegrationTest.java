package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mkammerer.argon2.Argon2Factory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.creatorparty.CreatorPartyEncoding;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.service.AccountCreatorMembershipSourceReader;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapAuthorizationSource;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.client.GameDesignOriginalTenantCreationOutcomeClient.VerifiedTerminal;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountInitialTenantBillingOwnerRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountTenantCreationBootstrapService;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRepository.Admitted;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.OriginalIntent;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL final-owner commit, exact replay and rollback with real constituent repositories.
 * Original intent/admission/source-holder rows and password reauthentication are persisted through
 * their existing storage patterns. Source-currentness observations, accepted keys, issuer/Redis
 * qualification and private terminal inputs remain controlled; this is atomic storage/composition
 * proof, not genuine current authority, production issuer custody or mTLS proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountTenantCreationCompletionPostgresIntegrationTest {
  private static final String PASSWORD = "test-only-final-creation-password";
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void downstreamBootstrapRefusalRollsBackActualIdentityAndTenantGenerationTogether() throws Exception {
    String schema = "completion_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(),
        postgres.getUsername(), postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema)).locations("classpath:db/migration").load().migrate();
    var actual = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(dataSource);
    var dsl = mock(DSLContext.class);
    doAnswer(invocation -> {
      actual.connection(invocation.<ConnectionRunnable>getArgument(0));
      return null;
    }).when(dsl).connection(any(ConnectionRunnable.class));

    UUID operation = UUID.randomUUID(), request = UUID.randomUUID(), gdRequest = UUID.randomUUID();
    UUID gdOperation = UUID.randomUUID(), account = UUID.randomUUID(), tenant = UUID.randomUUID();
    UUID issuanceId = UUID.randomUUID(), jti = UUID.randomUUID(), recentId = UUID.randomUUID();
    String gdDigest = GameTenantCreationDigest.requestDigest("dev", gdRequest, "rollback", "name", null);
    var receipt = new FreshTenantCreationEvidence(1, "dev", gdRequest, gdOperation, gdDigest,
        tenant, 1L, "rollback", "NEW_GAME_ROW", GameTenantCreationDigest.evidenceDigest(
            "dev", gdRequest, gdOperation, gdDigest, tenant, 1L, "rollback", "NEW_GAME_ROW"));
    String authorization = "sha256:" + "a".repeat(64);
    var binding = new GameTenantCreationOriginalOutcomeEvidence.Binding("dev", gdRequest, operation, account, gdDigest, authorization);
    var evidence = new GameTenantCreationOriginalOutcomeEvidence(1, binding,
        GameTenantCreationOriginalOutcomeEvidence.Outcome.CREATED, Optional.of(receipt));
    var terminal = mock(VerifiedTerminal.class);
    when(terminal.binding()).thenReturn(binding);
    when(terminal.evidence()).thenReturn(evidence);
    var intent = mock(OriginalIntent.class);
    when(intent.creationOperationId()).thenReturn(operation);
    when(intent.requestId()).thenReturn(request);
    when(intent.initiatingAccountId()).thenReturn(account);
    when(intent.requestPayload()).thenReturn(new byte[] {1});
    when(intent.requestDigest()).thenReturn("sha256:" + "b".repeat(64));
    when(intent.sourceGameTenantKey()).thenReturn("rollback");
    var intents = mock(AccountTenantCreationIntentRepository.class);
    when(intents.readOriginal(operation)).thenReturn(Optional.of(intent));
    var admitted = mock(Admitted.class);
    var admission = new AccountTenantCreationAdmissionBinding(request, operation, issuanceId, account,
        "dev", gdRequest, gdDigest, "sha256:" + "b".repeat(64), "c".repeat(64), recentId,
        "d".repeat(64), 2000000000000L, 1900000000000L, 1899999900000L, 1899999990000L, 1900086390000L);
    byte[] admissionPayload = admission.canonicalReceipt();
    when(admitted.binding()).thenReturn(admission);
    when(admitted.receiptPayload()).thenReturn(admissionPayload);
    when(admitted.receiptDigest()).thenReturn("admission");
    var admissions = mock(AccountTenantCreationAdmissionRepository.class);
    when(admissions.readAdmitted(operation)).thenReturn(Optional.of(admitted));
    var holder = mock(AccountTenantCreationSourceOrderBinding.class);
    when(holder.intentPayload()).thenReturn(new byte[] {1});
    when(holder.intentDigest()).thenReturn("sha256:" + "b".repeat(64));
    when(holder.admissionPayload()).thenReturn(admissionPayload);
    when(holder.admissionDigest()).thenReturn("admission");
    when(holder.outcomeBinding()).thenReturn(binding);
    when(holder.creationOperationId()).thenReturn(operation);
    when(holder.authorizationDigest()).thenReturn(authorization);
    when(holder.canonicalBytes()).thenReturn(new byte[] {2});
    var orders = mock(AccountTenantCreationSourceOrderRepository.class);
    when(orders.readOriginal(operation)).thenReturn(Optional.of(holder));
    when(orders.lockOriginalForCompletionLocked(any(), any(), any())).thenReturn(holder);
    Record row = mock(Record.class);
    when(row.get("authorization_scope", String.class)).thenReturn("ACCOUNT");
    when(row.get("operation_id", UUID.class)).thenReturn(issuanceId);
    when(row.get("account_uuid", UUID.class)).thenReturn(account);
    when(row.get("token_jti", UUID.class)).thenReturn(jti);
    when(row.get("token_hash", String.class)).thenReturn("d".repeat(64));
    when(row.get("issued_at_epoch_second", Long.class)).thenReturn(1L);
    when(row.get("expires_at_epoch_second", Long.class)).thenReturn(1900000000L);
    when(row.get("recovery_expires_at", java.time.OffsetDateTime.class)).thenReturn(Instant.EPOCH.atOffset(ZoneOffset.UTC));
    byte[] signer = AccountControlUiAuthority.canonical(Map.of("kid", "kid", "publicKeyFingerprint", "fingerprint"));
    when(row.get("signer_receipt", byte[].class)).thenReturn(signer);
    when(row.get("claims_payload", byte[].class)).thenReturn(new byte[] {3});
    var issuance = new AccountControlUiIssuanceRepository.Stored(row);
    var issuances = mock(AccountControlUiIssuanceRepository.class);
    when(issuances.decodeStored(row)).thenReturn(issuance);
    when(dsl.fetchOne("SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ?", issuanceId)).thenReturn(row);
    var actors = mock(AccountControlUiActorService.class);
    when(actors.readAcceptedSignerKey("kid")).thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return new AccountControlUiActorService.AcceptedSignerKey("kid", "fingerprint");
    });
    var captured = mock(AccountTenantCreationOwnerSourceCapture.Captured.class);
    when(captured.originalClaims()).thenReturn(issuance.claims);
    when(captured.originalSignerReceipt()).thenReturn(signer);
    when(captured.issuanceOperationId()).thenReturn(issuanceId);
    when(captured.tokenJti()).thenReturn(jti);
    when(captured.tokenHash()).thenReturn("d".repeat(64));
    when(captured.sources()).thenReturn(List.of());
    var capture = mock(AccountTenantCreationOwnerSourceCapture.class);
    when(capture.captureLocked(any(), any(), any(), any())).thenReturn(captured);
    var transaction = mock(Record.class);
    when(transaction.get("owner_transaction", String.class)).thenReturn("transaction");
    when(dsl.fetchOne("SELECT txid_current()::text AS owner_transaction")).thenReturn(transaction);
    var bootstrap = mock(AccountTenantCreationBootstrapService.class);
    when(bootstrap.bootstrapForCompletion(any(), any())).thenAnswer(invocation -> {
      assertThat(actual.fetchCount(DSL.table("account_fresh_tenant_identity_associations"))).isEqualTo(1);
      assertThat(actual.fetchCount(DSL.table("account_authority_generations"),
          DSL.field("tenant_uuid").eq(tenant))).isEqualTo(1);
      throw new IllegalStateException("Controlled downstream bootstrap refusal");
    });
    var owner = new AccountTenantCreationCompletionOwner(dsl, intents, admissions, orders, issuances,
        actors, capture, new FreshTenantIdentityAssociationRepository(actual, "dev"),
        new AccountAuthorityGenerationRepository(actual), bootstrap,
        mock(IndividualCreatorPartyRepository.class), mock(AccountInitialTenantBillingOwnerRepository.class),
        mock(AccountRecentReauthenticationService.class), mock(AccountAuditOutboxRepository.class), manager);
    assertThatThrownBy(() -> owner.complete(operation, terminal, mock(CapturedEnvironmentBoundary.class),
        mock(AccountRecentReauthenticationEvidence.class)))
        .isInstanceOf(IllegalStateException.class).hasMessage("Controlled downstream bootstrap refusal");
    assertThat(actual.fetchCount(DSL.table("account_fresh_tenant_identity_associations"))).isZero();
    assertThat(actual.fetchCount(DSL.table("account_canonical_tenant_identity_claims"))).isZero();
    assertThat(actual.fetchCount(DSL.table("account_authority_generations"), DSL.field("tenant_uuid").eq(tenant))).isZero();
    assertThat(actual.fetchCount(DSL.table("account_tenant_creation_completions"))).isZero();
  }
  @Test
  void fullStoredOwnerCompletionCommitsAllReceiptsAndExactReplayWithoutReconsumption() {
    StorageContext c = storageContext();
    var fixture = storageFixture(c);
    var audit = new AccountAuditOutboxRepository(c.dsl);
    var assembled = assembleStoredOwner(c, fixture, audit);
    var result = assembled.owner.complete(fixture.intent.creationOperationId(), assembled.terminal,
        assembled.environment, assembled.recent);
    assertAtomicRows(c, 1);
    assertThat(c.dsl.fetchCount(DSL.table("account_recent_reauthentication_consumptions"))).isEqualTo(1);
    assertThat(c.dsl.fetchCount(DSL.table("account_tenant_creation_source_order_settlements"))).isEqualTo(1);
    String initialXmin = Objects.requireNonNull(c.dsl.fetchOne(
        "SELECT xmin::text AS xmin FROM account_tenant_creation_completions WHERE creation_operation_id = ?",
        fixture.intent.creationOperationId())).get("xmin", String.class);
    var replay = assembled.owner.complete(fixture.intent.creationOperationId(), assembled.terminal,
        assembled.environment, assembled.recent);
    assertThat(replay.payload()).isEqualTo(result.payload());
    assertThat(replay.digest()).isEqualTo(result.digest());
    assertAtomicRows(c, 1);
    assertThat(c.dsl.fetchCount(DSL.table("account_recent_reauthentication_consumptions"))).isEqualTo(1);
    assertThat(Objects.requireNonNull(c.dsl.fetchOne(
        "SELECT xmin::text AS xmin FROM account_tenant_creation_completions WHERE creation_operation_id = ?",
        fixture.intent.creationOperationId())).get("xmin", String.class)).isEqualTo(initialXmin);
    assertThat(c.dsl.fetchCount(DSL.table("account_audit_outbox"))).isEqualTo(2);
  }

  @Test
  void failureAfterActualOwnerReauthAndAuditWritesRollsBackEveryNewTenantConstituent() {
    StorageContext c = storageContext();
    var fixture = storageFixture(c);
    var audits = org.mockito.Mockito.spy(new AccountAuditOutboxRepository(c.dsl));
    org.mockito.Mockito.doAnswer(invocation -> {
      invocation.callRealMethod();
      assertAtomicRowsBeforeFinal(c);
      assertThat(c.dsl.fetchCount(DSL.table("account_recent_reauthentication_consumptions"))).isEqualTo(1);
      assertThat(c.dsl.fetchCount(DSL.table("account_audit_outbox"))).isEqualTo(2);
      throw new IllegalStateException("Controlled failure after actual final audit insertion");
    }).when(audits).appendCanonicalTenant(any(), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.eq("ACCOUNT_TENANT_CREATION_COMPLETED"), org.mockito.ArgumentMatchers.anyString());
    var assembled = assembleStoredOwner(c, fixture, audits);
    assertThatThrownBy(() -> assembled.owner.complete(fixture.intent.creationOperationId(), assembled.terminal,
        assembled.environment, assembled.recent)).isInstanceOf(IllegalStateException.class)
        .hasMessage("Controlled failure after actual final audit insertion");
    assertAtomicRows(c, 0);
    assertThat(c.dsl.fetchCount(DSL.table("account_recent_reauthentication_consumptions"))).isZero();
    assertThat(c.dsl.fetchCount(DSL.table("account_tenant_creation_source_order_settlements"))).isZero();
    assertThat(c.dsl.fetchCount(DSL.table("account_audit_outbox"))).isZero();
    assertThat(c.dsl.fetchCount(DSL.table("account_authority_generations"), DSL.field("scope_kind").in("TENANT", "MEMBERSHIP"))).isZero();
    assertThat(c.dsl.fetchCount(DSL.table("account_tenant_creation_source_orders"))).isEqualTo(1);
  }

  @Test
  void storedDispatchClaimIsIndependentlyReadBackAndCannotBeRecoveredAsAnotherSend() {
    StorageContext c = storageContext();
    var fixture = storageFixture(c);
    var assembled = assembleStoredOwner(c, fixture, new AccountAuditOutboxRepository(c.dsl));
    var first = assembled.dispatches.claim(assembled.prepared, assembled.environment, assembled.recent).orElseThrow();
    Record row = Objects.requireNonNull(c.dsl.fetchOne(
        "SELECT *, xmin::text AS original_xmin FROM account_tenant_creation_dispatch_intents WHERE creation_operation_id = ?",
        fixture.intent.creationOperationId()));
    assertThat(row.get("dispatch_call_id", UUID.class)).isEqualTo(first.callId());
    assertThat(row.get("valid_before_epoch_millis", Long.class)).isEqualTo(first.validBefore());
    assertThat(row.get("authorization_binding_payload", byte[].class)).isEqualTo(assembled.prepared.sourceOrderBinding());
    // Discarding the first-send value models losing it before/after a send. No later claim renews it.
    assertThat(assembled.dispatches.claim(assembled.prepared, assembled.environment, assembled.recent)).isEmpty();
    assertThat(assembled.dispatches.hasClaim(fixture.intent.creationOperationId())).isTrue();
    Record retained = Objects.requireNonNull(c.dsl.fetchOne(
        "SELECT *, xmin::text AS original_xmin FROM account_tenant_creation_dispatch_intents WHERE creation_operation_id = ?",
        fixture.intent.creationOperationId()));
    assertThat(retained).isEqualTo(row);
    assertThat(c.dsl.fetchCount(DSL.table("account_tenant_creation_dispatch_intents"))).isEqualTo(1);
    assertThat(c.dsl.fetchCount(DSL.table("account_tenant_creation_completions"))).isZero();
    assertThat(c.dsl.fetchCount(DSL.table("account_tenant_creation_source_order_settlements"))).isZero();
  }

  @Test
  void expiredDispatchCommitRollsBackWithoutConsumingItsOnlyInitialClaim() {
    StorageContext c = storageContext();
    var fixture = storageFixture(c);
    var assembled = assembleStoredOwner(c, fixture, new AccountAuditOutboxRepository(c.dsl));
    assertThatThrownBy(() -> c.tx(() -> c.dsl.execute(
        "INSERT INTO account_tenant_creation_dispatch_intents"
            + " (creation_operation_id, dispatch_call_id, authorization_binding_payload, authorization_digest, valid_before_epoch_millis)"
            + " VALUES (?, ?, ?, ?, ?)", fixture.intent.creationOperationId(), UUID.randomUUID(),
        assembled.prepared.sourceOrderBinding(), assembled.prepared.authorizationDigest(), System.currentTimeMillis() - 1)))
        .isInstanceOf(RuntimeException.class);
    assertThat(c.dsl.fetchCount(DSL.table("account_tenant_creation_dispatch_intents"))).isZero();
    assertThat(assembled.dispatches.claim(assembled.prepared, assembled.environment, assembled.recent)).isPresent();
    assertThat(c.dsl.fetchCount(DSL.table("account_tenant_creation_dispatch_intents"))).isEqualTo(1);
  }

  private static void assertAtomicRowsBeforeFinal(StorageContext c) {
    for (String table : List.of("account_fresh_tenant_identity_associations", "account_tenant_creation_bootstrap_operations",
        "account_fresh_creator_party_association_operations", "account_initial_tenant_billing_owner_operations",
        "account_tenant_billing_owners", "account_tenant_membership")) {
      assertThat(c.dsl.fetchCount(DSL.table(table))).as(table).isEqualTo(1);
    }
  }

  private static void assertAtomicRows(StorageContext c, int expected) {
    for (String table : List.of("account_fresh_tenant_identity_associations", "account_tenant_creation_bootstrap_operations",
        "account_fresh_creator_party_association_operations", "account_initial_tenant_billing_owner_operations",
        "account_tenant_billing_owners", "account_tenant_membership", "account_tenant_creation_completions")) {
      assertThat(c.dsl.fetchCount(DSL.table(table))).as(table).isEqualTo(expected);
    }
  }

  /** All constituent repositories and source/admission/intent rows are real; authority observations remain controlled. */
  private static StoredOwner assembleStoredOwner(StorageContext c, StorageFixture fixture,
      AccountAuditOutboxRepository audit) {
    var dsl = c.dsl;
    var intent = fixture.intent;
    var recent = c.tx(() -> c.evidenceRepository.findByCreationOperation(intent.creationOperationId()).orElseThrow());
    var party = new IndividualCreatorPartySource(intent.creatorPartyId(), intent.initiatingAccountId(),
        IndividualCreatorPartySource.VerificationStatus.VERIFIED, 1L, "test-legal-policy", 1L,
        "test-controlled-verification", 1L, 1L);
    byte[] partyPayload = CreatorPartyEncoding.party(party);
    c.tx(() -> dsl.execute("INSERT INTO account_individual_creator_party_sources"
        + " (creator_party_id, account_uuid, verification_status, identity_version, policy_reference, policy_version,"
        + " verification_evidence_reference, verification_evidence_version, source_version, source_payload, source_digest)"
        + " VALUES (?, ?, 'VERIFIED', 1, ?, 1, ?, 1, 1, ?, ?)", party.creatorPartyId(), party.accountId(),
        party.policyReference(), party.verificationEvidenceReference(), partyPayload, CreatorPartyEncoding.digest(partyPayload)));
    List<SourceEvidence> sources = controlledSources(c, fixture);
    seedSerializationRows(c, sources);
    var orderRepository = new AccountTenantCreationSourceOrderRepository(dsl, new DraftAuthorizationFenceRepository(dsl));
    var holder = c.tx(() -> orderRepository.acquireLocked(intent, fixture.admitted, sources));
    UUID tenant = UUID.randomUUID(), gdOperation = UUID.randomUUID();
    var receipt = new FreshTenantCreationEvidence(1, intent.targetNamespace(), intent.gameDesignCreationRequestId(),
        gdOperation, intent.gameDesignRequestDigest(), tenant, 19L, intent.sourceGameTenantKey(), "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(intent.targetNamespace(), intent.gameDesignCreationRequestId(), gdOperation,
            intent.gameDesignRequestDigest(), tenant, 19L, intent.sourceGameTenantKey(), "NEW_GAME_ROW"));
    var evidence = new GameTenantCreationOriginalOutcomeEvidence(1, holder.outcomeBinding(),
        GameTenantCreationOriginalOutcomeEvidence.Outcome.CREATED, Optional.of(receipt));
    var terminal = mock(VerifiedTerminal.class);
    when(terminal.binding()).thenReturn(evidence.binding());
    when(terminal.evidence()).thenReturn(evidence);
    var issuances = new AccountControlUiIssuanceRepository(dsl);
    var issuance = c.tx(() -> issuances.findToken(fixture.admitted.binding().tokenHash()));
    var accepted = mock(AccountControlUiActorService.class);
    when(accepted.readAcceptedSignerKey("test-key")).thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return new AccountControlUiActorService.AcceptedSignerKey("test-key", "test-fingerprint");
    });
    var captured = mock(AccountTenantCreationOwnerSourceCapture.Captured.class);
    when(captured.originalClaims()).thenReturn(issuance.claims);
    when(captured.originalSignerReceipt()).thenReturn(issuance.signerReceipt);
    when(captured.issuanceOperationId()).thenReturn(issuance.operationId);
    when(captured.tokenJti()).thenReturn(issuance.jti);
    when(captured.tokenHash()).thenReturn(issuance.tokenHash);
    when(captured.sources()).thenReturn(sources);
    when(captured.reauthentication()).thenReturn(recent);
    var currentness = mock(net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.EnvironmentBoundCurrentness.class);
    var terms = mock(net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CurrentnessEvidence.class);
    when(currentness.terms()).thenReturn(terms);
    when(captured.currentness()).thenReturn(currentness);
    var capture = mock(AccountTenantCreationOwnerSourceCapture.class);
    when(capture.captureLocked(any(), any(), any(), any())).thenReturn(captured);
    var identities = new FreshTenantIdentityAssociationRepository(dsl, intent.targetNamespace());
    var generations = new AccountAuthorityGenerationRepository(dsl);
    var authorityOutbox = new AccountAuthorityOutboxRepository(dsl);
    var authoritySources = new AccountAuthoritySourceEvidenceRepository(dsl, generations, authorityOutbox);
    var pairs = new AccountMembershipPairAuthorityRepository(dsl);
    var memberships = new AccountTenantMembershipRepository(dsl, c.accounts, identities, pairs);
    var roles = new AccountTenantMembershipRoleSnapshotRepository(dsl);
    var bootstrapOperations = new AccountTenantCreationBootstrapOperationRepository(dsl);
    var creatorSources = new AccountCreatorMembershipSourceReader(c.accounts, new AccountJoinOperationRepository(dsl),
        memberships, pairs, roles, identities, generations, authorityOutbox, authoritySources, bootstrapOperations);
    var bootstraps = new AccountTenantCreationBootstrapService(c.accounts, identities, creatorSources, pairs,
        memberships, roles, authorityOutbox, audit, bootstrapOperations,
        new StaticListableBeanFactory().getBeanProvider(AccountTenantCreationBootstrapAuthorizationSource.class));
    var owner = new AccountTenantCreationCompletionOwner(dsl,
        new AccountTenantCreationIntentRepository(dsl, c.evidenceRepository),
        new AccountTenantCreationAdmissionRepository(dsl), orderRepository, issuances, accepted, capture,
        identities, generations, bootstraps, new IndividualCreatorPartyRepository(dsl, identities, bootstrapOperations),
        new AccountInitialTenantBillingOwnerRepository(dsl), c.service, audit, c.manager);
    var dispatches = new AccountTenantCreationDispatchRepository(dsl,
        new AccountTenantCreationIntentRepository(dsl, c.evidenceRepository),
        new AccountTenantCreationAdmissionRepository(dsl), orderRepository, issuances, accepted, capture, c.manager);
    var prepared = mock(AccountTenantCreationAdmissionOwner.PreparedResult.class);
    var admittedBinding = fixture.admitted.binding();
    byte[] holderBytes = holder.canonicalBytes();
    String authorizationDigest = holder.authorizationDigest();
    when(prepared.admission()).thenReturn(admittedBinding);
    when(prepared.sourceOrderBinding()).thenReturn(holderBytes);
    when(prepared.authorizationDigest()).thenReturn(authorizationDigest);
    return new StoredOwner(owner, terminal, mock(CapturedEnvironmentBoundary.class), recent, dispatches, prepared);
  }

  private record StoredOwner(AccountTenantCreationCompletionOwner owner, VerifiedTerminal terminal,
      CapturedEnvironmentBoundary environment, AccountRecentReauthenticationEvidence recent,
      AccountTenantCreationDispatchRepository dispatches, AccountTenantCreationAdmissionOwner.PreparedResult prepared) {}

  private static void seedSerializationRows(StorageContext context, List<SourceEvidence> sources) {
    context.tx(
        () -> {
          for (SourceEvidence source : sources) {
            context.dsl.execute(
                "INSERT INTO account_draft_authorization_source_locks(source_key) VALUES (?) "
                    + "ON CONFLICT DO NOTHING",
                source.key());
          }
          return null;
        });
  }

  private static List<SourceEvidence> controlledSources(StorageContext context, StorageFixture fixture) {
    List<SourceEvidence> policySources =
        context.tx(
            () ->
                new AccountRecentReauthenticationPolicyRepository(context.dsl)
                    .resolveCurrentSourcesForGameCreation()
                    .sources());
    UUID actor = fixture.intent().initiatingAccountId();
    UUID creatorParty = fixture.intent().creatorPartyId();
    UUID hostedScope = UUID.randomUUID();
    List<SourceEvidence> sources =
        new ArrayList<>(
            List.of(
                controlled(
                    SourceKind.ISSUER, "firemud-account-service", "issuer controlled fixture"),
                controlled(SourceKind.ACCOUNT, actor.toString(), "account controlled fixture"),
                controlled(
                    SourceKind.GLOBAL_ROLES, actor.toString(), "global roles controlled fixture"),
                controlled(
                    SourceKind.CREATOR_PARTY,
                    creatorParty.toString(),
                    "creator party controlled fixture"),
                controlled(
                    SourceKind.HOSTED_TERMS,
                    "environment-boundary/test-environment",
                    "hosted environment controlled fixture"),
                controlled(
                    SourceKind.HOSTED_TERMS,
                    hostedScope.toString(),
                    "hosted scope controlled fixture")));
    sources.addAll(policySources);
    return sources.stream().sorted(java.util.Comparator.comparing(SourceEvidence::key)).toList();
  }

  private static SourceEvidence controlled(SourceKind kind, String scope, String value) {
    return new SourceEvidence(
        kind,
        scope,
        kind == SourceKind.ISSUER || kind == SourceKind.ACCOUNT || kind == SourceKind.GLOBAL_ROLES
            ? "1"
            : null,
        "1",
        kind == SourceKind.ISSUER || kind == SourceKind.ACCOUNT || kind == SourceKind.GLOBAL_ROLES
            ? "test-controlled-checkpoint"
            : null,
        kind == SourceKind.ISSUER || kind == SourceKind.ACCOUNT || kind == SourceKind.GLOBAL_ROLES
            ? "1"
            : null,
        value.getBytes(StandardCharsets.UTF_8));
  }

  private static StorageFixture storageFixture(StorageContext context) {
    Account account = context.account();
    return storageFixture(context, account, UUID.randomUUID());
  }

  private static StorageFixture storageFixture(StorageContext context, Account account, UUID creatorPartyId) {
    UUID creationOperationId = UUID.randomUUID();
    var reauthentication =
        context.service.reauthenticate(
            context.request(account, UUID.randomUUID(), creationOperationId));
    var intent =
        context.tx(
            () ->
                new AccountTenantCreationIntentRepository(context.dsl, context.evidenceRepository)
                    .claim(
                        new AccountTenantCreationIntentRepository.Request(
                            UUID.randomUUID(),
                            creationOperationId,
                            UUID.randomUUID(),
                            account.getAccountUuid(),
                            "test-game",
                            "source-game",
                            "Game",
                            "controlled source-order test",
                            reauthentication.evidenceId(),
                            creatorPartyId,
                            UUID.randomUUID(),
                            1,
                            true)));

    UUID issuanceOperationId = UUID.randomUUID();
    String tokenHash = "e".repeat(64);
    byte[] activeRegistry = "test-only-active-registry".getBytes(StandardCharsets.UTF_8);
    long issuedAt = Instant.now().getEpochSecond();
    long tokenExpiry = (issuedAt + 300) * 1000;
    long preparedAt = issuedAt * 1000;
    long operationDeadline = tokenExpiry;
    long cleanupDeadline = tokenExpiry + 300_000;
    long receiptDeadline = operationDeadline + 86_400_000;
    context.tx(
        () ->
            context.dsl.execute(
                "INSERT INTO account_control_ui_issuance_operations "
                    + "(request_id, operation_id, token_jti, account_uuid, tenant_uuid, authorization_scope, caller_workload, "
                    + "caller_context_id, request_mac_key_id, request_digest, claims_payload, source_payload, bundle_payload, signer_receipt, "
                    + "issued_at_epoch_second, expires_at_epoch_second, registry_cleanup_deadline_epoch_millis, recovery_expires_at, status) "
                    + "VALUES (?, ?, ?, ?, NULL, 'ACCOUNT', 'spiffe://test-only/source-order', ?, 'test-mac', ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, 'PREPARED')",
                UUID.randomUUID(),
                issuanceOperationId,
                UUID.randomUUID(),
                account.getAccountUuid(),
                UUID.randomUUID(),
                "c".repeat(64),
                activeRegistry,
                activeRegistry,
                activeRegistry,
                AccountControlUiAuthority.canonical(Map.of("kid", "test-key", "publicKeyFingerprint", "test-fingerprint")),
                issuedAt,
                issuedAt + 300,
                cleanupDeadline,
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(issuedAt + 60), ZoneOffset.UTC)));
    context.tx(
        () ->
            context.dsl.execute(
                "UPDATE account_control_ui_issuance_operations SET status = 'CANDIDATE', token_hash = ?, "
                    + "pending_registry = ?, active_registry = ? WHERE operation_id = ?",
                tokenHash,
                activeRegistry,
                activeRegistry,
                issuanceOperationId));
    context.tx(
        () ->
            context.dsl.execute(
                "INSERT INTO account_control_ui_response_envelopes "
                    + "(operation_id, encrypted_response, owner_binding, recovery_expires_at) "
                    + "SELECT operation_id, ?, ?, recovery_expires_at "
                    + "FROM account_control_ui_issuance_operations WHERE operation_id = ?",
                activeRegistry,
                activeRegistry,
                issuanceOperationId));
    context.tx(
        () ->
            context.dsl.execute(
                "UPDATE account_control_ui_issuance_operations SET status = 'COMMITTED', "
                    + "pending_receipt = ?, committed_at = CURRENT_TIMESTAMP WHERE operation_id = ?",
                activeRegistry,
                issuanceOperationId));

    var admissionBinding =
        new AccountTenantCreationAdmissionBinding(
            intent.requestId(),
            creationOperationId,
            issuanceOperationId,
            account.getAccountUuid(),
            intent.targetNamespace(),
            intent.gameDesignCreationRequestId(),
            intent.gameDesignRequestDigest(),
            intent.requestDigest(),
            AccountControlUiCoordination.digest("SHA-256", activeRegistry),
            reauthentication.evidenceId(),
            tokenHash,
            cleanupDeadline,
            tokenExpiry,
            preparedAt,
            operationDeadline,
            receiptDeadline);
    context.tx(
        () ->
            context.dsl.execute(
                "INSERT INTO account_tenant_creation_admission_requests "
                    + "(creation_operation_id, intent_request_id, issuance_operation_id, actor_account_id, target_namespace, "
                    + "game_design_creation_request_id, game_design_request_digest, intent_digest, active_registry_digest, "
                    + "reauthentication_evidence_id, token_hash, token_cleanup_deadline_epoch_millis, token_expires_at_epoch_millis, "
                    + "prepared_at_epoch_millis, operation_deadline_epoch_millis, receipt_cleanup_deadline_epoch_millis, binding_payload, binding_digest) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                creationOperationId,
                intent.requestId(),
                issuanceOperationId,
                account.getAccountUuid(),
                admissionBinding.targetNamespace(),
                admissionBinding.gameDesignCreationRequestId(),
                admissionBinding.gameDesignRequestDigest(),
                admissionBinding.intentDigest(),
                admissionBinding.activeRegistryDigest(),
                reauthentication.evidenceId(),
                tokenHash,
                cleanupDeadline,
                tokenExpiry,
                preparedAt,
                operationDeadline,
                receiptDeadline,
                admissionBinding.canonicalReceipt(),
                AccountControlUiCoordination.digest(
                    "SHA-256", admissionBinding.canonicalReceipt())));
    // Physical AOF/replica/qualification values are controlled test facts. The private Admitted
    // value is still obtained only by reading the actual V1022 durable rows.
    context.tx(
        () ->
            context.dsl.execute(
                "INSERT INTO account_tenant_creation_admissions "
                    + "(creation_operation_id, receipt_payload, receipt_digest, local_aof_count, replica_aof_count, "
                    + "protected_binding_id, qualification_fence) VALUES (?, ?, ?, 1, 1, 'test-stipulated-physical-evidence', 7)",
                creationOperationId,
                admissionBinding.canonicalReceipt(),
                AccountControlUiCoordination.digest(
                    "SHA-256", admissionBinding.canonicalReceipt())));
    var admitted =
        context.tx(
            () ->
                new AccountTenantCreationAdmissionRepository(context.dsl)
                    .readAdmitted(creationOperationId)
                    .orElseThrow());
    return new StorageFixture(account, intent, admitted);
  }

  private static StorageContext storageContext() {
    String schema = "creation_source_order_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(source);
    var transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.executeWithoutResult(
        ignored -> {
          dsl.execute(
              "INSERT INTO account_recent_reauthentication_policy_values "
                  + "(policy_scope, policy_version, recent_age_seconds, max_clock_skew_seconds) "
                  + "VALUES ('PLATFORM', 1, 300, 30), ('DEPLOYMENT', 1, 300, 30)");
          dsl.execute(
              "INSERT INTO account_recent_reauthentication_policy_current "
                  + "(policy_scope, policy_version) VALUES ('PLATFORM', 1), ('DEPLOYMENT', 1)");
        });
    var generations = new AccountAuthorityGenerationRepository(dsl);
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    var sourceEvidence = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
    var accounts = new AccountRepository(dsl, sourceEvidence);
    var challenges = new AccountEmailLoginChallengeRepository(dsl);
    var primary =
        new AccountServiceImpl(
            accounts,
            generations,
            sourceEvidence,
            outbox,
            mock(AccountPasswordResetOperationRepository.class),
            mock(AccountLogoutAllOperationRepository.class),
            mock(AccountSecurityStateOperationRepository.class),
            mock(AccountPasswordResetDraftSourceChangeRepository.class),
            mock(AccountAuditOutboxRepository.class),
            mock(AccountConnectScopeRepository.class),
            mock(AccountJoinOperationRepository.class),
            challenges,
            null,
            null,
            Mappers.getMapper(AccountMapper.class),
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
            null,
            null,
            null,
            null,
            null,
            null,
            manager);
    var evidence = new AccountRecentReauthenticationRepository(dsl);
    var policies = new AccountRecentReauthenticationPolicyRepository(dsl);
    var service =
        new AccountRecentReauthenticationService(
            primary, accounts, generations, evidence, policies, manager, Clock.systemUTC());
    return new StorageContext(dsl, accounts, evidence, service, transaction, manager);
  }

  private static String hash(String secret) {
    char[] chars = secret.toCharArray();
    var argon = Argon2Factory.create();
    try {
      return argon.hash(2, 4096, 1, chars);
    } finally {
      argon.wipeArray(chars);
    }
  }

  private record StorageFixture(
      Account account,
      AccountTenantCreationIntentRepository.OriginalIntent intent,
      AccountTenantCreationAdmissionRepository.Admitted admitted) {}

  private record StorageContext(
      DSLContext dsl,
      AccountRepository accounts,
      AccountRecentReauthenticationRepository evidenceRepository,
      AccountRecentReauthenticationService service,
      TransactionTemplate transaction,
      org.springframework.transaction.PlatformTransactionManager manager) {
    <T> T tx(Supplier<T> action) {
      return transaction.execute(ignored -> action.get());
    }

    Account account() {
      Account account = new Account();
      String suffix = UUID.randomUUID().toString();
      account.setUsername("creation-source-order-" + suffix);
      account.setEmail(suffix + "@example.test");
      account.setPasswordHash(hash(PASSWORD));
      account.setRole("player");
      account.setLoginAuthModes("PASSWORD");
      account.setLifecycleState(AccountLifecycleState.ACTIVE);
      return tx(() -> accounts.save(account));
    }

    AccountRecentReauthenticationService.Request request(
        Account account, UUID requestId, UUID operationId) {
      return new AccountRecentReauthenticationService.Request(
          requestId,
          account.getAccountUuid(),
          AccountRecentReauthenticationPolicy.PURPOSE,
          operationId,
          account.getEmail(),
          PASSWORD);
    }
  }


}
