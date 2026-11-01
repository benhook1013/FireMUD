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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService.AcceptedSignerKey;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRepository.Admitted;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.OriginalIntent;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled unit composition only; no physical owner or accepted-key custody proof. */
class AccountTenantCreationAuthorizationPreparationTest {
  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void preparesOnlyAfterOutsideSqlKeyReadAndIndependentCommittedReadback() throws Exception {
    var f = new Fixture();
    var prepared = f.prepare();
    assertThat(f.commits).isEqualTo(3);
    assertThat(f.begins).isEqualTo(3);
    assertThat(prepared.digest()).isEqualTo("digest");
    byte[] exposed = prepared.binding();
    exposed[0] = 9;
    assertThat(prepared.binding()).containsExactly(1, 2);
    verify(f.intents, times(3)).readOriginal(f.operation);
    verify(f.capture).captureLocked(f.intent, f.admitted, f.environment, f.reauth);
    verify(f.orders).acquireLocked(f.intent, f.admitted, List.of());
    verify(f.orders).readOriginal(f.operation);
    verify(f.dsl, times(3))
        .fetchOne(
            "SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ?",
            f.issuanceId);
    verify(f.dsl, never())
        .fetchOne(
            "SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ? FOR SHARE",
            f.issuanceId);
    verify(f.actors, never()).withCurrentAccountCommitted(any(), any(), any());
  }

  @Test
  void missingOriginalCannotReadKeysOrAcquireSources() throws Exception {
    var f = new Fixture();
    when(f.intents.readOriginal(f.operation)).thenReturn(Optional.empty());
    assertThatThrownBy(f::prepare).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(f.actors, f.capture, f.orders);
  }

  @Test
  void missingAdmissionCannotReadKeysOrAcquireSources() throws Exception {
    var f = new Fixture();
    when(f.admissions.readAdmitted(f.operation)).thenReturn(Optional.empty());
    assertThatThrownBy(f::prepare).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(f.actors, f.capture, f.orders);
  }

  @Test
  void wrongAcceptedFingerprintFailsBeforeCapture() throws Exception {
    var f = new Fixture();
    org.mockito.Mockito.doReturn(new AcceptedSignerKey("kid", "different"))
        .when(f.actors)
        .readAcceptedSignerKey("kid");
    assertThatThrownBy(f::prepare).isInstanceOf(RuntimeException.class);
    verifyNoInteractions(f.capture, f.orders);
  }

  @Test
  void changedIndependentReadbackCannotReturnPreparation() throws Exception {
    var f = new Fixture();
    var changed = mock(AccountTenantCreationSourceOrderBinding.class);
    when(changed.canonicalBytes()).thenReturn(new byte[] {3});
    when(f.orders.readOriginal(f.operation)).thenReturn(Optional.of(changed));
    assertThatThrownBy(f::prepare).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void capturedOriginalMustMatchPreliminaryClaimsReceiptOperationAndToken() throws Exception {
    for (String changed : List.of("claims", "receipt", "operation", "jti", "hash")) {
      var f = new Fixture();
      switch (changed) {
        case "claims" -> when(f.captured.originalClaims()).thenReturn(new byte[] {8});
        case "receipt" -> when(f.captured.originalSignerReceipt()).thenReturn(new byte[] {8});
        case "operation" -> when(f.captured.issuanceOperationId()).thenReturn(UUID.randomUUID());
        case "jti" -> when(f.captured.tokenJti()).thenReturn(UUID.randomUUID());
        case "hash" -> when(f.captured.tokenHash()).thenReturn("e".repeat(64));
        default -> throw new IllegalStateException("Unknown changed original field");
      }
      assertThatThrownBy(f::prepare).isInstanceOf(IllegalStateException.class);
      verifyNoInteractions(f.orders);
    }
  }

  @Test
  void ownerCaptureRefusalCannotAcquireSourceHolder() throws Exception {
    var f = new Fixture();
    when(f.capture.captureLocked(f.intent, f.admitted, f.environment, f.reauth))
        .thenThrow(new IllegalStateException("Current owner evidence refused"));
    assertThatThrownBy(f::prepare).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(f.orders);
  }

  @Test
  void settledOriginalCannotBecomePendingPreparation() throws Exception {
    var f = new Fixture();
    var terminal =
        mock(net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.class);
    when(f.orders.readSettlement(f.operation)).thenReturn(Optional.of(terminal));
    assertThatThrownBy(f::prepare).isInstanceOf(IllegalStateException.class);
    verify(f.orders, never()).readOriginal(f.operation);
  }

  @Test
  void ambientTransactionIsRejectedBeforeOwnerReads() throws Exception {
    var f = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(f::prepare).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(f.intents, f.admissions, f.actors, f.capture, f.orders);
  }

  @Test
  void intentReadRequiresWritableOwnerTransactionAndNeverInfersMissingOriginal() {
    var dsl = mock(DSLContext.class);
    var repository =
        new AccountTenantCreationIntentRepository(
            dsl, mock(AccountRecentReauthenticationRepository.class));
    UUID operation = UUID.randomUUID();
    assertThatThrownBy(() -> repository.readOriginal(operation))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> repository.readOriginal(operation))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    assertThat(repository.readOriginal(operation)).isEmpty();
    assertThatThrownBy(() -> repository.readOriginal(new UUID(0, 0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static final class Fixture {
    final UUID operation = UUID.randomUUID();
    final UUID request = UUID.randomUUID();
    final UUID issuanceId = UUID.randomUUID();
    final UUID tokenJti = UUID.randomUUID();
    final UUID account = UUID.randomUUID();
    final UUID gdRequest = UUID.randomUUID();
    final UUID recentId = UUID.randomUUID();
    final DSLContext dsl = mock(DSLContext.class);
    final AccountTenantCreationIntentRepository intents =
        mock(AccountTenantCreationIntentRepository.class);
    final AccountTenantCreationAdmissionRepository admissions =
        mock(AccountTenantCreationAdmissionRepository.class);
    final AccountControlUiIssuanceRepository issuances =
        mock(AccountControlUiIssuanceRepository.class);
    final AccountControlUiActorService actors = mock(AccountControlUiActorService.class);
    final AccountTenantCreationOwnerSourceCapture capture =
        mock(AccountTenantCreationOwnerSourceCapture.class);
    final AccountTenantCreationSourceOrderRepository orders =
        mock(AccountTenantCreationSourceOrderRepository.class);
    final AccountTenantCreationOwnerSourceCapture.Captured captured =
        mock(AccountTenantCreationOwnerSourceCapture.Captured.class);
    final CapturedEnvironmentBoundary environment = mock(CapturedEnvironmentBoundary.class);
    final AccountRecentReauthenticationEvidence reauth =
        mock(AccountRecentReauthenticationEvidence.class);
    final OriginalIntent intent = mock(OriginalIntent.class);
    final Admitted admitted = mock(Admitted.class);
    final AccountTenantCreationSourceOrderBinding binding =
        mock(AccountTenantCreationSourceOrderBinding.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    int begins;
    int commits;

    Fixture() throws Exception {
      when(manager.getTransaction(any()))
          .thenAnswer(
              invocation -> {
                TransactionDefinition definition = invocation.getArgument(0);
                assertThat(definition.getIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                TransactionSynchronizationManager.setActualTransactionActive(true);
                begins++;
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
      Connection connection = mock(Connection.class);
      when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
      doAnswer(
              invocation -> {
                invocation.<ConnectionRunnable>getArgument(0).run(connection);
                return null;
              })
          .when(dsl)
          .connection(any(ConnectionRunnable.class));
      when(intents.readOriginal(operation)).thenReturn(Optional.of(intent));
      when(admissions.readAdmitted(operation)).thenReturn(Optional.of(admitted));
      when(intent.creationOperationId()).thenReturn(operation);
      when(intent.requestId()).thenReturn(request);
      when(intent.initiatingAccountId()).thenReturn(account);
      when(intent.targetNamespace()).thenReturn("dev");
      when(intent.recentReauthenticationEvidenceId()).thenReturn(recentId);
      when(intent.gameDesignCreationRequestId()).thenReturn(gdRequest);
      when(intent.gameDesignRequestDigest()).thenReturn("sha256:" + "a".repeat(64));
      when(intent.requestDigest()).thenReturn("sha256:" + "b".repeat(64));
      when(intent.requestPayload()).thenReturn(new byte[] {4});
      var admission =
          new AccountTenantCreationAdmissionBinding(
              request,
              operation,
              issuanceId,
              account,
              "dev",
              gdRequest,
              intent.gameDesignRequestDigest(),
              intent.requestDigest(),
              "c".repeat(64),
              recentId,
              "d".repeat(64),
              200000,
              120000,
              10000,
              110000,
              86510000);
      when(admitted.binding()).thenReturn(admission);
      when(admitted.receiptPayload()).thenReturn(admission.canonicalReceipt());
      when(admitted.receiptDigest()).thenReturn("receipt");
      when(admitted.protectedBindingId()).thenReturn("protected");
      when(admitted.admittedAt()).thenReturn(Instant.EPOCH);
      Record row = mock(Record.class);
      when(row.get("authorization_scope", String.class)).thenReturn("ACCOUNT");
      when(row.get("operation_id", UUID.class)).thenReturn(issuanceId);
      when(row.get("token_jti", UUID.class)).thenReturn(tokenJti);
      when(row.get("claims_payload", byte[].class)).thenReturn(new byte[] {7});
      when(row.get("account_uuid", UUID.class)).thenReturn(account);
      when(row.get("token_hash", String.class)).thenReturn(admission.tokenHash());
      when(row.get("issued_at_epoch_second", Long.class)).thenReturn(1L);
      when(row.get("expires_at_epoch_second", Long.class)).thenReturn(120L);
      when(row.get("recovery_expires_at", java.time.OffsetDateTime.class))
          .thenReturn(Instant.EPOCH.atOffset(ZoneOffset.UTC));
      byte[] receipt =
          net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority.canonical(
              Map.of("kid", "kid", "publicKeyFingerprint", "fingerprint"));
      when(row.get("signer_receipt", byte[].class)).thenReturn(receipt);
      var issuance = new AccountControlUiIssuanceRepository.Stored(row);
      when(dsl.fetchOne(
              "SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ?",
              issuanceId))
          .thenReturn(row);
      when(issuances.decodeStored(row)).thenReturn(issuance);
      when(actors.readAcceptedSignerKey("kid"))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(commits).isEqualTo(1);
                return new AcceptedSignerKey("kid", "fingerprint");
              });
      when(captured.originalSignerReceipt()).thenReturn(receipt);
      when(captured.originalClaims()).thenReturn(issuance.claims);
      when(captured.issuanceOperationId()).thenReturn(issuanceId);
      when(captured.tokenJti()).thenReturn(tokenJti);
      when(captured.tokenHash()).thenReturn(admission.tokenHash());
      when(captured.sources()).thenReturn(List.of());
      when(capture.captureLocked(intent, admitted, environment, reauth)).thenReturn(captured);
      when(orders.acquireLocked(intent, admitted, List.of())).thenReturn(binding);
      when(orders.readOriginal(operation)).thenReturn(Optional.of(binding));
      when(orders.readSettlement(operation)).thenReturn(Optional.empty());
      when(binding.canonicalBytes()).thenReturn(new byte[] {1, 2});
      when(binding.authorizationDigest()).thenReturn("digest");
      byte[] intentPayload = intent.requestPayload();
      String intentDigest = intent.requestDigest();
      byte[] admissionPayload = admitted.receiptPayload();
      String admissionDigest = admitted.receiptDigest();
      when(binding.intentPayload()).thenReturn(intentPayload);
      when(binding.intentDigest()).thenReturn(intentDigest);
      when(binding.admissionPayload()).thenReturn(admissionPayload);
      when(binding.admissionDigest()).thenReturn(admissionDigest);
    }

    AccountTenantCreationAuthorizationPreparation.Prepared prepare() {
      return new AccountTenantCreationAuthorizationPreparation(
              dsl, intents, admissions, issuances, actors, capture, orders, manager)
          .prepare(operation, environment, reauth);
    }
  }
}
