package net.firedevops.firemud.gamesession.repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered durable preparation for logout of one exact admitted original NEW binding. */
final class CanonicalGameplayOriginalNewAdmittedLogoutIntentRepository {
  private final DSLContext dsl;

  CanonicalGameplayOriginalNewAdmittedLogoutIntentRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /**
   * Records the intent before any serving mutation. The caller obtains the current physical bytes
   * under protected Game Session custody. Only their hashes are retained.
   */
  Intent prepare(
      UUID logoutId,
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      OriginalNewCapacityExecution execution,
      byte[] observedFinalSession) {
    requireNoAmbientTransaction();
    var receipt =
        source
            .finalReceipt()
            .orElseThrow(CanonicalGameplayOriginalNewAdmittedLogoutIntentRepository::conflict);
    var candidate = source.candidate();
    if (!candidate.transition().transitionId().equals(execution.transitionId())
        || !source.candidateDigest().equals(execution.candidateDigest())
        || observedFinalSession.length == 0
        || observedFinalSession.length
            > CanonicalGameplayOriginalNewAuthenticatedSession.MAX_SESSION_BYTES
        || !receipt
            .finalSessionSha256()
            .equals(OriginalNewCapacityExecution.sha256(observedFinalSession))) {
      throw conflict();
    }
    var expected =
        new Intent(
            logoutId,
            execution.digest(),
            execution.transitionId(),
            candidate.transition().bindingGeneration(),
            receipt.finalSessionSha256(),
            sha1(observedFinalSession));
    dsl.transaction(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          var retained = read(transaction, expected.executionDigest());
          if (retained == null) {
            transaction.execute(
                """
                INSERT INTO game_session_original_new_admitted_logout_intent
                    (logout_id, execution_digest, transition_id, binding_generation,
                     original_final_session_sha256, original_session_sha1)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (execution_digest) DO NOTHING
                """,
                expected.logoutId(),
                expected.executionDigest(),
                expected.transitionId(),
                new BigDecimal(expected.bindingGeneration()),
                expected.originalFinalSessionSha256(),
                expected.originalSessionSha1());
            retained = read(transaction, expected.executionDigest());
          }
          if (!expected.equals(retained)) throw conflict();
        });
    var committed = readExact(expected.executionDigest(), logoutId);
    if (!expected.equals(committed)) throw conflict();
    return committed;
  }

  /** A lost reply resumes only the exact originally retained operation. */
  Intent readExact(String executionDigest, UUID logoutId) {
    requireNoAmbientTransaction();
    var retained = read(dsl, executionDigest);
    if (retained == null || !retained.logoutId().equals(logoutId)) throw conflict();
    return retained;
  }

  private static @Nullable Intent read(DSLContext db, String executionDigest) {
    Record row =
        db.fetchOne(
            """
            SELECT logout_id, execution_digest, transition_id, binding_generation,
                   original_final_session_sha256, original_session_sha1
              FROM game_session_original_new_admitted_logout_intent
             WHERE execution_digest = ?
            """,
            executionDigest);
    if (row == null) return null;
    return new Intent(
        Objects.requireNonNull(row.get("logout_id", UUID.class)),
        Objects.requireNonNull(row.get("execution_digest", String.class)),
        Objects.requireNonNull(row.get("transition_id", UUID.class)),
        Objects.requireNonNull(row.get("binding_generation", BigDecimal.class)).toBigIntegerExact(),
        Objects.requireNonNull(row.get("original_final_session_sha256", String.class)),
        Objects.requireNonNull(row.get("original_session_sha1", String.class)));
  }

  private static String sha1(byte[] bytes) {
    // Redis compares its native session digest. The durable FINAL independently requires SHA-256.
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Redis session comparison digest is unavailable", impossible);
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) throw conflict();
  }

  private static CanonicalGameplayBindingInventoryConflictException conflict() {
    return new CanonicalGameplayBindingInventoryConflictException(
        "Admitted logout does not match the retained original operation");
  }

  record Intent(
      UUID logoutId,
      String executionDigest,
      UUID transitionId,
      BigInteger bindingGeneration,
      String originalFinalSessionSha256,
      String originalSessionSha1) {
    Intent {
      TenantCapacityAdmission.uuid(logoutId);
      TenantCapacityAdmission.uuid(transitionId);
      if (!executionDigest.matches("sha256:[0-9a-f]{64}")
          || Objects.requireNonNull(bindingGeneration).signum() <= 0
          || !originalFinalSessionSha256.matches("[0-9a-f]{64}")
          || !originalSessionSha1.matches("[0-9a-f]{40}")) throw conflict();
    }
  }
}
