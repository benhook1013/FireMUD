package net.firedevops.firemud.common.social;

import static net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogValidation.array;
import static net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogValidation.fields;
import static net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogValidation.identifier;
import static net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogValidation.text;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogValidation;
import tools.jackson.databind.JsonNode;

/** Immutable exact Social-local schema classification, not a row inventory or FRESH manifest. */
public final class SocialRuntimeFamilyCatalog {
  public static final String OWNER = "social-groups-service";
  public static final String SCHEMA = "social-runtime-family-catalog/v1";
  public static final int MAX_CATALOG_BYTES = 2 * 1024 * 1024;
  public static final int MAX_FAMILIES = 512;
  public static final int MAX_FACTS = 16384;

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
    JsonNode root = RuntimeFamilyCatalogValidation.decode(bytes, MAX_CATALOG_BYTES);
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
      throw new IllegalArgumentException("Social catalog must retain every known contract blocker");
    }
    return new SocialRuntimeFamilyCatalog(epoch, families, facts, gaps, bytes);
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
