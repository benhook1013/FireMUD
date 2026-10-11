package net.firedevops.firemud.common.gamedesign;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed branding asset-reference source operations for the existing Draft coordinator. This codec
 * does not qualify asset rows, authenticate an operation, establish genesis or attest completeness.
 */
public final class BrandingSource {
  public static final String SCOPE = "BRANDING_ASSET_REFERENCE_SET";
  public static final String SCOPE_ID = "effective";
  public static final String REVISION_KIND = "BRANDING_ASSET_REFERENCE";
  public static final String FAMILY = "BRANDING";

  public enum Role {
    RESOURCE,
    LOGO,
    FAVICON,
    THEME
  }

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private BrandingSource() {}

  public static Map<String, String> emptyRoleDeclarations() {
    return Map.of("RESOURCE", "EMPTY", "LOGO", "EMPTY", "FAVICON", "EMPTY", "THEME", "EMPTY");
  }

  public enum OperationKind {
    UPSERT,
    DELETE
  }

  public enum Requiredness {
    REQUIRED,
    OPTIONAL
  }

  /** The complete original binding retains request, target, commit and exact payload identity. */
  public record Mutation(
      DraftCommitBinding binding,
      String revisionOrder,
      UUID revisionId,
      OperationKind operation,
      String usageKey,
      String assetRowId,
      Role role,
      Requiredness requiredness) {
    public Mutation {
      Objects.requireNonNull(binding, "binding");
      Objects.requireNonNull(operation, "operation");
      requireUsageKey(usageKey);
      Objects.requireNonNull(role, "role");
      var revision =
          binding.revisions().stream()
              .filter(value -> value.revisionId().equals(revisionId))
              .filter(value -> value.revisionOrder().equals(revisionOrder))
              .filter(value -> value.owner() == Owner.GAME_DESIGN_CONTROL_PLANE)
              .findFirst()
              .orElseThrow(
                  () -> new IllegalArgumentException("Asset revision is not in its exact binding"));
      String expectedPayload;
      if (operation == OperationKind.UPSERT) {
        expectedPayload = upsertPayload(assetRowId, usageKey, role, requiredness);
      } else {
        if (assetRowId != null || requiredness != null) {
          throw new IllegalArgumentException("Asset DELETE cannot carry UPSERT fields");
        }
        expectedPayload = deletePayload(usageKey, role);
      }
      // Parse before canonicalization so duplicate fields or trailing JSON can never collapse.
      JsonNode actual = tree(revision.payload());
      if (!actual.path("schemaVersion").isInt() || actual.path("schemaVersion").intValue() != 1) {
        throw new IllegalArgumentException("Unsupported asset source schemaVersion");
      }
      fields(
          actual,
          operation == OperationKind.UPSERT
              ? Set.of(
                  "schemaVersion",
                  "revisionKind",
                  "family",
                  "role",
                  "operation",
                  "assetRowId",
                  "usageKey",
                  "requiredness")
              : Set.of("schemaVersion", "revisionKind", "family", "role", "operation", "usageKey"));
      if (!canonical(actual).equals(expectedPayload)) {
        throw new IllegalArgumentException("Asset mutation differs from the exact bound revision");
      }
    }
  }

  /** Exact creating source provenance; numeric asset row identity is internal to Game Design. */
  public record Reference(
      String usageKey,
      String assetRowId,
      Role role,
      Requiredness requiredness,
      DraftCommitBinding sourceBinding,
      String revisionOrder,
      UUID revisionId) {
    public Reference {
      // A reference cannot rewrite the requiredness or asset identity of its creating UPSERT.
      new Mutation(
          sourceBinding,
          revisionOrder,
          revisionId,
          OperationKind.UPSERT,
          usageKey,
          assetRowId,
          role,
          requiredness);
    }

    public String family() {
      return FAMILY;
    }
  }

  /** Typed source snapshot only; presence or an empty list is not COMPLETE evidence. */
  public record Snapshot(DraftCommitBinding binding, List<Reference> references) {
    public Snapshot {
      Objects.requireNonNull(binding, "binding");
      references = List.copyOf(Objects.requireNonNull(references, "references"));
      requireDistinctReferences(references);
      for (Reference reference : references) {
        if (!binding.target().equals(reference.sourceBinding().target())) {
          throw new IllegalArgumentException("Asset reference belongs to another source target");
        }
      }
    }
  }

  public static List<Mutation> mutations(DraftCommitBinding binding) {
    Objects.requireNonNull(binding, "binding");
    List<Mutation> result = new ArrayList<>();
    for (var revision : binding.revisions()) {
      if (revision.owner() != Owner.GAME_DESIGN_CONTROL_PLANE) continue;
      JsonNode payload = tree(revision.payload());
      String kind = text(payload, "revisionKind");
      if (CommandSource.REVISION_KIND.equals(kind)
          || RealmPolicySource.REVISION_KIND.equals(kind)
          || TemplateConfigSourceValues.REVISION_KIND.equals(kind)
          || GameplayRuleSource.REVISION_KIND.equals(kind)
          || AssetSource.REVISION_KIND.equals(kind)) continue;
      if (!REVISION_KIND.equals(kind)) {
        throw new IllegalArgumentException("Unsupported Game Design asset source revision kind");
      }
      if (!payload.path("schemaVersion").isInt() || payload.path("schemaVersion").intValue() != 1) {
        throw new IllegalArgumentException("Unsupported asset source schemaVersion");
      }
      OperationKind operation = OperationKind.valueOf(text(payload, "operation"));
      result.add(
          new Mutation(
              binding,
              revision.revisionOrder(),
              revision.revisionId(),
              operation,
              text(payload, "usageKey"),
              operation == OperationKind.UPSERT ? text(payload, "assetRowId") : null,
              Role.valueOf(text(payload, "role")),
              operation == OperationKind.UPSERT
                  ? Requiredness.valueOf(text(payload, "requiredness"))
                  : null));
    }
    return List.copyOf(result);
  }

  public static String upsertPayload(
      String assetRowId, String usageKey, Role role, Requiredness requiredness) {
    requireAssetRowId(assetRowId);
    Objects.requireNonNull(role, "role");
    requireUsageKey(usageKey);
    if (requiredness != Requiredness.REQUIRED) {
      throw new IllegalArgumentException("Branding RESOURCE requires REQUIRED owner policy");
    }
    return canonical(
        Map.of(
            "schemaVersion",
            1,
            "revisionKind",
            REVISION_KIND,
            "family",
            FAMILY,
            "role",
            role.name(),
            "operation",
            "UPSERT",
            "assetRowId",
            assetRowId,
            "usageKey",
            usageKey,
            "requiredness",
            requiredness.name()));
  }

  public static String deletePayload(String usageKey, Role role) {
    Objects.requireNonNull(role, "role");
    requireUsageKey(usageKey);
    return canonical(
        Map.of(
            "schemaVersion",
            1,
            "revisionKind",
            REVISION_KIND,
            "family",
            FAMILY,
            "role",
            role.name(),
            "operation",
            "DELETE",
            "usageKey",
            usageKey));
  }

  /** Applies only explicit ordered operations, preserving provenance on untouched references. */
  public static Snapshot replay(List<Reference> inherited, DraftCommitBinding binding) {
    var baseline = new Snapshot(binding, inherited);
    Map<String, Reference> effective = new HashMap<>();
    for (Reference reference : baseline.references())
      effective.put(reference.usageKey(), reference);
    for (Mutation mutation : mutations(binding)) {
      if (mutation.operation() == OperationKind.DELETE) {
        effective.remove(mutation.usageKey());
      } else {
        Reference reference =
            new Reference(
                mutation.usageKey(),
                mutation.assetRowId(),
                mutation.role(),
                mutation.requiredness(),
                binding,
                mutation.revisionOrder(),
                mutation.revisionId());
        for (Reference current : effective.values()) {
          if (current.assetRowId().equals(reference.assetRowId())
              && !current.usageKey().equals(reference.usageKey())) {
            throw new IllegalArgumentException(
                "One asset source cannot acquire multiple usage aliases");
          }
        }
        effective.put(reference.usageKey(), reference);
      }
    }
    return new Snapshot(
        binding,
        effective.values().stream()
            .sorted(Comparator.comparing(Reference::usageKey, BrandingSource::compareUtf8))
            .toList());
  }

  private static void requireDistinctReferences(List<Reference> references) {
    Set<String> keys = new HashSet<>();
    Set<String> assets = new HashSet<>();
    for (Reference reference : references) {
      if (!keys.add(reference.usageKey()) || !assets.add(reference.assetRowId())) {
        throw new IllegalArgumentException("Asset source repeats a usage key or source row");
      }
    }
  }

  private static void requireAssetRowId(String value) {
    if (value == null || value.length() > 19 || !value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(
          "assetRowId must be a positive canonical BIGINT decimal string");
    }
    try {
      Long.parseLong(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("assetRowId exceeds its owner BIGINT range", exception);
    }
  }

  private static void requireUsageKey(String value) {
    if (value == null
        || value.isBlank()
        || value.codePointCount(0, value.length()) > 255
        || "manifest.json".equals(value)) {
      throw new IllegalArgumentException(
          "Asset usageKey must fit its exact nonblank owner filename");
    }
  }

  private static String text(JsonNode value, String field) {
    if (!value.isObject() || !value.path(field).isTextual()) {
      throw new IllegalArgumentException("Asset source requires textual " + field);
    }
    return value.path(field).textValue();
  }

  private static void fields(JsonNode value, Set<String> expected) {
    if (!value.isObject()
        || value.size() != expected.size()
        || value.properties().stream().anyMatch(entry -> !expected.contains(entry.getKey()))) {
      throw new IllegalArgumentException("Asset source members differ from the closed schema");
    }
  }

  private static JsonNode tree(String value) {
    try {
      return JSON.readTree(value);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Malformed asset source JSON", exception);
    }
  }

  private static String canonical(Object value) {
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)),
          StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Asset source canonicalization failed", exception);
    }
  }

  private static int compareUtf8(String left, String right) {
    return java.util.Arrays.compareUnsigned(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }
}
