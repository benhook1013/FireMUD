package net.firedevops.firemud.accountservice.service.controlui;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountControlUiIssuanceRequest;
import net.firedevops.firemud.accountservice.repository.AccountControlUiCurrentSourceRepository;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceIntent;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceIntentRepository;
import net.firedevops.firemud.accountservice.repository.AccountControlUiIssuanceOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unwired two-phase preparation for actual control-ui issuance. A durable reservation precedes
 * signing; a separate owner transaction binds the signer's exact returned compact-JWT hash. Neither
 * phase authenticates credentials, signs, completes a bundle, or activates a registry.
 */
public final class AccountControlUiPreSignIntentService {
  private final AccountControlUiCurrentSourceRepository sources;
  private final AccountRepository accounts;
  private final AccountControlUiIssuanceOperationRepository operations;
  private final AccountControlUiIssuanceIntentRepository intents;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected persistence owners remain internal collaborators.")
  public AccountControlUiPreSignIntentService(
      AccountControlUiCurrentSourceRepository sources,
      AccountRepository accounts,
      AccountControlUiIssuanceOperationRepository operations,
      AccountControlUiIssuanceIntentRepository intents) {
    this.sources = Objects.requireNonNull(sources);
    this.accounts = Objects.requireNonNull(accounts);
    this.operations = Objects.requireNonNull(operations);
    this.intents = Objects.requireNonNull(intents);
  }

  /** Caller must commit this owner transaction before invoking any signer. */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountControlUiIssuanceIntent prepare(AccountControlUiIssuanceRequest request) {
    Objects.requireNonNull(request);
    UUID proposedOperation = UUID.randomUUID();
    UUID proposedJti = UUID.randomUUID();
    UUID proposedBundle = UUID.randomUUID();
    // Issuer-first source locking precedes V66's Account/operation lock even on retry.
    var current = currentSource(request);
    var prior = operations.findByRequest(request);
    if (prior.isPresent()) {
      var original = prior.orElseThrow();
      if (!original.originalCapture().equals(current.originalCapture())) throw stale();
      return intents
          .read(original)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "Existing control-ui operation has no original signing reservation; cannot recapture"));
    }
    var claim =
        operations.claim(proposedOperation, request, Optional.of(current.originalCapture()));
    if (!claim.created())
      throw new IllegalStateException("Control-ui reservation lost its first-writer claim");
    return intents.reserve(claim.operation(), proposedJti, proposedBundle);
  }

  /** Hashes the exact signer output; this method does not verify its signature or authorize it. */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountControlUiIssuanceIntent bindSignedToken(
      AccountControlUiIssuanceIntent original, String compactJwt) {
    Objects.requireNonNull(original);
    if (compactJwt == null
        || !compactJwt.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) {
      throw new IllegalArgumentException("Exact compact JWT signer output required");
    }
    var current = currentSource(original.operation().request());
    if (!original.operation().originalCapture().equals(current.originalCapture())) throw stale();
    var operation =
        operations.findByRequest(original.operation().request()).orElseThrow(() -> stale());
    if (!operation.operationId().equals(original.operation().operationId())
        || !operation.originalCapture().equals(original.operation().originalCapture()))
      throw stale();
    try {
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(compactJwt.getBytes(StandardCharsets.US_ASCII)));
      return intents.bindTokenHash(original, hash);
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
  }

  private AccountControlUiOriginalSourceCapture currentSource(
      AccountControlUiIssuanceRequest request) {
    UUID accountUuid = UUID.fromString(request.accountUuid());
    var source = sources.readUnscopedCurrent(accountUuid);
    var account = accounts.findByAccountUuidForUpdate(accountUuid).orElseThrow(() -> stale());
    return AccountControlUiOriginalSourceCapture.fromCurrent(source, account);
  }

  private static IllegalStateException stale() {
    return new IllegalStateException("Original control-ui source is no longer exact and current");
  }
}
