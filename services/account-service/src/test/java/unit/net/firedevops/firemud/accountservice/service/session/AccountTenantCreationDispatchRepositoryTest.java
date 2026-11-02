package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Controlled transaction orchestration; physical claim/rollback is defined in the PostgreSQL suite.
 */
class AccountTenantCreationDispatchRepositoryTest {
  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void onlyInsertInvocationCanObtainInitialDispatchAfterCommittedReadback() {
    var f = new Fixture();
    var first = f.claim().orElseThrow();
    assertThat(first.callId()).isNotNull();
    assertThat(first.validBefore()).isEqualTo(Fixture.DISPATCH_DEADLINE);
    assertThat(f.repository.hasClaim(f.operation)).isTrue();
    assertThat(f.claim()).isEmpty();
    verify(f.dsl).execute(anyString(), any(Object[].class));
    verify(f.actors).readAcceptedSignerKey("kid");
    verify(f.capture).captureLocked(f.intent, f.admitted, f.environment, f.recent);
    verify(f.orders).lockOriginalForCompletionLocked(f.intent, f.admitted, List.of());
    assertThat(f.commits).isGreaterThanOrEqualTo(4);
  }

  @Test
  void lostIndependentReadbackRetainsClaimAndCannotIssueReplacementPermit() {
    var f = new Fixture();
    f.hideClaim = true;
    assertThatThrownBy(f::claim).isInstanceOf(IllegalStateException.class);
    assertThat(f.row).isNotNull();
    f.hideClaim = false;
    assertThat(f.claim()).isEmpty();
    verify(f.dsl).execute(anyString(), any(Object[].class));
  }

  @Test
  void currentSourceRefusalDoesNotPersistClaim() {
    var f = new Fixture();
    when(f.capture.captureLocked(f.intent, f.admitted, f.environment, f.recent))
        .thenThrow(new IllegalStateException("Current original denied"));
    assertThatThrownBy(f::claim).isInstanceOf(IllegalStateException.class);
    assertThat(f.row).isNull();
    verify(f.dsl, org.mockito.Mockito.never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void retainedDispatchClosesOnlyAtExactDatabaseClockCutoff() {
    var f = new Fixture();
    var dispatch = f.claim().orElseThrow();
    Instant cutoff = Instant.ofEpochMilli(dispatch.validBefore());
    f.observe(cutoff.minusNanos(1));
    assertThat(f.repository.isDispatchWindowClosed(dispatch.original())).isFalse();
    f.observe(cutoff);
    assertThat(f.repository.isDispatchWindowClosed(dispatch.original())).isTrue();
    f.observe(cutoff.plusNanos(1));
    assertThat(f.repository.isDispatchWindowClosed(dispatch.original())).isTrue();
    assertThat(f.claim()).isEmpty();
    verify(f.dsl).execute(anyString(), any(Object[].class));
    verify(f.actors).readAcceptedSignerKey("kid");
  }

  @Test
  void noClaimWaitsForImmutableOperationDeadlineSoConcurrentFirstClaimKeepsItsWindow() {
    var f = new Fixture();
    var original = f.repository.readOriginal(f.operation);
    Instant cutoff =
        Instant.ofEpochMilli(original.admitted().binding().operationDeadlineEpochMillis());
    f.observe(Instant.ofEpochMilli(Fixture.DISPATCH_DEADLINE));
    assertThat(f.repository.isDispatchWindowClosed(original)).isFalse();
    f.observe(cutoff.minusNanos(1));
    assertThat(f.repository.isDispatchWindowClosed(original)).isFalse();
    f.observe(cutoff);
    assertThat(f.repository.isDispatchWindowClosed(original)).isTrue();
    assertThat(f.row).isNull();
    verify(f.dsl, org.mockito.Mockito.never()).execute(anyString(), any(Object[].class));
    org.mockito.Mockito.verifyNoInteractions(f.actors, f.capture);
  }

  @Test
  void unknownOutOfBoundsOrContradictoryClaimNeverEstablishesClosure() {
    for (Long deadline : new Long[] {null, 0L, -1L, 1900000000000L, Long.MAX_VALUE}) {
      assertClaimRefused("valid_before_epoch_millis", deadline);
    }
    assertClaimRefused("authorization_binding_payload", new byte[] {99});
    assertClaimRefused("authorization_digest", "different");
    assertClaimRefused("creation_operation_id", UUID.randomUUID());
    assertClaimRefused("dispatch_call_id", null);
    assertClaimRefused("dispatch_call_id", new UUID(0L, 0L));
  }

  @Test
  void missingOriginalOrDatabaseClockAndChangedHolderNeverEstablishClosure() {
    var f = new Fixture();
    var original = f.repository.readOriginal(f.operation);
    org.mockito.Mockito.doReturn(null)
        .when(f.dsl)
        .fetchOne("SELECT clock_timestamp() AS observed_at");
    assertThatThrownBy(() -> f.repository.isDispatchWindowClosed(original))
        .isInstanceOf(IllegalStateException.class);
    when(f.admissions.readAdmitted(f.operation)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> f.repository.isDispatchWindowClosed(original))
        .isInstanceOf(IllegalStateException.class);

    var changed = new Fixture();
    var retained = changed.repository.readOriginal(changed.operation);
    var holder = mock(AccountTenantCreationSourceOrderBinding.class);
    when(holder.intentPayload()).thenReturn(new byte[] {1});
    when(holder.intentDigest()).thenReturn("intent");
    when(holder.admissionPayload()).thenReturn(new byte[] {2});
    when(holder.admissionDigest()).thenReturn("admission");
    when(holder.canonicalBytes()).thenReturn(new byte[] {99});
    when(changed.orders.readOriginal(changed.operation)).thenReturn(Optional.of(holder));
    assertThatThrownBy(() -> changed.repository.isDispatchWindowClosed(retained))
        .isInstanceOf(IllegalStateException.class);
  }

  private static void assertClaimRefused(String field, Object value) {
    var f = new Fixture();
    var dispatch = f.claim().orElseThrow();
    f.claimFields.put(field, value);
    f.observe(Instant.ofEpochMilli(2000000000000L));
    assertThatThrownBy(() -> f.repository.isDispatchWindowClosed(dispatch.original()))
        .as(field)
        .isInstanceOf(IllegalStateException.class);
  }

  private static final class Fixture {
    final UUID operation = UUID.randomUUID(),
        issuance = UUID.randomUUID(),
        account = UUID.randomUUID(),
        jti = UUID.randomUUID();
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
    final AccountTenantCreationIntentRepository.OriginalIntent intent =
        mock(AccountTenantCreationIntentRepository.OriginalIntent.class);
    final AccountTenantCreationAdmissionRepository.Admitted admitted =
        mock(AccountTenantCreationAdmissionRepository.Admitted.class);
    final AccountTenantCreationSourceOrderBinding holder =
        mock(AccountTenantCreationSourceOrderBinding.class);
    final AccountTenantCreationAdmissionOwner.PreparedResult prepared =
        mock(AccountTenantCreationAdmissionOwner.PreparedResult.class);
    final CapturedEnvironmentBoundary environment = mock(CapturedEnvironmentBoundary.class);
    final AccountRecentReauthenticationEvidence recent =
        mock(AccountRecentReauthenticationEvidence.class);
    final AccountTenantCreationDispatchRepository repository;
    static final long DISPATCH_DEADLINE = 1899999940000L;
    final Map<String, Object> claimFields = new HashMap<>();
    final Record clock = mock(Record.class);
    Record row;
    boolean hideClaim;
    int commits;

    Fixture() {
      var manager = mock(PlatformTransactionManager.class);
      when(manager.getTransaction(any()))
          .thenAnswer(
              invocation -> {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                return mock(TransactionStatus.class);
              });
      doAnswer(
              invocation -> {
                commits++;
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
      var admission =
          new AccountTenantCreationAdmissionBinding(
              UUID.randomUUID(),
              operation,
              issuance,
              account,
              "test",
              UUID.randomUUID(),
              "sha256:" + "a".repeat(64),
              "sha256:" + "b".repeat(64),
              "c".repeat(64),
              UUID.randomUUID(),
              "d".repeat(64),
              2000000000000L,
              1900000000000L,
              1899999900000L,
              1899999990000L,
              1900086390000L);
      when(admitted.binding()).thenReturn(admission);
      when(admitted.receiptPayload()).thenReturn(new byte[] {2});
      when(admitted.receiptDigest()).thenReturn("admission");
      when(intent.creationOperationId()).thenReturn(operation);
      when(intent.initiatingAccountId()).thenReturn(account);
      when(intent.requestPayload()).thenReturn(new byte[] {1});
      when(intent.requestDigest()).thenReturn("intent");
      when(holder.intentPayload()).thenReturn(new byte[] {1});
      when(holder.intentDigest()).thenReturn("intent");
      when(holder.admissionPayload()).thenReturn(new byte[] {2});
      when(holder.admissionDigest()).thenReturn("admission");
      when(holder.canonicalBytes()).thenReturn(new byte[] {3});
      when(holder.authorizationDigest()).thenReturn("authorization");
      when(prepared.admission()).thenReturn(admission);
      when(prepared.sourceOrderBinding()).thenReturn(new byte[] {3});
      when(prepared.authorizationDigest()).thenReturn("authorization");
      when(intents.readOriginal(operation)).thenReturn(Optional.of(intent));
      when(admissions.readAdmitted(operation)).thenReturn(Optional.of(admitted));
      when(orders.readOriginal(operation)).thenReturn(Optional.of(holder));
      when(orders.readSettlement(operation)).thenReturn(Optional.empty());
      when(orders.lockOriginalForCompletionLocked(intent, admitted, List.of())).thenReturn(holder);
      Record original = mock(Record.class);
      when(original.get("authorization_scope", String.class)).thenReturn("ACCOUNT");
      when(original.get("operation_id", UUID.class)).thenReturn(issuance);
      when(original.get("account_uuid", UUID.class)).thenReturn(account);
      when(original.get("token_jti", UUID.class)).thenReturn(jti);
      when(original.get("token_hash", String.class)).thenReturn("d".repeat(64));
      when(original.get("issued_at_epoch_second", Long.class)).thenReturn(1L);
      when(original.get("expires_at_epoch_second", Long.class)).thenReturn(1900000000L);
      when(original.get("recovery_expires_at", java.time.OffsetDateTime.class))
          .thenReturn(Instant.EPOCH.atOffset(ZoneOffset.UTC));
      byte[] signer =
          AccountControlUiAuthority.canonical(
              Map.of("kid", "kid", "publicKeyFingerprint", "fingerprint"));
      when(original.get("signer_receipt", byte[].class)).thenReturn(signer);
      when(original.get("claims_payload", byte[].class)).thenReturn(new byte[] {4});
      var stored = new AccountControlUiIssuanceRepository.Stored(original);
      when(issuances.decodeStored(original)).thenReturn(stored);
      when(dsl.fetchOne(
              "SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ?",
              issuance))
          .thenReturn(original);
      when(actors.readAcceptedSignerKey("kid"))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return new AccountControlUiActorService.AcceptedSignerKey("kid", "fingerprint");
              });
      var observed = mock(AccountTenantCreationOwnerSourceCapture.Captured.class);
      when(observed.originalClaims()).thenReturn(stored.claims);
      when(observed.originalSignerReceipt()).thenReturn(signer);
      when(observed.issuanceOperationId()).thenReturn(issuance);
      when(observed.tokenJti()).thenReturn(jti);
      when(observed.tokenHash()).thenReturn("d".repeat(64));
      when(observed.sources()).thenReturn(List.of());
      when(observed.reauthentication()).thenReturn(recent);
      when(recent.issuedAt()).thenReturn(Instant.ofEpochMilli(1899999900000L));
      when(recent.recentAgeSeconds()).thenReturn(300L);
      when(recent.maxClockSkewSeconds()).thenReturn(30L);
      var currentness = mock(AccountHostedTermsService.EnvironmentBoundCurrentness.class);
      var terms = mock(AccountHostedTermsService.CurrentnessEvidence.class);
      when(terms.validUntil()).thenReturn(Instant.ofEpochMilli(DISPATCH_DEADLINE));
      when(currentness.terms()).thenReturn(terms);
      when(observed.currentness()).thenReturn(currentness);
      when(capture.captureLocked(intent, admitted, environment, recent)).thenReturn(observed);
      when(dsl.fetchOne(
              "SELECT * FROM account_tenant_creation_dispatch_intents WHERE creation_operation_id = ?",
              operation))
          .thenAnswer(invocation -> hideClaim ? null : row);
      observe(Instant.ofEpochMilli(1899999900000L));
      when(dsl.fetchOne("SELECT clock_timestamp() AS observed_at"))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return clock;
              });
      when(dsl.execute(anyString(), any(Object[].class)))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                Object[] values = (Object[]) invocation.getRawArguments()[1];
                claimFields.put("creation_operation_id", values[0]);
                claimFields.put("dispatch_call_id", values[1]);
                claimFields.put("authorization_binding_payload", values[2]);
                claimFields.put("authorization_digest", values[3]);
                claimFields.put("valid_before_epoch_millis", values[4]);
                row = mock(Record.class);
                when(row.get(anyString(), any(Class.class)))
                    .thenAnswer(read -> claimFields.get(read.getArgument(0)));
                return 1;
              });
      repository =
          new AccountTenantCreationDispatchRepository(
              dsl, intents, admissions, orders, issuances, actors, capture, manager);
    }

    Optional<AccountTenantCreationDispatchRepository.InitialDispatch> claim() {
      return repository.claim(prepared, environment, recent);
    }

    void observe(Instant instant) {
      when(clock.get("observed_at", java.time.OffsetDateTime.class))
          .thenReturn(instant.atOffset(ZoneOffset.UTC));
    }
  }
}
