package net.firedevops.firemud.common.world;

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

/** Immutable exact World-local schema classification, not runtime-state or FRESH completeness. */
public final class WorldRuntimeFamilyCatalog {
  public static final String OWNER = "world-management-service";
  public static final String SCHEMA = "world-runtime-family-catalog/v1";
  public static final int MAX_CATALOG_BYTES = 2 * 1024 * 1024;
  public static final int MAX_FAMILIES = 512;
  public static final int MAX_FACTS = 16384;

  public enum Role {
    RUNTIME_PAYLOAD,
    LIFECYCLE,
    SOURCE,
    CONTROL,
    AUDIT,
    PROVENANCE,
    EXCLUDED,
    CATALOG
  }

  public enum StateClass {
    S3,
    NOT_PAYLOAD
  }

  public enum FreshDisposition {
    REQUIRED_TOPOLOGY,
    REQUIRED_LIFECYCLE,
    NOT_INITIALIZED_ACTOR_LOCATION,
    UNSUPPORTED_WEATHER,
    OWNER_EVENT_PROOF_REQUIRED,
    LEGACY_EXCLUDED,
    SOURCE_ONLY,
    CONTROL_ONLY,
    AUDIT_ONLY,
    PROVENANCE_ONLY,
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
      if (!component.isEmpty()) identifier(component);
      if (!familyId.equals(relation + (component.isEmpty() ? "" : "." + component))) {
        throw new IllegalArgumentException("Family identity must name its exact schema component");
      }
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(stateClass, "stateClass");
      Objects.requireNonNull(freshDisposition, "freshDisposition");
      if ((role == Role.RUNTIME_PAYLOAD || role == Role.LIFECYCLE || role == Role.EXCLUDED)
          != (stateClass == StateClass.S3)) {
        throw new IllegalArgumentException("Payload and non-payload classifications differ");
      }
      Role expected =
          switch (freshDisposition) {
            case REQUIRED_TOPOLOGY,
                NOT_INITIALIZED_ACTOR_LOCATION,
                UNSUPPORTED_WEATHER,
                OWNER_EVENT_PROOF_REQUIRED ->
                Role.RUNTIME_PAYLOAD;
            case REQUIRED_LIFECYCLE -> Role.LIFECYCLE;
            case LEGACY_EXCLUDED -> Role.EXCLUDED;
            case SOURCE_ONLY -> Role.SOURCE;
            case CONTROL_ONLY -> Role.CONTROL;
            case AUDIT_ONLY -> Role.AUDIT;
            case PROVENANCE_ONLY -> Role.PROVENANCE;
            case CATALOG_ONLY -> Role.CATALOG;
          };
      if (role != expected) throw new IllegalArgumentException("Contradictory family disposition");
    }
  }

  /** Definition digests cover actual PostgreSQL facts, not row counts or caller assertions. */
  public record SchemaFact(FactKind kind, String identity, String definitionDigest) {
    public SchemaFact {
      Objects.requireNonNull(kind, "kind");
      RuntimeFamilyCatalogValidation.factIdentity(identity);
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

  public record Evidence(Request request, UUID snapshotId, WorldRuntimeFamilyCatalog catalog) {
    public Evidence {
      Objects.requireNonNull(request, "request");
      requireUuid(snapshotId);
      Objects.requireNonNull(catalog, "catalog");
    }

    public String digest() {
      return sha256(WorldRuntimeFamilyCatalogCodec.evidenceBytes(this));
    }
  }

  private final long catalogEpoch;
  private final List<Family> families;
  private final List<SchemaFact> schemaFacts;
  private final ByteString exactBytes;

  private WorldRuntimeFamilyCatalog(
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
    return other instanceof WorldRuntimeFamilyCatalog catalog
        && exactBytes.equals(catalog.exactBytes);
  }

  @Override
  public int hashCode() {
    return exactBytes.hashCode();
  }

  /**
   * Closed bounded decoding retains the original PostgreSQL-sealed bytes without recanonicalizing.
   */
  public static WorldRuntimeFamilyCatalog fromStored(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_CATALOG_BYTES) {
      throw new IllegalArgumentException("World catalog exceeds its complete-source bound");
    }
    JsonNode root = RuntimeFamilyCatalogValidation.decode(bytes, MAX_CATALOG_BYTES);
    fields(root, Set.of("schema", "owner", "catalogEpoch", "families", "schemaFacts"));
    if (!SCHEMA.equals(text(root, "schema")) || !OWNER.equals(text(root, "owner"))) {
      throw new IllegalArgumentException("Unsupported World catalog schema/owner");
    }
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
    return new WorldRuntimeFamilyCatalog(epoch, families, facts, bytes);
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
