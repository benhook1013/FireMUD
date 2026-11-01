package net.firedevops.firemud.gamedesign.service.impl;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import net.firedevops.firemud.gamedesign.client.AccountTenantCreationCurrentAuthorizationClient;
import net.firedevops.firemud.gamedesign.client.AccountTenantCreationCurrentAuthorizationClient.VerifiedAuthorization;
import net.firedevops.firemud.gamedesign.repository.AccountAuthorizedTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.AccountAuthorizedTenantCreationRepository.AuthorizedDispatch;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OriginalOutcomeBinding;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OriginalOutcomeSnapshot;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OutcomeStatus;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered private receiver for exact, currently authorized Account tenant creation. */
public final class AccountAuthorizedTenantCreationService {
  private static final int MAX_SOURCE_KEY_CHARS = 72;
  private static final int MAX_SOURCE_KEY_CODE_POINTS = 36;
  private static final int MAX_NAME_CHARS = 200;
  private static final int MAX_NAME_CODE_POINTS = 100;
  private static final int MAX_DESCRIPTION_CHARS = 510;
  private static final int MAX_DESCRIPTION_CODE_POINTS = 255;
  private static final int MAX_DESCRIPTION_UTF8_BYTES = 1020;

  private final AccountTenantCreationCurrentAuthorizationClient authorizationClient;
  private final GameTenantCreationRepository creationRepository;
  private final GameTenantCreationOriginalOutcomeRepository outcomeRepository;
  private final AccountAuthorizedTenantCreationRepository dispatchRepository;
  private final TransactionTemplate ownerWrite;

  public AccountAuthorizedTenantCreationService(
      AccountTenantCreationCurrentAuthorizationClient authorizationClient,
      GameTenantCreationRepository creationRepository,
      GameTenantCreationOriginalOutcomeRepository outcomeRepository,
      AccountAuthorizedTenantCreationRepository dispatchRepository,
      PlatformTransactionManager transactionManager) {
    this.authorizationClient = Objects.requireNonNull(authorizationClient, "authorizationClient");
    this.creationRepository = Objects.requireNonNull(creationRepository, "creationRepository");
    this.outcomeRepository = Objects.requireNonNull(outcomeRepository, "outcomeRepository");
    this.dispatchRepository = Objects.requireNonNull(dispatchRepository, "dispatchRepository");
    this.ownerWrite = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.ownerWrite.setName("account-authorized-tenant-creation-write");
    this.ownerWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerWrite.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerWrite.setTimeout(15);
  }

  /**
   * Replays an exact terminal creation before checking mutable authority. A new attempt resolves
   * current Account authority outside SQL, then commits V30, V1033, and its V1034 deadline fence
   * together.
   */
  public FreshTenantCreationEvidence create(CandidateRequest request) {
    requireOutsideSql();
    Objects.requireNonNull(request, "request");
    validateCandidate(request);
    String recomputedDigest =
        GameTenantCreationDigest.requestDigest(
            request.binding().targetNamespace(),
            request.binding().creationRequestId(),
            request.sourceGameTenantKey(),
            request.name(),
            request.description());
    if (!recomputedDigest.equals(request.binding().creationRequestDigest())) {
      throw new IllegalArgumentException(
          "Creation inputs differ from the exact Account original request digest");
    }

    OriginalOutcomeBinding repositoryBinding = toRepositoryBinding(request.binding());
    Optional<OriginalOutcomeSnapshot> committed =
        outcomeRepository.readCommitted(repositoryBinding);
    if (committed.isPresent()) {
      return replayTerminal(request, repositoryBinding, committed.orElseThrow());
    }
    if (creationRepository
        .read(repositoryBinding.creationRequestId(), repositoryBinding.targetNamespace())
        .isPresent()) {
      throw new IllegalStateException(
          "A retained Game Design creation cannot be adopted into a new Account original");
    }

    // This authenticated Account call is deliberately before the write transaction and shared
    // V30/V1033 identity lock. Prepared source digests and request DTOs are never permission.
    VerifiedAuthorization authorization = authorizationClient.resolveCurrent(request.binding());
    if (!authorization.binding().equals(request.binding())
        || authorization.validBeforeEpochMillis() <= System.currentTimeMillis()) {
      throw new IllegalStateException(
          "Exact current Account tenant-creation authority is required");
    }

    WriteDisposition disposition =
        ownerWrite.execute(
            transaction -> {
              dispatchRepository.applyLocalWriteTimeouts();
              if (!dispatchRepository.isAuthorizationCurrent(
                  authorization.validBeforeEpochMillis())) {
                throw new IllegalStateException(
                    "Current Account tenant-creation authorization expired before owner writes");
              }

              OriginalOutcomeSnapshot claimed = outcomeRepository.claimLocked(repositoryBinding);
              if (claimed.status() == OutcomeStatus.CREATED) {
                return WriteDisposition.REPLAY;
              }
              if (claimed.status() == OutcomeStatus.NOT_CREATED) {
                throw new IllegalStateException(
                    "A definitively closed fresh tenant original cannot be reopened");
              }

              FreshTenantCreationEvidence receipt =
                  creationRepository.createCandidate(
                      repositoryBinding.targetNamespace(),
                      repositoryBinding.creationRequestId(),
                      request.sourceGameTenantKey(),
                      request.name(),
                      request.description());
              outcomeRepository.markCreatedLocked(repositoryBinding, receipt);
              dispatchRepository.recordAuthorizedDispatch(
                  repositoryBinding,
                  authorization.readRequestId(),
                  authorization.validBeforeEpochMillis());
              return WriteDisposition.CREATED;
            });
    if (disposition == null) {
      throw new IllegalStateException("Game Design creation transaction returned no disposition");
    }

    if (disposition == WriteDisposition.CREATED) {
      AuthorizedDispatch dispatch =
          dispatchRepository
              .readCommitted(repositoryBinding)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Committed Game Design creation lacks its authorization deadline fence"));
      if (!dispatch.authorizationReadRequestId().equals(authorization.readRequestId())
          || dispatch.validBeforeEpochMillis() != authorization.validBeforeEpochMillis()) {
        throw new IllegalStateException(
            "Committed Game Design authorization fence differs from the exact Account read");
      }
    }
    OriginalOutcomeSnapshot terminal =
        outcomeRepository
            .readCommitted(repositoryBinding)
            .orElseThrow(
                () ->
                    new IllegalStateException("Committed Game Design original outcome is absent"));
    if (!terminal.binding().equals(repositoryBinding)
        || terminal.status() != OutcomeStatus.CREATED) {
      throw new IllegalStateException("Committed Game Design original outcome is not CREATED");
    }
    return readAndVerifyReceipt(request, repositoryBinding, terminal);
  }

  private FreshTenantCreationEvidence replayTerminal(
      CandidateRequest request, OriginalOutcomeBinding binding, OriginalOutcomeSnapshot snapshot) {
    if (!snapshot.binding().equals(binding)) {
      throw new IllegalStateException("Stored Game Design original binding differs from request");
    }
    if (snapshot.status() == OutcomeStatus.NOT_CREATED) {
      throw new IllegalStateException("A definitively closed fresh tenant original cannot reopen");
    }
    if (snapshot.status() != OutcomeStatus.CREATED) {
      throw new IllegalStateException("A pending Game Design original cannot be replayed");
    }
    return readAndVerifyReceipt(request, binding, snapshot);
  }

  private FreshTenantCreationEvidence readAndVerifyReceipt(
      CandidateRequest request, OriginalOutcomeBinding binding, OriginalOutcomeSnapshot terminal) {
    FreshTenantCreationEvidence receipt =
        creationRepository
            .read(binding.creationRequestId(), binding.targetNamespace())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "CREATED Game Design original lacks its committed V30 receipt"));
    if (!receipt.targetNamespace().equals(binding.targetNamespace())
        || !receipt.creationRequestId().equals(binding.creationRequestId())
        || !receipt.requestDigest().equals(binding.creationRequestDigest())
        || !receipt.operationId().equals(terminal.creationOperationId())
        || !receipt.evidenceDigest().equals(terminal.creationEvidenceDigest())
        || !receipt.sourceGameTenantKey().equals(request.sourceGameTenantKey())) {
      throw new IllegalStateException(
          "Committed Game Design receipt differs from the exact original creation");
    }
    return receipt;
  }

  private static void validateCandidate(CandidateRequest request) {
    String sourceKey = request.sourceGameTenantKey();
    String name = request.name();
    String description = request.description();
    if (sourceKey.isBlank()
        || sourceKey.length() > MAX_SOURCE_KEY_CHARS
        || sourceKey.codePointCount(0, sourceKey.length()) > MAX_SOURCE_KEY_CODE_POINTS) {
      throw new IllegalArgumentException("sourceGameTenantKey exceeds the owner bounds");
    }
    if (name.length() > MAX_NAME_CHARS
        || name.codePointCount(0, name.length()) > MAX_NAME_CODE_POINTS) {
      throw new IllegalArgumentException("name exceeds the owner bounds");
    }
    if (description != null
        && (description.length() > MAX_DESCRIPTION_CHARS
            || description.codePointCount(0, description.length()) > MAX_DESCRIPTION_CODE_POINTS
            || GameTenantCreationDigest.utf8ByteLength(description) > MAX_DESCRIPTION_UTF8_BYTES)) {
      throw new IllegalArgumentException("description exceeds the owner bounds");
    }
    GameTenantCreationDigest.utf8ByteLength(sourceKey);
    GameTenantCreationDigest.utf8ByteLength(name);
  }

  private static OriginalOutcomeBinding toRepositoryBinding(Binding binding) {
    return new OriginalOutcomeBinding(
        binding.targetNamespace(),
        binding.creationRequestId(),
        binding.accountAuthorizationOperationId(),
        binding.initiatingAccountId(),
        binding.creationRequestDigest(),
        binding.accountAuthorizationDigest());
  }

  private static void requireOutsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Account current authorization must be resolved outside ambient SQL");
    }
  }

  public record CandidateRequest(
      UUID createRequestId,
      Binding binding,
      String sourceGameTenantKey,
      String name,
      String description) {
    public CandidateRequest {
      Objects.requireNonNull(createRequestId, "createRequestId");
      if (new UUID(0L, 0L).equals(createRequestId)) {
        throw new IllegalArgumentException("createRequestId must not be nil");
      }
      Objects.requireNonNull(binding, "binding");
      Objects.requireNonNull(sourceGameTenantKey, "sourceGameTenantKey");
      Objects.requireNonNull(name, "name");
    }
  }

  private enum WriteDisposition {
    CREATED,
    REPLAY
  }
}
