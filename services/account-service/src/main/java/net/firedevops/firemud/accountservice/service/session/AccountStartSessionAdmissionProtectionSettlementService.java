package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository.StoredSettlement;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionSettlement;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalReadClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalRequest;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalResult;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered internal Account recovery composition for exact original StartSession admission
 * protection settlement.
 *
 * <p>This is not an external settlement permission or runtime registration. Historical terminal
 * recovery neither checks live expiry/currentness nor creates a new admission grant. The repository
 * owns source-lock-before-protection ordering and immutable retained-row comparison.
 */
public final class AccountStartSessionAdmissionProtectionSettlementService {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final AccountStartSessionAdmissionProtectionRepository repository;
  private final OriginalStartSessionAdmissionTerminalReadClient gameSessionClient;
  private final TransactionTemplate ownerTransaction;
  private final String workloadNamespace;

  public AccountStartSessionAdmissionProtectionSettlementService(
      AccountStartSessionAdmissionProtectionRepository repository,
      OriginalStartSessionAdmissionTerminalReadClient gameSessionClient,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.repository =
        Objects.requireNonNull(repository, "admission protection repository required");
    this.gameSessionClient =
        Objects.requireNonNull(gameSessionClient, "Game Session terminal client required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    ownerTransaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "Account transaction manager required"));
    ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ownerTransaction.setReadOnly(false);
  }

  /**
   * Settles or recovers one exact historical protection. The ID/fence are lookup selectors only;
   * all terminal-read identity is derived from retained Account evidence.
   */
  public StoredSettlement settle(UUID protectionId, long protectionFence) {
    requireNoAuthenticatedEndUser();
    requireNoAmbientTransaction();
    if (protectionId == null || NIL_UUID.equals(protectionId) || protectionFence <= 0L) {
      throw new IllegalArgumentException(
          "Non-nil Account protection ID and positive fence required");
    }

    Lookup lookup =
        Objects.requireNonNull(
            ownerTransaction.execute(
                ignored -> {
                  AccountStartSessionAdmissionProtectionEvidence evidence =
                      repository
                          .findHistoricalExact(protectionId, protectionFence)
                          .orElseThrow(
                              () ->
                                  Status.FAILED_PRECONDITION
                                      .withDescription(
                                          "Exact historical Account admission protection is unavailable")
                                      .asRuntimeException());
                  requireExactProtection(evidence, protectionId, protectionFence);
                  String retainedNamespace =
                      evidence
                          .request()
                          .originalTuple()
                          .preAuthorizationTuple()
                          .action()
                          .scope()
                          .targetNamespace();
                  if (!workloadNamespace.equals(retainedNamespace)) {
                    throw Status.PERMISSION_DENIED
                        .withDescription(
                            "Account admission protection target namespace differs from configuration")
                        .asRuntimeException();
                  }
                  Optional<StoredSettlement> receipt =
                      repository.findSettlementExact(protectionId, protectionFence);
                  return new Lookup(evidence, receipt.orElse(null));
                }),
            "Account historical protection transaction returned no result");
    if (lookup.receipt() != null) {
      return requireReceipt(lookup.receipt(), lookup.evidence(), protectionId, protectionFence);
    }

    OriginalStartSessionAdmissionTerminalRequest request =
        new OriginalStartSessionAdmissionTerminalRequest(lookup.evidence().canonicalBytes());
    requireNoAmbientTransaction();
    OriginalStartSessionAdmissionTerminalResult remoteResult = gameSessionClient.read(request);
    requireNoAmbientTransaction();
    if (remoteResult == null) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              "Original Game Session admission outcome remains unresolved without terminal evidence")
          .asRuntimeException();
    }
    if (!request.equals(remoteResult.request())
        || !Arrays.equals(request.canonicalBytes(), remoteResult.request().canonicalBytes())) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              "Game Session terminal result did not echo the complete Account protection evidence")
          .asRuntimeException();
    }
    OriginalStartSessionAdmissionTerminalResult exactResult =
        new OriginalStartSessionAdmissionTerminalResult(request, remoteResult.ownerProof());
    if (!Arrays.equals(remoteResult.canonicalBytes(), exactResult.canonicalBytes())) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Game Session terminal result is not its exact typed owner proof")
          .asRuntimeException();
    }
    AccountStartSessionAdmissionProtectionSettlement settlement =
        AccountStartSessionAdmissionProtectionSettlement.create(exactResult);

    requireNoAmbientTransaction();
    StoredSettlement committed =
        Objects.requireNonNull(
            ownerTransaction.execute(ignored -> repository.settleExact(settlement)),
            "Account terminal settlement transaction returned no receipt");
    requireReceipt(committed, lookup.evidence(), protectionId, protectionFence);
    if (!settlement.digest().equals(committed.settlement().digest())
        || settlement.outcome() != committed.outcome()
        || !Arrays.equals(settlement.canonicalBytes(), committed.settlement().canonicalBytes())) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Account settlement repository returned a different terminal value")
          .asRuntimeException();
    }

    // This separate lookup is intentionally read-only at the repository API level. In particular,
    // never call settleExact here: doing so could conceal an unknown first commit as an exact
    // retry.
    requireNoAmbientTransaction();
    StoredSettlement readback =
        Objects.requireNonNull(
            ownerTransaction.execute(
                ignored ->
                    repository
                        .findSettlementExact(protectionId, protectionFence)
                        .orElseThrow(
                            () ->
                                Status.FAILED_PRECONDITION
                                    .withDescription(
                                        "Committed Account admission settlement is not readable")
                                    .asRuntimeException())),
            "Account terminal settlement readback transaction returned no receipt");
    requireReceipt(readback, lookup.evidence(), protectionId, protectionFence);
    if (!sameSettlement(committed, readback)) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Committed Account admission settlement readback differs")
          .asRuntimeException();
    }
    return readback;
  }

  private static void requireNoAuthenticatedEndUser() {
    if (SessionContext.hasAuthenticatedCallerContext()) {
      throw Status.PERMISSION_DENIED
          .withDescription("Internal Account terminal recovery rejects end-user context")
          .asRuntimeException();
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Account terminal settlement requires independent transactions")
          .asRuntimeException();
    }
  }

  private static void requireExactProtection(
      AccountStartSessionAdmissionProtectionEvidence evidence,
      UUID protectionId,
      long protectionFence) {
    if (!protectionId.equals(evidence.accountProtectionId())
        || protectionFence != evidence.accountProtectionFence()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Historical Account protection differs from its exact lookup identity")
          .asRuntimeException();
    }
  }

  private static StoredSettlement requireReceipt(
      StoredSettlement receipt,
      AccountStartSessionAdmissionProtectionEvidence evidence,
      UUID protectionId,
      long protectionFence) {
    Objects.requireNonNull(receipt, "stored Account settlement receipt is required");
    Objects.requireNonNull(receipt.settledAt(), "stored Account settlement timestamp is required");
    AccountStartSessionAdmissionProtectionSettlement settlement =
        Objects.requireNonNull(receipt.settlement(), "stored Account settlement value is required");
    if (!protectionId.equals(receipt.protectionId())
        || protectionFence != receipt.protectionFence()
        || !protectionId.equals(settlement.protectionEvidence().accountProtectionId())
        || protectionFence != settlement.protectionEvidence().accountProtectionFence()
        || receipt.outcome() != settlement.outcome()
        || !Arrays.equals(
            evidence.canonicalBytes(), settlement.protectionEvidence().canonicalBytes())) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Stored Account settlement differs from exact historical protection")
          .asRuntimeException();
    }
    return receipt;
  }

  private static boolean sameSettlement(StoredSettlement left, StoredSettlement right) {
    return left.protectionId().equals(right.protectionId())
        && left.protectionFence() == right.protectionFence()
        && left.outcome() == right.outcome()
        && left.settledAt().equals(right.settledAt())
        && left.settlement().digest().equals(right.settlement().digest())
        && Arrays.equals(left.settlement().canonicalBytes(), right.settlement().canonicalBytes());
  }

  private record Lookup(
      AccountStartSessionAdmissionProtectionEvidence evidence, StoredSettlement receipt) {}
}
