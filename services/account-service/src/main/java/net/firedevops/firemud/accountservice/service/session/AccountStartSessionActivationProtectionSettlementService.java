package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository.StoredProtection;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository.StoredSettlement;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionSettlementEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionSettlementInput;
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
 * Unregistered Account composition that settles only an authenticated exact World activation
 * terminal. Historical settlement does not renew authority or rearm an expired StartSession lease.
 */
public final class AccountStartSessionActivationProtectionSettlementService {
  private final AccountStartSessionActivationProtectionRepository repository;
  private final WorldOriginalStartSessionActivationTerminalReadClient worldClient;
  private final TransactionTemplate ownerTransaction;
  private final TransactionTemplate readbackTransaction;
  private final String workloadNamespace;

  public AccountStartSessionActivationProtectionSettlementService(
      AccountStartSessionActivationProtectionRepository repository,
      WorldOriginalStartSessionActivationTerminalReadClient worldClient,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.repository = Objects.requireNonNull(repository, "activation protection repository is required");
    this.worldClient = Objects.requireNonNull(worldClient, "World terminal reader is required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    ownerTransaction = transaction(transactionManager);
    readbackTransaction = transaction(transactionManager);
    readbackTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Settles the exact stored protection after an independent authenticated World terminal read. */
  public AccountStartSessionActivationProtectionSettlementEvidence settle(
      AccountStartSessionActivationProtectionSettlementInput input) {
    requireWorldPeer();
    requireNoAmbientTransaction();
    Objects.requireNonNull(input, "activation settlement input is required");
    if (!workloadNamespace.equals(input.targetNamespace())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Activation settlement target differs from the authenticated World peer")
          .asRuntimeException();
    }

    Lookup lookup =
        Objects.requireNonNull(
            ownerTransaction.execute(
                ignored -> {
                  StoredProtection protection =
                      repository
                          .findHistoricalExact(input.accountProtectionId(), input.accountProtectionFence())
                          .orElseThrow(
                              () ->
                                  failedPrecondition(
                                      "Exact historical Account activation protection is unavailable"));
                  requireTargetBinding(protection, input.targetNamespace());
                  StoredSettlement settled =
                      repository
                          .findSettlementExact(input.accountProtectionId(), input.accountProtectionFence())
                          .orElse(null);
                  return new Lookup(protection, settled);
                }),
            "Account activation-protection lookup transaction returned no result");

    if (lookup.settlement() != null) {
      return evidence(input.settlementRequestId(), lookup.settlement());
    }

    WorldOriginalStartSessionActivationTerminalReadInput readInput =
        new WorldOriginalStartSessionActivationTerminalReadInput(
            freshReadId(
                input.settlementRequestId(),
                input.accountProtectionId(),
                lookup.protection().evidence().request().worldActivationRequest().activationRequestId()),
            lookup.protection().evidence());
    requireNoAmbientTransaction();
    WorldOriginalStartSessionActivationTerminalEvidence terminal = worldClient.read(readInput);
    requireNoAmbientTransaction();
    if (terminal == null) {
      throw failedPrecondition("World activation remains unresolved without an exact terminal receipt");
    }
    if (!Arrays.equals(
        lookup.protection().evidence().canonicalBytes(),
        terminal.accountProtectionEvidence().canonicalBytes())) {
      throw failedPrecondition("World terminal readback differs from the exact Account protection");
    }

    StoredSettlement committed =
        Objects.requireNonNull(
            ownerTransaction.execute(ignored -> repository.settleExact(terminal)),
            "Account activation settlement transaction returned no value");

    // Use a fresh lookup transaction; repeating settleExact here could hide an uncertain commit.
    requireNoAmbientTransaction();
    StoredSettlement readback =
        Objects.requireNonNull(
            readbackTransaction.execute(
                ignored ->
                    repository
                        .findSettlementExact(input.accountProtectionId(), input.accountProtectionFence())
                        .orElseThrow(
                            () ->
                                failedPrecondition(
                                    "Committed World activation settlement is not readable"))),
            "Account activation settlement readback transaction returned no value");
    if (!sameSettlement(committed, readback)) {
      throw failedPrecondition("Committed World activation settlement readback differs");
    }
    return evidence(input.settlementRequestId(), readback);
  }

  private static AccountStartSessionActivationProtectionSettlementEvidence evidence(
      UUID requestId, StoredSettlement stored) {
    return new AccountStartSessionActivationProtectionSettlementEvidence(
        requestId, stored.terminal(), stored.settledAt());
  }

  private static boolean sameSettlement(StoredSettlement left, StoredSettlement right) {
    return left.terminalDigest().equals(right.terminalDigest())
        && left.settledAt().equals(right.settledAt())
        && Arrays.equals(left.terminalBytes(), right.terminalBytes());
  }

  private void requireTargetBinding(StoredProtection protection, String targetNamespace) {
    if (!targetNamespace.equals(
        protection
            .evidence()
            .request()
            .originalTuple()
            .preAuthorizationTuple()
            .action()
            .scope()
            .targetNamespace())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Stored activation protection belongs to another namespace")
          .asRuntimeException();
    }
  }

  private void requireWorldPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified World workload identity required")
          .asRuntimeException();
    }
    if (SessionContext.hasAuthenticatedCallerContext()
        || !workloadNamespace.equals(peer.namespace())
        || !peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service")) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace World workload without end-user context required")
          .asRuntimeException();
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("World terminal reads must run outside Account transactions")
          .asRuntimeException();
    }
  }

  private static UUID freshReadId(UUID... reserved) {
    UUID readId;
    do {
      readId = UUID.randomUUID();
    } while (Arrays.asList(reserved).contains(readId));
    return readId;
  }

  private static TransactionTemplate transaction(PlatformTransactionManager manager) {
    TransactionTemplate template = new TransactionTemplate(Objects.requireNonNull(manager));
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setReadOnly(false);
    return template;
  }

  private static RuntimeException failedPrecondition(String message) {
    return Status.FAILED_PRECONDITION.withDescription(message).asRuntimeException();
  }

  private record Lookup(StoredProtection protection, StoredSettlement settlement) {}
}
