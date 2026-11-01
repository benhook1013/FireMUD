package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRepository.Admitted;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.OriginalIntent;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled owner sequencing and finite-bound proof, not physical source authority or custody. */
class AccountTenantCreationCurrentAuthorizationOwnerTest {
  @AfterEach
  void clearTransaction() { TransactionSynchronizationManager.clear(); }

  @Test
  void genuineCurrentCaptureAndIndependentPendingReadbackBoundTheReply() throws Exception {
    var f = new Fixture();
    var result = f.resolve();
    assertThat(result.binding()).isEqualTo(f.binding);
    assertThat(result.validBeforeEpochMillis()).isEqualTo(60000L);
    assertThat(f.commits).isEqualTo(3);
    verify(f.intents, times(3)).readOriginal(f.operation);
    verify(f.capture).captureLocked(f.intent, f.admitted, f.environment, f.recent);
    verify(f.orders).lockOriginalForCompletionLocked(f.intent, f.admitted, List.of());
    verify(f.orders, never()).acquireLocked(any(), any(), any());
    verify(f.actors, never()).withCurrentAccountCommitted(any(), any(), any());
  }

  @Test
  void independentRepositoryReadbacksMayReturnDistinctImmutableEvidenceObjects() throws Exception {
    var f = new Fixture();
    when(f.recentRepository.findByCreationOperation(f.operation))
        .thenAnswer(call -> Optional.of(f.recentEvidence()));
    assertThat(f.resolve().validBeforeEpochMillis()).isEqualTo(60000L);
    assertThat(f.commits).isEqualTo(3);
  }

  @Test
  void alreadySettledOriginalCannotReachCurrentKeyOrOwnerReads() throws Exception {
    var f = new Fixture();
    var terminal = new net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence(1, f.binding,
        net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Outcome.NOT_CREATED, Optional.empty());
    when(f.orders.readSettlement(f.operation)).thenReturn(Optional.of(terminal));
    assertThatThrownBy(f::resolve).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(f.actors, f.capture, f.terms);
  }

  @Test
  void conservativeReauthenticationExpiryStillBoundsAnUnboundedTermsReply() throws Exception {
    var f = new Fixture();
    when(f.currentTerms.validUntil()).thenReturn(null);
    assertThat(f.resolve().validBeforeEpochMillis()).isEqualTo(65000L);
  }

  @Test
  void missingOrSettledOriginalCannotReachCurrentKeyOrOwnerReads() throws Exception {
    var f = new Fixture();
    when(f.orders.readOriginal(f.operation)).thenReturn(Optional.empty());
    assertThatThrownBy(f::resolve).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(f.actors, f.capture, f.terms);
  }

  @Test
  void substitutedBindingCannotReachCurrentKeyOrOwnerReads() throws Exception {
    var f = new Fixture();
    var changed = new Binding("dev", f.gdRequest, f.operation, UUID.randomUUID(), f.gdDigest, f.authDigest);
    assertThatThrownBy(() -> f.owner.resolve(changed)).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(f.actors, f.capture, f.terms);
  }

  @Test
  void responseAtTheConservativeDeadlineIsRefused() throws Exception {
    var f = new Fixture();
    when(f.clock.get("observed_at", OffsetDateTime.class))
        .thenReturn(Instant.ofEpochMilli(60000).atOffset(ZoneOffset.UTC));
    assertThatThrownBy(f::resolve).isInstanceOf(IllegalStateException.class);
    assertThat(f.commits).isEqualTo(1);
  }

  @Test
  void sourceSubstitutionCannotYieldAnAuthorizationResponse() throws Exception {
    var f = new Fixture();
    when(f.captured.originalClaims()).thenReturn(new byte[] {99});
    assertThatThrownBy(f::resolve).isInstanceOf(IllegalStateException.class);
    verify(f.orders, never()).lockOriginalForCompletionLocked(any(), any(), any());
  }

  @Test
  void originalLostAfterCurrentCaptureCannotYieldAReply() throws Exception {
    var f = new Fixture();
    when(f.orders.readOriginal(f.operation))
        .thenReturn(Optional.of(f.holder), Optional.of(f.holder), Optional.empty());
    assertThatThrownBy(f::resolve).isInstanceOf(IllegalStateException.class);
    assertThat(f.commits).isEqualTo(2);
  }

  @Test
  void absentOrMismatchedDispatchClaimCannotAuthorizeGameDesignCallback() throws Exception {
    var missing = new Fixture();
    when(missing.dsl.fetchOne(
        "SELECT * FROM account_tenant_creation_dispatch_intents WHERE creation_operation_id = ?", missing.operation))
        .thenReturn(null);
    assertThatThrownBy(missing::resolve).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(missing.actors, missing.capture, missing.terms);
    var mismatched = new Fixture();
    when(mismatched.dispatch.get("authorization_binding_payload", byte[].class)).thenReturn(new byte[] {99});
    assertThatThrownBy(mismatched::resolve).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(mismatched.actors, mismatched.capture, mismatched.terms);
  }

  @Test
  void retainedDispatchDeadlineCanOnlyNarrowCurrentAuthority() throws Exception {
    var fixture = new Fixture();
    when(fixture.dispatch.get("valid_before_epoch_millis", Long.class)).thenReturn(40000L);
    assertThat(fixture.resolve().validBeforeEpochMillis()).isEqualTo(40000L);
    var expired = new Fixture();
    when(expired.dispatch.get("valid_before_epoch_millis", Long.class)).thenReturn(19000L);
    assertThatThrownBy(expired::resolve).isInstanceOf(IllegalStateException.class);
  }

  private static final class Fixture {
    final UUID operation = UUID.randomUUID(), request = UUID.randomUUID(), issuanceId = UUID.randomUUID();
    final UUID account = UUID.randomUUID(), gdRequest = UUID.randomUUID(), recentId = UUID.randomUUID();
    final String gdDigest = "sha256:" + "a".repeat(64), authDigest = "sha256:" + "b".repeat(64);
    final Binding binding = new Binding("dev", gdRequest, operation, account, gdDigest, authDigest);
    final DSLContext dsl = mock(DSLContext.class);
    final AccountTenantCreationIntentRepository intents = mock(AccountTenantCreationIntentRepository.class);
    final AccountTenantCreationAdmissionRepository admissions = mock(AccountTenantCreationAdmissionRepository.class);
    final AccountTenantCreationSourceOrderRepository orders = mock(AccountTenantCreationSourceOrderRepository.class);
    final AccountControlUiIssuanceRepository issuances = mock(AccountControlUiIssuanceRepository.class);
    final AccountControlUiActorService actors = mock(AccountControlUiActorService.class);
    final AccountTenantCreationOwnerSourceCapture capture = mock(AccountTenantCreationOwnerSourceCapture.class);
    final AccountRecentReauthenticationRepository recentRepository = mock(AccountRecentReauthenticationRepository.class);
    final AccountHostedTermsService terms = mock(AccountHostedTermsService.class);
    final AccountHostedTermsService.CapturedEnvironmentBoundary environment = mock(AccountHostedTermsService.CapturedEnvironmentBoundary.class);
    final AccountHostedTermsService.CurrentnessEvidence currentTerms = mock(AccountHostedTermsService.CurrentnessEvidence.class);
    final AccountTenantCreationOwnerSourceCapture.Captured captured = mock(AccountTenantCreationOwnerSourceCapture.Captured.class);
    final AccountTenantCreationSourceOrderBinding holder = mock(AccountTenantCreationSourceOrderBinding.class);
    final AccountRecentReauthenticationEvidence recent = recentEvidence();
    final Admitted admitted = mock(Admitted.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final Record clock = mock(Record.class);
    final Record dispatch = mock(Record.class);
    final OriginalIntent intent = new OriginalIntent(request, operation, gdRequest, account, "dev", "key", "name", null,
        recentId, UUID.randomUUID(), UUID.randomUUID(), 1, true, gdDigest, new byte[] {1}, "sha256:" + "c".repeat(64), Instant.EPOCH);
    final AccountTenantCreationCurrentAuthorizationOwner owner;
    int commits;

    Fixture() throws Exception {
      when(manager.getTransaction(any())).thenAnswer(call -> {
        TransactionDefinition definition = call.getArgument(0);
        assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(definition.getTimeout()).isEqualTo(5);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        return mock(TransactionStatus.class);
      });
      doAnswer(call -> { commits++; TransactionSynchronizationManager.setActualTransactionActive(false); return null; })
          .when(manager).commit(any());
      doAnswer(call -> { TransactionSynchronizationManager.setActualTransactionActive(false); return null; })
          .when(manager).rollback(any());
      Connection connection = mock(Connection.class);
      when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
      doAnswer(call -> { call.<ConnectionRunnable>getArgument(0).run(connection); return null; })
          .when(dsl).connection(any(ConnectionRunnable.class));
      when(intents.readOriginal(operation)).thenReturn(Optional.of(intent));
      when(admissions.readAdmitted(operation)).thenReturn(Optional.of(admitted));
      when(orders.readOriginal(operation)).thenReturn(Optional.of(holder));
      when(orders.readSettlement(operation)).thenReturn(Optional.empty());
      when(recentRepository.findByCreationOperation(operation)).thenReturn(Optional.of(recent));
      var admission = new AccountTenantCreationAdmissionBinding(request, operation, issuanceId, account, "dev", gdRequest, gdDigest,
          intent.requestDigest(), "d".repeat(64), recentId, "e".repeat(64), 200000, 120000, 10000, 110000, 86510000);
      byte[] admissionBytes = admission.canonicalReceipt();
      when(admitted.binding()).thenReturn(admission);
      when(admitted.receiptPayload()).thenReturn(admissionBytes);
      when(admitted.receiptDigest()).thenReturn("receipt");
      when(holder.outcomeBinding()).thenReturn(binding);
      when(holder.intentPayload()).thenReturn(new byte[] {1});
      when(holder.intentDigest()).thenReturn("sha256:" + "c".repeat(64));
      when(holder.admissionPayload()).thenReturn(admissionBytes);
      when(holder.admissionDigest()).thenReturn("receipt");
      when(holder.canonicalBytes()).thenReturn(new byte[] {2});
      when(holder.authorizationDigest()).thenReturn(binding.accountAuthorizationDigest());
      when(dispatch.get("creation_operation_id", UUID.class)).thenReturn(operation);
      when(dispatch.get("authorization_binding_payload", byte[].class)).thenReturn(new byte[] {2});
      when(dispatch.get("authorization_digest", String.class)).thenReturn(binding.accountAuthorizationDigest());
      when(dispatch.get("dispatch_call_id", UUID.class)).thenReturn(UUID.randomUUID());
      when(dispatch.get("valid_before_epoch_millis", Long.class)).thenReturn(110000L);
      when(dsl.fetchOne("SELECT * FROM account_tenant_creation_dispatch_intents WHERE creation_operation_id = ?", operation))
          .thenReturn(dispatch);
      when(orders.lockOriginalForCompletionLocked(intent, admitted, List.of())).thenReturn(holder);
      Record row = mock(Record.class);
      UUID jti = UUID.randomUUID();
      when(row.get("authorization_scope", String.class)).thenReturn("ACCOUNT");
      when(row.get("operation_id", UUID.class)).thenReturn(issuanceId);
      when(row.get("token_jti", UUID.class)).thenReturn(jti);
      when(row.get("account_uuid", UUID.class)).thenReturn(account);
      when(row.get("token_hash", String.class)).thenReturn("e".repeat(64));
      when(row.get("status", String.class)).thenReturn("REVOKED");
      when(row.get("issued_at_epoch_second", Long.class)).thenReturn(1L);
      when(row.get("expires_at_epoch_second", Long.class)).thenReturn(120L);
      when(row.get("recovery_expires_at", OffsetDateTime.class)).thenReturn(Instant.EPOCH.atOffset(ZoneOffset.UTC));
      when(row.get("claims_payload", byte[].class)).thenReturn(new byte[] {3});
      byte[] signer = AccountControlUiAuthority.canonical(Map.of("kid", "kid", "publicKeyFingerprint", "fingerprint"));
      when(row.get("signer_receipt", byte[].class)).thenReturn(signer);
      var issuance = new AccountControlUiIssuanceRepository.Stored(row);
      when(dsl.fetchOne("SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ?", issuanceId)).thenReturn(row);
      when(issuances.decodeStored(row)).thenReturn(issuance);
      when(actors.readAcceptedSignerKey("kid")).thenAnswer(call -> {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(commits).isEqualTo(1);
        return new AccountControlUiActorService.AcceptedSignerKey("kid", "fingerprint");
      });
      when(terms.captureCurrentEnvironmentBoundary()).thenAnswer(call -> {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return environment;
      });
      when(capture.captureLocked(any(), any(), any(), any())).thenReturn(captured);
      when(captured.originalClaims()).thenReturn(new byte[] {3});
      when(captured.originalSignerReceipt()).thenReturn(signer);
      when(captured.issuanceOperationId()).thenReturn(issuanceId);
      when(captured.tokenJti()).thenReturn(jti);
      when(captured.tokenHash()).thenReturn("e".repeat(64));
      when(captured.reauthentication()).thenReturn(recent);
      when(captured.sources()).thenReturn(List.of());
      var currentness = mock(AccountHostedTermsService.EnvironmentBoundCurrentness.class);
      when(currentness.terms()).thenReturn(currentTerms);
      when(captured.currentness()).thenReturn(currentness);
      when(currentTerms.validUntil()).thenReturn(Instant.ofEpochMilli(60000));
      when(dsl.fetchOne("SELECT clock_timestamp() AS observed_at")).thenReturn(clock);
      when(clock.get("observed_at", OffsetDateTime.class)).thenReturn(Instant.ofEpochMilli(20000).atOffset(ZoneOffset.UTC));
      owner = new AccountTenantCreationCurrentAuthorizationOwner(dsl, intents, admissions, orders, issuances, actors,
          capture, recentRepository, terms, manager);
    }

    AccountRecentReauthenticationEvidence recentEvidence() {
      return new AccountRecentReauthenticationEvidence(recentId, request, account,
          AccountRecentReauthenticationPolicy.PURPOSE, operation, "sha256:" + "f".repeat(64),
          net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT,
          42L, net.firedevops.firemud.accountservice.entity.AccountLifecycleState.ACTIVE,
          1L, 1L, 1L, 1L, 1L, 1L, "sha256:" + "a".repeat(64), 60L, 5L, Instant.ofEpochMilli(10000));
    }

    AccountTenantCreationCurrentAuthorizationOwner.Authorization resolve() { return owner.resolve(binding); }
  }
}
