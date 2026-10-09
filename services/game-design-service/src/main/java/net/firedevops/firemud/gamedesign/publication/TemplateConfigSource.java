package net.firedevops.firemud.gamedesign.publication;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.automation.AutomationAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.entity.EntityAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceValues;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;

/** Closed authored wiring. Syntax validation never qualifies an external owner's reference. */
public final class TemplateConfigSource {
  public static final String REVISION_KIND = TemplateConfigSourceValues.REVISION_KIND;
  public static final String SCOPE = TemplateConfigSourceValues.SCOPE;
  public static final String SCOPE_ID = TemplateConfigSourceValues.SCOPE_ID;

  private TemplateConfigSource() {}

  public enum OperationKind {
    CREATE,
    UPSERT,
    DELETE,
    DECLARE_OWNER_SOURCE_INVENTORY
  }

  public record GameplayInput(Family family, String key, UUID revisionId) {
    public GameplayInput {
      new TemplateConfigSourceValues.GameplayInput(family, key, revisionId);
    }
  }

  /** Canonical config remains the actual authored document, without derived commit evidence. */
  public record Config(String canonicalJson) {
    public Config {
      canonicalJson = new TemplateConfigSourceValues.Config(canonicalJson).canonicalJson();
    }

    TemplateConfigSourceValues.Config shared() {
      return new TemplateConfigSourceValues.Config(canonicalJson);
    }

    public UUID baseVersionId() {
      return shared().baseVersionId();
    }

    public List<GameplayInput> gameplayInputs() {
      return shared().gameplayInputs().stream()
          .map(value -> new GameplayInput(value.family(), value.key(), value.revisionId()))
          .toList();
    }

    /** These missing authenticated owner boundaries deliberately prevent qualification. */
    public void requireAvailableOwnerReads() {
      var value = shared();
      if (value.hasWorldReferences())
        throw new IllegalStateException("TEMPLATE_CONFIG_WORLD_EXACT_OWNER_READ_UNAVAILABLE");
      if (value.hasEntityReferences())
        throw new IllegalStateException("TEMPLATE_CONFIG_ENTITY_EXACT_OWNER_READ_UNAVAILABLE");
      if (value.hasAutomationReferences())
        throw new IllegalStateException("TEMPLATE_CONFIG_AUTOMATION_EXACT_OWNER_READ_UNAVAILABLE");
    }
  }

  public record Mutation(
      DraftCommitBinding binding,
      String revisionOrder,
      UUID revisionId,
      OperationKind operation,
      String templateId,
      String templateName,
      Config config,
      Owner declaredOwner,
      AutomationAuthoredSourceInventoryDeclaration inventory,
      EntityAuthoredSourceInventoryDeclaration entityInventory) {
    public Mutation(
        DraftCommitBinding binding,
        String revisionOrder,
        UUID revisionId,
        OperationKind operation,
        String templateId,
        String templateName,
        Config config) {
      this(
          binding,
          revisionOrder,
          revisionId,
          operation,
          templateId,
          templateName,
          config,
          null,
          null,
          null);
    }

    public Mutation {
      new TemplateConfigSourceValues.Mutation(
          binding,
          revisionOrder,
          revisionId,
          operation == null
              ? null
              : TemplateConfigSourceValues.OperationKind.valueOf(operation.name()),
          templateId,
          templateName,
          config == null ? null : config.shared(),
          declaredOwner,
          inventory,
          entityInventory);
    }

    public String payload() {
      return shared().payload();
    }

    public boolean changesTemplateRow() {
      return shared().changesTemplateRow();
    }

    public TemplateConfigOwnerSourceInventoryDeclaration ownerInventoryDeclaration() {
      var declaration = shared().ownerInventoryDeclaration();
      return declaration == null
          ? null
          : TemplateConfigOwnerSourceInventoryDeclaration.fromShared(declaration);
    }

    public String inventoryJson() {
      return shared().inventoryJson();
    }

    TemplateConfigSourceValues.Mutation shared() {
      return new TemplateConfigSourceValues.Mutation(
          binding,
          revisionOrder,
          revisionId,
          TemplateConfigSourceValues.OperationKind.valueOf(operation.name()),
          templateId,
          templateName,
          config == null ? null : config.shared(),
          declaredOwner,
          inventory,
          entityInventory);
    }

    static Mutation fromShared(TemplateConfigSourceValues.Mutation value) {
      return new Mutation(
          value.binding(),
          value.revisionOrder(),
          value.revisionId(),
          OperationKind.valueOf(value.operation().name()),
          value.templateId(),
          value.templateName(),
          value.config() == null ? null : new Config(value.config().canonicalJson()),
          value.declaredOwner(),
          value.inventory(),
          value.entityInventory());
    }
  }

  public record Entry(
      String templateId,
      Config config,
      DraftCommitBinding sourceBinding,
      String revisionOrder,
      UUID revisionId,
      String createdName) {
    public Entry {
      new TemplateConfigSourceValues.Entry(
          templateId,
          config == null ? null : config.shared(),
          sourceBinding,
          revisionOrder,
          revisionId,
          createdName);
    }

    Map<String, Object> object() {
      return shared().object();
    }

    TemplateConfigSourceValues.Entry shared() {
      return new TemplateConfigSourceValues.Entry(
          templateId, config.shared(), sourceBinding, revisionOrder, revisionId, createdName);
    }

    static Entry fromShared(TemplateConfigSourceValues.Entry value) {
      return new Entry(
          value.templateId(),
          new Config(value.config().canonicalJson()),
          value.sourceBinding(),
          value.revisionOrder(),
          value.revisionId(),
          value.createdName());
    }
  }

  public static List<Mutation> mutations(DraftCommitBinding binding) {
    return TemplateConfigSourceValues.mutations(binding).stream()
        .map(Mutation::fromShared)
        .toList();
  }

  public static String upsertPayload(String templateId, Config config) {
    return TemplateConfigSourceValues.upsertPayload(templateId, config.shared());
  }

  public static String createPayload(String templateName, Config config) {
    return TemplateConfigSourceValues.createPayload(templateName, config.shared());
  }

  public static String deletePayload(String templateId) {
    return TemplateConfigSourceValues.deletePayload(templateId);
  }

  public static String ownerInventoryPayload(
      Owner owner, AutomationAuthoredSourceInventoryDeclaration inventory) {
    return TemplateConfigSourceValues.ownerInventoryPayload(owner, inventory);
  }

  public static String ownerInventoryPayload(
      Owner owner, EntityAuthoredSourceInventoryDeclaration inventory) {
    return TemplateConfigSourceValues.ownerInventoryPayload(owner, inventory);
  }

  public static List<TemplateConfigOwnerSourceInventoryDeclaration>
      replayOwnerInventoryDeclarations(
          List<TemplateConfigOwnerSourceInventoryDeclaration> inherited,
          DraftCommitBinding binding) {
    Map<Owner, TemplateConfigOwnerSourceInventoryDeclaration> declarations =
        new java.util.EnumMap<>(Owner.class);
    for (var declaration : inherited) {
      if (!binding.target().equals(declaration.sourceBinding().target())
          || declarations.put(declaration.owner(), declaration) != null)
        throw new IllegalArgumentException("Invalid inherited owner inventory declarations");
    }
    for (Mutation mutation : mutations(binding)) {
      if (mutation.operation() == OperationKind.DECLARE_OWNER_SOURCE_INVENTORY) {
        declarations.put(mutation.declaredOwner(), mutation.ownerInventoryDeclaration());
      }
    }
    return declarations.values().stream()
        .sorted(Comparator.comparing(value -> value.owner().name()))
        .toList();
  }

  public static List<Entry> replay(
      List<Entry> inherited, DraftCommitBinding binding, Map<UUID, String> createdRows) {
    Map<String, Entry> entries = new java.util.HashMap<>();
    for (Entry entry : inherited) {
      if (!binding.target().equals(entry.sourceBinding().target())
          || entries.put(entry.templateId(), entry) != null)
        throw new IllegalArgumentException("Invalid inherited template config inventory");
    }
    for (Mutation mutation : mutations(binding)) {
      if (!mutation.changesTemplateRow()) continue;
      if (mutation.operation() == OperationKind.DELETE) {
        if (entries.remove(mutation.templateId()) == null)
          throw new IllegalArgumentException("Template DELETE requires an authored entry");
      } else {
        String id =
            mutation.operation() == OperationKind.CREATE
                ? Objects.requireNonNull(
                    createdRows.get(mutation.revisionId()),
                    "Actual generated template row required")
                : mutation.templateId();
        if (mutation.operation() == OperationKind.CREATE && entries.containsKey(id)
            || mutation.operation() == OperationKind.UPSERT && !entries.containsKey(id))
          throw new IllegalArgumentException(
              "Template source identity is not an existing qualified entry");
        entries.put(
            id,
            new Entry(
                id,
                mutation.config(),
                binding,
                mutation.revisionOrder(),
                mutation.revisionId(),
                mutation.templateName()));
      }
    }
    return entries.values().stream()
        .sorted(Comparator.comparing(e -> new BigInteger(e.templateId())))
        .toList();
  }

  public static boolean isScope(DraftCommitBinding.AffectedUnit unit) {
    return SCOPE.equals(unit.aggregateType())
        && SCOPE.equals(unit.scopeType())
        && SCOPE_ID.equals(unit.scopeId());
  }

  static UUID uuid(String value) {
    return TemplateConfigSourceValues.uuid(value);
  }
}
