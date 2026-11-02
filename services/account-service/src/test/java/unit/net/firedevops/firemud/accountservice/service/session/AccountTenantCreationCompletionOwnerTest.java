package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.client.GameDesignOriginalTenantCreationOutcomeClient.VerifiedTerminal;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.dto.AccountTenantCreationBootstrapResult;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
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
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled owner composition, not physical SQL rollback, source authority, or mTLS proof. */
class AccountTenantCreationCompletionOwnerTest {
  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactCompletionUsesActualRetainedConsentAndReplaysWithoutNewWritesOrConsumption()
      throws Exception {
    var f = new Fixture();
    var result = f.complete();
    assertThat(result.digest()).startsWith("sha256:");
    InOrder order =
        Mockito.inOrder(
            f.identities,
            f.generations,
            f.bootstraps,
            f.parties,
            f.owners,
            f.reauth,
            f.audits,
            f.orders);
    order.verify(f.identities).importVerified(f.receipt);
    order.verify(f.identities).read(f.tenant);
    order.verify(f.generations).initializeTenantIfAbsent(f.tenant);
    order.verify(f.bootstraps).bootstrapForCompletion(any(), any());
    order.verify(f.parties).readIndividualSource(f.partyId, f.account);
    order.verify(f.parties).associateFresh(any(), any(), any());
    order.verify(f.parties).readInitialAssociation(any(), any(), any());
    order
        .verify(f.owners)
        .establishInitial(
            Mockito.argThat(
                request ->
                    request.requestId().equals(f.request)
                        && request.consentOperationId().equals(f.consent)
                        && request.consentStatementVersion() == 7
                        && request.affirmativeConsent()));
    order
        .verify(f.reauth)
        .validateAndConsumeLocked(
            f.recent, f.account, AccountRecentReauthenticationPolicy.PURPOSE, f.operation);
    order.verify(f.audits).appendCanonicalTenant(any(), anyString(), anyString(), anyString());
    order.verify(f.orders).settleLocked(f.holder, f.terminal);
    clearInvocations(
        f.identities,
        f.generations,
        f.bootstraps,
        f.parties,
        f.owners,
        f.reauth,
        f.audits,
        f.actors);
    var replay = f.complete();
    assertThat(replay.payload()).isEqualTo(result.payload());
    verifyNoInteractions(
        f.identities,
        f.generations,
        f.bootstraps,
        f.parties,
        f.owners,
        f.reauth,
        f.audits,
        f.actors);
    byte[] exposed = replay.payload();
    exposed[0] ^= 1;
    assertThat(replay.payload()).isEqualTo(result.payload());
  }

  @Test
  void currentOwnerRefusalIncludingExpiredReauthCannotPerformAnyCreatorWrites() throws Exception {
    var f = new Fixture();
    when(f.capture.captureLocked(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("Current bounds expired"));
    assertThatThrownBy(f::complete).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(
        f.identities, f.generations, f.bootstraps, f.parties, f.owners, f.reauth, f.audits);
    assertThat(f.resultRow).isNull();
  }

  @Test
  void consumedMarkerWithoutDurableFinalResultCannotAuthorizeFreshCompletion() throws Exception {
    var f = new Fixture();
    when(f.consumption.disposition())
        .thenReturn(AccountRecentReauthenticationService.Disposition.EXACT_REPLAY);
    assertThatThrownBy(f::complete).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(f.audits);
    verify(f.orders, never()).settleLocked(any(), any());
    assertThat(f.resultRow).isNull();
  }

  @Test
  void notCreatedAndMismatchedOriginalCannotReachCurrentCaptureOrWrites() throws Exception {
    var f = new Fixture();
    var absent =
        new GameTenantCreationOriginalOutcomeEvidence(
            1,
            f.evidence.binding(),
            GameTenantCreationOriginalOutcomeEvidence.Outcome.NOT_CREATED,
            Optional.empty());
    when(f.terminal.evidence()).thenReturn(absent);
    assertThatThrownBy(f::complete).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(f.capture, f.actors, f.identities, f.owners);
  }

  private static final class Fixture {
    final UUID operation = UUID.randomUUID(),
        request = UUID.randomUUID(),
        account = UUID.randomUUID();
    final UUID gdRequest = UUID.randomUUID(),
        gdOperation = UUID.randomUUID(),
        tenant = UUID.randomUUID();
    final UUID partyId = UUID.randomUUID(),
        consent = UUID.randomUUID(),
        recentId = UUID.randomUUID();
    final UUID issuanceId = UUID.randomUUID(), jti = UUID.randomUUID();
    final DSLContext dsl = mock(DSLContext.class);
    final AccountTenantCreationIntentRepository intents =
        mock(AccountTenantCreationIntentRepository.class);
    final AccountTenantCreationAdmissionRepository admissions =
        mock(AccountTenantCreationAdmissionRepository.class);
    final AccountTenantCreationSourceOrderRepository orders =
        mock(AccountTenantCreationSourceOrderRepository.class);
    final AccountControlUiIssuanceRepository issuances =
        mock(AccountControlUiIssuanceRepository.class);
    final AccountControlUiActorService actors = mock(AccountControlUiActorService.class);
    final AccountTenantCreationOwnerSourceCapture capture =
        mock(AccountTenantCreationOwnerSourceCapture.class);
    final FreshTenantIdentityAssociationRepository identities =
        mock(FreshTenantIdentityAssociationRepository.class);
    final AccountAuthorityGenerationRepository generations =
        mock(AccountAuthorityGenerationRepository.class);
    final AccountTenantCreationBootstrapService bootstraps =
        mock(AccountTenantCreationBootstrapService.class);
    final IndividualCreatorPartyRepository parties = mock(IndividualCreatorPartyRepository.class);
    final AccountInitialTenantBillingOwnerRepository owners =
        mock(AccountInitialTenantBillingOwnerRepository.class);
    final AccountRecentReauthenticationService reauth =
        mock(AccountRecentReauthenticationService.class);
    final AccountAuditOutboxRepository audits = mock(AccountAuditOutboxRepository.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final CapturedEnvironmentBoundary environment = mock(CapturedEnvironmentBoundary.class);
    final AccountRecentReauthenticationEvidence recent =
        mock(AccountRecentReauthenticationEvidence.class);
    final AccountRecentReauthenticationService.Validation consumption =
        mock(AccountRecentReauthenticationService.Validation.class);
    final AccountTenantCreationSourceOrderBinding holder =
        mock(AccountTenantCreationSourceOrderBinding.class);
    final VerifiedTerminal terminal = mock(VerifiedTerminal.class);
    final GameTenantCreationOriginalOutcomeEvidence evidence;
    final FreshTenantCreationEvidence receipt;
    Record resultRow;
    boolean settled;

    Fixture() throws Exception {
      when(manager.getTransaction(any()))
          .thenAnswer(
              invocation -> {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                return mock(TransactionStatus.class);
              });
      doAnswer(
              invocation -> {
                TransactionSynchronizationManager.setActualTransactionActive(false);
                return null;
              })
          .when(manager)
          .commit(any());
      doAnswer(
              invocation -> {
                TransactionSynchronizationManager.setActualTransactionActive(false);
                return null;
              })
          .when(manager)
          .rollback(any());
      var connection = mock(Connection.class);
      when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
      doAnswer(
              invocation -> {
                invocation.<ConnectionRunnable>getArgument(0).run(connection);
                return null;
              })
          .when(dsl)
          .connection(any(ConnectionRunnable.class));
      String gdDigest =
          GameTenantCreationDigest.requestDigest("dev", gdRequest, "new", "name", null);
      receipt =
          new FreshTenantCreationEvidence(
              1,
              "dev",
              gdRequest,
              gdOperation,
              gdDigest,
              tenant,
              1L,
              "new",
              "NEW_GAME_ROW",
              GameTenantCreationDigest.evidenceDigest(
                  "dev", gdRequest, gdOperation, gdDigest, tenant, 1L, "new", "NEW_GAME_ROW"));
      String authorization = "sha256:" + "a".repeat(64);
      var outcomeBinding =
          new GameTenantCreationOriginalOutcomeEvidence.Binding(
              "dev", gdRequest, operation, account, gdDigest, authorization);
      evidence =
          new GameTenantCreationOriginalOutcomeEvidence(
              1,
              outcomeBinding,
              GameTenantCreationOriginalOutcomeEvidence.Outcome.CREATED,
              Optional.of(receipt));
      when(terminal.binding()).thenReturn(outcomeBinding);
      when(terminal.evidence()).thenReturn(evidence);
      var intent = mock(OriginalIntent.class);
      when(intent.creationOperationId()).thenReturn(operation);
      when(intent.requestId()).thenReturn(request);
      when(intent.initiatingAccountId()).thenReturn(account);
      when(intent.creatorPartyId()).thenReturn(partyId);
      when(intent.consentOperationId()).thenReturn(consent);
      when(intent.consentStatementVersion()).thenReturn(7);
      when(intent.affirmativeConsent()).thenReturn(true);
      when(intent.requestPayload()).thenReturn(new byte[] {1});
      when(intent.requestDigest()).thenReturn("sha256:" + "b".repeat(64));
      when(intent.sourceGameTenantKey()).thenReturn("new");
      when(intents.readOriginal(operation)).thenReturn(Optional.of(intent));
      var admitted = mock(Admitted.class);
      var admission =
          new AccountTenantCreationAdmissionBinding(
              request,
              operation,
              issuanceId,
              account,
              "dev",
              gdRequest,
              gdDigest,
              "sha256:" + "b".repeat(64),
              "c".repeat(64),
              recentId,
              "d".repeat(64),
              2000000000000L,
              1900000000000L,
              1899999900000L,
              1899999990000L,
              1900086390000L);
      when(admitted.binding()).thenReturn(admission);
      byte[] admissionPayload = admission.canonicalReceipt();
      when(admitted.receiptPayload()).thenReturn(admissionPayload);
      when(admitted.receiptDigest()).thenReturn("admission");
      when(admissions.readAdmitted(operation)).thenReturn(Optional.of(admitted));
      when(holder.intentPayload()).thenReturn(new byte[] {1});
      when(holder.intentDigest()).thenReturn("sha256:" + "b".repeat(64));
      when(holder.admissionPayload()).thenReturn(admissionPayload);
      when(holder.admissionDigest()).thenReturn("admission");
      when(holder.outcomeBinding()).thenReturn(outcomeBinding);
      when(holder.creationOperationId()).thenReturn(operation);
      when(holder.authorizationDigest()).thenReturn(authorization);
      when(holder.canonicalBytes()).thenReturn(new byte[] {2});
      when(orders.readOriginal(operation)).thenReturn(Optional.of(holder));
      when(orders.lockOriginalForCompletionLocked(any(), any(), any())).thenReturn(holder);
      when(orders.readSettlement(operation))
          .thenAnswer(invocation -> settled ? Optional.of(evidence) : Optional.empty());
      when(orders.settleLocked(holder, terminal))
          .thenAnswer(
              invocation -> {
                settled = true;
                return evidence;
              });
      Record row = mock(Record.class);
      when(row.get("authorization_scope", String.class)).thenReturn("ACCOUNT");
      when(row.get("operation_id", UUID.class)).thenReturn(issuanceId);
      when(row.get("account_uuid", UUID.class)).thenReturn(account);
      when(row.get("token_jti", UUID.class)).thenReturn(jti);
      when(row.get("token_hash", String.class)).thenReturn("d".repeat(64));
      when(row.get("issued_at_epoch_second", Long.class)).thenReturn(1L);
      when(row.get("expires_at_epoch_second", Long.class)).thenReturn(1900000000L);
      when(row.get("recovery_expires_at", java.time.OffsetDateTime.class))
          .thenReturn(Instant.EPOCH.atOffset(ZoneOffset.UTC));
      byte[] signer =
          AccountControlUiAuthority.canonical(
              Map.of("kid", "kid", "publicKeyFingerprint", "fingerprint"));
      when(row.get("signer_receipt", byte[].class)).thenReturn(signer);
      when(row.get("claims_payload", byte[].class)).thenReturn(new byte[] {3});
      var issuance = new AccountControlUiIssuanceRepository.Stored(row);
      when(issuances.decodeStored(row)).thenReturn(issuance);
      when(dsl.fetchOne(
              "SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ?",
              issuanceId))
          .thenReturn(row);
      when(actors.readAcceptedSignerKey("kid"))
          .thenAnswer(
              invocation -> {
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
      when(captured.reauthentication()).thenReturn(recent);
      when(recent.issuedAt()).thenReturn(Instant.now());
      when(recent.recentAgeSeconds()).thenReturn(300L);
      when(recent.maxClockSkewSeconds()).thenReturn(30L);
      when(recent.evidenceId()).thenReturn(recentId);
      var currentness = mock(AccountHostedTermsService.EnvironmentBoundCurrentness.class);
      var terms = mock(AccountHostedTermsService.CurrentnessEvidence.class);
      when(currentness.terms()).thenReturn(terms);
      when(captured.currentness()).thenReturn(currentness);
      when(capture.captureLocked(any(), any(), any(), any())).thenReturn(captured);
      var generation = mock(AccountAuthorityGenerationRepository.ScopeState.class);
      when(generation.generation()).thenReturn(1L);
      when(generation.sourceVersion()).thenReturn(1L);
      when(generations.initializeTenantIfAbsent(tenant)).thenReturn(generation);
      when(identities.importVerified(receipt)).thenReturn(receipt);
      when(identities.read(tenant)).thenReturn(Optional.of(receipt));
      var bootstrap = mock(AccountTenantCreationBootstrapResult.class);
      when(bootstrap.resultDigest()).thenReturn("bootstrap");
      when(bootstraps.bootstrapForCompletion(any(), any()))
          .thenAnswer(
              invocation -> {
                var creator =
                    invocation
                        .<net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence>
                            getArgument(0);
                var source =
                    invocation.<AccountTenantCreationCompletionOwner.CurrentSource>getArgument(1);
                return source.withCurrentAuthorizationAndSourceParticipation(
                    creator, () -> bootstrap);
              });
      var transaction = mock(Record.class);
      when(transaction.get("owner_transaction", String.class)).thenReturn("transaction");
      when(dsl.fetchOne("SELECT txid_current()::text AS owner_transaction"))
          .thenReturn(transaction);
      var party = mock(IndividualCreatorPartySource.class);
      var association = mock(IndividualCreatorPartyRepository.AssociationReceipt.class);
      when(parties.readIndividualSource(partyId, account)).thenReturn(party);
      when(parties.associateFresh(any(), any(), any())).thenReturn(association);
      when(parties.readInitialAssociation(any(), any(), any())).thenReturn(association);
      var owner = mock(AccountInitialTenantBillingOwnerRepository.InitialBindingResult.class);
      when(owner.ownerGeneration()).thenReturn(UUID.randomUUID());
      when(owner.ownerVersion()).thenReturn(1L);
      when(owner.resultDigest()).thenReturn("owner");
      when(owners.establishInitial(any())).thenReturn(owner);
      when(consumption.disposition())
          .thenReturn(AccountRecentReauthenticationService.Disposition.CONSUMED);
      when(reauth.validateAndConsumeLocked(any(), any(), anyString(), any()))
          .thenReturn(consumption);
      when(audits.appendCanonicalTenant(any(), anyString(), anyString(), anyString()))
          .thenAnswer(
              invocation -> {
                var audit = mock(AccountAuditEnvelope.class);
                String payload = invocation.getArgument(3);
                String digest =
                    "sha256:"
                        + AccountControlUiIssuanceRepository.hash(
                            payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                when(audit.payloadDigest()).thenReturn(digest);
                return audit;
              });
      when(dsl.fetchOne(
              "SELECT * FROM account_tenant_creation_completions WHERE creation_operation_id = ?",
              operation))
          .thenAnswer(invocation -> resultRow);
      when(dsl.execute(anyString(), any(Object[].class)))
          .thenAnswer(
              invocation -> {
                Object[] values = (Object[]) invocation.getRawArguments()[1];
                Map<String, Object> stored = new HashMap<>();
                stored.put("intent_request_id", values[1]);
                stored.put("tenant_uuid", values[2]);
                stored.put("terminal_payload", values[3]);
                stored.put("terminal_digest", values[4]);
                stored.put("result_payload", values[5]);
                stored.put("result_digest", values[6]);
                resultRow = mock(Record.class);
                when(resultRow.get(anyString(), any(Class.class)))
                    .thenAnswer(read -> stored.get(read.getArgument(0)));
                return 1;
              });
    }

    AccountTenantCreationCompletionOwner.Result complete() {
      return new AccountTenantCreationCompletionOwner(
              dsl,
              intents,
              admissions,
              orders,
              issuances,
              actors,
              capture,
              identities,
              generations,
              bootstraps,
              parties,
              owners,
              reauth,
              audits,
              manager)
          .complete(operation, terminal, environment, recent);
    }
  }
}
