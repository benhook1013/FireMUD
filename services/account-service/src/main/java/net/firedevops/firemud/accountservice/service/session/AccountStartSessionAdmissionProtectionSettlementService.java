package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository.StoredProtection;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository.StoredSettlement;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadClient;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadEvidence;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadInput;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionSettlementEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered Account composition for exact original Game Session admission settlement. */
public final class AccountStartSessionAdmissionProtectionSettlementService {
  private final AccountStartSessionAdmissionProtectionRepository repository;
  private final AccountInitialAdmissionSettlementReadClient gameSessionClient;
  private final TransactionTemplate lookupTransaction;
  private final TransactionTemplate ownerTransaction;
  private final TransactionTemplate readbackTransaction;
  private final String workloadNamespace;

  public AccountStartSessionAdmissionProtectionSettlementService(
      AccountStartSessionAdmissionProtectionRepository repository,
      AccountInitialAdmissionSettlementReadClient gameSessionClient,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.repository =
        Objects.requireNonNull(repository, "admission protection repository is required");
    this.gameSessionClient =
        Objects.requireNonNull(gameSessionClient, "Game Session terminal reader is required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    lookupTransaction = transaction(transactionManager);
    ownerTransaction = transaction(transactionManager);
    readbackTransaction = transaction(transactionManager);
    ownerTransaction.setReadOnly(false);
    readbackTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Settles or replays one exact Account protection from an independent Game Session owner read.
   */
  public AccountStartSessionAdmissionProtectionSettlementEvidence settle(
      UUID protectionId, long protectionFence) {
    requireGameSessionPeer();
    requireNoAmbientTransaction();
    requireNonnil(protectionId, "protectionId");
    if (protectionFence <= 0L)
      throw new IllegalArgumentException("protectionFence must be positive");

    Lookup lookup =
        Objects.requireNonNull(
            lookupTransaction.execute(
                ignored -> {
                  StoredProtection protection =
                      repository
                          .findHistoricalExact(protectionId, protectionFence)
                          .orElseThrow(
                              () ->
                                  failedPrecondition(
                                      "Exact historical Account admission protection is unavailable"));
                  requireExactProtection(protection, protectionId, protectionFence);
                  requireTargetBinding(protection);
                  StoredSettlement settled =
                      repository.findSettlementExact(protectionId, protectionFence).orElse(null);
                  return new Lookup(protection, settled);
                }),
            "Account admission protection lookup transaction returned no value");

    if (lookup.settlement() != null) {
      return settlementEvidence(lookup.settlement(), protectionId, protectionFence);
    }

    UUID readRequestId = freshReadId(protectionId, lookup.protection());
    AccountInitialAdmissionSettlementReadInput readInput =
        new AccountInitialAdmissionSettlementReadInput(
            readRequestId, lookup.protection().evidence());
    readInput.requireTargetNamespace(workloadNamespace);

    requireNoAmbientTransaction();
    AccountInitialAdmissionSettlementReadEvidence observed;
    try {
      observed = gameSessionClient.read(readInput);
    } catch (StatusRuntimeException failure) {
      throw dependencyFailure(failure);
    } catch (IllegalArgumentException malformed) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Game Session admission terminal response is malformed")
          .asRuntimeException();
    }
    requireNoAmbientTransaction();
    requireExactOwnerRead(readInput, observed);

    StoredSettlement committed =
        Objects.requireNonNull(
            ownerTransaction.execute(ignored -> repository.settleExact(observed)),
            "Account admission settlement transaction returned no value");
    AccountStartSessionAdmissionProtectionSettlementEvidence expectedSettlement =
        AccountStartSessionAdmissionProtectionSettlementEvidence.create(
            observed.protection(), observed.terminal());
    if (!Arrays.equals(committed.canonicalBytes(), expectedSettlement.canonicalBytes())
        || !committed.digest().equals(expectedSettlement.digest())) {
      throw failedPrecondition("Committed admission settlement differs from its owner read");
    }

    // A new lookup transaction verifies the first durable receipt. Never repeat the write here:
    // that could hide an uncertain commit as an exact retry.
    requireNoAmbientTransaction();
    StoredSettlement readback =
        Objects.requireNonNull(
            readbackTransaction.execute(
                ignored ->
                    repository
                        .findSettlementExact(protectionId, protectionFence)
                        .orElseThrow(
                            () ->
                                failedPrecondition(
                                    "Committed Game Session terminal settlement is not readable"))),
            "Account admission settlement readback transaction returned no value");
    if (!Arrays.equals(committed.canonicalBytes(), readback.canonicalBytes())) {
      throw failedPrecondition("Committed admission settlement readback differs");
    }
    return settlementEvidence(readback, protectionId, protectionFence);
  }

  private static AccountStartSessionAdmissionProtectionSettlementEvidence settlementEvidence(
      StoredSettlement stored, UUID expectedProtectionId, long expectedProtectionFence) {
    requireExactProtection(stored, expectedProtectionId, expectedProtectionFence);
    try {
      var evidence =
          AccountStartSessionAdmissionProtectionSettlementEvidence.create(
              stored.protection(), stored.terminal());
      if (!Arrays.equals(stored.canonicalBytes(), evidence.canonicalBytes())
          || !stored.digest().equals(evidence.digest())) {
        throw failedPrecondition("Stored Account admission settlement bytes or digest differ");
      }
      return evidence;
    } catch (IllegalArgumentException malformed) {
      throw failedPrecondition("Stored Account admission settlement is inconsistent");
    }
  }

  private static void requireExactProtection(
      StoredProtection stored, UUID expectedProtectionId, long expectedProtectionFence) {
    if (!expectedProtectionId.equals(stored.evidence().accountProtectionId())
        || stored.evidence().accountProtectionFence() != expectedProtectionFence) {
      throw failedPrecondition("Stored Account admission protection identity differs");
    }
  }

  private static void requireExactProtection(
      StoredSettlement stored, UUID expectedProtectionId, long expectedProtectionFence) {
    if (!expectedProtectionId.equals(stored.protection().accountProtectionId())
        || stored.protection().accountProtectionFence() != expectedProtectionFence) {
      throw failedPrecondition("Stored Account admission settlement identity differs");
    }
    if (stored.outcome() != stored.terminal().outcome()) {
      throw failedPrecondition("Stored Account admission terminal outcome differs");
    }
  }

  private void requireTargetBinding(StoredProtection protection) {
    String target =
        protection.evidence().request().worldAdmissionHoldIdentity().request().targetNamespace();
    if (!workloadNamespace.equals(target)) {
      throw Status.PERMISSION_DENIED
          .withDescription("Stored admission protection belongs to another namespace")
          .asRuntimeException();
    }
  }

  private static void requireExactOwnerRead(
      AccountInitialAdmissionSettlementReadInput expected,
      AccountInitialAdmissionSettlementReadEvidence observed) {
    if (observed == null
        || !expected.readRequestId().equals(observed.readRequestId())
        || !Arrays.equals(
            expected.protection().canonicalBytes(), observed.protection().canonicalBytes())
        || observed.terminal().outcome()
            == net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof
                .Outcome.PENDING
        || !Arrays.equals(
            expected.protection().request().worldAdmissionHoldIdentityBytes(),
            observed.terminal().holdIdentity().canonicalBytes())) {
      throw failedPrecondition(
          "Game Session terminal read differs from the exact Account protection");
    }
    try {
      AccountStartSessionAdmissionProtectionSettlementEvidence.create(
          expected.protection(), observed.terminal());
    } catch (IllegalArgumentException malformed) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Game Session terminal is not a valid exact admission outcome")
          .withCause(malformed)
          .asRuntimeException();
    }
  }

  private void requireGameSessionPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified Game Session workload identity required")
          .asRuntimeException();
    }
    if (SessionContext.hasAuthenticatedCallerContext()
        || !workloadNamespace.equals(peer.namespace())
        || !peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service")) {
      throw Status.PERMISSION_DENIED
          .withDescription(
              "Exact same-namespace Game Session workload without end-user context required")
          .asRuntimeException();
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Game Session terminal read requires an independent Account transaction")
          .asRuntimeException();
    }
  }

  private static UUID freshReadId(UUID protectionId, StoredProtection protection) {
    UUID id;
    do {
      id = UUID.randomUUID();
    } while (id.equals(protectionId)
        || id.equals(protection.evidence().request().accountWorldParticipationId())
        || id.equals(protection.evidence().request().gameSessionOwnerMutationId())
        || id.equals(protection.evidence().request().gameSessionOwnerAttemptId()));
    return id;
  }

  private static TransactionTemplate transaction(PlatformTransactionManager manager) {
    TransactionTemplate template = new TransactionTemplate(Objects.requireNonNull(manager));
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setReadOnly(false);
    return template;
  }

  private static void requireNonnil(UUID value, String field) {
    if (value == null || value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(field + " must be nonnil");
    }
  }

  private static StatusRuntimeException failedPrecondition(String description) {
    return Status.FAILED_PRECONDITION.withDescription(description).asRuntimeException();
  }

  private static StatusRuntimeException dependencyFailure(StatusRuntimeException failure) {
    Status.Code code = failure.getStatus().getCode();
    Status status =
        switch (code) {
          case UNAVAILABLE, DEADLINE_EXCEEDED, CANCELLED, RESOURCE_EXHAUSTED, INTERNAL, UNKNOWN ->
              Status.fromCode(code);
          default -> Status.FAILED_PRECONDITION;
        };
    return status
        .withDescription("Game Session admission terminal dependency failed")
        .asRuntimeException();
  }

  private record Lookup(StoredProtection protection, StoredSettlement settlement) {}
}
