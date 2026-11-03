package net.firedevops.firemud.common.social;

import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
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

/** Immutable exact Social-local schema classification, not a row inventory or FRESH manifest. */
public final class SocialRuntimeFamilyCatalog {
  public static final String OWNER = "social-groups-service";
  public static final String SCHEMA = "social-runtime-family-catalog/v1";
  public static final int MAX_CATALOG_BYTES = 2 * 1024 * 1024;
  public static final int MAX_FAMILIES = 512;
  public static final int MAX_FACTS = 16384;

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public enum Role {
    GUILD_DEFINITION_ACL,
    GUILD_MEMBERSHIP_ACL,
    SOCIAL_RELATIONSHIP,
    OWNERSHIP_DRIFT,
    PLAYER_HISTORY,
    EFFECT_REPLAY,
    ACCOUNT_IDENTITY_CARRIER,
    OPERATIONAL_EVIDENCE,
    MIGRATION_HISTORY,
    CATALOG
  }

  /**
   * Explicit current-scope qualification; no S1/S2/S3 or absence-derived disposition is implied.
   */
  public enum FreshDisposition {
    GUILD_SUBJECT_TYPE_UNSUPPORTED,
    GUILD_CHILD_TENANT_IDENTITY_UNPROVEN,
    RELATIONSHIP_BLOCK_LIFECYCLE_UNIMPLEMENTED,
    ACCOUNT_PAIR_SCOPE_DRIFT,
    ENTITY_VALUE_OWNERSHIP_DRIFT_BLOCKING,
    CHAT_HISTORY_RETENTION_VIEW_AND_WHISPER_SCOPE_UNPROVEN,
    CHAT_EFFECT_REPLAY_DIGEST_AND_HORIZON_UNPROVEN,
    MAIL_HISTORY_RETENTION_AND_ACKNOWLEDGEMENT_UNPROVEN,
    ACCOUNT_IDENTITY_CARRIER_ONLY,
    SAGA_ADOPTER_UNRESOLVED,
    MIGRATION_HISTORY_ONLY,
    CATALOG_ONLY
  }

  public enum TargetOwner {
    NONE,
    ACCOUNT_SERVICE,
    ENTITY_MANAGEMENT_SERVICE
  }

  public enum ContractGap {
    CHAT_RESTRICTION_AND_REVISION,
    DIRECTIONAL_BLOCK_LIFECYCLE,
    ENTITY_ESCROW_REFERENCE_FAMILIES,
    GAMEPLAY_WHISPER_NAMESPACE_SCOPE,
    SAFETY_EVIDENCE_AND_CONTENT_FREE_RECEIPTS,
    SOCIAL_PLAYER_STANDING_OWNER,
    SOCIAL_SAGA_ADOPTER_CLASSIFICATION
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
      FreshDisposition freshDisposition,
      TargetOwner targetOwner) {
    public Family {
      identifier(relation);
      if (!component.isEmpty()) identifier(component);
      if (!familyId.equals(relation + (component.isEmpty() ? "" : "." + component))) {
        throw new IllegalArgumentException("Family identity must name its exact schema component");
      }
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(freshDisposition, "freshDisposition");
      Objects.requireNonNull(targetOwner, "targetOwner");
      Role expectedRole =
          switch (freshDisposition) {
            case GUILD_SUBJECT_TYPE_UNSUPPORTED ->
                role == Role.GUILD_DEFINITION_ACL || role == Role.GUILD_MEMBERSHIP_ACL
                    ? role
                    : null;
            case GUILD_CHILD_TENANT_IDENTITY_UNPROVEN,
                RELATIONSHIP_BLOCK_LIFECYCLE_UNIMPLEMENTED,
                ACCOUNT_PAIR_SCOPE_DRIFT ->
                Role.SOCIAL_RELATIONSHIP;
            case ENTITY_VALUE_OWNERSHIP_DRIFT_BLOCKING -> Role.OWNERSHIP_DRIFT;
            case CHAT_HISTORY_RETENTION_VIEW_AND_WHISPER_SCOPE_UNPROVEN,
                MAIL_HISTORY_RETENTION_AND_ACKNOWLEDGEMENT_UNPROVEN ->
                Role.PLAYER_HISTORY;
            case CHAT_EFFECT_REPLAY_DIGEST_AND_HORIZON_UNPROVEN -> Role.EFFECT_REPLAY;
            case ACCOUNT_IDENTITY_CARRIER_ONLY -> Role.ACCOUNT_IDENTITY_CARRIER;
            case SAGA_ADOPTER_UNRESOLVED -> Role.OPERATIONAL_EVIDENCE;
            case MIGRATION_HISTORY_ONLY -> Role.MIGRATION_HISTORY;
            case CATALOG_ONLY -> Role.CATALOG;
          };
      if (role != expectedRole)
        throw new IllegalArgumentException("Contradictory family disposition");
      TargetOwner expectedOwner =
          switch (freshDisposition) {
            case ENTITY_VALUE_OWNERSHIP_DRIFT_BLOCKING -> TargetOwner.ENTITY_MANAGEMENT_SERVICE;
            case ACCOUNT_IDENTITY_CARRIER_ONLY -> TargetOwner.ACCOUNT_SERVICE;
            default -> TargetOwner.NONE;
          };
      if (targetOwner != expectedOwner) {
        throw new IllegalArgumentException("Contradictory Social target-owner classification");
      }
    }
  }

  /** Definition digests cover live PostgreSQL facts, not row counts or caller assertions. */
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

  public record Evidence(Request request, UUID snapshotId, SocialRuntimeFamilyCatalog catalog) {
    public Evidence {
      Objects.requireNonNull(request, "request");
      requireUuid(snapshotId);
      Objects.requireNonNull(catalog, "catalog");
    }

    public String digest() {
      return sha256(SocialRuntimeFamilyCatalogCodec.evidenceBytes(this));
    }
  }

  private final long catalogEpoch;
  private final List<Family> families;
  private final List<SchemaFact> schemaFacts;
  private final List<ContractGap> unimplementedContracts;
  private final ByteString exactBytes;

  private SocialRuntimeFamilyCatalog(
      long epoch,
      List<Family> families,
      List<SchemaFact> facts,
      List<ContractGap> gaps,
      byte[] bytes) {
    catalogEpoch = epoch;
    this.families = List.copyOf(families);
    schemaFacts = List.copyOf(facts);
    unimplementedContracts = List.copyOf(gaps);
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

  public List<ContractGap> unimplementedContracts() {
    return unimplementedContracts;
  }

  public byte[] exactBytes() {
    return exactBytes.toByteArray();
  }

  public String digest() {
    return sha256(exactBytes.toByteArray());
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SocialRuntimeFamilyCatalog catalog
        && exactBytes.equals(catalog.exactBytes);
  }

  @Override
  public int hashCode() {
    return exactBytes.hashCode();
  }

  /** Closed bounded decoding retains original PostgreSQL-sealed bytes without recanonicalizing. */
  public static SocialRuntimeFamilyCatalog fromStored(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_CATALOG_BYTES) {
      throw new IllegalArgumentException("Social catalog exceeds its complete-source bound");
    }
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      JsonNode root = JSON.readTree(text);
      fields(
          root,
          Set.of(
              "schema",
              "owner",
              "catalogEpoch",
              "families",
              "schemaFacts",
              "unimplementedContracts"));
      if (!SCHEMA.equals(text(root, "schema")) || !OWNER.equals(text(root, "owner"))) {
        throw new IllegalArgumentException("Unsupported Social catalog schema/owner");
      }
      String epochText = text(root, "catalogEpoch");
      if (!epochText.matches("[1-9][0-9]{0,18}")) {
        throw new IllegalArgumentException("Invalid Social catalog epoch");
      }
      long epoch = Long.parseLong(epochText);
      var families = new ArrayList<Family>();
      var facts = new ArrayList<SchemaFact>();
      var gaps = new ArrayList<ContractGap>();
      array(root.get("families"), MAX_FAMILIES);
      String previous = "";
      Set<String> familyIds = new HashSet<>();
      for (JsonNode node : root.get("families")) {
        fields(
            node,
            Set.of("familyId", "relation", "component", "role", "freshDisposition", "targetOwner"));
        Family family =
            new Family(
                text(node, "familyId"),
                text(node, "relation"),
                text(node, "component"),
                Role.valueOf(text(node, "role")),
                FreshDisposition.valueOf(text(node, "freshDisposition")),
                TargetOwner.valueOf(text(node, "targetOwner")));
        if (previous.compareTo(family.familyId()) >= 0 || !familyIds.add(family.familyId())) {
          throw new IllegalArgumentException("Unordered/duplicate Social family");
        }
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
        if (previous.compareTo(key) >= 0) {
          throw new IllegalArgumentException("Unordered/duplicate Social schema fact");
        }
        previous = key;
        if (fact.kind() == FactKind.TABLE) tables.add(fact.identity());
        if (fact.kind() == FactKind.COLUMN) columns.add(fact.identity());
        facts.add(fact);
      }
      Set<String> declaredTables = new HashSet<>();
      for (Family family : families) {
        if (!tables.contains(family.relation())) {
          throw new IllegalArgumentException("Missing Social family table fact");
        }
        if (family.component().isEmpty()) {
          declaredTables.add(family.relation());
        } else if (!columns.contains(family.familyId())) {
          throw new IllegalArgumentException("Missing Social family component fact");
        }
      }
      if (!tables.equals(declaredTables) || tables.isEmpty()) {
        throw new IllegalArgumentException("Every installed Social table needs a disposition");
      }
      array(root.get("unimplementedContracts"), ContractGap.values().length);
      previous = "";
      Set<ContractGap> uniqueGaps = EnumSet.noneOf(ContractGap.class);
      for (JsonNode node : root.get("unimplementedContracts")) {
        if (!node.isTextual())
          throw new IllegalArgumentException("Text Social contract gap required");
        ContractGap gap = ContractGap.valueOf(node.asText());
        if (previous.compareTo(gap.name()) >= 0 || !uniqueGaps.add(gap)) {
          throw new IllegalArgumentException("Unordered/duplicate Social contract gap");
        }
        previous = gap.name();
        gaps.add(gap);
      }
      if (!uniqueGaps.equals(EnumSet.allOf(ContractGap.class))) {
        throw new IllegalArgumentException(
            "Social catalog must retain every known contract blocker");
      }
      return new SocialRuntimeFamilyCatalog(epoch, families, facts, gaps, bytes);
    } catch (java.io.IOException | tools.jackson.core.JacksonException invalid) {
      throw new IllegalArgumentException("Invalid closed Social catalog", invalid);
    }
  }

  static void requireUuid(UUID value) {
    if (value == null || value.equals(new UUID(0, 0))) {
      throw new IllegalArgumentException("Non-nil UUID required");
    }
  }

  static void requireDigest(String value) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("SHA-256 required");
    }
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
    if (value == null || !value.matches("[a-z][a-z0-9_]{0,62}")) {
      throw new IllegalArgumentException("Schema identifier required");
    }
  }

  private static void fields(JsonNode node, Set<String> expected) {
    if (node == null || !node.isObject())
      throw new IllegalArgumentException("Closed object required");
    Set<String> actual = new HashSet<>();
    node.propertyNames().forEach(actual::add);
    if (!actual.equals(expected))
      throw new IllegalArgumentException("Unknown or missing Social field");
  }

  private static String text(JsonNode node, String key) {
    JsonNode value = node.get(key);
    if (value == null || !value.isTextual())
      throw new IllegalArgumentException("Text field required");
    return value.asText();
  }

  private static void array(JsonNode node, int maximum) {
    if (node == null || !node.isArray() || node.isEmpty() || node.size() > maximum) {
      throw new IllegalArgumentException("Complete bounded Social catalog array required");
    }
  }
}
