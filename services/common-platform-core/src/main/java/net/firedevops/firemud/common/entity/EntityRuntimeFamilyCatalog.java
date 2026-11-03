package net.firedevops.firemud.common.entity;

import static net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogValidation.array;
import static net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogValidation.fields;
import static net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogValidation.identifier;
import static net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogValidation.text;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogValidation;
import tools.jackson.databind.JsonNode;

/** Exact Entity-local schema facts. Conditional/unresolved entries never qualify runtime state. */
public final class EntityRuntimeFamilyCatalog {
  public static final String OWNER = "entity-management-service";
  public static final String SCHEMA = "entity-runtime-family-catalog/v1";
  public static final int MAX_CATALOG_BYTES = 2 * 1024 * 1024;
  public static final int MAX_FAMILIES = 512;
  public static final int MAX_FACTS = 16384;

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
    JsonNode root = RuntimeFamilyCatalogValidation.decode(bytes, MAX_CATALOG_BYTES);
    fields(root, Set.of("schema", "owner", "catalogEpoch", "families", "schemaFacts"));
    if (!SCHEMA.equals(text(root, "schema")) || !OWNER.equals(text(root, "owner")))
      throw new IllegalArgumentException("Unsupported Entity catalog schema/owner");
    String epochText = text(root, "catalogEpoch");
    if (!epochText.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid epoch");
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
    var structuralFacts =
        RuntimeFamilyCatalogValidation.schemaFacts(root.get("schemaFacts"), MAX_FACTS);
    for (var fact : structuralFacts) {
      facts.add(
          new SchemaFact(FactKind.valueOf(fact.kind()), fact.identity(), fact.definitionDigest()));
    }
    RuntimeFamilyCatalogValidation.requireComplete(
        families.stream()
            .map(
                family ->
                    new RuntimeFamilyCatalogValidation.FamilyComponent(
                        family.relation(), family.component()))
            .toList(),
        structuralFacts);
    return new EntityRuntimeFamilyCatalog(epoch, families, facts, bytes);
  }

  static void requireUuid(UUID value) {
    RuntimeFamilyCatalogValidation.requireUuid(value);
  }

  static void requireDigest(String value) {
    RuntimeFamilyCatalogValidation.requireDigest(value);
  }

  public static String sha256(byte[] bytes) {
    return RuntimeFamilyCatalogValidation.sha256(bytes);
  }
}
