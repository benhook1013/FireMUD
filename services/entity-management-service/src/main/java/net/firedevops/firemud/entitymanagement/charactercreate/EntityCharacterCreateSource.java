package net.firedevops.firemud.entitymanagement.charactercreate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.entitymanagement.sourceintake.EntityEmptySelectedSourceIntakeRepository;
import org.jooq.DSLContext;

/** Correlates published creation provenance with Entity's real immutable selected-source intake. */
public final class EntityCharacterCreateSource {
  private final DSLContext dsl;
  private final EntityEmptySelectedSourceIntakeRepository intakes;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected DSLContext and repository are internal service collaborators.")
  public EntityCharacterCreateSource(
      DSLContext dsl, EntityEmptySelectedSourceIntakeRepository intakes) {
    this.dsl = Objects.requireNonNull(dsl);
    this.intakes = Objects.requireNonNull(intakes);
  }

  public EntityEmptySelectedSourceIntakeReceipt read(CharacterCreateOperatorAction action) {
    var source = action.mutation().authoredSource();
    var association =
        dsl.fetchOne(
            "SELECT intake_request_id FROM entity_empty_selected_source_association "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ? AND canonical_version_id = ?",
            action.scope().targetNamespace(),
            UUID.fromString(action.scope().tenantId()),
            UUID.fromString(action.target().versionId()));
    if (association == null)
      throw new IllegalStateException("Genuine Entity selected source is unavailable");
    var receipt =
        intakes
            .read(action.scope().targetNamespace(), association.get(0, UUID.class))
            .orElseThrow(
                () -> new IllegalStateException("Entity selected source receipt is unavailable"));
    var owner =
        receipt.inputs().worldInventoryReadEvidence().inventory().publicEvidence().ownerScope();
    if (!action.scope().targetNamespace().equals(receipt.targetNamespace())
        || !action.scope().tenantId().equals(receipt.canonicalTenantId().toString())
        || !action.target().versionId().equals(receipt.canonicalVersionId().toString())
        || !source.selectedCommitId().equals(receipt.selectedCommitId().toString())
        || !source.selectedCommitDigest().equals(receipt.authorizationBinding().selected().digest())
        || !source.worldSourceOperationId().equals(owner.sourceOperationId().toString())
        || !source.worldSourceEvidenceDigest().equals(owner.sourceEvidenceDigest())) {
      throw EntityCharacterCreateBindings.conflict();
    }
    return receipt;
  }
}
