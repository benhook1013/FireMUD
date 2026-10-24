package net.firedevops.firemud.entitymanagement.charactercreate;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalPlayerCreatedRosterEvidence;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterResponse;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceRequest;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered composition for the two non-admitting PLAYER_CREATED roster owner reads. */
public final class EntityPlayerCreatedRosterService {
  private final String namespace;
  private final EntityPlayerCreatedRosterSourceClient sourceClient;
  private final GameSessionCanonicalGameplayRosterOwnerReadClient originalOwnerClient;
  private final EntityPlayerCreatedRosterSnapshotRepository snapshotRepository;
  private final CanonicalGameplayRosterRetentionRepository retentionRepository;
  private final TransactionOperations transactions;

  public EntityPlayerCreatedRosterService(
      String namespace,
      EntityPlayerCreatedRosterSourceClient sourceClient,
      GameSessionCanonicalGameplayRosterOwnerReadClient originalOwnerClient,
      EntityPlayerCreatedRosterSnapshotRepository snapshotRepository,
      CanonicalGameplayRosterRetentionRepository retentionRepository,
      TransactionOperations transactions) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException(
          "Entity Management workload namespace must be one DNS label");
    }
    this.namespace = namespace;
    this.sourceClient = Objects.requireNonNull(sourceClient, "sourceClient");
    this.originalOwnerClient = Objects.requireNonNull(originalOwnerClient, "originalOwnerClient");
    this.snapshotRepository = Objects.requireNonNull(snapshotRepository, "snapshotRepository");
    this.retentionRepository = Objects.requireNonNull(retentionRepository, "retentionRepository");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  /** Retains one local admission, resolves the exact owner operation, then reads current source. */
  public CanonicalPlayerCreatedRosterResponse list(CanonicalPlayerCreatedRosterRequest request) {
    requireNoAmbientTransaction();
    CanonicalPlayerCreatedRosterEvidence.requireRequest(request);
    UUID admission =
        requireTransactionResult(
            transactions.execute(status -> retentionRepository.reserveAdmission(request)),
            "PLAYER_CREATED admission reservation returned no nonce");
    var expectedOriginal =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedRequest(
            UUID.fromString(request.getRequestUuid()),
            UUID.fromString(request.getCanonicalAccountUuid()),
            UUID.fromString(request.getPlayerExecutionContext().getSessionId()),
            namespace,
            request.getExpectedTarget(),
            request.getExpectedSource());
    GetCanonicalGameplayRosterReferenceResponse original =
        originalOwnerClient.getCanonicalGameplayRosterReference(expectedOriginal);
    var checkedOriginal =
        CanonicalGameplayRosterReferenceEvidence.requireResponse(expectedOriginal, original);
    CanonicalGameplayRosterReferenceEvidence.requireDereference(checkedOriginal);
    var retained =
        requireTransactionResult(
            transactions.execute(
                status -> {
                  retentionRepository.requireAdmission(request, admission);
                  return retentionRepository.originalPlayerCreatedReceipt(checkedOriginal);
                }),
            "PLAYER_CREATED receipt lookup returned no result");
    if (retained.isPresent()) {
      return CanonicalPlayerCreatedRosterEvidence.requireResponse(request, retained.orElseThrow());
    }
    if (!checkedOriginal.getCanonicalPlayerCreatedRosterReceipt().isEmpty()) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RECEIPT_UNRETAINED");
    }

    var source = sourceClient.read(sourceRequest(namespace, request.getExpectedTarget()));
    return snapshotRepository.capture(request, source, checkedOriginal, admission);
  }

  /** Reads the actual current source and original owner before the repository's writable TX. */
  public CanonicalSelectedPlayerCreatedCharacterResponse selected(
      CanonicalSelectedPlayerCreatedCharacterRequest request) {
    requireNoAmbientTransaction();
    CanonicalPlayerCreatedRosterEvidence.requireSelectedRequest(request);
    var source = sourceClient.read(sourceRequest(namespace, request.getExpectedTarget()));
    var sourceIdentity = EntityPlayerCreatedRosterSnapshotRepository.sourceIdentity(source);
    if (!source.target().toWire().equals(request.getExpectedTarget())
        || !sourceIdentity.equals(request.getExpectedSource())) {
      throw new IllegalStateException("CANONICAL_ROSTER_PLAYER_CREATED_SOURCE_CHANGED");
    }
    var published = EntityPlayerCreatedRosterReadService.PublishedIdentity.from(source);
    var expectedOriginal =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedRequest(
            UUID.fromString(request.getRequestUuid()),
            UUID.fromString(request.getCanonicalAccountUuid()),
            UUID.fromString(request.getPlayerExecutionContext().getSessionId()),
            published.targetNamespace(),
            request.getExpectedTarget(),
            sourceIdentity);
    GetCanonicalGameplayRosterReferenceResponse original =
        originalOwnerClient.getCanonicalGameplayRosterReference(expectedOriginal);
    var checkedOriginal =
        CanonicalGameplayRosterReferenceEvidence.requireResponse(expectedOriginal, original);
    CanonicalGameplayRosterReferenceEvidence.requireDereference(checkedOriginal);
    return snapshotRepository.readSelected(request, source, checkedOriginal);
  }

  private static ReadPlayerCreatedRosterSourceRequest sourceRequest(
      String namespace,
      net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget target) {
    return ReadPlayerCreatedRosterSourceRequest.newBuilder()
        .setSchemaVersion(1)
        .setReadRequestId(UUID.randomUUID().toString())
        .setTargetNamespace(namespace)
        .setCanonicalTenantId(target.getTenantUuid())
        .setRealmId(target.getRealmUuid())
        .setWorldSlug(target.getWorldSlug())
        .setRealmSlug(target.getRealmSlug())
        .setPlayableStateNamespaceId(target.getPlayableStateNamespaceUuid())
        .setPlayableStateScope("SHARED")
        .setCatalogRevision(target.getCatalogRevision())
        .setPointerVersion(target.getPointerVersion())
        .setActiveWorldEpoch(target.getActiveWorldEpoch())
        .setCanonicalGameInstanceId(target.getGameInstanceUuid())
        .setExpectedCanonicalVersionId(target.getCanonicalVersionUuid())
        .build();
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "PLAYER_CREATED owner reads require no ambient Entity transaction");
    }
  }

  private static <T> T requireTransactionResult(T result, String message) {
    return Objects.requireNonNull(result, message);
  }
}
