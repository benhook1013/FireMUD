package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Supplemental V74 storage; V66 retains sole ownership of operation/request correlation. */
public final class AccountControlUiIssuanceIntentRepository {
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected Account persistence context remains an internal owner collaborator.")
  public AccountControlUiIssuanceIntentRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  public AccountControlUiIssuanceIntent reserve(
      AccountControlUiIssuanceOperation operation, UUID jti, UUID bundleId) {
    transaction();
    // The durable source version/fence are database allocated, never inferred.
    if (jti == null
        || bundleId == null
        || jti.equals(new UUID(0L, 0L))
        || bundleId.equals(new UUID(0L, 0L)))
      throw new IllegalArgumentException("Non-nil signing identities required");
    if (operation.lifecycle() != AccountControlUiIssuanceOperation.Lifecycle.PENDING)
      throw conflict();
    int inserted =
        dsl.execute(
            "INSERT INTO account_control_ui_issuance_intents "
                + "(operation_id, jti, bundle_id, bundle_version, authority_capture_digest, issuance_fence_digest, phase) "
                + "VALUES (?, ?, ?, 1, ?, ?, 'PENDING') ON CONFLICT (operation_id) DO NOTHING",
            operation.operationId(),
            jti,
            bundleId,
            operation.originalCapture().authorityCaptureDigest(),
            operation.originalCapture().issuanceFenceDigest());
    AccountControlUiIssuanceIntent stored =
        read(operation).orElseThrow(AccountControlUiIssuanceIntentRepository::conflict);
    if (inserted != 1
        || !stored.jti().equals(jti)
        || !stored.bundleId().equals(bundleId)
        || stored.tokenHash().isPresent()) throw conflict();
    return stored;
  }

  public Optional<AccountControlUiIssuanceIntent> read(
      AccountControlUiIssuanceOperation operation) {
    transaction();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_control_ui_issuance_intents WHERE operation_id = ? FOR UPDATE",
            operation.operationId());
    if (row == null) return Optional.empty();
    if (!MessageDigest.isEqual(
            row.get("authority_capture_digest", byte[].class),
            operation.originalCapture().authorityCaptureDigest())
        || !MessageDigest.isEqual(
            row.get("issuance_fence_digest", byte[].class),
            operation.originalCapture().issuanceFenceDigest())) throw conflict();
    String hash = row.get("token_hash", String.class);
    if (!(hash == null ? "PENDING" : "HASH_BOUND").equals(row.get("phase", String.class)))
      throw conflict();
    return Optional.of(
        new AccountControlUiIssuanceIntent(
            operation,
            row.get("jti", UUID.class),
            row.get("bundle_id", UUID.class),
            row.get("bundle_version", Long.class),
            row.get("source_version", Long.class),
            row.get("source_fence", Long.class),
            Optional.ofNullable(hash)));
  }

  /**
   * The owner service must already hold issuer-first current-source locks and prove exact equality.
   */
  public AccountControlUiIssuanceIntent bindTokenHash(
      AccountControlUiIssuanceIntent expected, String tokenHash) {
    transaction();
    if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid compact JWT hash");
    if (expected.tokenHash().isPresent() && !expected.tokenHash().orElseThrow().equals(tokenHash))
      throw conflict();
    var stored =
        read(expected.operation()).orElseThrow(AccountControlUiIssuanceIntentRepository::conflict);
    if (!sameIdentity(stored, expected)) throw conflict();
    if (stored.tokenHash().isPresent()) {
      if (!stored.tokenHash().orElseThrow().equals(tokenHash)) throw conflict();
      return stored;
    }
    if (dsl.execute(
            "UPDATE account_control_ui_issuance_intents SET phase = 'HASH_BOUND', token_hash = ? "
                + "WHERE operation_id = ? AND jti = ? AND bundle_id = ? AND bundle_version = ? "
                + "AND source_version = ? AND source_fence = ? AND phase = 'PENDING' AND token_hash IS NULL",
            tokenHash,
            stored.operation().operationId(),
            expected.jti(),
            expected.bundleId(),
            expected.bundleVersion(),
            expected.sourceVersion(),
            expected.sourceFence())
        != 1) throw conflict();
    var readback =
        read(expected.operation()).orElseThrow(AccountControlUiIssuanceIntentRepository::conflict);
    if (!sameIdentity(readback, expected) || !readback.tokenHash().equals(Optional.of(tokenHash)))
      throw conflict();
    return readback;
  }

  private static boolean sameIdentity(
      AccountControlUiIssuanceIntent left, AccountControlUiIssuanceIntent right) {
    return left.operation().operationId().equals(right.operation().operationId())
        && left.operation().request().equals(right.operation().request())
        && left.operation().originalCapture().equals(right.operation().originalCapture())
        && left.jti().equals(right.jti())
        && left.bundleId().equals(right.bundleId())
        && left.bundleVersion() == right.bundleVersion()
        && left.sourceVersion() == right.sourceVersion()
        && left.sourceFence() == right.sourceFence();
  }

  private static void transaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new IllegalStateException("Writable Account owner transaction required");
  }

  private static IllegalStateException conflict() {
    return new IllegalStateException(
        "Original control-ui signing intent conflicts or is incomplete");
  }
}
