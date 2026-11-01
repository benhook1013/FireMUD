package net.firedevops.firemud.accountservice.service.session;

import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRepository.Admitted;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.OriginalIntent;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered current Account owner for an existing, admitted and source-protected original.
 * The private authenticated transport carries this observation; a stored digest alone never grants
 * creation authority. The durable pending source holder orders writers until definitive settlement.
 */
public final class AccountTenantCreationCurrentAuthorizationOwner {
  private final DSLContext dsl;
  private final AccountTenantCreationIntentRepository intents;
  private final AccountTenantCreationAdmissionRepository admissions;
  private final AccountTenantCreationSourceOrderRepository orders;
  private final AccountControlUiIssuanceRepository issuances;
  private final AccountControlUiActorService actors;
  private final AccountTenantCreationOwnerSourceCapture capture;
  private final AccountRecentReauthenticationRepository reauthentication;
  private final AccountHostedTermsService terms;
  private final TransactionTemplate transactions;

  AccountTenantCreationCurrentAuthorizationOwner(
      DSLContext dsl,
      AccountTenantCreationIntentRepository intents,
      AccountTenantCreationAdmissionRepository admissions,
      AccountTenantCreationSourceOrderRepository orders,
      AccountControlUiIssuanceRepository issuances,
      AccountControlUiActorService actors,
      AccountTenantCreationOwnerSourceCapture capture,
      AccountRecentReauthenticationRepository reauthentication,
      AccountHostedTermsService terms,
      PlatformTransactionManager manager) {
    this.dsl = Objects.requireNonNull(dsl);
    this.intents = Objects.requireNonNull(intents);
    this.admissions = Objects.requireNonNull(admissions);
    this.orders = Objects.requireNonNull(orders);
    this.issuances = Objects.requireNonNull(issuances);
    this.actors = Objects.requireNonNull(actors);
    this.capture = Objects.requireNonNull(capture);
    this.reauthentication = Objects.requireNonNull(reauthentication);
    this.terms = Objects.requireNonNull(terms);
    transactions = new TransactionTemplate(Objects.requireNonNull(manager));
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transactions.setTimeout(5);
  }

  /** Does not admit, acquire a missing holder, reauthenticate a revoked JWT, or dispatch work. */
  public Authorization resolve(Binding binding) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw denied();
    Objects.requireNonNull(binding);
    Original first = Objects.requireNonNull(transactions.execute(status -> read(binding)));
    String kid = (String) AccountControlUiIssuanceRepository.object(first.issuance.signerReceipt).get("kid");
    var accepted = actors.readAcceptedSignerKey(kid);
    AccountControlUiActorService.requireAcceptedSignerKey(first.issuance, accepted);
    var environment = terms.captureCurrentEnvironmentBoundary();
    Authorization observed = Objects.requireNonNull(transactions.execute(status -> {
      Original original = read(binding);
      same(first, original);
      // Actual Account-first capture precedes issuer/issuance/source locking. No network in SQL.
      var current = capture.captureLocked(original.intent, original.admitted, environment, original.recent);
      AccountControlUiActorService.requireAcceptedSignerKey(original.issuance, accepted);
      if (!Arrays.equals(current.originalClaims(), original.issuance.claims)
          || !Arrays.equals(current.originalSignerReceipt(), original.issuance.signerReceipt)
          || !original.issuance.operationId.equals(current.issuanceOperationId())
          || !original.issuance.jti.equals(current.tokenJti())
          || !original.issuance.tokenHash.equals(current.tokenHash())) throw denied();
      var held = orders.lockOriginalForCompletionLocked(original.intent, original.admitted, current.sources());
      if (!Arrays.equals(held.canonicalBytes(), original.holder.canonicalBytes())) throw denied();
      Instant deadline = Instant.ofEpochMilli(original.dispatchDeadline);
      var recent = current.reauthentication();
      Instant recentDeadline = recent.issuedAt().plusSeconds(recent.recentAgeSeconds() - recent.maxClockSkewSeconds());
      if (recentDeadline.isBefore(deadline)) deadline = recentDeadline;
      Instant termsDeadline = current.currentness().terms().validUntil();
      if (termsDeadline != null && termsDeadline.isBefore(deadline)) deadline = termsDeadline;
      long validBefore = deadline.toEpochMilli();
      requireTime(original, validBefore);
      return new Authorization(binding, validBefore);
    }));
    // Independently read the committed original and still-pending holder before transport reply.
    return Objects.requireNonNull(transactions.execute(status -> {
      Original committed = read(binding);
      same(first, committed);
      requireTime(committed, observed.validBeforeEpochMillis());
      return observed;
    }));
  }

  private Original read(Binding binding) {
    requireOwnerTransaction();
    var operation = binding.accountAuthorizationOperationId();
    var intent = intents.readOriginal(operation).orElseThrow(AccountTenantCreationCurrentAuthorizationOwner::denied);
    var admitted = admissions.readAdmitted(operation).orElseThrow(AccountTenantCreationCurrentAuthorizationOwner::denied);
    var holder = orders.readOriginal(operation).orElseThrow(AccountTenantCreationCurrentAuthorizationOwner::denied);
    var recent = reauthentication.findByCreationOperation(operation).orElseThrow(AccountTenantCreationCurrentAuthorizationOwner::denied);
    if (!holder.outcomeBinding().equals(binding)
        || !operation.equals(intent.creationOperationId())
        || !operation.equals(admitted.binding().creationOperationId())
        || !intent.initiatingAccountId().equals(binding.initiatingAccountId())
        || !Arrays.equals(intent.requestPayload(), holder.intentPayload())
        || !intent.requestDigest().equals(holder.intentDigest())
        || !Arrays.equals(admitted.receiptPayload(), holder.admissionPayload())
        || !admitted.receiptDigest().equals(holder.admissionDigest())
        || !recent.evidenceId().equals(intent.recentReauthenticationEvidenceId())
        || !recent.evidenceId().equals(admitted.binding().reauthenticationEvidenceId())
        || orders.readSettlement(operation).isPresent()) throw denied();
    Record row = dsl.fetchOne("SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ?",
        admitted.binding().issuanceOperationId());
    if (row == null) throw denied();
    var issuance = issuances.decodeStored(row);
    if (!issuance.operationId.equals(admitted.binding().issuanceOperationId())
        || !issuance.accountId.equals(binding.initiatingAccountId())
        || !issuance.tokenHash.equals(admitted.binding().tokenHash())) throw denied();
    Record dispatch = dsl.fetchOne(
        "SELECT * FROM account_tenant_creation_dispatch_intents WHERE creation_operation_id = ?", operation);
    if (dispatch == null
        || !operation.equals(dispatch.get("creation_operation_id", UUID.class))
        || !Arrays.equals(holder.canonicalBytes(), dispatch.get("authorization_binding_payload", byte[].class))
        || !holder.authorizationDigest().equals(dispatch.get("authorization_digest", String.class))
        || dispatch.get("dispatch_call_id", UUID.class) == null) throw denied();
    Long dispatchDeadline = dispatch.get("valid_before_epoch_millis", Long.class);
    if (dispatchDeadline == null || dispatchDeadline <= 0
        || dispatchDeadline > admitted.binding().operationDeadlineEpochMillis()) throw denied();
    return new Original(intent, admitted, holder, recent, issuance,
        dispatch.get("dispatch_call_id", UUID.class), dispatchDeadline);
  }

  private void requireTime(Original original, long validBefore) {
    Record row = dsl.fetchOne("SELECT clock_timestamp() AS observed_at");
    if (row == null) throw denied();
    Instant now = Objects.requireNonNull(row.get("observed_at", OffsetDateTime.class)).toInstant();
    if (now.isBefore(Instant.ofEpochMilli(original.admitted.binding().preparedAtEpochMillis()))
        || !now.isBefore(Instant.ofEpochMilli(validBefore))
        || validBefore > original.admitted.binding().operationDeadlineEpochMillis()) throw denied();
  }

  private static void same(Original first, Original second) {
    if (!Arrays.equals(first.intent.requestPayload(), second.intent.requestPayload())
        || !Arrays.equals(first.admitted.receiptPayload(), second.admitted.receiptPayload())
        || !Arrays.equals(first.holder.canonicalBytes(), second.holder.canonicalBytes())
        || !Arrays.equals(first.issuance.claims, second.issuance.claims)
        || !Arrays.equals(first.issuance.signerReceipt, second.issuance.signerReceipt)
        || !first.recent.evidenceId().equals(second.recent.evidenceId())
        || !first.recent.requestDigest().equals(second.recent.requestDigest())
        || !first.recent.issuedAt().equals(second.recent.issuedAt())
        || !first.dispatchCallId.equals(second.dispatchCallId)
        || first.dispatchDeadline != second.dispatchDeadline) throw denied();
  }

  private void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw denied();
    dsl.connection(connection -> {
      if (connection.getAutoCommit() || connection.isReadOnly()
          || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) throw denied();
    });
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Current original tenant-creation authorization is unavailable");
  }

  private record Original(OriginalIntent intent, Admitted admitted,
      AccountTenantCreationSourceOrderBinding holder, AccountRecentReauthenticationEvidence recent,
      AccountControlUiIssuanceRepository.Stored issuance, UUID dispatchCallId, long dispatchDeadline) {}

  /** Transport data minted after genuine owner verification, never an in-process dispatch grant. */
  public record Authorization(Binding binding, long validBeforeEpochMillis) {
    public Authorization {
      Objects.requireNonNull(binding);
      if (validBeforeEpochMillis <= 0
          || validBeforeEpochMillis > AccountTenantCreationAdmissionBinding.MAX_REDIS_SAFE_INTEGER) throw denied();
    }
  }
}
