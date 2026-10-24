package net.firedevops.firemud.entitymanagement.charactercreate;

import com.google.protobuf.InvalidProtocolBufferException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection.AuthoredCore;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterActor;
import net.firedevops.firemud.entitymanagement.v1.CommitProtectedCharacterCreateRequest;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateOutcome;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered short owner transactions. No RPC, credential, external mapping or seed promotion.
 */
public final class EntityCharacterCreateRepository {
  private final DSLContext dsl;
  private final Clock clock;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected DSLContext is an internal service collaborator.")
  public EntityCharacterCreateRepository(DSLContext dsl, Clock clock) {
    this.dsl = Objects.requireNonNull(dsl);
    this.clock = Objects.requireNonNull(clock);
  }

  public Optional<EntityCharacterCreateTerminal> read(ReadOriginalCharacterCreateRequest request) {
    outsideTransaction();
    EntityCharacterCreateBindings.requireOriginalRequest(request);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM entity_character_create_operations WHERE target_namespace = ? AND operation_id = ?",
            request.getTargetNamespace(),
            UUID.fromString(request.getOperationId()));
    if (row == null) return Optional.empty();
    if (!request.getMutationDigest().equals(row.get("mutation_digest", String.class)))
      throw EntityCharacterCreateBindings.conflict();
    return Optional.of(terminal(row));
  }

  Optional<EntityCharacterCreateTerminal> replay(CommitProtectedCharacterCreateRequest request) {
    var result = read(request.getOriginal());
    result.ifPresent(terminal -> requireSameRequest(request, terminal));
    return result;
  }

  List<CanonicalGameplayRosterActor> readPlayerCreatedRoster(
      EntityPlayerCreatedRosterReadService.AuthenticatedAccountSelector account,
      EntityPlayerCreatedRosterReadService.PublishedIdentity published) {
    outsideTransaction();
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(published, "published");
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          tx.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
          var actors =
              tx.fetch(
                  "SELECT character_uuid, account_uuid, tenant_uuid, playable_state_namespace_id, playable_state_scope, actor_identity_status, actor_identity_quarantine_reason, name, creation_target_namespace, creation_operation_id, actor_entry_policy, authored_initial_core_json, creation_descriptor_json, creation_descriptor_digest FROM characters WHERE tenant_uuid = ? AND account_uuid = ? AND playable_state_namespace_id = ? AND playable_state_scope = 'PLAYABLE_STATE_SCOPE_SHARED' AND actor_entry_policy = 'PLAYER_CREATED' AND actor_identity_status = 'OWNER_RESOLVED' AND actor_identity_quarantine_reason IS NULL ORDER BY character_uuid",
                  published.tenantId(),
                  account.accountId(),
                  published.playableStateNamespaceId());
          var result = new java.util.ArrayList<CanonicalGameplayRosterActor>(actors.size());
          for (Record actor : actors) {
            readStoredPlayerCreatedTerminal(tx, account, published, actor, false);
            result.add(
                new CanonicalGameplayRosterActor(
                    actor.get("character_uuid", UUID.class), actor.get("name", String.class)));
          }
          return List.copyOf(result);
        });
  }

  EntityCharacterCreateTerminal readSelectedPlayerCreatedTerminal(
      EntityPlayerCreatedRosterReadService.AuthenticatedAccountSelector account,
      EntityPlayerCreatedRosterReadService.PublishedIdentity published,
      UUID characterId) {
    outsideTransaction();
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(published, "published");
    requireCanonicalCharacterId(characterId);
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          tx.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
          Record actor =
              tx.fetchOne(
                  "SELECT character_uuid, account_uuid, tenant_uuid, playable_state_namespace_id, playable_state_scope, actor_identity_status, actor_identity_quarantine_reason, name, creation_target_namespace, creation_operation_id, actor_entry_policy, authored_initial_core_json, creation_descriptor_json, creation_descriptor_digest FROM characters WHERE character_uuid = ? AND tenant_uuid = ? AND account_uuid = ? AND playable_state_namespace_id = ? AND playable_state_scope = 'PLAYABLE_STATE_SCOPE_SHARED' AND actor_entry_policy = 'PLAYER_CREATED' AND actor_identity_status = 'OWNER_RESOLVED' AND actor_identity_quarantine_reason IS NULL",
                  characterId,
                  published.tenantId(),
                  account.accountId(),
                  published.playableStateNamespaceId());
          if (actor == null) throw selectedPlayerCreatedActorUnavailable();
          return readStoredPlayerCreatedTerminal(tx, account, published, actor, true);
        });
  }

  /**
   * Reads the current persisted created roster inside an existing writable owner transaction. This
   * bounded path deliberately shares the same terminal and source-provenance verifier as the public
   * read path, while avoiding a nested read-only transaction.
   */
  static List<CanonicalGameplayRosterActor> readPlayerCreatedRosterWithinOwnerTransaction(
      DSLContext tx,
      EntityPlayerCreatedRosterReadService.AuthenticatedAccountSelector account,
      EntityPlayerCreatedRosterReadService.PublishedIdentity published) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(published, "published");
    var actors =
        tx.fetch(
            "SELECT character_uuid, account_uuid, tenant_uuid, playable_state_namespace_id, playable_state_scope, actor_identity_status, actor_identity_quarantine_reason, name, creation_target_namespace, creation_operation_id, actor_entry_policy, authored_initial_core_json, creation_descriptor_json, creation_descriptor_digest FROM characters WHERE tenant_uuid = ? AND account_uuid = ? AND playable_state_namespace_id = ? AND playable_state_scope = 'PLAYABLE_STATE_SCOPE_SHARED' AND actor_entry_policy = 'PLAYER_CREATED' AND actor_identity_status = 'OWNER_RESOLVED' AND actor_identity_quarantine_reason IS NULL ORDER BY character_uuid LIMIT 101 FOR SHARE",
            published.tenantId(),
            account.accountId(),
            published.playableStateNamespaceId());
    if (actors.size() > 100) {
      throw Status.RESOURCE_EXHAUSTED
          .withDescription("PLAYER_CREATED roster exceeds its bounded size")
          .asRuntimeException();
    }
    var result = new java.util.ArrayList<CanonicalGameplayRosterActor>(actors.size());
    for (Record actor : actors) {
      readStoredPlayerCreatedTerminal(tx, account, published, actor, false);
      result.add(
          new CanonicalGameplayRosterActor(
              actor.get("character_uuid", UUID.class), actor.get("name", String.class)));
    }
    return List.copyOf(result);
  }

  /** Reads one selected created terminal inside the caller's writable owner transaction. */
  static EntityCharacterCreateTerminal readSelectedPlayerCreatedTerminalWithinOwnerTransaction(
      DSLContext tx,
      EntityPlayerCreatedRosterReadService.AuthenticatedAccountSelector account,
      EntityPlayerCreatedRosterReadService.PublishedIdentity published,
      UUID characterId) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(published, "published");
    requireCanonicalCharacterId(characterId);
    Record actor =
        tx.fetchOne(
            "SELECT character_uuid, account_uuid, tenant_uuid, playable_state_namespace_id, playable_state_scope, actor_identity_status, actor_identity_quarantine_reason, name, creation_target_namespace, creation_operation_id, actor_entry_policy, authored_initial_core_json, creation_descriptor_json, creation_descriptor_digest FROM characters WHERE character_uuid = ? AND tenant_uuid = ? AND account_uuid = ? AND playable_state_namespace_id = ? AND playable_state_scope = 'PLAYABLE_STATE_SCOPE_SHARED' AND actor_entry_policy = 'PLAYER_CREATED' AND actor_identity_status = 'OWNER_RESOLVED' AND actor_identity_quarantine_reason IS NULL FOR SHARE",
            characterId,
            published.tenantId(),
            account.accountId(),
            published.playableStateNamespaceId());
    if (actor == null) throw selectedPlayerCreatedActorUnavailable();
    return readStoredPlayerCreatedTerminal(tx, account, published, actor, true);
  }

  private static EntityCharacterCreateTerminal readStoredPlayerCreatedTerminal(
      DSLContext tx,
      EntityPlayerCreatedRosterReadService.AuthenticatedAccountSelector account,
      EntityPlayerCreatedRosterReadService.PublishedIdentity published,
      Record actor,
      boolean sourceMismatchIsUnavailable) {
    UUID operationId = actor.get("creation_operation_id", UUID.class);
    String operationNamespace = actor.get("creation_target_namespace", String.class);
    if (operationId == null || operationNamespace == null) throw corruptRoster();
    Record operation =
        tx.fetchOne(
            "SELECT * FROM entity_character_create_operations WHERE target_namespace = ? AND operation_id = ?",
            operationNamespace,
            operationId);
    if (operation == null) throw corruptRoster();
    EntityCharacterCreateTerminal creation = terminal(operation);
    CharacterCreateOperatorAction action;
    try {
      action =
          EntityCharacterCreateBindings.requireOriginal(
              creation.getOriginalRequest().getOriginal(), creation.getAccountOriginal());
    } catch (RuntimeException malformed) {
      throw corruptRoster(malformed);
    }
    if (creation.getOutcome()
            != EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED
        || !action.target().accountId().equals(account.accountId().toString())
        || !creation.getCharacterId().equals(actor.get("character_uuid", UUID.class).toString())
        || !account.accountId().equals(actor.get("account_uuid", UUID.class))
        || !published.tenantId().equals(actor.get("tenant_uuid", UUID.class))
        || !published
            .playableStateNamespaceId()
            .equals(actor.get("playable_state_namespace_id", UUID.class))
        || !"PLAYABLE_STATE_SCOPE_SHARED".equals(actor.get("playable_state_scope", String.class))
        || !"OWNER_RESOLVED".equals(actor.get("actor_identity_status", String.class))
        || actor.get("actor_identity_quarantine_reason", String.class) != null
        || !"PLAYER_CREATED".equals(actor.get("actor_entry_policy", String.class))
        || !action
            .mutation()
            .authoredSource()
            .core()
            .canonicalJson()
            .equals(actor.get("authored_initial_core_json", String.class))
        || !action
            .mutation()
            .authoredSource()
            .descriptor()
            .canonicalJson()
            .equals(actor.get("creation_descriptor_json", String.class))
        || !action
            .mutation()
            .authoredSource()
            .descriptor()
            .digest()
            .equals(actor.get("creation_descriptor_digest", String.class))) throw corruptRoster();
    if (!published.matches(action) || !published.targetNamespace().equals(operationNamespace)) {
      if (sourceMismatchIsUnavailable) throw selectedPlayerCreatedActorUnavailable();
      throw publishedPlayerCreatedSourceMismatch();
    }
    return creation;
  }

  static void requireCanonicalCharacterId(UUID characterId) {
    Objects.requireNonNull(characterId, "characterId");
    if (characterId.equals(new UUID(0L, 0L))) throw EntityCharacterCreateBindings.invalid();
  }

  private static RuntimeException selectedPlayerCreatedActorUnavailable() {
    return Status.FAILED_PRECONDITION
        .withDescription("Selected PLAYER_CREATED character is unavailable")
        .asRuntimeException();
  }

  private static RuntimeException publishedPlayerCreatedSourceMismatch() {
    return Status.FAILED_PRECONDITION
        .withDescription("Current PLAYER_CREATED source does not match the retained character")
        .asRuntimeException();
  }

  private static RuntimeException corruptRoster() {
    return Status.DATA_LOSS
        .withDescription("Stored PLAYER_CREATED roster provenance is inconsistent")
        .asRuntimeException();
  }

  private static RuntimeException corruptRoster(Throwable cause) {
    return Status.DATA_LOSS
        .withDescription("Stored PLAYER_CREATED roster provenance is inconsistent")
        .withCause(cause)
        .asRuntimeException();
  }

  EntityCharacterCreateTerminal settle(EntityCharacterCreateProofs proof, String abortRequestId) {
    outsideTransaction();
    if (abortRequestId != null) EntityCharacterCreateBindings.id(abortRequestId);
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          tx.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
          var action = proof.action();
          UUID tenant = UUID.fromString(action.scope().tenantId());
          UUID namespace = UUID.fromString(action.scope().playableStateNamespaceId());
          UUID account = UUID.fromString(action.target().accountId());
          UUID operation = UUID.fromString(proof.original().getOperationId());
          UUID requestId = UUID.fromString(proof.original().getCreateCharacterRequestId());
          String workloadNamespace = proof.request().getOriginal().getTargetNamespace();
          String scope = action.scope().playableStateScope().name();
          // The selected intake association is actual Entity owner storage, not a numeric ID
          // bridge.
          var association =
              tx.fetchOne(
                  "SELECT receipt_bytes FROM entity_empty_selected_source_receipt WHERE target_namespace = ? AND intake_request_id = ? FOR KEY SHARE",
                  workloadNamespace,
                  proof.entitySource().intakeRequestId());
          if (association == null
              || !Arrays.equals(
                  association.get(0, byte[].class), proof.entitySource().canonicalBytes())) {
            throw EntityCharacterCreateBindings.conflict();
          }
          tx.execute(
              "INSERT INTO entity_playable_state_namespace_scopes (tenant_uuid, playable_state_namespace_id, playable_state_scope) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
              tenant,
              namespace,
              scope);
          var enrolled =
              tx.fetchOne(
                  "SELECT playable_state_scope FROM entity_playable_state_namespace_scopes WHERE tenant_uuid = ? AND playable_state_namespace_id = ? FOR UPDATE",
                  tenant,
                  namespace);
          if (enrolled == null || !scope.equals(enrolled.get(0, String.class)))
            throw EntityCharacterCreateBindings.conflict();
          tx.execute(
              "INSERT INTO entity_character_create_operations (target_namespace, operation_id, create_character_request_id, mutation_digest, tenant_uuid, playable_state_namespace_id, playable_state_scope, account_uuid, canonical_version_id, original_request, original_account, original_account_protection, original_game_session_protection, original_world_protection, entity_source_receipt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
              workloadNamespace,
              operation,
              requestId,
              proof.original().getMutationDigest(),
              tenant,
              namespace,
              scope,
              account,
              UUID.fromString(action.target().versionId()),
              proof.request().toByteArray(),
              proof.original().toByteArray(),
              proof.account().toByteArray(),
              proof.gameSession().originalOwnerReadback().toByteArray(),
              proof.world().toByteArray(),
              proof.entitySource().canonicalBytes());
          var candidates =
              tx.fetch(
                  "SELECT * FROM entity_character_create_operations WHERE (target_namespace = ? AND operation_id = ?) OR (tenant_uuid = ? AND playable_state_namespace_id = ? AND account_uuid = ? AND create_character_request_id = ?) FOR UPDATE",
                  workloadNamespace,
                  operation,
                  tenant,
                  namespace,
                  account,
                  requestId);
          if (candidates.size() != 1) throw EntityCharacterCreateBindings.conflict();
          Record row = candidates.getFirst();
          requireSameProof(row, proof);
          if (!"PENDING".equals(row.get("status", String.class))) return terminal(row);

          // Match the existing canonical assignment writer's real tenant row lock before
          // zero-roster
          // observation, then lock the exact account/namespace roster scope. No identity is
          // derived.
          tx.execute(
              "INSERT INTO entity_tenant_identities (canonical_tenant_uuid) VALUES (?) ON CONFLICT DO NOTHING",
              tenant);
          var tenantRow =
              tx.fetchOne(
                  "SELECT entity_tenant_row_id FROM entity_tenant_identities WHERE canonical_tenant_uuid = ? FOR UPDATE",
                  tenant);
          if (tenantRow == null)
            throw new IllegalStateException("Entity tenant identity unavailable");
          tx.execute(
              "INSERT INTO entity_character_create_roster_guards (tenant_uuid, playable_state_namespace_id, account_uuid) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
              tenant,
              namespace,
              account);
          tx.fetchOne(
              "SELECT account_uuid FROM entity_character_create_roster_guards WHERE tenant_uuid = ? AND playable_state_namespace_id = ? AND account_uuid = ? FOR UPDATE",
              tenant,
              namespace,
              account);
          UUID character = null;
          if (abortRequestId == null) {
            // Recheck expiry after every potentially blocking lock and immediately before
            // allocation.
            requireCurrentAuthority(proof);
            if (!tx.fetch(
                    "SELECT character_uuid FROM characters WHERE tenant_uuid = ? AND playable_state_namespace_id = ? AND account_uuid = ? FOR UPDATE",
                    tenant,
                    namespace,
                    account)
                .isEmpty()) {
              throw Status.FAILED_PRECONDITION
                  .withDescription("PLAYER_CREATED roster is not empty")
                  .asRuntimeException();
            }
            requireCurrentAuthority(proof);
            character = UUID.randomUUID();
            String core = action.mutation().authoredSource().core().canonicalJson();
            int inserted =
                tx.execute(
                    "INSERT INTO characters (character_uuid, account_uuid, tenant_uuid, playable_state_namespace_id, playable_state_scope, actor_identity_status, actor_identity_quarantine_reason, tenant_id, account_id, playable_state_key, name, level, experience, strength, agility, intelligence, stamina, health, mana, body_layout_key, creation_target_namespace, creation_operation_id, actor_entry_policy, authored_initial_core_json, creation_descriptor_json, creation_descriptor_digest) VALUES (?, ?, ?, ?, ?, 'OWNER_RESOLVED', NULL, ?, NULL, NULL, ?, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, ?, ?, 'PLAYER_CREATED', ?, ?, ?) ON CONFLICT DO NOTHING",
                    character,
                    account,
                    tenant,
                    namespace,
                    scope,
                    tenantRow.get(0, Long.class),
                    new AuthoredCore(core).displayName(),
                    workloadNamespace,
                    operation,
                    core,
                    action.mutation().authoredSource().descriptor().canonicalJson(),
                    action.mutation().authoredSource().descriptor().digest());
            if (inserted != 1)
              throw Status.FAILED_PRECONDITION
                  .withDescription("Canonical roster changed before creation")
                  .asRuntimeException();
          }
          var builder =
              EntityCharacterCreateTerminal.newBuilder()
                  .setOriginalRequest(proof.request())
                  .setAccountOriginal(proof.original())
                  .setOriginalAccountProtection(proof.account().toByteString())
                  .setOriginalGameSessionProtection(proof.gameSession().originalOwnerReadback())
                  .setOriginalWorldProtection(proof.world().toByteString())
                  .setEntityIntakeRequestId(proof.entitySource().intakeRequestId().toString())
                  .setEntitySourceReceiptDigest(proof.entitySource().receiptDigest())
                  .setOutcome(
                      character == null
                          ? EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_ABORTED
                          : EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED)
                  .setTerminalId(UUID.randomUUID().toString())
                  .setTerminalFence(1L)
                  .setCompletedAt(clock.instant().toString());
          if (character != null) builder.setCharacterId(character.toString());
          if (abortRequestId != null) builder.setAbortRequestId(abortRequestId);
          var terminal = builder.build();
          EntityCharacterCreateBindings.requireBound(
              terminal, EntityCharacterCreateBindings.MAX_TERMINAL_BYTES);
          int updated =
              tx.execute(
                  "UPDATE entity_character_create_operations SET status = ?, terminal_bytes = ?, character_uuid = ? WHERE target_namespace = ? AND operation_id = ? AND status = 'PENDING'",
                  character == null ? "ABORTED" : "COMMITTED",
                  terminal.toByteArray(),
                  character,
                  workloadNamespace,
                  operation);
          if (updated != 1)
            throw new IllegalStateException("Creation terminal serialization failed");
          return terminal;
        });
  }

  private void requireCurrentAuthority(EntityCharacterCreateProofs proof) {
    // This is Entity's local admission deadline. Remote observation/acquisition timestamps remain
    // exact retained owner values and are never compared with Entity's clock.
    if (!clock.instant().isBefore(proof.action().expectedVersion().connectScopeExpiresAt())) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Original creation authority expired; no abort inferred")
          .asRuntimeException();
    }
  }

  private static void requireSameProof(Record row, EntityCharacterCreateProofs proof) {
    if (!Arrays.equals(row.get("original_request", byte[].class), proof.request().toByteArray())
        || !Arrays.equals(row.get("original_account", byte[].class), proof.original().toByteArray())
        || !Arrays.equals(
            row.get("original_account_protection", byte[].class), proof.account().toByteArray())
        || !Arrays.equals(
            row.get("original_game_session_protection", byte[].class),
            proof.gameSession().originalOwnerReadback().toByteArray())
        || !Arrays.equals(
            row.get("original_world_protection", byte[].class), proof.world().toByteArray())
        || !Arrays.equals(
            row.get("entity_source_receipt", byte[].class), proof.entitySource().canonicalBytes()))
      throw EntityCharacterCreateBindings.conflict();
  }

  private static EntityCharacterCreateTerminal terminal(Record row) {
    byte[] bytes = row.get("terminal_bytes", byte[].class);
    if (bytes == null)
      throw Status.UNAVAILABLE
          .withDescription("Creation has no definitive terminal")
          .asRuntimeException();
    try {
      var terminal = EntityCharacterCreateTerminal.parseFrom(bytes);
      EntityCharacterCreateBindings.exact(terminal);
      EntityCharacterCreateBindings.requireRequest(terminal.getOriginalRequest());
      var action =
          EntityCharacterCreateBindings.requireOriginal(
              terminal.getOriginalRequest().getOriginal(), terminal.getAccountOriginal());
      if (!action.scope().tenantId().equals(row.get("tenant_uuid", UUID.class).toString())
          || !action
              .scope()
              .playableStateNamespaceId()
              .equals(row.get("playable_state_namespace_id", UUID.class).toString())
          || !action
              .scope()
              .playableStateScope()
              .name()
              .equals(row.get("playable_state_scope", String.class))
          || !action.target().accountId().equals(row.get("account_uuid", UUID.class).toString())
          || !action
              .target()
              .versionId()
              .equals(row.get("canonical_version_id", UUID.class).toString())
          || !terminal
              .getAccountOriginal()
              .getCreateCharacterRequestId()
              .equals(row.get("create_character_request_id", UUID.class).toString())) {
        throw new IllegalStateException(
            "Stored creation target differs from its immutable original");
      }
      EntityCharacterCreateBindings.id(terminal.getTerminalId());
      EntityCharacterCreateBindings.instant(terminal.getCompletedAt());
      var source =
          net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt
              .fromStored(row.get("entity_source_receipt", byte[].class));
      if (!action.scope().targetNamespace().equals(source.targetNamespace())
          || !action.scope().tenantId().equals(source.canonicalTenantId().toString())
          || !action.target().versionId().equals(source.canonicalVersionId().toString())
          || !action
              .mutation()
              .authoredSource()
              .selectedCommitId()
              .equals(source.selectedCommitId().toString())
          || !action
              .mutation()
              .authoredSource()
              .selectedCommitDigest()
              .equals(source.authorizationBinding().selected().digest())
          || !terminal.getEntityIntakeRequestId().equals(source.intakeRequestId().toString())
          || !terminal.getEntitySourceReceiptDigest().equals(source.receiptDigest()))
        throw new IllegalStateException("Corrupt creation source identity");
      boolean committed =
          terminal.getOutcome()
              == EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED;
      boolean aborted =
          terminal.getOutcome()
              == EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_ABORTED;
      if ((!committed && !aborted)
          || terminal.getTerminalFence() != 1L
          || !Arrays.equals(bytes, terminal.toByteArray())
          || !Arrays.equals(
              row.get("original_request", byte[].class),
              terminal.getOriginalRequest().toByteArray())
          || !Arrays.equals(
              row.get("original_account", byte[].class),
              terminal.getAccountOriginal().toByteArray())
          || !Arrays.equals(
              row.get("original_account_protection", byte[].class),
              terminal.getOriginalAccountProtection().toByteArray())
          || !Arrays.equals(
              row.get("original_game_session_protection", byte[].class),
              terminal.getOriginalGameSessionProtection().toByteArray())
          || !Arrays.equals(
              row.get("original_world_protection", byte[].class),
              terminal.getOriginalWorldProtection().toByteArray())
          || !terminal
              .getOriginalRequest()
              .getOriginal()
              .getTargetNamespace()
              .equals(row.get("target_namespace", String.class))
          || !terminal
              .getOriginalRequest()
              .getOriginal()
              .getOperationId()
              .equals(row.get("operation_id", UUID.class).toString())
          || !terminal
              .getAccountOriginal()
              .getMutationDigest()
              .equals(row.get("mutation_digest", String.class))
          || !(committed ? "COMMITTED" : "ABORTED").equals(row.get("status", String.class)))
        throw new IllegalStateException("Corrupt creation terminal");
      if (committed) {
        EntityCharacterCreateBindings.id(terminal.getCharacterId());
        if (!terminal.getAbortRequestId().isEmpty()
            || !UUID.fromString(terminal.getCharacterId())
                .equals(row.get("character_uuid", UUID.class)))
          throw new IllegalStateException("Corrupt committed actor");
      } else {
        EntityCharacterCreateBindings.id(terminal.getAbortRequestId());
        if (!terminal.getCharacterId().isEmpty() || row.get("character_uuid", UUID.class) != null)
          throw new IllegalStateException("Corrupt abort terminal");
      }
      return terminal;
    } catch (InvalidProtocolBufferException | IllegalArgumentException corrupt) {
      throw Status.DATA_LOSS
          .withDescription("Stored creation terminal is invalid")
          .asRuntimeException();
    }
  }

  static void requireSameRequest(
      CommitProtectedCharacterCreateRequest request, EntityCharacterCreateTerminal terminal) {
    if (!request.equals(terminal.getOriginalRequest()))
      throw EntityCharacterCreateBindings.conflict();
  }

  static void outsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("CHARACTER_CREATE owns its short Entity transactions");
    }
  }
}
