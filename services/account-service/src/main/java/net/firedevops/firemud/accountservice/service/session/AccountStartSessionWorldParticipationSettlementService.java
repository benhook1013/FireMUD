package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredSettlement;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminalReadClient;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminalReadGrpcCodec;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminalReadRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Account composition that settles only authenticated exact World StartSession
 * terminal readback. Historical settlement is not fresh authorization, admission, or renewal.
 *
 * <p>The World owner recovery verifier and Game Session original-claim producer remain separate
 * prerequisites; this composition alone is not end-to-end execution proof.
 */
public final class AccountStartSessionWorldParticipationSettlementService {
  private final AccountStartSessionWorldParticipationRepository repository;
  private final WorldStartSessionExecutionTerminalReadClient worldClient;
  private final TransactionTemplate ownerTransaction;
  private final String workloadNamespace;

  public AccountStartSessionWorldParticipationSettlementService(
      AccountStartSessionWorldParticipationRepository repository,
      WorldStartSessionExecutionTerminalReadClient worldClient,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.repository = Objects.requireNonNull(repository, "participation repository is required");
    this.worldClient = Objects.requireNonNull(worldClient, "World terminal client is required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    ownerTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ownerTransaction.setReadOnly(false);
  }

  /**
   * Settles or recovers one exact participation using World-owned historical terminal evidence. The
   * request supplies only the immutable participation lookup identity; all terminal-read fields are
   * derived from Account's retained participation.
   */
  public StoredSettlement settle(UUID participationId, long participationFence) {
    requireWorldPeer();
    requireNoAmbientTransaction();
    Objects.requireNonNull(participationId, "participationId is required");
    if (participationId.equals(new UUID(0L, 0L)) || participationFence <= 0L) {
      throw new IllegalArgumentException("Non-nil participation ID and positive fence required");
    }

    Lookup lookup =
        Objects.requireNonNull(
            ownerTransaction.execute(
                ignored -> {
                  StoredParticipation participation =
                      repository
                          .findHistoricalExact(participationId, participationFence)
                          .orElseThrow(
                              () ->
                                  Status.FAILED_PRECONDITION
                                      .withDescription(
                                          "Exact historical World participation is unavailable")
                                      .asRuntimeException());
                  if (!workloadNamespace.equals(participation.targetNamespace())) {
                    throw Status.PERMISSION_DENIED
                        .withDescription(
                            "World participation target namespace differs from authenticated peer")
                        .asRuntimeException();
                  }
                  Optional<StoredSettlement> settled =
                      repository.findSettlementExact(participationId, participationFence);
                  return new Lookup(participation, settled.orElse(null));
                }),
            "Account participation lookup transaction returned no result");
    if (lookup.settlement() != null) return lookup.settlement();

    WorldStartSessionExecutionTerminalReadRequest request = requestFor(lookup.participation());
    requireNoAmbientTransaction();
    WorldStartSessionExecutionTerminal terminal = worldClient.read(request);
    if (terminal == null) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              "World StartSession execution remains unresolved without exact terminal proof")
          .asRuntimeException();
    }
    // Reuse the closed Common codec's complete identity check. The client already performs this
    // decode for a real response; this round trip keeps the service boundary explicit and testable.
    var response = WorldStartSessionExecutionTerminalReadGrpcCodec.toResponse(request, terminal);
    WorldStartSessionExecutionTerminal exactTerminal =
        WorldStartSessionExecutionTerminalReadGrpcCodec.fromResponse(request, response);

    requireNoAmbientTransaction();
    StoredSettlement committed =
        Objects.requireNonNull(
            ownerTransaction.execute(ignored -> repository.settleExact(exactTerminal)),
            "Account terminal settlement transaction returned no receipt");

    // This lookup-only transaction establishes durable post-commit readback. It must not call
    // settleExact again, since doing so could hide an uncertain first commit as an exact replay.
    requireNoAmbientTransaction();
    StoredSettlement readback =
        Objects.requireNonNull(
            ownerTransaction.execute(
                ignored ->
                    repository
                        .findSettlementExact(participationId, participationFence)
                        .orElseThrow(
                            () ->
                                Status.FAILED_PRECONDITION
                                    .withDescription(
                                        "Committed World terminal settlement is not readable")
                                    .asRuntimeException())),
            "Account terminal settlement readback transaction returned no receipt");
    if (!sameSettlement(committed, readback)) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Committed World terminal settlement readback differs")
          .asRuntimeException();
    }
    return readback;
  }

  private WorldStartSessionExecutionTerminalReadRequest requestFor(
      StoredParticipation participation) {
    return new WorldStartSessionExecutionTerminalReadRequest(
        freshReadId(participation),
        participation.targetNamespace(),
        participation.originalPostAuthorizationTuple(),
        participation.participationId(),
        participation.participationFence(),
        participation.gameSessionOwnerAttemptId(),
        participation.gameSessionOwnerFence(),
        participation.canonicalGameInstanceId(),
        participation.preparationInputJson());
  }

  private static UUID freshReadId(StoredParticipation participation) {
    UUID readId;
    do {
      readId = UUID.randomUUID();
    } while (readId.equals(participation.participationId())
        || readId.equals(participation.gameSessionOwnerAttemptId())
        || readId.equals(participation.canonicalGameInstanceId())
        || readId.equals(
            net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple
                .decode(participation.originalPostAuthorizationTuple())
                .reservationOwnerId()));
    return readId;
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
          .withDescription("World terminal settlement requires independent Account transactions")
          .asRuntimeException();
    }
  }

  private static boolean sameSettlement(StoredSettlement left, StoredSettlement right) {
    return left.participationId().equals(right.participationId())
        && left.outcome() == right.outcome()
        && left.worldExecutionFence() == right.worldExecutionFence()
        && left.terminalDigest().equals(right.terminalDigest())
        && java.util.Arrays.equals(left.terminalBytes(), right.terminalBytes());
  }

  private record Lookup(StoredParticipation participation, StoredSettlement settlement) {}
}
