package net.firedevops.firemud.gamedesign.publication;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Definition;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;

/** Real Game Design-owned revisions; author history is separate from Game Logic retention. */
public final class GameplayRuleSource {
  public static final String REVISION_KIND = "GAMEPLAY_RULE";
  public static final String SCOPE = "GAMEPLAY_RULE_SET";
  public static final String SCOPE_ID = "effective";

  private GameplayRuleSource() {}

  public enum OperationKind {
    UPSERT,
    DELETE
  }

  public record Mutation(
      DraftCommitBinding binding,
      String revisionOrder,
      UUID revisionId,
      OperationKind operation,
      Family family,
      String key,
      Definition definition) {
    public Mutation {
      Objects.requireNonNull(binding);
      Objects.requireNonNull(operation);
      Objects.requireNonNull(family);
      GameplayRuleManifest.identifier(key);
      var revision =
          binding.revisions().stream()
              .filter(value -> value.owner() == Owner.GAME_DESIGN_CONTROL_PLANE)
              .filter(value -> value.revisionId().equals(revisionId))
              .filter(value -> value.revisionOrder().equals(revisionOrder))
              .findFirst()
              .orElseThrow();
      String expected;
      if (operation == OperationKind.UPSERT) {
        Objects.requireNonNull(definition);
        if (definition.family() != family || !definition.key().equals(key))
          throw new IllegalArgumentException("Rule UPSERT identity differs from definition");
        expected = upsertPayload(definition);
      } else {
        if (definition != null) throw new IllegalArgumentException("DELETE carries no definition");
        expected = deletePayload(family, key);
      }
      if (!expected.equals(
          GameplayRuleManifest.canonical(GameplayRuleManifest.tree(revision.payload()))))
        throw new IllegalArgumentException("Rule mutation differs from original revision");
    }

    public String payload() {
      return operation == OperationKind.UPSERT
          ? upsertPayload(definition)
          : deletePayload(family, key);
    }
  }

  public record Entry(
      Family family,
      Definition definition,
      DraftCommitBinding sourceBinding,
      String revisionOrder,
      UUID revisionId) {
    public Entry {
      Objects.requireNonNull(definition);
      new Mutation(
          sourceBinding,
          revisionOrder,
          revisionId,
          OperationKind.UPSERT,
          family,
          definition.key(),
          definition);
    }

    public Map<String, Object> object() {
      return Map.of(
          "family",
          family.name(),
          "definitionJson",
          GameplayRuleManifest.canonical(definition),
          "sourceBindingJson",
          sourceBinding.canonicalJson(),
          "sourceBindingDigest",
          sourceBinding.digest(),
          "revisionOrder",
          revisionOrder,
          "revisionId",
          revisionId.toString());
    }
  }

  public static List<Mutation> mutations(DraftCommitBinding binding) {
    List<Mutation> result = new ArrayList<>();
    for (var revision : binding.revisions()) {
      if (revision.owner() != Owner.GAME_DESIGN_CONTROL_PLANE) continue;
      var node = GameplayRuleManifest.tree(revision.payload());
      String kind = text(node, "revisionKind");
      if (Set.of(
              "COMMAND_DEFINITION",
              "REALM_ENTRY_POLICY",
              "ASSET_REFERENCE",
              BrandingSource.REVISION_KIND,
              TemplateConfigSource.REVISION_KIND)
          .contains(kind)) continue;
      if (!REVISION_KIND.equals(kind))
        throw new IllegalArgumentException("Unknown Game Design revision kind");
      OperationKind operation = OperationKind.valueOf(text(node, "operation"));
      GameplayRuleManifest.fields(
          node,
          operation == OperationKind.UPSERT
              ? Set.of("schemaVersion", "revisionKind", "operation", "family", "definitionJson")
              : Set.of("schemaVersion", "revisionKind", "operation", "family", "key"));
      if (!node.path("schemaVersion").isInt() || node.path("schemaVersion").intValue() != 1)
        throw new IllegalArgumentException("Unsupported rule-source schema");
      Family family = Family.valueOf(text(node, "family"));
      Definition definition =
          operation == OperationKind.UPSERT
              ? GameplayRuleManifest.definition(family, text(node, "definitionJson"))
              : null;
      result.add(
          new Mutation(
              binding,
              revision.revisionOrder(),
              revision.revisionId(),
              operation,
              family,
              definition == null ? text(node, "key") : definition.key(),
              definition));
    }
    return List.copyOf(result);
  }

  public static String upsertPayload(Definition definition) {
    return net.firedevops.firemud.common.gamelogic.GameplayRuleSourceRevision.upsertPayload(
        definition);
  }

  public static String deletePayload(Family family, String key) {
    GameplayRuleManifest.identifier(key);
    return GameplayRuleManifest.canonical(
        Map.of(
            "schemaVersion",
            1,
            "revisionKind",
            REVISION_KIND,
            "operation",
            "DELETE",
            "family",
            family.name(),
            "key",
            key));
  }

  public static List<Entry> replay(List<Entry> inherited, DraftCommitBinding binding) {
    Map<Family, LinkedHashMap<String, Entry>> families = new EnumMap<>(Family.class);
    for (Family family : Family.values()) families.put(family, new LinkedHashMap<>());
    for (Entry entry : inherited) {
      if (!entry.sourceBinding().target().equals(binding.target())
          || families.get(entry.family()).put(entry.definition().key(), entry) != null)
        throw new IllegalArgumentException("Invalid inherited rule-source inventory");
    }
    for (Mutation mutation : mutations(binding)) {
      var family = families.get(mutation.family());
      if (mutation.operation() == OperationKind.DELETE) {
        if (family.remove(mutation.key()) == null)
          throw new IllegalArgumentException("Rule DELETE requires an existing authored key");
      } else
        family.put(
            mutation.key(),
            new Entry(
                mutation.family(),
                mutation.definition(),
                binding,
                mutation.revisionOrder(),
                mutation.revisionId()));
    }
    List<Entry> result =
        families.values().stream().flatMap(value -> value.values().stream()).toList();
    manifest(result); // Validate the complete atomic result, allowing forward references within a
    // commit.
    return result;
  }

  public static GameplayRuleManifest manifest(List<Entry> entries) {
    Map<Family, List<Definition>> families = new EnumMap<>(Family.class);
    for (Family family : Family.values()) families.put(family, new ArrayList<>());
    for (Entry entry : entries) families.get(entry.family()).add(entry.definition());
    return new GameplayRuleManifest(families);
  }

  public static boolean isScope(DraftCommitBinding.AffectedUnit unit) {
    return SCOPE.equals(unit.aggregateType())
        && SCOPE.equals(unit.scopeType())
        && SCOPE_ID.equals(unit.scopeId());
  }

  static String text(tools.jackson.databind.JsonNode node, String key) {
    if (!node.path(key).isTextual() || node.path(key).textValue().isBlank())
      throw new IllegalArgumentException("Required rule-source field missing: " + key);
    return node.path(key).textValue();
  }
}
