package net.firedevops.firemud.entitymanagement.charactercreate;

import java.util.Objects;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateResponse;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.v1.CommitProtectedCharacterCreateRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldCharacterCreateLifecycleProtection;

/** Internal result of independent owner reads; never accepted as an RPC request. */
record EntityCharacterCreateProofs(
    CommitProtectedCharacterCreateRequest request,
    ReadOriginalCharacterCreateResponse original,
    ReadOriginalCharacterCreateProtectionResponse account,
    GameSessionCharacterCreateProtectionReader.Readback gameSession,
    WorldCharacterCreateLifecycleProtection world,
    EntityEmptySelectedSourceIntakeReceipt entitySource) {
  EntityCharacterCreateProofs {
    EntityCharacterCreateBindings.requireRequest(request);
    CharacterCreateOperatorAction action =
        EntityCharacterCreateBindings.requireOriginal(request.getOriginal(), original);
    EntityCharacterCreateBindings.requireAccountProtection(request, original, account);
    Objects.requireNonNull(gameSession);
    EntityCharacterCreateBindings.requireBound(
        world, EntityCharacterCreateBindings.MAX_WORLD_RESPONSE_BYTES);
    Objects.requireNonNull(entitySource);
    if (!account.equals(gameSession.accountProtection())
        || !request.getGameSessionProtection().equals(gameSession.reference())
        || !world.hasAccountProtection()
        || !account.equals(world.getAccountProtection())
        || !request.getWorldProtection().getProtectionId().equals(world.getWorldProtectionId())
        || request.getWorldProtection().getProtectionFence() != world.getWorldProtectionFence()
        || world.getOriginalLifecycleEvidence().isEmpty())
      throw EntityCharacterCreateBindings.conflict();
    for (String acquiredAt :
        java.util.List.of(
            account.getAcquiredAt(), gameSession.acquiredAt(), world.getAcquiredAt())) {
      // Each owner establishes acquisition validity with its own clock. Retain and parse its
      // original timestamp without comparing clocks belonging to different services.
      EntityCharacterCreateBindings.instant(acquiredAt);
    }
    if (!action.scope().targetNamespace().equals(entitySource.targetNamespace())
        || !action.scope().tenantId().equals(entitySource.canonicalTenantId().toString())
        || !action.target().versionId().equals(entitySource.canonicalVersionId().toString())
        || !action
            .mutation()
            .authoredSource()
            .selectedCommitId()
            .equals(entitySource.selectedCommitId().toString())) {
      throw EntityCharacterCreateBindings.conflict();
    }
    var lifecycle =
        WorldCanonicalInstanceLifecycleEvidence.fromStored(
            world.getOriginalLifecycleEvidence().toByteArray());
    var target = lifecycle.request();
    var authored = action.mutation().authoredSource();
    var launch = lifecycle.launchBinding();
    if (!authored
            .worldSourceOperationId()
            .equals(launch.descriptor().authoredWorldSourceOperationId().toString())
        || !authored
            .worldSourceEvidenceDigest()
            .equals(launch.descriptor().authoredWorldSourceEvidenceDigest())
        || !authored.selectedCommitId().equals(launch.releaseAttestation().commitId())
        || !authored
            .publicationWorkflowId()
            .equals(launch.releaseAttestation().publishWorkflowId()))
      throw EntityCharacterCreateBindings.conflict();
    if (!original.getOperationId().equals(target.readRequestId().toString())
        || !action.scope().targetNamespace().equals(target.targetNamespace())
        || !action.scope().tenantId().equals(target.canonicalTenantId().toString())
        || !action
            .scope()
            .playableStateNamespaceId()
            .equals(target.playableStateNamespaceId().toString())
        || !action.mutation().catalog().stateScope().equals(target.playableStateScope())
        || !action.target().gameInstanceId().equals(target.canonicalGameInstanceId().toString())
        || !action.target().versionId().equals(target.canonicalVersionId().toString())
        || !action.target().worldSlug().equals(target.worldSlug())
        || !target.publicProduction()
        || !"ACTIVE".equals(lifecycle.lifecycleStatus())
        || !action
            .expectedVersion()
            .activeLifecycleEpoch()
            .equals(Long.toString(lifecycle.lifecycleEpoch()))) {
      throw EntityCharacterCreateBindings.conflict();
    }
  }

  CharacterCreateOperatorAction action() {
    return EntityCharacterCreateBindings.requireOriginal(request.getOriginal(), original);
  }
}
