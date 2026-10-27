package net.firedevops.firemud.entitymanagement.charactercreate;

import java.util.Objects;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.entitymanagement.service.RuntimeEntityId;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;

/**
 * Immutable non-admitting reference retaining the complete original creation target and provenance.
 */
record EntityPlayerCreatedRuntimeReference(
    RuntimeEntityId runtimeEntityId,
    long projectionGeneration,
    long projectionFence,
    EntityCharacterCreateTerminal creationTerminal,
    CharacterCreateOperatorAction action,
    String terminalDigest) {
  EntityPlayerCreatedRuntimeReference {
    Objects.requireNonNull(runtimeEntityId, "runtimeEntityId");
    Objects.requireNonNull(creationTerminal, "creationTerminal");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(terminalDigest, "terminalDigest");
    if (projectionGeneration != 1 || projectionFence != 1) {
      throw new IllegalArgumentException("Unsupported initial runtime projection generation/fence");
    }
  }
}
