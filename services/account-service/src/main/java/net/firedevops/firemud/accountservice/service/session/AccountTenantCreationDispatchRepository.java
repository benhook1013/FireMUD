package net.firedevops.firemud.accountservice.service.session;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionOwner.PreparedResult;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRepository.Admitted;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.OriginalIntent;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable original dispatch claim. Only its inserting invocation may attempt the first send. */
final class AccountTenantCreationDispatchRepository {
  private static final String TABLE = "account_tenant_creation_dispatch_intents";
  private final DSLContext dsl;
  private final AccountTenantCreationIntentRepository intents;
  private final AccountTenantCreationAdmissionRepository admissions;
  private final AccountTenantCreationSourceOrderRepository orders;
  private final AccountControlUiIssuanceRepository issuances;
  private final AccountControlUiActorService actors;
  private final AccountTenantCreationOwnerSourceCapture capture;
  private final TransactionTemplate transactions;

  AccountTenantCreationDispatchRepository(
      DSLContext dsl,
      AccountTenantCreationIntentRepository intents,
      AccountTenantCreationAdmissionRepository admissions,
      AccountTenantCreationSourceOrderRepository orders,
      AccountControlUiIssuanceRepository issuances,
      AccountControlUiActorService actors,
      AccountTenantCreationOwnerSourceCapture capture,
      PlatformTransactionManager manager) {
    this.dsl = Objects.requireNonNull(dsl);
    this.intents = Objects.requireNonNull(intents);
    this.admissions = Objects.requireNonNull(admissions);
    this.orders = Objects.requireNonNull(orders);
    this.issuances = Objects.requireNonNull(issuances);
    this.actors = Objects.requireNonNull(actors);
    this.capture = Objects.requireNonNull(capture);
    transactions = new TransactionTemplate(Objects.requireNonNull(manager));
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transactions.setTimeout(5);
  }

  boolean hasClaim(UUID operation) {
    outsideSql();
    return Boolean.TRUE.equals(transactions.execute(status -> row(operation) != null));
  }

  Original readOriginal(UUID operation) {
    outsideSql();
    return Objects.requireNonNull(transactions.execute(status -> read(operation).original));
  }

  Optional<InitialDispatch> claim(
      PreparedResult prepared,
      CapturedEnvironmentBoundary environment,
      AccountRecentReauthenticationEvidence recent) {
    outsideSql();
    Objects.requireNonNull(prepared);
    UUID operation = prepared.admission().creationOperationId();
    Stored first = Objects.requireNonNull(transactions.execute(status -> read(operation)));
    requirePrepared(prepared, first.original);
    // Existing claims, including claims whose delivery is unknown, never acquire another send.
    if (Boolean.TRUE.equals(transactions.execute(status -> existing(first.original))))
      return Optional.empty();
    String kid =
        (String) AccountControlUiIssuanceRepository.object(first.issuance.signerReceipt).get("kid");
    var accepted = actors.readAcceptedSignerKey(kid);
    AccountControlUiActorService.requireAcceptedSignerKey(first.issuance, accepted);
    UUID callId = UUID.randomUUID();
    Long deadline =
        transactions.execute(
            status -> {
              Stored current = read(operation);
              same(first, current);
              var observed =
                  capture.captureLocked(
                      current.original.intent, current.original.admitted, environment, recent);
              AccountControlUiActorService.requireAcceptedSignerKey(current.issuance, accepted);
              if (!Arrays.equals(observed.originalClaims(), current.issuance.claims)
                  || !Arrays.equals(
                      observed.originalSignerReceipt(), current.issuance.signerReceipt)
                  || !current.issuance.operationId.equals(observed.issuanceOperationId())
                  || !current.issuance.jti.equals(observed.tokenJti())
                  || !current.issuance.tokenHash.equals(observed.tokenHash())) throw denied();
              var locked =
                  orders.lockOriginalForCompletionLocked(
                      current.original.intent, current.original.admitted, observed.sources());
              if (!Arrays.equals(locked.canonicalBytes(), current.original.holder.canonicalBytes()))
                throw denied();
              if (existing(current.original)) return null;
              long validBefore = deadline(current.original, observed);
              int inserted =
                  dsl.execute(
                      "INSERT INTO "
                          + TABLE
                          + " (creation_operation_id, dispatch_call_id, authorization_binding_payload, authorization_digest, valid_before_epoch_millis)"
                          + " VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                      operation,
                      callId,
                      locked.canonicalBytes(),
                      locked.authorizationDigest(),
                      validBefore);
              if (inserted == 0) {
                if (!existing(current.original)) throw denied();
                return null;
              }
              if (inserted != 1) throw denied();
              return validBefore;
            });
    if (deadline == null) return Optional.empty();
    // The committed claim is independently read before returning the sole initial-send value.
    return Optional.of(
        Objects.requireNonNull(
            transactions.execute(
                status -> {
                  Stored committed = read(operation);
                  same(first, committed);
                  Record row = row(operation);
                  requireRow(committed.original, row);
                  if (!callId.equals(row.get("dispatch_call_id", UUID.class))
                      || !deadline.equals(row.get("valid_before_epoch_millis", Long.class))
                      || orders.readSettlement(operation).isPresent()) throw denied();
                  Record clock = dsl.fetchOne("SELECT clock_timestamp() AS observed_at");
                  if (clock == null
                      || !Objects.requireNonNull(clock.get("observed_at", OffsetDateTime.class))
                          .toInstant()
                          .isBefore(Instant.ofEpochMilli(deadline))) throw denied();
                  return new InitialDispatch(committed.original, callId, deadline);
                })));
  }

  private Stored read(UUID operation) {
    var intent =
        intents
            .readOriginal(operation)
            .orElseThrow(AccountTenantCreationDispatchRepository::denied);
    var admitted =
        admissions
            .readAdmitted(operation)
            .orElseThrow(AccountTenantCreationDispatchRepository::denied);
    var holder =
        orders.readOriginal(operation).orElseThrow(AccountTenantCreationDispatchRepository::denied);
    if (!operation.equals(intent.creationOperationId())
        || !operation.equals(admitted.binding().creationOperationId())
        || !Arrays.equals(intent.requestPayload(), holder.intentPayload())
        || !intent.requestDigest().equals(holder.intentDigest())
        || !Arrays.equals(admitted.receiptPayload(), holder.admissionPayload())
        || !admitted.receiptDigest().equals(holder.admissionDigest())) throw denied();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ?",
            admitted.binding().issuanceOperationId());
    if (row == null) throw denied();
    var issuance = issuances.decodeStored(row);
    if (!admitted.binding().issuanceOperationId().equals(issuance.operationId)
        || !intent.initiatingAccountId().equals(issuance.accountId)
        || !admitted.binding().tokenHash().equals(issuance.tokenHash)) throw denied();
    return new Stored(new Original(intent, admitted, holder), issuance);
  }

  private boolean existing(Original original) {
    Record row = row(original.intent.creationOperationId());
    if (row == null) return false;
    requireRow(original, row);
    return true;
  }

  private Record row(UUID operation) {
    return dsl.fetchOne(
        "SELECT * FROM " + TABLE + " WHERE creation_operation_id = ?",
        Objects.requireNonNull(operation));
  }

  private static void requireRow(Original original, Record row) {
    if (row == null
        || !original
            .intent
            .creationOperationId()
            .equals(row.get("creation_operation_id", UUID.class))
        || !Arrays.equals(
            original.holder.canonicalBytes(),
            row.get("authorization_binding_payload", byte[].class))
        || !original
            .holder
            .authorizationDigest()
            .equals(row.get("authorization_digest", String.class))
        || row.get("dispatch_call_id", UUID.class) == null
        || row.get("valid_before_epoch_millis", Long.class) == null) throw denied();
  }

  private static long deadline(
      Original original, AccountTenantCreationOwnerSourceCapture.Captured capture) {
    Instant deadline =
        Instant.ofEpochMilli(original.admitted.binding().operationDeadlineEpochMillis());
    var recent = capture.reauthentication();
    Instant recentDeadline =
        recent.issuedAt().plusSeconds(recent.recentAgeSeconds() - recent.maxClockSkewSeconds());
    if (recentDeadline.isBefore(deadline)) deadline = recentDeadline;
    Instant termsDeadline = capture.currentness().terms().validUntil();
    if (termsDeadline != null && termsDeadline.isBefore(deadline)) deadline = termsDeadline;
    return deadline.toEpochMilli();
  }

  private static void requirePrepared(PreparedResult prepared, Original original) {
    if (!Arrays.equals(
            prepared.admission().canonicalReceipt(), original.admitted.binding().canonicalReceipt())
        || !Arrays.equals(prepared.sourceOrderBinding(), original.holder.canonicalBytes())
        || !prepared.authorizationDigest().equals(original.holder.authorizationDigest()))
      throw denied();
  }

  private static void same(Stored first, Stored second) {
    if (!Arrays.equals(
            first.original.holder.canonicalBytes(), second.original.holder.canonicalBytes())
        || !Arrays.equals(first.issuance.claims, second.issuance.claims)
        || !Arrays.equals(first.issuance.signerReceipt, second.issuance.signerReceipt))
      throw denied();
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException(
          "Tenant-creation dispatch coordination must start outside SQL");
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Exact original tenant-creation dispatch claim unavailable");
  }

  record Original(
      OriginalIntent intent, Admitted admitted, AccountTenantCreationSourceOrderBinding holder) {
    Binding binding() {
      return holder.outcomeBinding();
    }
  }

  private record Stored(Original original, AccountControlUiIssuanceRepository.Stored issuance) {}

  /**
   * Private construction after insert and committed readback; cannot be recovered as a new permit.
   */
  static final class InitialDispatch {
    private final Original original;
    private final UUID callId;
    private final long validBefore;

    private InitialDispatch(Original original, UUID callId, long validBefore) {
      this.original = original;
      this.callId = callId;
      this.validBefore = validBefore;
    }

    Original original() {
      return original;
    }

    UUID callId() {
      return callId;
    }

    long validBefore() {
      return validBefore;
    }
  }
}
