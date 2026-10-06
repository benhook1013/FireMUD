package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountControlUiIssuanceRequest;
import net.firedevops.firemud.accountservice.dto.AccountControlUiIssuanceRequestDigest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import net.firedevops.firemud.accountservice.service.controlui.AccountControlUiAuthenticationRequest.Purpose;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records fresh credential evidence against an existing V66 issuance, without creating an issuance
 * operation, capturing authority, signing, committing or activating a token.
 */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "The Account DSL collaborator remains internal.")
public class AccountControlUiCredentialOperationRepository {
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "Null rejection acquires no resources or secret state in this Spring bean.")
  public AccountControlUiCredentialOperationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /**
   * Called only after the Account owner has verified the current credential under its Account and
   * challenge locks. Single-use challenge deletion and immutable evidence commit atomically.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public RecordedAttempt recordVerifiedCredential(
      UUID requestId,
      Account account,
      Purpose purpose,
      Optional<AccountEmailLoginChallenge> matchedChallenge) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Control-UI credential recording requires an owner transaction");
    }
    Objects.requireNonNull(account);
    Objects.requireNonNull(purpose);
    Objects.requireNonNull(matchedChallenge);
    AccountControlUiIssuanceRequest request =
        new AccountControlUiIssuanceRequest(
            requestId.toString(), account.getAccountUuid().toString());
    Record operation =
        dsl.fetchOne(
            "SELECT * FROM account_control_ui_issuance_operations WHERE request_id = ? FOR UPDATE",
            requestId);
    if (operation == null
        || !requestId.equals(operation.get("request_id", UUID.class))
        || !account.getAccountUuid().equals(operation.get("account_uuid", UUID.class))
        || !account.getId().equals(operation.get("account_id", Long.class))
        || !account
            .getAccountUuidProvenance()
            .name()
            .equals(operation.get("account_provenance", String.class))
        || !"control-ui".equals(operation.get("profile", String.class))
        || !"control-ui".equals(operation.get("audience", String.class))
        || !Integer.valueOf(1).equals(operation.get("request_digest_version", Integer.class))
        || operation.get("request_digest", byte[].class) == null
        || !MessageDigest.isEqual(
            AccountControlUiIssuanceRequestDigest.digest(request),
            operation.get("request_digest", byte[].class))) {
      throw new CredentialOperationConflictException();
    }
    String lifecycle = operation.get("status", String.class);
    if ((purpose == Purpose.INITIAL_ISSUANCE && !"PENDING".equals(lifecycle))
        || (purpose == Purpose.EXACT_RESPONSE_RECOVERY
            && (!"COMMITTED".equals(lifecycle)
                || operation.get("expires_at", OffsetDateTime.class) == null
                || !operation
                    .get("expires_at", OffsetDateTime.class)
                    .toInstant()
                    .isAfter(java.time.Instant.now())))) {
      throw new CredentialOperationConflictException();
    }
    Long challengeId = null;
    if (matchedChallenge.isPresent()) {
      AccountEmailLoginChallenge challenge = matchedChallenge.orElseThrow();
      if (challenge.getId() == null
          || challenge.getId() <= 0L
          || !account.getId().equals(challenge.getAccountId())) {
        throw new CredentialOperationConflictException();
      }
      Record consumed =
          dsl.fetchOne(
              "DELETE FROM account_email_login_challenge WHERE id = ? AND account_id = ? "
                  + "AND code_hash = ? AND expires_at > CAST(? AS timestamp) "
                  + "AND invalid_attempt_count < 5 RETURNING id",
              challenge.getId(),
              account.getId(),
              challenge.getCodeHash(),
              LocalDateTime.now());
      if (consumed == null || !challenge.getId().equals(consumed.get("id", Long.class))) {
        throw new CredentialOperationConflictException();
      }
      challengeId = challenge.getId();
    }
    UUID operationId = operation.get("operation_id", UUID.class);
    UUID attemptId = UUID.randomUUID();
    String method = challengeId == null ? "PASSWORD" : "EMAIL_OTP";
    Record inserted =
        dsl.fetchOne(
            "INSERT INTO account_control_ui_credential_authentication_attempts "
                + "(attempt_id, operation_id, request_id, account_uuid, account_id, account_provenance, "
                + "purpose, authentication_method, consumed_challenge_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING attempt_id, operation_id, authenticated_at",
            attemptId,
            operationId,
            requestId,
            account.getAccountUuid(),
            account.getId(),
            account.getAccountUuidProvenance().name(),
            purpose.name(),
            method,
            challengeId);
    if (inserted == null
        || !attemptId.equals(inserted.get("attempt_id", UUID.class))
        || !operationId.equals(inserted.get("operation_id", UUID.class))) {
      throw new IllegalStateException("Control-UI credential evidence readback failed");
    }
    return new RecordedAttempt(
        attemptId,
        operationId,
        method,
        inserted.get("authenticated_at", OffsetDateTime.class).toInstant());
  }

  public record RecordedAttempt(
      UUID attemptId, UUID operationId, String method, java.time.Instant authenticatedAt) {}

  public static final class CredentialOperationConflictException extends RuntimeException {
    public CredentialOperationConflictException() {
      super("Fresh credential does not bind the exact original control-UI operation");
    }
  }
}
