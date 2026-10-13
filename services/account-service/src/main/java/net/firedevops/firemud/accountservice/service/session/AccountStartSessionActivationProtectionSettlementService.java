package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository.StoredSettlement;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalReadClient;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalReadInput;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered, default-denied Account settlement composition for one original activation hold.
 *
 * <p>Only the exact same-namespace World workload may request recovery. Historical Account
 * authority is read without currentness or lease checks; authenticated World terminal evidence is
 * fetched outside Account SQL, then source locks precede immutable settlement storage. Absence,
 * timeout, generic close, and original expiry never settle or release protection.
 */
public final class AccountStartSessionActivationProtectionSettlementService {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final AccountStartSessionActivationProtectionRepository repository;
  private final WorldOriginalStartSessionActivationTerminalReadClient worldClient;
  private final TransactionTemplate ownerTransaction;
  private final String workloadNamespace;

  public AccountStartSessionActivationProtectionSettlementService(
      AccountStartSessionActivationProtectionRepository repository,
      WorldOriginalStartSessionActivationTerminalReadClient worldClient,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.repository =
        Objects.requireNonNull(repository, "activation protection repository required");
    this.worldClient = Objects.requireNonNull(worldClient, "World terminal client required");
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
   * Settles or recovers one exact historical protection. Selectors convey no authority; all remote
   * lookup identity is derived from the retained Account receipt.
   */
  public StoredSettlement settle(UUID protectionId, long protectionFence) {
    requireAuthenticatedWorldPeer();
    requireNoAuthenticatedEndUser();
    requireNoAmbientTransaction();
    if (protectionId == null || NIL_UUID.equals(protectionId) || protectionFence <= 0L) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Non-nil Account protection ID and positive fence required")
          .asRuntimeException();
    }

    Lookup lookup =
        Objects.requireNonNull(
            ownerTransaction.execute(
                ignored -> {
                  AccountStartSessionActivationProtectionEvidence evidence =
                      repository
                          .findHistoricalExact(protectionId, protectionFence)
                          .orElseThrow(
                              () ->
                                  Status.FAILED_PRECONDITION
                                      .withDescription(
                                          "Exact historical activation protection is unavailable")
                                      .asRuntimeException());
                  requireExactProtection(evidence, protectionId, protectionFence);
                  if (!workloadNamespace.equals(
                      evidence
                          .request()
                          .originalTuple()
                          .preAuthorizationTuple()
                          .action()
                          .scope()
                          .targetNamespace())) {
                    throw Status.PERMISSION_DENIED
                        .withDescription(
                            "Activation protection namespace differs from Account configuration")
                        .asRuntimeException();
                  }
                  Optional<StoredSettlement> prior =
                      repository.findSettlementExact(protectionId, protectionFence);
                  return new Lookup(evidence, prior.orElse(null));
                }),
            "Account historical activation transaction returned no result");
    if (lookup.priorSettlement() != null) {
      return requireReceipt(
          lookup.priorSettlement(), lookup.evidence(), protectionId, protectionFence);
    }

    WorldOriginalStartSessionActivationTerminalReadInput input =
        new WorldOriginalStartSessionActivationTerminalReadInput(
            UUID.randomUUID(), lookup.evidence());
    input.requireTargetNamespace(workloadNamespace);
    requireNoAmbientTransaction();
    WorldOriginalStartSessionActivationTerminalEvidence terminal = worldClient.read(input);
    requireNoAmbientTransaction();
    if (terminal == null) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              "World has no exact original activation terminal; Account protection remains pending")
          .asRuntimeException();
    }
    if (!MessageDigest.isEqual(
        lookup.evidence().canonicalBytes(),
        terminal.accountProtectionEvidence().canonicalBytes())) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              "World terminal evidence differs from the full first Account protection receipt")
          .asRuntimeException();
    }
    requireExactTerminal(terminal, lookup.evidence());

    requireNoAmbientTransaction();
    StoredSettlement committed =
        Objects.requireNonNull(
            ownerTransaction.execute(ignored -> repository.settleExact(terminal)),
            "Account terminal settlement transaction returned no receipt");
    requireReceipt(committed, lookup.evidence(), protectionId, protectionFence);
    if (!Arrays.equals(terminal.canonicalBytes(), committed.terminal().canonicalBytes())
        || !terminal.terminalDigest().equals(committed.terminal().terminalDigest())
        || terminal.outcome() != committed.outcome()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Account stored a different World activation terminal value")
          .asRuntimeException();
    }

    // The independent lookup distinguishes a confirmed durable receipt from an ambiguous commit
    // or lost response. It cannot write, adopt, renew, or release the source protection.
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
                                        "Committed activation settlement is not readable")
                                    .asRuntimeException())),
            "Account activation settlement readback returned no receipt");
    requireReceipt(readback, lookup.evidence(), protectionId, protectionFence);
    if (!sameSettlement(committed, readback)) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Committed Account activation settlement readback differs")
          .asRuntimeException();
    }
    return readback;
  }

  private void requireAuthenticatedWorldPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    String expected = "spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service";
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Authenticated World workload identity is required")
          .asRuntimeException();
    }
    if (!expected.equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription(
              "Only the exact same-namespace World workload may resolve activation protection")
          .asRuntimeException();
    }
  }

  private static void requireNoAuthenticatedEndUser() {
    if (SessionContext.hasAuthenticatedCallerContext()) {
      throw Status.PERMISSION_DENIED
          .withDescription("Internal activation settlement rejects end-user context")
          .asRuntimeException();
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Activation settlement requires independent owner transactions")
          .asRuntimeException();
    }
  }

  private static void requireExactProtection(
      AccountStartSessionActivationProtectionEvidence evidence,
      UUID protectionId,
      long protectionFence) {
    if (!protectionId.equals(evidence.accountProtectionId())
        || protectionFence != evidence.accountProtectionFence()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Historical activation protection differs from its lookup identity")
          .asRuntimeException();
    }
  }

  private static void requireExactTerminal(
      WorldOriginalStartSessionActivationTerminalEvidence terminal,
      AccountStartSessionActivationProtectionEvidence evidence) {
    if (!evidence
            .request()
            .worldActivationRequest()
            .activationRequestId()
            .equals(terminal.activationRequestId())
        || terminal.worldActivationFence() <= 0L
        || terminal.outcome() == null) {
      throw Status.FAILED_PRECONDITION
          .withDescription("World activation terminal receipt is not the exact protected operation")
          .asRuntimeException();
    }
  }

  private static StoredSettlement requireReceipt(
      StoredSettlement receipt,
      AccountStartSessionActivationProtectionEvidence evidence,
      UUID protectionId,
      long protectionFence) {
    Objects.requireNonNull(receipt, "stored Account settlement receipt required");
    WorldOriginalStartSessionActivationTerminalEvidence terminal =
        Objects.requireNonNull(receipt.terminal(), "stored World terminal required");
    if (!protectionId.equals(receipt.protectionId())
        || protectionFence != receipt.protectionFence()
        || receipt.outcome() != terminal.outcome()
        || !MessageDigest.isEqual(
            evidence.canonicalBytes(), terminal.accountProtectionEvidence().canonicalBytes())) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Stored settlement differs from exact historical activation protection")
          .asRuntimeException();
    }
    return receipt;
  }

  private static boolean sameSettlement(StoredSettlement left, StoredSettlement right) {
    return left.protectionId().equals(right.protectionId())
        && left.protectionFence() == right.protectionFence()
        && left.outcome() == right.outcome()
        && left.settledAt().equals(right.settledAt())
        && left.terminal().terminalDigest().equals(right.terminal().terminalDigest())
        && Arrays.equals(left.terminal().canonicalBytes(), right.terminal().canonicalBytes());
  }

  private record Lookup(
      AccountStartSessionActivationProtectionEvidence evidence, StoredSettlement priorSettlement) {}
}
