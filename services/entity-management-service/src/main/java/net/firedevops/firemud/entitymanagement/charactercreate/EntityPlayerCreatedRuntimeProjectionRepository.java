package net.firedevops.firemud.entitymanagement.charactercreate;

import io.grpc.Status;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.entitymanagement.service.RuntimeEntityId;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateOutcome;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import org.jooq.DSLContext;

/** Owner-internal storage; callers provide an existing owner transaction. Reads never allocate. */
final class EntityPlayerCreatedRuntimeProjectionRepository {
  void allocateFreshCreation(
      DSLContext tx, EntityCharacterCreateTerminal terminal, CharacterCreateOperatorAction action) {
    requireAction(terminal, action);
    int inserted =
        tx.execute(
            """
            INSERT INTO entity_player_created_runtime_projections
              (tenant_uuid, playable_state_namespace_id, game_instance_id, character_uuid,
               account_uuid, canonical_version_id, realm_uuid, target_namespace,
               creation_operation_id, mutation_digest, terminal_id, terminal_digest)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            UUID.fromString(action.scope().tenantId()),
            UUID.fromString(action.scope().playableStateNamespaceId()),
            action.target().gameInstanceId(),
            UUID.fromString(terminal.getCharacterId()),
            UUID.fromString(action.target().accountId()),
            UUID.fromString(action.target().versionId()),
            UUID.fromString(action.target().realmId()),
            action.scope().targetNamespace(),
            UUID.fromString(terminal.getAccountOriginal().getOperationId()),
            terminal.getAccountOriginal().getMutationDigest(),
            UUID.fromString(terminal.getTerminalId()),
            terminalSha256(terminal));
    if (inserted != 1) throw new IllegalStateException("Fresh creation runtime allocation failed");
  }

  EntityPlayerCreatedRuntimeReference read(
      DSLContext tx, EntityCharacterCreateTerminal expected, CharacterCreateOperatorAction action) {
    requireAction(expected, action);
    var original = expected.getAccountOriginal();
    var operation =
        tx.fetchOne(
            "SELECT * FROM entity_character_create_operations WHERE target_namespace = ? AND operation_id = ?",
            action.scope().targetNamespace(),
            UUID.fromString(original.getOperationId()));
    if (operation == null) throw unavailable();
    var actual = EntityCharacterCreateRepository.terminal(operation);
    if (!Arrays.equals(actual.toByteArray(), expected.toByteArray())) throw unavailable();
    // Exact action equality retains the full original target, source, epoch and pointer counters.
    requireAction(actual, action);
    var actor =
        tx.fetchOne(
            """
            SELECT authored_initial_core_json, creation_descriptor_json, creation_descriptor_digest
              FROM characters WHERE character_uuid = ? AND tenant_uuid = ? AND account_uuid = ?
               AND playable_state_namespace_id = ? AND playable_state_scope = 'PLAYABLE_STATE_SCOPE_SHARED'
               AND actor_entry_policy = 'PLAYER_CREATED' AND actor_identity_status = 'OWNER_RESOLVED'
               AND actor_identity_quarantine_reason IS NULL
               AND creation_target_namespace = ? AND creation_operation_id = ?
            """,
            UUID.fromString(actual.getCharacterId()),
            UUID.fromString(action.scope().tenantId()),
            UUID.fromString(action.target().accountId()),
            UUID.fromString(action.scope().playableStateNamespaceId()),
            action.scope().targetNamespace(),
            UUID.fromString(original.getOperationId()));
    var authored = action.mutation().authoredSource();
    if (actor == null
        || !authored.core().canonicalJson().equals(actor.get(0, String.class))
        || !authored.descriptor().canonicalJson().equals(actor.get(1, String.class))
        || !authored.descriptor().digest().equals(actor.get(2, String.class))) throw unavailable();
    String digest = terminalSha256(actual);
    var row =
        tx.fetchOne(
            """
            SELECT runtime_entity_id, projection_generation, projection_fence
              FROM entity_player_created_runtime_projections
             WHERE tenant_uuid = ? AND playable_state_namespace_id = ? AND game_instance_id = ?
               AND character_uuid = ? AND account_uuid = ? AND canonical_version_id = ? AND realm_uuid = ?
               AND target_namespace = ? AND creation_operation_id = ? AND mutation_digest = ?
               AND terminal_id = ? AND terminal_digest = ? AND projection_state = 'CURRENT'
               AND projection_generation = 1 AND projection_fence = 1
            """,
            UUID.fromString(action.scope().tenantId()),
            UUID.fromString(action.scope().playableStateNamespaceId()),
            action.target().gameInstanceId(),
            UUID.fromString(actual.getCharacterId()),
            UUID.fromString(action.target().accountId()),
            UUID.fromString(action.target().versionId()),
            UUID.fromString(action.target().realmId()),
            action.scope().targetNamespace(),
            UUID.fromString(original.getOperationId()),
            original.getMutationDigest(),
            UUID.fromString(actual.getTerminalId()),
            digest);
    if (row == null) throw unavailable();
    return new EntityPlayerCreatedRuntimeReference(
        new RuntimeEntityId(row.get("runtime_entity_id", Long.class)),
        row.get("projection_generation", Long.class),
        row.get("projection_fence", Long.class),
        actual,
        action,
        digest);
  }

  private static void requireAction(
      EntityCharacterCreateTerminal terminal, CharacterCreateOperatorAction action) {
    Objects.requireNonNull(terminal, "terminal");
    Objects.requireNonNull(action, "action");
    if (terminal.getOutcome()
            != EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED
        || !action.equals(
            EntityCharacterCreateBindings.requireOriginal(
                terminal.getOriginalRequest().getOriginal(), terminal.getAccountOriginal()))) {
      throw unavailable();
    }
  }

  private static String terminalSha256(EntityCharacterCreateTerminal terminal) {
    // SQL and the runtime-reference origin retain raw SHA256, without the content-digest prefix.
    return CommandSource.sha256(terminal.toByteArray()).substring("sha256:".length());
  }

  private static RuntimeException unavailable() {
    return Status.FAILED_PRECONDITION
        .withDescription("Original PLAYER_CREATED runtime projection is unavailable")
        .asRuntimeException();
  }
}
