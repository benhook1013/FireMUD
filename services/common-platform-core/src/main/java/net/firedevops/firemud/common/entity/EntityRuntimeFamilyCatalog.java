package net.firedevops.firemud.common.entity;

import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exact Entity-local schema facts. Conditional/unresolved entries never qualify runtime state. */
public final class EntityRuntimeFamilyCatalog {
  public static final String OWNER = "entity-management-service";
  public static final String SCHEMA = "entity-runtime-family-catalog/v1";
  public static final int MAX_CATALOG_BYTES = 2 * 1024 * 1024;
  public static final int MAX_FAMILIES = 512;
  public static final int MAX_FACTS = 16384;
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public enum Role {
    RUNTIME_PAYLOAD,
    MIXED,
    SOURCE,
    IDENTITY,
    ASSIGNMENT,
    CREATION,
    DISCOVERY,
    RETENTION,
    PROJECTION,
    REPLAY,
    AUDIT,
    CONTROL,
    PROVENANCE,
    ALLOCATOR,
    HISTORY,
    CATALOG
  }

  public enum StateClass {
    S1,
    S2,
    S3,
    CONDITIONAL_S1_S2,
    CONDITIONAL_HOLDER,
    UNRESOLVED,
    NOT_PAYLOAD
  }

  /** These are catalog facts, not runtime initialization outcomes. */
  public enum FreshDisposition {
    PROVISIONAL_NAMESPACE,
    TEMPLATE_MAPPING_REQUIRED,
    BLOCKING_NAMESPACE_REMAP,
    BLOCKING_HOLDER_CLOSURE,
    BLOCKING_MIXED_NPC,
    BLOCKING_ALLOCATOR_CLASS,
    GROUND_ONLY_S3,
    PROJECTION_S3_RETAIN_LINEAGE,
    AUTHORED_SOURCE_ONLY,
    SELECTED_EMPTY_SOURCE_ONLY,
    IDENTITY_ONLY,
    ASSIGNMENT_ONLY,
    CREATE_ONLY,
    DISCOVERY_ONLY,
    RETENTION_ONLY,
    REPLAY_ONLY,
    AUDIT_ONLY,
    CONTROL_ONLY,
    PROVENANCE_ONLY,
    HISTORY_ONLY,
    CATALOG_ONLY
  }

  public enum FactKind {
    TABLE,
    COLUMN,
    CONSTRAINT,
    INDEX,
    TRIGGER,
    ROUTINE,
    SEQUENCE,
    VIEW,
    TYPE,
    POLICY,
    RULE,
    MIGRATION
  }

  public record Family(
      String familyId,
      String relation,
      String component,
      Role role,
      StateClass stateClass,
      FreshDisposition freshDisposition) {
    public Family {
      identifier(relation);
      Objects.requireNonNull(component, "component");
      if (!component.isEmpty()) identifier(component);
      if (!Objects.equals(familyId, relation + (component.isEmpty() ? "" : "." + component)))
        throw new IllegalArgumentException("Family must name its exact physical component");
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(stateClass, "stateClass");
      Objects.requireNonNull(freshDisposition, "freshDisposition");
      Role expectedRole =
          switch (freshDisposition) {
            case PROVISIONAL_NAMESPACE,
                TEMPLATE_MAPPING_REQUIRED,
                BLOCKING_NAMESPACE_REMAP,
                BLOCKING_HOLDER_CLOSURE,
                GROUND_ONLY_S3 ->
                Role.RUNTIME_PAYLOAD;
            case BLOCKING_MIXED_NPC -> Role.MIXED;
            case BLOCKING_ALLOCATOR_CLASS -> Role.ALLOCATOR;
            case PROJECTION_S3_RETAIN_LINEAGE -> Role.PROJECTION;
            case AUTHORED_SOURCE_ONLY, SELECTED_EMPTY_SOURCE_ONLY -> Role.SOURCE;
            case IDENTITY_ONLY -> Role.IDENTITY;
            case ASSIGNMENT_ONLY -> Role.ASSIGNMENT;
            case CREATE_ONLY -> Role.CREATION;
            case DISCOVERY_ONLY -> Role.DISCOVERY;
            case RETENTION_ONLY -> Role.RETENTION;
            case REPLAY_ONLY -> Role.REPLAY;
            case AUDIT_ONLY -> Role.AUDIT;
            case CONTROL_ONLY -> Role.CONTROL;
            case PROVENANCE_ONLY -> Role.PROVENANCE;
            case HISTORY_ONLY -> Role.HISTORY;
            case CATALOG_ONLY -> Role.CATALOG;
          };
      StateClass expectedClass =
          switch (freshDisposition) {
            case PROVISIONAL_NAMESPACE -> StateClass.S1;
            case TEMPLATE_MAPPING_REQUIRED -> StateClass.S2;
            case BLOCKING_NAMESPACE_REMAP -> StateClass.CONDITIONAL_S1_S2;
            case BLOCKING_HOLDER_CLOSURE -> StateClass.CONDITIONAL_HOLDER;
            case BLOCKING_MIXED_NPC, BLOCKING_ALLOCATOR_CLASS -> StateClass.UNRESOLVED;
            case GROUND_ONLY_S3, PROJECTION_S3_RETAIN_LINEAGE -> StateClass.S3;
            default -> StateClass.NOT_PAYLOAD;
          };
      if (role != expectedRole || stateClass != expectedClass)
        throw new IllegalArgumentException("Contradictory Entity family classification");
    }

    /** The owning contract's singular character family has one physical/wire spelling. */
    public String logicalFamily() {
      return relation.equals("characters") ? "character" : relation;
    }
  }

  public record SchemaFact(FactKind kind, String identity, String definitionDigest) {
    public SchemaFact {
      Objects.requireNonNull(kind, "kind");
      if (identity == null
          || identity.isBlank()
          || identity.length() > 8192
          || identity.codePoints().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("Invalid schema fact identity");
      requireDigest(definitionDigest);
    }
  }

  public record Request(UUID readRequestId, String targetNamespace) {
    public Request {
      requireUuid(readRequestId);
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace))
        throw new IllegalArgumentException("Exact workload namespace required");
    }
  }

  public record Evidence(Request request, UUID snapshotId, EntityRuntimeFamilyCatalog catalog) {
    public Evidence {
      Objects.requireNonNull(request, "request");
      requireUuid(snapshotId);
      Objects.requireNonNull(catalog, "catalog");
    }

    public String digest() {
      return sha256(EntityRuntimeFamilyCatalogCodec.evidenceBytes(this));
    }
  }

  private final long catalogEpoch;
  private final List<Family> families;
  private final List<SchemaFact> schemaFacts;
  private final ByteString exactBytes;

  private EntityRuntimeFamilyCatalog(
      long epoch, List<Family> families, List<SchemaFact> facts, byte[] bytes) {
    catalogEpoch = epoch;
    this.families = List.copyOf(families);
    schemaFacts = List.copyOf(facts);
    exactBytes = ByteString.copyFrom(bytes);
  }

  public long catalogEpoch() {
    return catalogEpoch;
  }

  public List<Family> families() {
    return families;
  }

  public List<SchemaFact> schemaFacts() {
    return schemaFacts;
  }

  public byte[] exactBytes() {
    return exactBytes.toByteArray();
  }

  public String digest() {
    return sha256(exactBytes.toByteArray());
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof EntityRuntimeFamilyCatalog catalog
        && exactBytes.equals(catalog.exactBytes);
  }

  @Override
  public int hashCode() {
    return exactBytes.hashCode();
  }

  /** Closed bounded parsing retains original PostgreSQL bytes rather than recanonicalizing. */
  public static EntityRuntimeFamilyCatalog fromStored(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_CATALOG_BYTES)
      throw new IllegalArgumentException("Entity catalog exceeds complete-source bound");
    try {
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      JsonNode root = JSON.readTree(json);
      fields(root, Set.of("schema", "owner", "catalogEpoch", "families", "schemaFacts"));
      if (!SCHEMA.equals(text(root, "schema")) || !OWNER.equals(text(root, "owner")))
        throw new IllegalArgumentException("Unsupported Entity catalog schema/owner");
      String epochText = text(root, "catalogEpoch");
      if (!epochText.matches("[1-9][0-9]{0,18}"))
        throw new IllegalArgumentException("Invalid epoch");
      long epoch = Long.parseLong(epochText);
      var families = new ArrayList<Family>();
      var facts = new ArrayList<SchemaFact>();
      array(root.get("families"), MAX_FAMILIES);
      String previous = "";
      for (JsonNode node : root.get("families")) {
        fields(
            node,
            Set.of("familyId", "relation", "component", "role", "stateClass", "freshDisposition"));
        Family family =
            new Family(
                text(node, "familyId"),
                text(node, "relation"),
                text(node, "component"),
                Role.valueOf(text(node, "role")),
                StateClass.valueOf(text(node, "stateClass")),
                FreshDisposition.valueOf(text(node, "freshDisposition")));
        if (previous.compareTo(family.familyId()) >= 0)
          throw new IllegalArgumentException("Unordered/duplicate family");
        previous = family.familyId();
        families.add(family);
      }
      array(root.get("schemaFacts"), MAX_FACTS);
      previous = "";
      Set<String> tables = new HashSet<>();
      Set<String> columns = new HashSet<>();
      for (JsonNode node : root.get("schemaFacts")) {
        fields(node, Set.of("kind", "identity", "definitionDigest"));
        SchemaFact fact =
            new SchemaFact(
                FactKind.valueOf(text(node, "kind")),
                text(node, "identity"),
                text(node, "definitionDigest"));
        String key = fact.kind().name() + "/" + fact.identity();
        if (previous.compareTo(key) >= 0)
          throw new IllegalArgumentException("Unordered/duplicate schema fact");
        previous = key;
        if (fact.kind() == FactKind.TABLE) tables.add(fact.identity());
        if (fact.kind() == FactKind.COLUMN) columns.add(fact.identity());
        facts.add(fact);
      }
      Set<String> declaredTables = new HashSet<>();
      for (Family family : families) {
        if (!tables.contains(family.relation()))
          throw new IllegalArgumentException("Missing family table");
        if (family.component().isEmpty()) declaredTables.add(family.relation());
        else if (!columns.contains(family.familyId()))
          throw new IllegalArgumentException("Missing family component");
      }
      if (!tables.equals(declaredTables) || tables.isEmpty())
        throw new IllegalArgumentException("Every installed table needs an explicit disposition");
      return new EntityRuntimeFamilyCatalog(epoch, families, facts, bytes);
    } catch (java.io.IOException | tools.jackson.core.JacksonException invalid) {
      throw new IllegalArgumentException("Invalid closed Entity catalog", invalid);
    }
  }

  static void requireUuid(UUID value) {
    if (value == null || value.equals(new UUID(0, 0)))
      throw new IllegalArgumentException("Non-nil UUID required");
  }

  static void requireDigest(String value) {
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

  private static void identifier(String value) {
    if (value == null || !value.matches("[a-z][a-z0-9_]{0,62}"))
      throw new IllegalArgumentException("Schema identifier required");
  }

  private static void fields(JsonNode node, Set<String> expected) {
    if (node == null || !node.isObject())
      throw new IllegalArgumentException("Closed object required");
    Set<String> actual = new HashSet<>();
    node.propertyNames().forEach(actual::add);
    if (!actual.equals(expected))
      throw new IllegalArgumentException("Unknown or missing catalog field");
  }

  private static String text(JsonNode node, String key) {
    JsonNode value = node.get(key);
    if (value == null || !value.isTextual())
      throw new IllegalArgumentException("Text field required");
    return value.asText();
  }

  private static void array(JsonNode node, int maximum) {
    if (node == null || !node.isArray() || node.isEmpty() || node.size() > maximum)
      throw new IllegalArgumentException("Complete bounded catalog array required");
  }
}
