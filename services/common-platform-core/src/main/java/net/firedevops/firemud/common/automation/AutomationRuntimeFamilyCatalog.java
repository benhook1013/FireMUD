package net.firedevops.firemud.common.automation;

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

/** Exact Automation SQL source classification; neither enrollment nor a FRESH state manifest. */
public final class AutomationRuntimeFamilyCatalog {
  public static final String OWNER = "automation-scripting-service";
  public static final String SCHEMA = "automation-runtime-family-catalog/v1";
  public static final int MAX_CATALOG_BYTES = 2 * 1024 * 1024;
  public static final int MAX_FAMILIES = 512;
  public static final int MAX_FACTS = 16384;
  public static final int MAX_NON_SQL_GAPS = 32;
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public enum Role {
    RUNTIME_PAYLOAD,
    SOURCE,
    CONTROL,
    AUDIT,
    PROVENANCE,
    CATALOG
  }

  /** UNKNOWN/UNRESOLVED are blocking facts, never implicit S3 or a new replacement class. */
  public enum StateClass {
    S2,
    UNKNOWN,
    UNRESOLVED,
    NOT_PAYLOAD
  }

  public enum FreshDisposition {
    STATE_CLASS_UNKNOWN_BLOCKING,
    NAMESPACE_MAPPING_BLOCKING,
    OWNERSHIP_DRIFT_BLOCKING,
    RUNTIME_EVIDENCE_BLOCKING,
    SOURCE_ONLY,
    CONTROL_ONLY,
    AUDIT_ONLY,
    PROVENANCE_ONLY,
    CATALOG_ONLY
  }

  public enum RetentionDisposition {
    OWNER_POLICY_RETAINED,
    UNRESOLVED_RETAINED,
    IMMUTABLE_RETAINED
  }

  public enum GapId {
    CAPACITY_LEASE_ACCOUNTING,
    CENTRAL_RESET_ACL_REGISTRATION,
    DURABLE_CHARGE_MARKERS,
    QUEUE_DISCOVERY_AND_ENCODING,
    QUEUE_POINTER_RETENTION
  }

  public enum GapDisposition {
    UNRESOLVED_BLOCKING,
    UNSUPPORTED_BLOCKING
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
      FreshDisposition freshDisposition,
      RetentionDisposition retentionDisposition,
      String ownerConsequence) {
    public Family {
      identifier(relation);
      Objects.requireNonNull(component, "component");
      if (!component.isEmpty()) identifier(component);
      if (!Objects.equals(familyId, relation + (component.isEmpty() ? "" : "." + component))) {
        throw new IllegalArgumentException("Family must name its exact installed component");
      }
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(stateClass, "stateClass");
      Objects.requireNonNull(freshDisposition, "freshDisposition");
      Objects.requireNonNull(retentionDisposition, "retentionDisposition");
      consequence(ownerConsequence);
      boolean coherent =
          switch (freshDisposition) {
            case STATE_CLASS_UNKNOWN_BLOCKING ->
                role == Role.RUNTIME_PAYLOAD && stateClass == StateClass.UNKNOWN;
            case NAMESPACE_MAPPING_BLOCKING ->
                role == Role.RUNTIME_PAYLOAD && stateClass == StateClass.S2;
            case OWNERSHIP_DRIFT_BLOCKING ->
                role == Role.RUNTIME_PAYLOAD && stateClass == StateClass.UNRESOLVED;
            case RUNTIME_EVIDENCE_BLOCKING ->
                (role == Role.CONTROL || role == Role.AUDIT || role == Role.PROVENANCE)
                    && stateClass == StateClass.NOT_PAYLOAD;
            case SOURCE_ONLY -> role == Role.SOURCE && stateClass == StateClass.NOT_PAYLOAD;
            case CONTROL_ONLY -> role == Role.CONTROL && stateClass == StateClass.NOT_PAYLOAD;
            case AUDIT_ONLY -> role == Role.AUDIT && stateClass == StateClass.NOT_PAYLOAD;
            case PROVENANCE_ONLY -> role == Role.PROVENANCE && stateClass == StateClass.NOT_PAYLOAD;
            case CATALOG_ONLY -> role == Role.CATALOG && stateClass == StateClass.NOT_PAYLOAD;
          };
      if (!coherent) throw new IllegalArgumentException("Contradictory Automation disposition");
      if (role == Role.CATALOG && retentionDisposition != RetentionDisposition.IMMUTABLE_RETAINED) {
        throw new IllegalArgumentException("Catalog evidence must remain retained and immutable");
      }
    }
  }

  /** Explicit known limitations, not an enumeration or census of Redis keys. */
  public record NonSqlGap(GapId id, GapDisposition disposition, String ownerConsequence) {
    public NonSqlGap {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(disposition, "disposition");
      consequence(ownerConsequence);
    }
  }

  public record SchemaFact(FactKind kind, String identity, String definitionDigest) {
    public SchemaFact {
      Objects.requireNonNull(kind, "kind");
      if (identity == null
          || identity.isBlank()
          || identity.length() > 8192
          || identity.codePoints().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("Invalid schema fact identity");
      }
      requireDigest(definitionDigest);
    }
  }

  public record Request(UUID readRequestId, String targetNamespace) {
    public Request {
      requireUuid(readRequestId);
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Exact workload namespace required");
      }
    }
  }

  public record Evidence(Request request, UUID snapshotId, AutomationRuntimeFamilyCatalog catalog) {
    public Evidence {
      Objects.requireNonNull(request, "request");
      requireUuid(snapshotId);
      Objects.requireNonNull(catalog, "catalog");
    }

    public String digest() {
      return sha256(AutomationRuntimeFamilyCatalogCodec.evidenceBytes(this));
    }
  }

  private final long catalogEpoch;
  private final List<Family> families;
  private final List<SchemaFact> schemaFacts;
  private final List<NonSqlGap> nonSqlGaps;
  private final ByteString exactBytes;

  private AutomationRuntimeFamilyCatalog(
      long epoch,
      List<Family> families,
      List<SchemaFact> facts,
      List<NonSqlGap> gaps,
      byte[] bytes) {
    catalogEpoch = epoch;
    this.families = List.copyOf(families);
    schemaFacts = List.copyOf(facts);
    nonSqlGaps = List.copyOf(gaps);
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

  public List<NonSqlGap> nonSqlGaps() {
    return nonSqlGaps;
  }

  public byte[] exactBytes() {
    return exactBytes.toByteArray();
  }

  public String digest() {
    return sha256(exactBytes.toByteArray());
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof AutomationRuntimeFamilyCatalog catalog
        && exactBytes.equals(catalog.exactBytes);
  }

  @Override
  public int hashCode() {
    return exactBytes.hashCode();
  }

  /** Retains original PostgreSQL-sealed bytes; no constructor can assert schema completeness. */
  public static AutomationRuntimeFamilyCatalog fromStored(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_CATALOG_BYTES) {
      throw new IllegalArgumentException("Automation catalog exceeds complete-source bound");
    }
    try {
      String decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      JsonNode root = JSON.readTree(decoded);
      fields(
          root, Set.of("schema", "owner", "catalogEpoch", "families", "schemaFacts", "nonSqlGaps"));
      if (!SCHEMA.equals(text(root, "schema")) || !OWNER.equals(text(root, "owner"))) {
        throw new IllegalArgumentException("Unsupported Automation schema/owner");
      }
      String epochText = text(root, "catalogEpoch");
      if (!epochText.matches("[1-9][0-9]{0,18}"))
        throw new IllegalArgumentException("Invalid epoch");
      long epoch = Long.parseLong(epochText);
      var families = new ArrayList<Family>();
      var facts = new ArrayList<SchemaFact>();
      var gaps = new ArrayList<NonSqlGap>();
      array(root.get("families"), MAX_FAMILIES);
      String previous = "";
      for (JsonNode node : root.get("families")) {
        fields(
            node,
            Set.of(
                "familyId",
                "relation",
                "component",
                "role",
                "stateClass",
                "freshDisposition",
                "retentionDisposition",
                "ownerConsequence"));
        Family family =
            new Family(
                text(node, "familyId"),
                text(node, "relation"),
                text(node, "component"),
                Role.valueOf(text(node, "role")),
                StateClass.valueOf(text(node, "stateClass")),
                FreshDisposition.valueOf(text(node, "freshDisposition")),
                RetentionDisposition.valueOf(text(node, "retentionDisposition")),
                text(node, "ownerConsequence"));
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
      Set<String> declared = new HashSet<>();
      for (Family family : families) {
        if (!tables.contains(family.relation()))
          throw new IllegalArgumentException("Missing family table");
        if (family.component().isEmpty()) declared.add(family.relation());
        else if (!columns.contains(family.familyId()))
          throw new IllegalArgumentException("Missing family component");
      }
      if (!tables.equals(declared) || tables.isEmpty()) {
        throw new IllegalArgumentException("Every installed table needs an explicit disposition");
      }
      array(root.get("nonSqlGaps"), MAX_NON_SQL_GAPS);
      previous = "";
      for (JsonNode node : root.get("nonSqlGaps")) {
        fields(node, Set.of("id", "disposition", "ownerConsequence"));
        NonSqlGap gap =
            new NonSqlGap(
                GapId.valueOf(text(node, "id")),
                GapDisposition.valueOf(text(node, "disposition")),
                text(node, "ownerConsequence"));
        if (previous.compareTo(gap.id().name()) >= 0)
          throw new IllegalArgumentException("Unordered/duplicate non-SQL gap");
        previous = gap.id().name();
        gaps.add(gap);
      }
      return new AutomationRuntimeFamilyCatalog(epoch, families, facts, gaps, bytes);
    } catch (java.io.IOException | tools.jackson.core.JacksonException invalid) {
      throw new IllegalArgumentException("Invalid closed Automation catalog", invalid);
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

  private static void consequence(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 1024
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Bounded explicit owner consequence required");
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
    if (node == null || !node.isArray() || node.isEmpty() || node.size() > maximum) {
      throw new IllegalArgumentException("Complete bounded catalog array required");
    }
  }
}
