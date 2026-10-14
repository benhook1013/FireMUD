package net.firedevops.firemud.gamesession.dto;

import java.util.UUID;
import net.firedevops.firemud.account.v1.RetireInitialGameplayLoginRequest;
import net.firedevops.firemud.account.v1.RetireInitialGameplayLoginResponse;

/**
 * Immutable nonsecret lifecycle evidence; never authentication, recovery or admission authority.
 */
public record InitialGameplayLoginDisposalResult(
    UUID requestId,
    UUID callerContextId,
    UUID disposalOperationId,
    Disposition disposition,
    ProofKind proofKind) {
  public InitialGameplayLoginDisposalResult {
    requireV4(requestId);
    requireV4(callerContextId);
    requireV4(disposalOperationId);
    if (disposition == null || proofKind == null) throw invalid();
    if (proofKind
        != switch (disposition) {
          case PENDING -> ProofKind.NONE;
          case RETIRED -> ProofKind.PHYSICAL_TOKEN_DENIAL;
          case NO_TOKEN -> ProofKind.NO_ISSUANCE_BINDING;
        }) throw invalid();
  }

  public enum Disposition {
    PENDING,
    RETIRED,
    NO_TOKEN
  }

  public enum ProofKind {
    NONE,
    PHYSICAL_TOKEN_DENIAL,
    NO_ISSUANCE_BINDING
  }

  /** Called only after the client authenticates the exact Account mTLS peer. */
  public static InitialGameplayLoginDisposalResult fromAuthenticatedResponse(
      RetireInitialGameplayLoginResponse response,
      RetireInitialGameplayLoginRequest originalRequest) {
    requireOriginalRequest(originalRequest);
    if (response == null
        || !response.getUnknownFields().asMap().isEmpty()
        || !originalRequest.getRequestId().equals(response.getRequestId())
        || !originalRequest.getCallerContextId().equals(response.getCallerContextId()))
      throw invalid();
    Disposition disposition =
        switch (response.getDisposition()) {
          case INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_PENDING -> Disposition.PENDING;
          case INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_RETIRED -> Disposition.RETIRED;
          case INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_NO_TOKEN -> Disposition.NO_TOKEN;
          default -> throw invalid();
        };
    ProofKind proofKind =
        switch (response.getProofKind()) {
          case INITIAL_GAMEPLAY_LOGIN_DISPOSAL_PROOF_KIND_UNSPECIFIED -> ProofKind.NONE;
          case INITIAL_GAMEPLAY_LOGIN_DISPOSAL_PROOF_KIND_PHYSICAL_TOKEN_DENIAL ->
              ProofKind.PHYSICAL_TOKEN_DENIAL;
          case INITIAL_GAMEPLAY_LOGIN_DISPOSAL_PROOF_KIND_NO_ISSUANCE_BINDING ->
              ProofKind.NO_ISSUANCE_BINDING;
          default -> throw invalid();
        };
    return new InitialGameplayLoginDisposalResult(
        uuid(response.getRequestId()),
        uuid(response.getCallerContextId()),
        uuid(response.getDisposalOperationId()),
        disposition,
        proofKind);
  }

  /** No token, expiry or current signing key is needed; a supplied witness must be complete. */
  public static void requireOriginalRequest(RetireInitialGameplayLoginRequest request) {
    if (request == null || !request.getUnknownFields().asMap().isEmpty()) throw invalid();
    requireV4(uuid(request.getRequestId()));
    requireV4(uuid(request.getCallerContextId()));
    if (request.hasTokenWitness()) {
      var witness = request.getTokenWitness();
      if (!witness.getUnknownFields().asMap().isEmpty()
          || !witness.getTokenSha256().matches("[0-9a-f]{64}")
          || witness.getTokenGeneration() != 1L
          || witness.getIssuanceFence() <= 0L) throw invalid();
      requireV4(uuid(witness.getTokenJti()));
    }
  }

  private static UUID uuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value) || id.variant() != 2 || id.equals(new UUID(0, 0)))
        throw invalid();
      return id;
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
  }

  private static void requireV4(UUID value) {
    if (value == null || value.version() != 4 || value.variant() != 2) throw invalid();
  }

  private static InvalidResultException invalid() {
    return new InvalidResultException();
  }

  public static final class InvalidResultException extends IllegalArgumentException {
    private InvalidResultException() {
      super("Invalid original LOGIN disposal evidence");
    }
  }
}
