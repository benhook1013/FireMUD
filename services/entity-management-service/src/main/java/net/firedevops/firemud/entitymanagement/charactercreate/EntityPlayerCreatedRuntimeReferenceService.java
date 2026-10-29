package net.firedevops.firemud.entitymanagement.charactercreate;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Objects;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest;
import net.firedevops.firemud.common.entity.CanonicalPlayerCreatedRuntimeReferenceEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateOutcome;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceResponse;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Manually composed, read-only owner service for original PLAYER_CREATED runtime provenance. */
public final class EntityPlayerCreatedRuntimeReferenceService {
  private final String namespace;
  private final DSLContext dsl;
  private final EntityCharacterCreateRepository creationRepository;
  private final EntityPlayerCreatedRuntimeProjectionRepository projectionRepository =
      new EntityPlayerCreatedRuntimeProjectionRepository();

  public EntityPlayerCreatedRuntimeReferenceService(
      String namespace, DSLContext dsl, EntityCharacterCreateRepository creationRepository) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical namespace required");
    }
    this.namespace = namespace;
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.creationRepository = Objects.requireNonNull(creationRepository, "creationRepository");
  }

  public PlayerCreatedActorRuntimeReferenceResponse readPlacementReceipt(
      PlayerCreatedActorRuntimeReferenceRequest request) {
    return read("ReadPlayerCreatedActorPlacementReceipt", request);
  }

  public PlayerCreatedActorRuntimeReferenceResponse readRuntimeReference(
      PlayerCreatedActorRuntimeReferenceRequest request) {
    return read("ReadPlayerCreatedActorRuntimeReference", request);
  }

  private PlayerCreatedActorRuntimeReferenceResponse read(
      String method, PlayerCreatedActorRuntimeReferenceRequest wire) {
    EntityPlayerCreatedRuntimeReferencePeerInterceptor.requirePeer(namespace, method);
    requireOutsideTransaction();
    PlayerCreatedActorRuntimeReferenceRequest request;
    try {
      request = CanonicalPlayerCreatedRuntimeReferenceEvidence.requireRequest(wire);
    } catch (IllegalArgumentException malformed) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Closed original PLAYER_CREATED runtime selectors required")
          .withCause(malformed)
          .asRuntimeException();
    }
    var origin = request.getOrigin();
    if (!namespace.equals(origin.getTargetNamespace())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Original runtime reference namespace does not match Entity owner")
          .asRuntimeException();
    }

    var originalRequest =
        ReadOriginalCharacterCreateRequest.newBuilder()
            .setTargetNamespace(origin.getTargetNamespace())
            .setOperationId(origin.getCreationOperationUuid())
            .setMutationDigest(origin.getMutationDigest())
            .build();
    EntityCharacterCreateTerminal terminal;
    try {
      terminal = creationRepository.read(originalRequest).orElseThrow(() -> unavailable());
    } catch (StatusRuntimeException failure) {
      // The shared writer reports digest reuse as a conflict; this read exposes one refusal.
      if (failure.getStatus().getCode() == Status.Code.ALREADY_EXISTS) throw unavailable();
      throw failure;
    }
    CharacterCreateOperatorAction action =
        EntityCharacterCreateBindings.requireOriginal(
            terminal.getOriginalRequest().getOriginal(), terminal.getAccountOriginal());
    requireSelector(request, terminal, action);

    EntityPlayerCreatedRuntimeReference retained =
        dsl.transactionResult(
            configuration -> {
              DSLContext tx = DSL.using(configuration);
              tx.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
              return projectionRepository.read(tx, terminal, action);
            });
    return CanonicalPlayerCreatedRuntimeReferenceEvidence.requireResponse(
        request, response(request, retained));
  }

  private static void requireSelector(
      PlayerCreatedActorRuntimeReferenceRequest request,
      EntityCharacterCreateTerminal terminal,
      CharacterCreateOperatorAction action) {
    var scope = action.scope();
    var target = action.target();
    var mutation = action.mutation();
    var origin = request.getOrigin();
    if (terminal.getOutcome()
            != EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED
        || !terminal.getTerminalId().equals(origin.getTerminalUuid())
        || !net.firedevops.firemud.common.gamedesign.CommandSource.sha256(terminal.toByteArray())
            .substring("sha256:".length())
            .equals(origin.getTerminalSha256())
        || !terminal.getCharacterId().equals(request.getCharacterUuid())
        || !origin.getTargetNamespace().equals(scope.targetNamespace())
        || !request.getTenantUuid().equals(scope.tenantId())
        || !request.getAccountUuid().equals(target.accountId())
        || !request.getPlayableStateNamespaceUuid().equals(scope.playableStateNamespaceId())
        || scope.playableStateScope()
            != CharacterCreateOperatorAction.PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
        || !request.getGameInstanceUuid().equals(target.gameInstanceId())
        || !request.getCanonicalVersionUuid().equals(target.versionId())
        || !request.getRealmUuid().equals(target.realmId())
        || !request.getWorldSlug().equals(target.worldSlug())
        || !request.getRealmSlug().equals(target.realmSlug())
        || !"CHARACTER_CREATE".equals(mutation.purpose())
        || !"PLAYER_CREATED".equals(mutation.entryPolicy())) {
      throw unavailable();
    }
  }

  private static PlayerCreatedActorRuntimeReferenceResponse response(
      PlayerCreatedActorRuntimeReferenceRequest request,
      EntityPlayerCreatedRuntimeReference reference) {
    var action = reference.action();
    var version = action.expectedVersion();
    var authored = action.mutation().authoredSource();
    return PlayerCreatedActorRuntimeReferenceResponse.newBuilder()
        .setSchemaVersion(1)
        .setSelector(request)
        .setRuntimeEntityId(reference.runtimeEntityId().value())
        .setProjectionGeneration(reference.projectionGeneration())
        .setProjectionFence(reference.projectionFence())
        .setOriginalCatalogRevision(canonicalPositiveLong(version.catalogRevision()))
        .setOriginalPointerVersion(canonicalPositiveLong(version.pointerVersion()))
        .setOriginalActiveLifecycleEpoch(canonicalPositiveLong(version.activeLifecycleEpoch()))
        .setOriginalDescriptorDigest(authored.descriptor().digest())
        .setOriginalAuthoredBindingDigest(authored.bindingDigest())
        .setOriginalPolicyContentDigest(authored.policy().contentDigest())
        .build();
  }

  private static long canonicalPositiveLong(String value) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0 || !Long.toString(parsed).equals(value)) throw unavailable();
      return parsed;
    } catch (NumberFormatException malformed) {
      throw unavailable();
    }
  }

  private static void requireOutsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Runtime reference owner reads require no ambient Entity transaction")
          .asRuntimeException();
    }
  }

  private static RuntimeException unavailable() {
    return Status.FAILED_PRECONDITION
        .withDescription("Original PLAYER_CREATED runtime evidence is unavailable")
        .asRuntimeException();
  }
}
