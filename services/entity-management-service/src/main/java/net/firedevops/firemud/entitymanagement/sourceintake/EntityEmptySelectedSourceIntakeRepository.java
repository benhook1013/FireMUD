package net.firedevops.firemud.entitymanagement.sourceintake;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerEmptySourceInputs;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered Entity persistence boundary for immutable, freshly founded empty catalogues. */
public final class EntityEmptySelectedSourceIntakeRepository {
  private static final String KEY_RESERVATION = "entity_empty_source_numeric_key_reservation";
  private static final String ASSOCIATION = "entity_empty_selected_source_association";
  private static final String FAMILY_STATE = "entity_empty_selected_source_family_state";
  private static final String RECEIPT = "entity_empty_selected_source_receipt";
  private static final Map<String, String> EMPTY_ONLY_PROVIDER_TABLES =
      Map.ofEntries(
          Map.entry(
              "ACTOR_BODY_LAYOUT_ASSIGNMENTS", "entity_empty_source_actor_body_layout_assignments"),
          Map.entry("ARCHETYPE_ASSIGNMENTS", "entity_empty_source_archetype_assignments"),
          Map.entry("ARCHETYPE_CONSTRAINTS", "entity_empty_source_archetype_constraints"),
          Map.entry("ARCHETYPE_ROOTS", "entity_empty_source_archetype_roots"),
          Map.entry("BALANCE_CURVE_ATTACHMENTS", "entity_empty_source_balance_curve_attachments"),
          Map.entry("BALANCE_CURVE_ROOTS", "entity_empty_source_balance_curve_roots"),
          Map.entry("EQUIPMENT_ATTACHMENT_RULES", "entity_empty_source_equipment_attachment_rules"),
          Map.entry("EQUIPMENT_CAPABILITIES", "entity_empty_source_equipment_capabilities"),
          Map.entry(
              "EQUIPMENT_COMPATIBILITY_RULES", "entity_empty_source_equipment_compatibility_rules"),
          Map.entry("EQUIPMENT_OCCUPANCY_RULES", "entity_empty_source_equipment_occupancy_rules"),
          Map.entry("INBOUND_LOOT_BINDINGS", "entity_empty_source_inbound_loot_bindings"),
          Map.entry("LOOT_ITEM_MAPPINGS", "entity_empty_source_loot_item_mappings"),
          Map.entry("LOOT_TABLE_ROOTS", "entity_empty_source_loot_table_roots"),
          Map.entry(
              "OTHER_ACTOR_TEMPLATE_ROOTS", "entity_empty_source_other_actor_template_roots"));
  private static final List<String> EMPTY_ONLY_PROVIDER_LOCKS =
      List.of(
          "entity_empty_source_other_actor_template_roots",
          "entity_empty_source_equipment_capabilities",
          "entity_empty_source_equipment_attachment_rules",
          "entity_empty_source_equipment_compatibility_rules",
          "entity_empty_source_equipment_occupancy_rules",
          "entity_empty_source_actor_body_layout_assignments",
          "entity_empty_source_loot_table_roots",
          "entity_empty_source_loot_item_mappings",
          "entity_empty_source_inbound_loot_bindings",
          "entity_empty_source_balance_curve_roots",
          "entity_empty_source_balance_curve_attachments",
          "entity_empty_source_archetype_roots",
          "entity_empty_source_archetype_constraints",
          "entity_empty_source_archetype_assignments");

  private static final String READ_RECEIPT =
      "SELECT a.target_namespace, a.genesis_id, a.operation_id, a.fence_id, "
          + "a.intake_request_id, a.canonical_tenant_id, a.canonical_version_id, "
          + "a.local_tenant_key, a.local_version_key, a.request_digest, a.schema_digest, "
          + "a.authorization_binding_digest, a.selected_source_digest, a.world_read_request_id, "
          + "a.world_read_request_digest, a.world_closure_digest, a.receipt_digest, a.associated_at, "
          + "r.request_digest, r.schema_digest, r.receipt_digest, r.receipt_bytes, r.retained_at "
          + "FROM "
          + ASSOCIATION
          + " a LEFT JOIN "
          + RECEIPT
          + " r ON r.target_namespace = a.target_namespace "
          + "AND r.intake_request_id = a.intake_request_id "
          + "WHERE a.target_namespace = ? AND a.intake_request_id = ?";

  private static final String SOURCE_TABLE_LOCKS =
      "LOCK TABLE actor_active_conditions, actor_resource_states, body_layout_slot_definitions, "
          + "character_equipment, character_friend, characters, container_instances, "
          + "crafting_ingredients, crafting_recipes, entity_mutation_effects, "
          + "equipment_slot_definitions, inventory, item_instances, item_stacks, "
          + "item_transfer_audits, item_visible_ref_counters, items, npcs, room_ground_inventory IN SHARE MODE";

  private final DSLContext dsl;

  public EntityEmptySelectedSourceIntakeRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /**
   * Reads only a committed exact receipt; absence is not interpreted as abort or source absence.
   */
  public Optional<EntityEmptySelectedSourceIntakeReceipt> read(
      String targetNamespace, UUID intakeRequestId) {
    requireNoAmbientOwnerSql("Entity source receipt read");
    requireReadKey(targetNamespace, intakeRequestId);
    return readValidated(dsl, targetNamespace, intakeRequestId);
  }

  /**
   * Commits a fresh empty catalogue and reads it back independently. A provider missing from the
   * current Entity schema blocks founding, even if its creator declaration says the family is
   * empty.
   */
  public EntityEmptySelectedSourceIntakeReceipt retainFresh(
      SelectedOwnerEmptySourceInputs inputs, String requestDigest) {
    Objects.requireNonNull(inputs, "validated selected empty-source inputs are required");
    SelectedOwnerIntakeAuthorizationBinding binding = inputs.authorizationBinding();
    String expectedDigest =
        EntityEmptySelectedSourceIntakeReceipt.requestDigest(
            binding.targetNamespace(),
            binding,
            inputs.worldInventoryReadEvidence().request().freezeEvidence());
    if (!expectedDigest.equals(requestDigest)) {
      throw new IllegalArgumentException("Entity source intake request digest differs");
    }
    requireExactEntityBinding(binding);
    requireNoAmbientOwnerSql("Entity source catalogue transaction");

    UUID genesisId = UUID.randomUUID();
    EntityEmptySelectedSourceIntakeReceipt committed =
        dsl.transactionResult(
            configuration -> {
              DSLContext tx = DSL.using(configuration);
              tx.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
              lockOwnerRecords(tx);
              lockSourceAndInboundTables(tx);

              Optional<EntityEmptySelectedSourceIntakeReceipt> previous =
                  readValidated(tx, binding.targetNamespace(), binding.intakeRequestId());
              if (previous.isPresent()) {
                EntityEmptySelectedSourceIntakeReceipt receipt = previous.orElseThrow();
                receipt.requireSameInputs(inputs);
                return receipt;
              }
              requireNoPriorScopeOrOperation(tx, binding);

              long maximumOccupiedKey = maximumOccupiedSourceKey(tx);
              long localTenantKey = Math.addExact(maximumOccupiedKey, 1L);
              long localVersionKey = Math.addExact(maximumOccupiedKey, 2L);
              List<EntityEmptySelectedSourceIntakeReceipt.FamilyCensus> familyStates =
                  censusEveryDeclaredFamily(tx, inputs, localTenantKey, localVersionKey);

              reserveCanonicalKey(tx, "TENANT", localTenantKey);
              reserveCanonicalKey(tx, "VERSION", localVersionKey);
              OffsetDateTime retainedAt =
                  requiredScalar(tx, "SELECT CURRENT_TIMESTAMP", OffsetDateTime.class)
                      .withOffsetSameInstant(ZoneOffset.UTC);
              EntityEmptySelectedSourceIntakeReceipt receipt =
                  EntityEmptySelectedSourceIntakeReceipt.create(
                      inputs,
                      genesisId,
                      localTenantKey,
                      localVersionKey,
                      requestDigest,
                      familyStates,
                      retainedAt);
              insertAssociation(tx, receipt);
              insertEmptyOnlyProviderStates(tx, receipt);
              insertFamilyStates(tx, receipt);
              insertReceipt(tx, receipt);
              return receipt;
            });

    EntityEmptySelectedSourceIntakeReceipt readBack =
        read(committed.targetNamespace(), committed.intakeRequestId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Committed Entity source catalogue receipt is unavailable"));
    if (!Arrays.equals(committed.canonicalBytes(), readBack.canonicalBytes())
        || !committed.receiptDigest().equals(readBack.receiptDigest())
        || !committed.requestDigest().equals(readBack.requestDigest())
        || !committed.genesisId().equals(readBack.genesisId())) {
      throw new IllegalStateException("Committed Entity source receipt readback differs");
    }
    return readBack;
  }

  private static void lockOwnerRecords(DSLContext tx) {
    tx.execute(
        "LOCK TABLE "
            + KEY_RESERVATION
            + ", "
            + ASSOCIATION
            + ", "
            + FAMILY_STATE
            + ", "
            + RECEIPT
            + ", "
            + String.join(", ", EMPTY_ONLY_PROVIDER_LOCKS)
            + " IN SHARE ROW EXCLUSIVE MODE");
  }

  private static void lockSourceAndInboundTables(DSLContext tx) {
    // Locks cover every source row, Entity item FK, actor/holder path, and logical slot/layout
    // reference read by the conservative census. SHARE is held until the catalogue commit.
    tx.execute(SOURCE_TABLE_LOCKS);
  }

  private static void requireNoPriorScopeOrOperation(
      DSLContext tx, SelectedOwnerIntakeAuthorizationBinding binding) {
    Record prior =
        tx.fetchOne(
            "SELECT intake_request_id FROM "
                + ASSOCIATION
                + " WHERE target_namespace = ? AND ("
                + "(canonical_tenant_id = ? AND canonical_version_id = ?) OR operation_id = ?) ",
            binding.targetNamespace(),
            binding.tenantId(),
            binding.versionId(),
            binding.operationId());
    if (prior != null) {
      throw new IntakeConflictException(
          "Entity selected scope or owner operation is already associated");
    }
  }

  private static List<EntityEmptySelectedSourceIntakeReceipt.FamilyCensus>
      censusEveryDeclaredFamily(
          DSLContext tx,
          SelectedOwnerEmptySourceInputs inputs,
          long localTenantKey,
          long localVersionKey) {
    List<String> declaredFamilies =
        declaredFamilies(
            inputs.ownerSourceInventoryDeclaration().entityInventory().canonicalJson());
    var result = new ArrayList<EntityEmptySelectedSourceIntakeReceipt.FamilyCensus>(23);
    for (String family : declaredFamilies) {
      requireReadableProvider(family);
      Census counts =
          EMPTY_ONLY_PROVIDER_TABLES.containsKey(family)
              ? Census.emptyOnlyProviderState()
              : switch (family) {
                case "ITEM_TEMPLATE_ROOTS" -> censusItems(tx, localTenantKey, localVersionKey);
                case "NPC_TEMPLATE_ROOTS" -> censusNpcs(tx, localTenantKey, localVersionKey);
                case "CRAFTING_RECIPE_ROOTS" ->
                    censusRecipes(tx, localTenantKey, localVersionKey, false);
                case "CRAFTING_RECIPE_RESULT_BINDINGS" ->
                    censusRecipes(tx, localTenantKey, localVersionKey, true);
                case "CRAFTING_INGREDIENT_BINDINGS" ->
                    censusIngredients(tx, localTenantKey, localVersionKey);
                case "EQUIPMENT_SLOT_ROOTS" ->
                    censusEquipmentSlots(tx, localTenantKey, localVersionKey, false);
                case "EQUIPMENT_SLOT_GROUPS" ->
                    censusEquipmentSlots(tx, localTenantKey, localVersionKey, true);
                case "BODY_LAYOUT_ROOTS" ->
                    censusBodyLayouts(tx, localTenantKey, localVersionKey, true);
                case "BODY_LAYOUT_MEMBERSHIPS" ->
                    censusBodyLayouts(tx, localTenantKey, localVersionKey, false);
                default ->
                    throw new IllegalStateException(
                        "No readable persisted Entity source provider for family " + family);
              };
      if (!counts.isZero()) {
        throw new IntakeConflictException(
            "Entity source family or inbound reference census is not freshly empty: " + family);
      }
      result.add(
          new EntityEmptySelectedSourceIntakeReceipt.FamilyCensus(
              family,
              EntityEmptySelectedSourceIntakeReceipt.FamilyState.EMPTY,
              EMPTY_ONLY_PROVIDER_TABLES.containsKey(family)
                  ? EntityEmptySelectedSourceIntakeReceipt.FamilyEvidenceKind
                      .EMPTY_ONLY_OWNER_PROVIDER
                  : EntityEmptySelectedSourceIntakeReceipt.FamilyEvidenceKind.V1_SOURCE_CENSUS,
              counts.rows(),
              counts.unqualifiedRows(),
              counts.retainedRows(),
              counts.selectedScopeRows(),
              counts.references()));
    }
    if (result.size() != 23) {
      throw new IllegalStateException("Entity source census did not read all 23 declared families");
    }
    return List.copyOf(result);
  }

  static void requireReadableProvider(String family) {
    if (!EMPTY_ONLY_PROVIDER_TABLES.containsKey(family)
        && !List.of(
                "ITEM_TEMPLATE_ROOTS",
                "NPC_TEMPLATE_ROOTS",
                "CRAFTING_RECIPE_ROOTS",
                "CRAFTING_RECIPE_RESULT_BINDINGS",
                "CRAFTING_INGREDIENT_BINDINGS",
                "EQUIPMENT_SLOT_ROOTS",
                "EQUIPMENT_SLOT_GROUPS",
                "BODY_LAYOUT_ROOTS",
                "BODY_LAYOUT_MEMBERSHIPS")
            .contains(family)) {
      throw new IllegalStateException(
          "No readable persisted Entity source provider for family " + family);
    }
  }

  private static Census censusItems(DSLContext tx, long tenantKey, long versionKey) {
    return new Census(
        count(tx, "SELECT COUNT(*) FROM items"),
        count(
            tx,
            "SELECT COUNT(*) FROM items i WHERE NOT EXISTS (SELECT 1 FROM "
                + ASSOCIATION
                + " a WHERE a.local_tenant_key = i.tenant_id AND a.local_version_key = i.version_id)"),
        count(
            tx,
            "SELECT COUNT(*) FROM items i WHERE EXISTS (SELECT 1 FROM "
                + ASSOCIATION
                + " a WHERE a.local_tenant_key = i.tenant_id AND a.local_version_key = i.version_id)"),
        count(
            tx,
            "SELECT COUNT(*) FROM items WHERE tenant_id = ? OR version_id = ?",
            tenantKey,
            versionKey),
        countItemReferenceEdges(tx));
  }

  private static Census censusNpcs(DSLContext tx, long tenantKey, long versionKey) {
    return new Census(
        count(tx, "SELECT COUNT(*) FROM npcs"),
        count(
            tx,
            "SELECT COUNT(*) FROM npcs n WHERE NOT EXISTS (SELECT 1 FROM "
                + ASSOCIATION
                + " a WHERE a.local_tenant_key = n.tenant_id AND a.local_version_key = n.version_id)"),
        count(
            tx,
            "SELECT COUNT(*) FROM npcs n WHERE EXISTS (SELECT 1 FROM "
                + ASSOCIATION
                + " a WHERE a.local_tenant_key = n.tenant_id AND a.local_version_key = n.version_id)"),
        count(
            tx,
            "SELECT COUNT(*) FROM npcs WHERE tenant_id = ? OR version_id = ?",
            tenantKey,
            versionKey),
        0L);
  }

  private static Census censusRecipes(
      DSLContext tx, long tenantKey, long versionKey, boolean resultBinding) {
    String nonnullResult = resultBinding ? " WHERE result_item_id IS NOT NULL" : "";
    String aliasFilter = resultBinding ? " AND r.result_item_id IS NOT NULL" : "";
    return new Census(
        count(tx, "SELECT COUNT(*) FROM crafting_recipes" + nonnullResult),
        count(
            tx,
            "SELECT COUNT(*) FROM crafting_recipes r WHERE NOT EXISTS (SELECT 1 FROM "
                + ASSOCIATION
                + " a WHERE a.local_tenant_key = r.tenant_id AND a.local_version_key = r.version_id)"
                + aliasFilter),
        count(
            tx,
            "SELECT COUNT(*) FROM crafting_recipes r WHERE EXISTS (SELECT 1 FROM "
                + ASSOCIATION
                + " a WHERE a.local_tenant_key = r.tenant_id AND a.local_version_key = r.version_id)"
                + aliasFilter),
        count(
            tx,
            "SELECT COUNT(*) FROM crafting_recipes r WHERE (r.tenant_id = ? OR r.version_id = ?)"
                + aliasFilter,
            tenantKey,
            versionKey),
        resultBinding
            ? count(tx, "SELECT COUNT(*) FROM crafting_recipes WHERE result_item_id IS NOT NULL")
            : count(tx, "SELECT COUNT(*) FROM crafting_ingredients"));
  }

  private static Census censusIngredients(DSLContext tx, long tenantKey, long versionKey) {
    String recipeAssociation =
        "SELECT COUNT(*) FROM crafting_ingredients i JOIN crafting_recipes r ON r.id = i.recipe_id ";
    return new Census(
        count(tx, "SELECT COUNT(*) FROM crafting_ingredients"),
        count(
            tx,
            recipeAssociation
                + "WHERE NOT EXISTS (SELECT 1 FROM "
                + ASSOCIATION
                + " a WHERE a.local_tenant_key = r.tenant_id AND a.local_version_key = r.version_id)"),
        count(
            tx,
            recipeAssociation
                + "WHERE EXISTS (SELECT 1 FROM "
                + ASSOCIATION
                + " a WHERE a.local_tenant_key = r.tenant_id AND a.local_version_key = r.version_id)"),
        count(
            tx,
            recipeAssociation + "WHERE r.tenant_id = ? OR r.version_id = ?",
            tenantKey,
            versionKey),
        count(tx, "SELECT COUNT(*) FROM crafting_ingredients"));
  }

  private static Census censusEquipmentSlots(
      DSLContext tx, long tenantKey, long versionKey, boolean groups) {
    String identity =
        groups
            ? "SELECT DISTINCT tenant_id, version_id, slot_group_key AS source_key "
                + "FROM equipment_slot_definitions WHERE slot_group_key IS NOT NULL"
            : "SELECT tenant_id, version_id, slot_key AS source_key FROM equipment_slot_definitions";
    String itemReferences =
        "SELECT COUNT(*) FROM ("
            + "SELECT equipment_slot AS source_key FROM items WHERE equipment_slot IS NOT NULL "
            + "UNION ALL SELECT slot FROM character_equipment WHERE slot IS NOT NULL "
            + "UNION ALL SELECT equipment_slot FROM item_instances WHERE equipment_slot IS NOT NULL "
            + "UNION ALL SELECT equipment_slot FROM item_stacks WHERE equipment_slot IS NOT NULL "
            + "UNION ALL SELECT equipment_slot FROM container_instances WHERE equipment_slot IS NOT NULL"
            + ") slot_refs";
    String groupReferences =
        "SELECT COUNT(*) FROM items WHERE equipment_slot_group_key IS NOT NULL";
    String total = "SELECT COUNT(*) FROM (" + identity + ") entity_family";
    String unqualified =
        "SELECT COUNT(*) FROM (SELECT f.* FROM ("
            + identity
            + ") f WHERE NOT EXISTS (SELECT 1 FROM "
            + ASSOCIATION
            + " a WHERE a.local_tenant_key = f.tenant_id AND a.local_version_key = f.version_id)) q";
    String retained =
        "SELECT COUNT(*) FROM (SELECT f.* FROM ("
            + identity
            + ") f WHERE EXISTS (SELECT 1 FROM "
            + ASSOCIATION
            + " a WHERE a.local_tenant_key = f.tenant_id AND a.local_version_key = f.version_id)) q";
    String selected =
        "SELECT COUNT(*) FROM (" + identity + ") f WHERE f.tenant_id = ? OR f.version_id = ?";
    return new Census(
        count(tx, total),
        count(tx, unqualified),
        count(tx, retained),
        count(tx, selected, tenantKey, versionKey),
        groups ? count(tx, groupReferences) : count(tx, itemReferences));
  }

  private static Census censusBodyLayouts(
      DSLContext tx, long tenantKey, long versionKey, boolean roots) {
    String identity =
        roots
            ? "SELECT DISTINCT tenant_id, version_id, body_layout_key AS source_key "
                + "FROM body_layout_slot_definitions"
            : "SELECT tenant_id, version_id, body_layout_key, slot_key "
                + "FROM body_layout_slot_definitions";
    String total = "SELECT COUNT(*) FROM (" + identity + ") entity_family";
    String unqualified =
        "SELECT COUNT(*) FROM (SELECT f.* FROM ("
            + identity
            + ") f WHERE NOT EXISTS (SELECT 1 FROM "
            + ASSOCIATION
            + " a WHERE a.local_tenant_key = f.tenant_id AND a.local_version_key = f.version_id)) q";
    String retained =
        "SELECT COUNT(*) FROM (SELECT f.* FROM ("
            + identity
            + ") f WHERE EXISTS (SELECT 1 FROM "
            + ASSOCIATION
            + " a WHERE a.local_tenant_key = f.tenant_id AND a.local_version_key = f.version_id)) q";
    String selected =
        "SELECT COUNT(*) FROM (" + identity + ") f WHERE f.tenant_id = ? OR f.version_id = ?";
    return new Census(
        count(tx, total),
        count(tx, unqualified),
        count(tx, retained),
        count(tx, selected, tenantKey, versionKey),
        roots
            ? count(tx, "SELECT COUNT(*) FROM characters WHERE body_layout_key IS NOT NULL")
            : count(
                tx,
                "SELECT COUNT(*) FROM characters c JOIN character_equipment e "
                    + "ON e.character_id = c.id WHERE c.body_layout_key IS NOT NULL"));
  }

  private static long countItemReferenceEdges(DSLContext tx) {
    return count(
        tx,
        "SELECT COUNT(*) FROM ("
            + "SELECT item_id FROM inventory "
            + "UNION ALL SELECT result_item_id FROM crafting_recipes "
            + "UNION ALL SELECT item_id FROM crafting_ingredients "
            + "UNION ALL SELECT item_id FROM room_ground_inventory "
            + "UNION ALL SELECT item_id FROM character_equipment "
            + "UNION ALL SELECT item_id FROM container_instances "
            + "UNION ALL SELECT item_id FROM item_instances "
            + "UNION ALL SELECT item_id FROM item_stacks "
            + "UNION ALL SELECT item_id FROM item_transfer_audits "
            + "UNION ALL SELECT item_instance_id FROM item_transfer_audits WHERE item_instance_id IS NOT NULL"
            + ") item_refs");
  }

  private static long maximumOccupiedSourceKey(DSLContext tx) {
    return requiredScalar(
        tx,
        "SELECT COALESCE(MAX(key_value), 0) FROM ("
            + "SELECT numeric_key AS key_value FROM "
            + KEY_RESERVATION
            + " UNION ALL SELECT tenant_id FROM items WHERE tenant_id > 0"
            + " UNION ALL SELECT tenant_id FROM npcs WHERE tenant_id > 0"
            + " UNION ALL SELECT tenant_id FROM crafting_recipes WHERE tenant_id > 0"
            + " UNION ALL SELECT tenant_id FROM equipment_slot_definitions WHERE tenant_id > 0"
            + " UNION ALL SELECT tenant_id FROM body_layout_slot_definitions WHERE tenant_id > 0"
            + " UNION ALL SELECT tenant_id FROM room_ground_inventory WHERE tenant_id > 0"
            + " UNION ALL SELECT tenant_id FROM container_instances WHERE tenant_id > 0"
            + " UNION ALL SELECT tenant_id FROM item_instances WHERE tenant_id > 0"
            + " UNION ALL SELECT tenant_id FROM item_stacks WHERE tenant_id > 0"
            + " UNION ALL SELECT tenant_id FROM characters WHERE tenant_id > 0"
            + " UNION ALL SELECT tenant_id FROM character_friend WHERE tenant_id > 0"
            + " UNION ALL SELECT tenant_id FROM item_visible_ref_counters WHERE tenant_id > 0"
            + " UNION ALL SELECT tenant_id FROM entity_mutation_effects WHERE tenant_id > 0"
            + " UNION ALL SELECT tenant_id FROM item_transfer_audits WHERE tenant_id > 0"
            + " UNION ALL SELECT tenant_id FROM actor_resource_states WHERE tenant_id > 0"
            + " UNION ALL SELECT tenant_id FROM actor_active_conditions WHERE tenant_id > 0"
            + " UNION ALL SELECT version_id FROM items WHERE version_id > 0"
            + " UNION ALL SELECT version_id FROM npcs WHERE version_id > 0"
            + " UNION ALL SELECT version_id FROM crafting_recipes WHERE version_id > 0"
            + " UNION ALL SELECT version_id FROM equipment_slot_definitions WHERE version_id > 0"
            + " UNION ALL SELECT version_id FROM body_layout_slot_definitions WHERE version_id > 0"
            + ") occupied_entity_source_keys",
        Long.class);
  }

  private static void reserveCanonicalKey(DSLContext tx, String kind, long key) {
    int inserted =
        tx.execute(
            "INSERT INTO "
                + KEY_RESERVATION
                + " (key_kind, numeric_key, claim_kind) VALUES (?, ?, 'CANONICAL_EMPTY_SOURCE')",
            kind,
            key);
    if (inserted != 1) {
      throw new IllegalStateException("Entity allocated numeric source key was not reserved");
    }
  }

  private static void insertAssociation(
      DSLContext tx, EntityEmptySelectedSourceIntakeReceipt receipt) {
    int inserted =
        tx.execute(
            "INSERT INTO "
                + ASSOCIATION
                + " (target_namespace, genesis_id, operation_id, fence_id, intake_request_id, "
                + "canonical_tenant_id, canonical_version_id, selected_commit_id, "
                + "source_revision_id, source_revision_order, local_tenant_key, local_version_key, "
                + "request_digest, schema_digest, authorization_binding_digest, selected_source_digest, "
                + "world_read_request_id, world_read_request_digest, world_closure_digest, receipt_digest, "
                + "associated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "CAST(? AS TIMESTAMPTZ))",
            receipt.targetNamespace(),
            receipt.genesisId(),
            receipt.operationId(),
            receipt.fenceId(),
            receipt.intakeRequestId(),
            receipt.canonicalTenantId(),
            receipt.canonicalVersionId(),
            receipt.selectedCommitId(),
            receipt.sourceRevisionId(),
            receipt.sourceRevisionOrder(),
            receipt.localTenantKey(),
            receipt.localVersionKey(),
            receipt.requestDigest(),
            receipt.schemaDigest(),
            receipt.authorizationBindingDigest(),
            receipt.selectedSourceDigest(),
            receipt.worldReadRequestId(),
            receipt.worldReadRequestDigest(),
            receipt.worldClosureDigest(),
            receipt.receiptDigest(),
            receipt.retainedAt());
    if (inserted != 1)
      throw new IllegalStateException("Entity source association was not inserted");
  }

  private static void insertFamilyStates(
      DSLContext tx, EntityEmptySelectedSourceIntakeReceipt receipt) {
    for (var state : receipt.familyStates()) {
      int inserted =
          tx.execute(
              "INSERT INTO "
                  + FAMILY_STATE
                  + " (target_namespace, intake_request_id, family_name, owner_state, evidence_kind, row_count, "
                  + "unqualified_row_count, retained_row_count, selected_scope_row_count, reference_count) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              receipt.targetNamespace(),
              receipt.intakeRequestId(),
              state.family(),
              state.state().name(),
              state.evidenceKind().name(),
              state.rowCount(),
              state.unqualifiedRowCount(),
              state.retainedRowCount(),
              state.selectedScopeRowCount(),
              state.referenceCount());
      if (inserted != 1) {
        throw new IllegalStateException("Entity source family state was not retained");
      }
    }
  }

  private static void insertEmptyOnlyProviderStates(
      DSLContext tx, EntityEmptySelectedSourceIntakeReceipt receipt) {
    for (var state : receipt.familyStates()) {
      if (state.evidenceKind()
          != EntityEmptySelectedSourceIntakeReceipt.FamilyEvidenceKind.EMPTY_ONLY_OWNER_PROVIDER) {
        continue;
      }
      String table = EMPTY_ONLY_PROVIDER_TABLES.get(state.family());
      if (table == null) {
        throw new IllegalStateException("Entity EMPTY-only family has no owner table");
      }
      int inserted =
          tx.execute(
              "INSERT INTO "
                  + table
                  + " (target_namespace, intake_request_id, provider_schema_version, owner_state, "
                  + "row_count, reference_count, authorization_binding_digest, selected_source_digest, "
                  + "source_revision_binding_digest, world_closure_digest, provider_state_digest, observed_at) "
                  + "VALUES (?, ?, 1, ?, 0, 0, ?, ?, ?, ?, ?, "
                  + "CAST(? AS TIMESTAMPTZ))",
              receipt.targetNamespace(),
              receipt.intakeRequestId(),
              state.state().name(),
              receipt.authorizationBindingDigest(),
              receipt.selectedSourceDigest(),
              receipt.selectedSourceRevisionBindingDigest(),
              receipt.worldClosureDigest(),
              receipt.providerStateDigest(state.family()),
              receipt.retainedAt());
      if (inserted != 1) {
        throw new IllegalStateException("Entity EMPTY-only provider state was not retained");
      }
    }
  }

  private static void insertReceipt(DSLContext tx, EntityEmptySelectedSourceIntakeReceipt receipt) {
    int inserted =
        tx.execute(
            "INSERT INTO "
                + RECEIPT
                + " (target_namespace, intake_request_id, request_digest, schema_digest, "
                + "receipt_digest, receipt_bytes, retained_at) VALUES (?, ?, ?, ?, ?, ?, "
                + "CAST(? AS TIMESTAMPTZ))",
            receipt.targetNamespace(),
            receipt.intakeRequestId(),
            receipt.requestDigest(),
            receipt.schemaDigest(),
            receipt.receiptDigest(),
            receipt.canonicalBytes(),
            receipt.retainedAt());
    if (inserted != 1) throw new IllegalStateException("Entity source receipt was not inserted");
  }

  private static Optional<EntityEmptySelectedSourceIntakeReceipt> readValidated(
      DSLContext tx, String targetNamespace, UUID intakeRequestId) {
    Record row = tx.fetchOne(READ_RECEIPT, targetNamespace, intakeRequestId);
    if (row == null) return Optional.empty();
    byte[] receiptBytes = row.get(21, byte[].class);
    if (receiptBytes == null || row.get(22, OffsetDateTime.class) == null) {
      throw new IllegalStateException("Entity source association has no committed receipt");
    }
    EntityEmptySelectedSourceIntakeReceipt receipt =
        EntityEmptySelectedSourceIntakeReceipt.fromStored(receiptBytes);
    if (!Objects.equals(row.get(0, String.class), receipt.targetNamespace())
        || !Objects.equals(row.get(1, UUID.class), receipt.genesisId())
        || !Objects.equals(row.get(2, UUID.class), receipt.operationId())
        || !Objects.equals(row.get(3, UUID.class), receipt.fenceId())
        || !Objects.equals(row.get(4, UUID.class), receipt.intakeRequestId())
        || !Objects.equals(row.get(5, UUID.class), receipt.canonicalTenantId())
        || !Objects.equals(row.get(6, UUID.class), receipt.canonicalVersionId())
        || !Objects.equals(row.get(7, Long.class), receipt.localTenantKey())
        || !Objects.equals(row.get(8, Long.class), receipt.localVersionKey())
        || !Objects.equals(row.get(9, String.class), receipt.requestDigest())
        || !Objects.equals(row.get(10, String.class), receipt.schemaDigest())
        || !Objects.equals(row.get(11, String.class), receipt.authorizationBindingDigest())
        || !Objects.equals(row.get(12, String.class), receipt.selectedSourceDigest())
        || !Objects.equals(row.get(13, UUID.class), receipt.worldReadRequestId())
        || !Objects.equals(row.get(14, String.class), receipt.worldReadRequestDigest())
        || !Objects.equals(row.get(15, String.class), receipt.worldClosureDigest())
        || !Objects.equals(row.get(16, String.class), receipt.receiptDigest())
        || !Objects.equals(row.get(17, OffsetDateTime.class), receipt.retainedAt())
        || !Objects.equals(row.get(18, String.class), receipt.requestDigest())
        || !Objects.equals(row.get(19, String.class), receipt.schemaDigest())
        || !Objects.equals(row.get(20, String.class), receipt.receiptDigest())
        || !Objects.equals(row.get(22, OffsetDateTime.class), receipt.retainedAt())) {
      throw new IllegalStateException("Entity association differs from its immutable receipt");
    }
    requirePersistedEmptyOnlyProviderStates(tx, receipt);
    requirePersistedFamilyStates(tx, receipt);
    return Optional.of(receipt);
  }

  private static void requirePersistedEmptyOnlyProviderStates(
      DSLContext tx, EntityEmptySelectedSourceIntakeReceipt receipt) {
    for (var state : receipt.familyStates()) {
      if (state.evidenceKind()
          != EntityEmptySelectedSourceIntakeReceipt.FamilyEvidenceKind.EMPTY_ONLY_OWNER_PROVIDER) {
        continue;
      }
      String table = EMPTY_ONLY_PROVIDER_TABLES.get(state.family());
      if (table == null) {
        throw new IllegalStateException("Entity EMPTY-only family has no owner table");
      }
      Record row =
          tx.fetchOne(
              "SELECT provider_schema_version, owner_state, row_count, reference_count, "
                  + "authorization_binding_digest, selected_source_digest, "
                  + "source_revision_binding_digest, world_closure_digest, provider_state_digest, observed_at "
                  + "FROM "
                  + table
                  + " WHERE target_namespace = ? AND intake_request_id = ?",
              receipt.targetNamespace(),
              receipt.intakeRequestId());
      if (row == null
          || !Objects.equals(row.get(0, Short.class), (short) 1)
          || !Objects.equals(row.get(1, String.class), state.state().name())
          || !Objects.equals(row.get(2, Long.class), state.rowCount())
          || !Objects.equals(row.get(3, Long.class), state.referenceCount())
          || !Objects.equals(row.get(4, String.class), receipt.authorizationBindingDigest())
          || !Objects.equals(row.get(5, String.class), receipt.selectedSourceDigest())
          || !Objects.equals(
              row.get(6, String.class), receipt.selectedSourceRevisionBindingDigest())
          || !Objects.equals(row.get(7, String.class), receipt.worldClosureDigest())
          || !Objects.equals(row.get(8, String.class), receipt.providerStateDigest(state.family()))
          || !Objects.equals(row.get(9, OffsetDateTime.class), receipt.retainedAt())) {
        throw new IllegalStateException(
            "Entity EMPTY-only provider readback is missing or differs for " + state.family());
      }
    }
  }

  private static void requirePersistedFamilyStates(
      DSLContext tx, EntityEmptySelectedSourceIntakeReceipt receipt) {
    var rows =
        tx.fetch(
            "SELECT family_name, owner_state, evidence_kind, row_count, unqualified_row_count, retained_row_count, "
                + "selected_scope_row_count, reference_count FROM "
                + FAMILY_STATE
                + " WHERE target_namespace = ? AND intake_request_id = ? ORDER BY family_name",
            receipt.targetNamespace(),
            receipt.intakeRequestId());
    if (rows.size() != receipt.familyStates().size()) {
      throw new IllegalStateException("Entity retained family-state vector is incomplete");
    }
    var expected =
        receipt.familyStates().stream()
            .sorted(
                java.util.Comparator.comparing(
                    EntityEmptySelectedSourceIntakeReceipt.FamilyCensus::family))
            .toList();
    for (int index = 0; index < expected.size(); index++) {
      Record row = rows.get(index);
      var state = expected.get(index);
      if (!Objects.equals(row.get(0, String.class), state.family())
          || !Objects.equals(row.get(1, String.class), state.state().name())
          || !Objects.equals(row.get(2, String.class), state.evidenceKind().name())
          || !Objects.equals(row.get(3, Long.class), state.rowCount())
          || !Objects.equals(row.get(4, Long.class), state.unqualifiedRowCount())
          || !Objects.equals(row.get(5, Long.class), state.retainedRowCount())
          || !Objects.equals(row.get(6, Long.class), state.selectedScopeRowCount())
          || !Objects.equals(row.get(7, Long.class), state.referenceCount())) {
        throw new IllegalStateException("Entity retained family state differs from its receipt");
      }
    }
  }

  private static List<String> declaredFamilies(String canonicalDeclaration) {
    return EntityEmptySelectedSourceIntakeReceipt.familyNames(canonicalDeclaration);
  }

  private static void requireExactEntityBinding(SelectedOwnerIntakeAuthorizationBinding binding) {
    if (binding.owner() != Owner.ENTITY_MANAGEMENT
        || !"account-entity-intake-authorization/v1".equals(binding.schema())
        || !"ENTITY_INTAKE_RETENTION".equals(binding.purpose())
        || !binding
            .intendedReader()
            .equals(
                "spiffe://firemud/ns/"
                    + binding.targetNamespace()
                    + "/sa/entity-management-service")) {
      throw new IllegalArgumentException("Exact original Entity Account authorization is required");
    }
  }

  private static long count(DSLContext tx, String sql, Object... arguments) {
    return requiredScalar(tx, sql, Long.class, arguments);
  }

  private static <T> T requiredScalar(
      DSLContext tx, String sql, Class<T> type, Object... arguments) {
    Record result = tx.fetchOne(sql, arguments);
    if (result == null || result.get(0, type) == null) {
      throw new IllegalStateException("Entity source census query returned no scalar value");
    }
    return result.get(0, type);
  }

  private static void requireReadKey(String targetNamespace, UUID intakeRequestId) {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace) || intakeRequestId == null) {
      throw new IllegalArgumentException(
          "Canonical Entity source receipt read identity is required");
    }
    DraftAuthorizationFenceBinding.requireUuid(intakeRequestId);
  }

  private static void requireNoAmbientOwnerSql(String label) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          label + " requires an independent Entity transaction boundary");
    }
  }

  private record Census(
      long rows, long unqualifiedRows, long retainedRows, long selectedScopeRows, long references) {
    private Census {
      if (rows < 0L
          || unqualifiedRows < 0L
          || retainedRows < 0L
          || selectedScopeRows < 0L
          || references < 0L
          || Math.addExact(unqualifiedRows, retainedRows) != rows) {
        throw new IllegalStateException("Entity census partitions are invalid");
      }
    }

    private static Census emptyOnlyProviderState() {
      // This is not inferred from missing legacy storage: the caller persists a typed, immutable
      // EMPTY provider record for this exact newly authorized source and independently reads it.
      return new Census(0L, 0L, 0L, 0L, 0L);
    }

    private boolean isZero() {
      return rows == 0L
          && unqualifiedRows == 0L
          && retainedRows == 0L
          && selectedScopeRows == 0L
          && references == 0L;
    }
  }

  private static final class IntakeConflictException extends IllegalStateException {
    private IntakeConflictException(String message) {
      super(message);
    }
  }
}
