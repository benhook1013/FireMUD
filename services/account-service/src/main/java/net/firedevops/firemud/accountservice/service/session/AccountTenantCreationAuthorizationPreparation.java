package net.firedevops.firemud.accountservice.service.session;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService.AcceptedSignerKey;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRepository.Admitted;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.OriginalIntent;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Account original-source preparation. A returned value is committed data, never
 * permission to dispatch Game Design, create bindings, or consume reauthentication.
 */
final class AccountTenantCreationAuthorizationPreparation {
  private final DSLContext dsl;
  private final AccountTenantCreationIntentRepository intents;
  private final AccountTenantCreationAdmissionRepository admissions;
  private final AccountControlUiIssuanceRepository issuances;
  private final AccountControlUiActorService actors;
  private final AccountTenantCreationOwnerSourceCapture capture;
  private final AccountTenantCreationSourceOrderRepository orders;
  private final TransactionTemplate transactions;

  AccountTenantCreationAuthorizationPreparation(
      DSLContext dsl,
      AccountTenantCreationIntentRepository intents,
      AccountTenantCreationAdmissionRepository admissions,
      AccountControlUiIssuanceRepository issuances,
      AccountControlUiActorService actors,
      AccountTenantCreationOwnerSourceCapture capture,
      AccountTenantCreationSourceOrderRepository orders,
      PlatformTransactionManager transactionManager) {
    this.dsl = Objects.requireNonNull(dsl);
    this.intents = Objects.requireNonNull(intents);
    this.admissions = Objects.requireNonNull(admissions);
    this.issuances = Objects.requireNonNull(issuances);
    this.actors = Objects.requireNonNull(actors);
    this.capture = Objects.requireNonNull(capture);
    this.orders = Objects.requireNonNull(orders);
    transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  Prepared prepare(
      UUID creationOperationId,
      CapturedEnvironmentBoundary environment,
      AccountRecentReauthenticationEvidence reauthentication) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw denied();
    }
    Objects.requireNonNull(creationOperationId);
    Objects.requireNonNull(environment);
    Objects.requireNonNull(reauthentication);
    Original first =
        Objects.requireNonNull(transactions.execute(status -> read(creationOperationId)));
    String kid =
        (String) AccountControlUiIssuanceRepository.object(first.issuance.signerReceipt).get("kid");
    AcceptedSignerKey accepted = actors.readAcceptedSignerKey(kid);
    AccountControlUiActorService.requireAcceptedSignerKey(first.issuance, accepted);

    AccountTenantCreationSourceOrderBinding stored =
        Objects.requireNonNull(
            transactions.execute(
                status -> {
                  Original current = read(creationOperationId);
                  same(first, current);
                  AccountControlUiActorService.requireAcceptedSignerKey(current.issuance, accepted);
                  var sources =
                      capture.captureLocked(
                          current.intent, current.admitted, environment, reauthentication);
                  if (!Arrays.equals(sources.originalClaims(), current.issuance.claims)
                      || !Arrays.equals(
                          sources.originalSignerReceipt(), current.issuance.signerReceipt)
                      || !current.issuance.operationId.equals(sources.issuanceOperationId())
                      || !current.issuance.jti.equals(sources.tokenJti())
                      || !current.issuance.tokenHash.equals(sources.tokenHash())) {
                    throw denied();
                  }
                  var result =
                      orders.acquireLocked(current.intent, current.admitted, sources.sources());
                  requirePending(creationOperationId);
                  return result;
                }));

    return Objects.requireNonNull(
        transactions.execute(
            status -> {
              Original committed = read(creationOperationId);
              same(first, committed);
              var readback =
                  orders
                      .readOriginal(creationOperationId)
                      .orElseThrow(AccountTenantCreationAuthorizationPreparation::denied);
              if (!Arrays.equals(stored.canonicalBytes(), readback.canonicalBytes())
                  || !stored.authorizationDigest().equals(readback.authorizationDigest())
                  || !Arrays.equals(committed.intent.requestPayload(), readback.intentPayload())
                  || !committed.intent.requestDigest().equals(readback.intentDigest())
                  || !Arrays.equals(
                      committed.admitted.receiptPayload(), readback.admissionPayload())
                  || !committed.admitted.receiptDigest().equals(readback.admissionDigest())) {
                throw denied();
              }
              requirePending(creationOperationId);
              return new Prepared(readback);
            }));
  }

  private Original read(UUID operationId) {
    requireOwnerTransaction();
    OriginalIntent intent =
        intents
            .readOriginal(operationId)
            .orElseThrow(AccountTenantCreationAuthorizationPreparation::denied);
    Admitted admitted =
        admissions
            .readAdmitted(operationId)
            .orElseThrow(AccountTenantCreationAuthorizationPreparation::denied);
    var binding = admitted.binding();
    if (!intent.creationOperationId().equals(operationId)
        || !binding.creationOperationId().equals(operationId)
        || !binding.intentRequestId().equals(intent.requestId())
        || !binding.intentDigest().equals(intent.requestDigest())
        || !binding.actorAccountId().equals(intent.initiatingAccountId())
        || !binding.reauthenticationEvidenceId().equals(intent.recentReauthenticationEvidenceId())
        || !binding.targetNamespace().equals(intent.targetNamespace())
        || !binding.gameDesignCreationRequestId().equals(intent.gameDesignCreationRequestId())
        || !binding.gameDesignRequestDigest().equals(intent.gameDesignRequestDigest())) {
      throw denied();
    }
    // Immutable preliminary/readback data only. The genuine owner capture acquires Account
    // before issuer/issuance locks; taking SHARE here would invert that ordering.
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ?",
            binding.issuanceOperationId());
    if (row == null) throw denied();
    var issuance = issuances.decodeStored(row);
    if (!binding.issuanceOperationId().equals(issuance.operationId)
        || !binding.actorAccountId().equals(issuance.accountId)
        || !binding.tokenHash().equals(issuance.tokenHash)) throw denied();
    return new Original(intent, admitted, issuance);
  }

  private void requirePending(UUID operationId) {
    if (orders.readSettlement(operationId).isPresent()) throw denied();
  }

  private void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw denied();
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw denied();
          }
        });
  }

  private static void same(Original expected, Original actual) {
    if (!Arrays.equals(expected.intent.requestPayload(), actual.intent.requestPayload())
        || !expected.intent.requestDigest().equals(actual.intent.requestDigest())
        || !Arrays.equals(expected.admitted.receiptPayload(), actual.admitted.receiptPayload())
        || !expected.admitted.receiptDigest().equals(actual.admitted.receiptDigest())
        || !expected.admitted.protectedBindingId().equals(actual.admitted.protectedBindingId())
        || expected.admitted.qualificationFence() != actual.admitted.qualificationFence()
        || expected.admitted.localAofCount() != actual.admitted.localAofCount()
        || expected.admitted.replicaAofCount() != actual.admitted.replicaAofCount()
        || !expected.admitted.admittedAt().equals(actual.admitted.admittedAt())
        || !Arrays.equals(expected.issuance.signerReceipt, actual.issuance.signerReceipt)
        || !Arrays.equals(expected.issuance.claims, actual.issuance.claims)) throw denied();
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Exact admitted original creation preparation is unavailable");
  }

  private record Original(
      OriginalIntent intent,
      Admitted admitted,
      AccountControlUiIssuanceRepository.Stored issuance) {}

  /** Immutable independently read-back data; no dispatch or current authorization capability. */
  static final class Prepared {
    private final byte[] binding;
    private final String digest;

    private Prepared(AccountTenantCreationSourceOrderBinding original) {
      binding = original.canonicalBytes();
      digest = original.authorizationDigest();
    }

    byte[] binding() {
      return binding.clone();
    }

    String digest() {
      return digest;
    }
  }
}
