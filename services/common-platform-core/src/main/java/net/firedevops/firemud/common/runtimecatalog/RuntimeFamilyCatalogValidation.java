package net.firedevops.firemud.common.runtimecatalog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Internal structural checks shared by the four foreign-owner catalog values. Public only for their
 * separate packages; owner schemas, fact enums and disposition authority remain local.
 */
public final class RuntimeFamilyCatalogValidation {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private RuntimeFamilyCatalogValidation() {}

  public static JsonNode decode(byte[] bytes, int maximum) {
    if (bytes == null || bytes.length == 0 || bytes.length > maximum)
      throw new IllegalArgumentException("Catalog exceeds complete-source bound");
    try {
      String decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      return JSON.readTree(decoded);
    } catch (IOException | tools.jackson.core.JacksonException invalid) {
      throw new IllegalArgumentException("Invalid closed catalog", invalid);
    }
  }

  public static void fields(JsonNode node, Set<String> expected) {
    if (node == null || !node.isObject())
      throw new IllegalArgumentException("Closed object required");
    Set<String> actual = new HashSet<>();
    node.propertyNames().forEach(actual::add);
    if (!actual.equals(expected))
      throw new IllegalArgumentException("Unknown or missing catalog field");
  }

  public static String text(JsonNode node, String key) {
    JsonNode value = node.get(key);
    if (value == null || !value.isTextual())
      throw new IllegalArgumentException("Text field required");
    return value.asText();
  }

  public static void array(JsonNode node, int maximum) {
    if (node == null || !node.isArray() || node.isEmpty() || node.size() > maximum)
      throw new IllegalArgumentException("Complete bounded catalog array required");
  }

  /** Structural projection only; each owner must still parse kind through its own enum. */
  public record SchemaFact(String kind, String identity, String definitionDigest) {
    public SchemaFact {
      if (kind == null || kind.isBlank()) throw new IllegalArgumentException("Fact kind required");
      factIdentity(identity);
      requireDigest(definitionDigest);
    }
  }

  public static List<SchemaFact> schemaFacts(JsonNode node, int maximum) {
    array(node, maximum);
    var facts = new ArrayList<SchemaFact>();
    String previous = "";
    for (JsonNode value : node) {
      fields(value, Set.of("kind", "identity", "definitionDigest"));
      var fact =
          new SchemaFact(
              text(value, "kind"), text(value, "identity"), text(value, "definitionDigest"));
      String key = fact.kind() + "/" + fact.identity();
      if (previous.compareTo(key) >= 0)
        throw new IllegalArgumentException("Unordered/duplicate schema fact");
      previous = key;
      facts.add(fact);
    }
    return List.copyOf(facts);
  }

  public record FamilyComponent(String relation, String component) {
    public FamilyComponent {
      identifier(relation);
      if (component == null) throw new IllegalArgumentException("Component required");
      if (!component.isEmpty()) identifier(component);
    }
  }

  public static void requireComplete(List<FamilyComponent> families, List<SchemaFact> facts) {
    Set<String> tables = new HashSet<>();
    Set<String> columns = new HashSet<>();
    for (SchemaFact fact : facts) {
      if (fact.kind().equals("TABLE")) tables.add(fact.identity());
      if (fact.kind().equals("COLUMN")) columns.add(fact.identity());
    }
    Set<String> declaredTables = new HashSet<>();
    for (FamilyComponent family : families) {
      if (!tables.contains(family.relation()))
        throw new IllegalArgumentException("Missing family table");
      if (family.component().isEmpty()) declaredTables.add(family.relation());
      else if (!columns.contains(family.relation() + "." + family.component()))
        throw new IllegalArgumentException("Missing family component");
    }
    if (!tables.equals(declaredTables) || tables.isEmpty())
      throw new IllegalArgumentException("Every installed table needs an explicit disposition");
  }

  public static void factIdentity(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 8192
        || value.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid schema fact identity");
  }

  public static void identifier(String value) {
    if (value == null || !value.matches("[a-z][a-z0-9_]{0,62}"))
      throw new IllegalArgumentException("Schema identifier required");
  }

  public static void requireUuid(UUID value) {
    if (value == null || value.equals(new UUID(0, 0)))
      throw new IllegalArgumentException("Non-nil UUID required");
  }

  public static void requireDigest(String value) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}"))
      throw new IllegalArgumentException("SHA-256 required");
  }

  public static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 required by Java", impossible);
    }
  }
}
