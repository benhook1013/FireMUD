package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed exact candidate evidence; parsing establishes no installation or registration authority.
 */
public final class WorldRuntimeInstallationArtifact {
  public static final int MAX_BYTES = 4 * 1024 * 1024;
  private static final String SCHEMA = "firemud-runtime-installation-inventory/v1";
  private static final String DIGEST = "sha256:[0-9a-f]{64}";
  private static final String DNS_LABEL = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?";
  private static final String DNS_NAME = "[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?";
  private static final Set<String> ENVIRONMENTS =
      Set.of(
          "local-dev",
          "pr-preview",
          "dev-demo-cluster",
          "hobby-self-hosted",
          "staging",
          "production");
  private static final Set<String> BINDINGS =
      Set.of(
          "migrations",
          "configurations",
          "mountedResources",
          "secretContracts",
          "externalBindings");
  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "schema",
          "artifactId",
          "installationId",
          "environment",
          "generation",
          "clusterUid",
          "namespaceUid",
          "namespace",
          "sourceCommit",
          "renderedManifestDigest",
          "declarationsDigest",
          "executor",
          "inputBindings",
          "members");
  private static final Set<String> MEMBER_FIELDS =
      Set.of(
          "workloadApiVersion",
          "workloadKind",
          "workloadNamespace",
          "workloadName",
          "containerKind",
          "containerName",
          "image",
          "ownerId",
          "ownership",
          "catalogSources",
          "families");
  private static final List<String> IDENTITY_FIELDS =
      List.of(
          "workloadApiVersion",
          "workloadKind",
          "workloadNamespace",
          "workloadName",
          "containerKind",
          "containerName");
  private static final Set<List<String>> WORKLOADS =
      Set.of(
          List.of("v1", "Pod"),
          List.of("apps/v1", "Deployment"),
          List.of("apps/v1", "StatefulSet"),
          List.of("apps/v1", "DaemonSet"),
          List.of("apps/v1", "ReplicaSet"),
          List.of("batch/v1", "Job"),
          List.of("batch/v1", "CronJob"));
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(64)
                          .maxStringLength(MAX_BYTES)
                          .maxNumberLength(64)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final ByteString exactBytes;
  private final UUID artifactId;
  private final UUID installationId;
  private final String namespace;
  private final boolean resolved;

  private WorldRuntimeInstallationArtifact(
      ByteString bytes, UUID artifactId, UUID installationId, String namespace, boolean resolved) {
    exactBytes = bytes;
    this.artifactId = artifactId;
    this.installationId = installationId;
    this.namespace = namespace;
    this.resolved = resolved;
  }

  public byte[] exactBytes() {
    return exactBytes.toByteArray();
  }

  public String digest() {
    return WorldRuntimeFamilyCatalog.sha256(exactBytes.toByteArray());
  }

  public UUID artifactId() {
    return artifactId;
  }

  public UUID installationId() {
    return installationId;
  }

  public String namespace() {
    return namespace;
  }

  /** Classification prerequisite only; this does not qualify installation, FRESH or admission. */
  public void requireResolved() {
    require(resolved, "Complete runtime-owner set with resolved family classifications required");
  }

  /**
   * Validate the complete closed artifact and retain its original bytes without reserialization.
   */
  public static WorldRuntimeInstallationArtifact parse(byte[] bytes) {
    require(
        bytes != null && bytes.length > 0 && bytes.length <= MAX_BYTES,
        "Artifact byte bound exceeded");
    ByteString retained = ByteString.copyFrom(bytes);
    try {
      String decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(retained.toByteArray()))
              .toString();
      JsonNode root = JSON.readTree(decoded);
      boundedTree(root, 0, new int[] {200000});
      fields(root, ROOT_FIELDS);
      require(SCHEMA.equals(text(root, "schema", 256, false)), "Unsupported installation schema");
      UUID artifactId = uuid(root, "artifactId");
      UUID installationId = uuid(root, "installationId");
      require(ENVIRONMENTS.contains(text(root, "environment", 256, false)), "Invalid environment");
      for (String field : List.of("generation", "clusterUid", "namespaceUid")) {
        text(root, field, 256, false);
      }
      String namespace = matched(root, "namespace", DNS_LABEL, 63);
      matched(root, "sourceCommit", "[0-9a-f]{40}", 40);
      matched(root, "renderedManifestDigest", DIGEST, 256);
      matched(root, "declarationsDigest", DIGEST, 256);
      JsonNode executor = root.get("executor");
      fields(executor, Set.of("identity", "versionDigest"));
      text(executor, "identity", 512, false);
      matched(executor, "versionDigest", DIGEST, 256);
      bindings(root.get("inputBindings"));
      JsonNode members = root.get("members");
      array(members, 1024);
      List<String> previous = null;
      Set<List<String>> ownerFamilies = new HashSet<>();
      boolean hasRuntimeOwner = false;
      boolean allResolved = true;
      for (JsonNode member : members) {
        fields(member, MEMBER_FIELDS);
        require(
            WORKLOADS.contains(
                List.of(
                    text(member, "workloadApiVersion", 256, false),
                    text(member, "workloadKind", 256, false))),
            "Unsupported workload");
        require(
            namespace.equals(text(member, "workloadNamespace", 256, false)), "Namespace mismatch");
        matched(member, "workloadName", DNS_NAME, 253);
        matched(member, "containerName", DNS_LABEL, 63);
        require(
            Set.of("APPLICATION", "INIT").contains(text(member, "containerKind", 256, false)),
            "Unsupported container kind");
        matched(member, "image", "[a-z0-9][a-z0-9./:_-]*@sha256:[0-9a-f]{64}", 1024);
        List<String> current = new ArrayList<>();
        for (String field : IDENTITY_FIELDS) current.add(text(member, field, 256, false));
        require(
            previous == null || compare(previous, current) < 0,
            "Unsorted or duplicate member identity");
        previous = current;
        String ownership = text(member, "ownership", 256, false);
        require(
            Set.of("RUNTIME_OWNER", "NO_INSTANCE_STATE").contains(ownership), "Invalid ownership");
        boolean runtimeOwner = ownership.equals("RUNTIME_OWNER");
        String ownerId = text(member, "ownerId", 256, !runtimeOwner);
        JsonNode sources = member.get("catalogSources");
        JsonNode families = member.get("families");
        array(sources, 64);
        array(families, 256);
        if (runtimeOwner) {
          require(!sources.isEmpty(), "Runtime owner requires catalog evidence");
          hasRuntimeOwner = true;
        } else {
          require(
              sources.isEmpty() && families.isEmpty(), "NO_INSTANCE_STATE has ownership evidence");
        }
        Set<String> sourceIds = sources(sources);
        for (JsonNode family : families) {
          fields(
              family, Set.of("familyId", "catalogSourceId", "replacementClass", "cleanupContract"));
          String familyId = text(family, "familyId", 256, false);
          require(ownerFamilies.add(List.of(ownerId, familyId)), "Duplicate owner/family identity");
          require(
              sourceIds.contains(text(family, "catalogSourceId", 256, false)),
              "Missing member-local catalog source");
          String replacement = text(family, "replacementClass", 256, false);
          require(
              Set.of("S1", "S2", "S3", "UNRESOLVED").contains(replacement),
              "Invalid replacement class");
          allResolved &= !replacement.equals("UNRESOLVED");
          JsonNode cleanup = family.get("cleanupContract");
          Set<String> cleanupFields =
              Set.of("operation", "requestIdentity", "acknowledgement", "retention");
          fields(cleanup, cleanupFields);
          for (String field : cleanupFields) text(cleanup, field, 512, false);
        }
      }
      return new WorldRuntimeInstallationArtifact(
          retained, artifactId, installationId, namespace, hasRuntimeOwner && allResolved);
    } catch (java.io.IOException | tools.jackson.core.JacksonException invalid) {
      throw new IllegalArgumentException("Invalid closed runtime installation artifact", invalid);
    }
  }

  private static void bindings(JsonNode node) {
    fields(node, BINDINGS);
    for (String category : BINDINGS) {
      JsonNode entries = node.get(category);
      array(entries, 256);
      Set<String> names = new HashSet<>();
      for (JsonNode entry : entries) {
        fields(entry, Set.of("name", "version", "digest"));
        require(names.add(text(entry, "name", 256, false)), "Duplicate input binding");
        text(entry, "version", 512, false);
        matched(entry, "digest", DIGEST, 256);
      }
    }
  }

  private static Set<String> sources(JsonNode sources) {
    Set<String> ids = new HashSet<>();
    for (JsonNode source : sources) {
      fields(source, Set.of("sourceId", "contract", "epoch", "digest"));
      require(ids.add(text(source, "sourceId", 256, false)), "Duplicate member catalog source");
      text(source, "contract", 512, false);
      matched(source, "epoch", "[1-9][0-9]{0,18}", 19);
      matched(source, "digest", DIGEST, 256);
    }
    return ids;
  }

  private static UUID uuid(JsonNode root, String field) {
    String value = text(root, field, 36, false);
    UUID parsed = UUID.fromString(value);
    require(
        parsed.toString().equals(value) && !parsed.equals(new UUID(0, 0)),
        "Canonical non-nil UUID required");
    return parsed;
  }

  private static int compare(List<String> first, List<String> second) {
    for (int i = 0; i < first.size(); i++) {
      int compared = first.get(i).compareTo(second.get(i));
      if (compared != 0) return compared;
    }
    return 0;
  }

  private static void fields(JsonNode node, Set<String> expected) {
    require(node != null && node.isObject(), "Closed object required");
    Set<String> actual = new HashSet<>();
    node.propertyNames().forEach(actual::add);
    require(actual.equals(expected), "Unknown or missing installation field");
  }

  private static String text(JsonNode node, String field, int maximum, boolean empty) {
    JsonNode value = node.get(field);
    require(value != null && value.isTextual(), "Text field required: " + field);
    String result = value.asText();
    require(empty || !result.isEmpty(), "Empty installation field: " + field);
    require(
        result.isEmpty()
            || (!whitespace(result.codePointAt(0))
                && !whitespace(result.codePointBefore(result.length()))),
        "Whitespace around installation field: " + field);
    for (int i = 0; i < result.length(); i++) {
      char character = result.charAt(i);
      require(character >= 32 && character != 127, "Control character in installation field");
      if (Character.isHighSurrogate(character)) {
        require(
            i + 1 < result.length() && Character.isLowSurrogate(result.charAt(i + 1)),
            "Invalid Unicode scalar");
        i++;
      } else {
        require(!Character.isLowSurrogate(character), "Invalid Unicode scalar");
      }
    }
    require(
        result.getBytes(StandardCharsets.UTF_8).length <= maximum,
        "Installation field byte bound exceeded");
    return result;
  }

  private static boolean whitespace(int value) {
    return Character.isWhitespace(value) || Character.isSpaceChar(value) || value == 0x85;
  }

  private static String matched(JsonNode node, String field, String pattern, int maximum) {
    String value = text(node, field, maximum, false);
    require(value.matches(pattern), "Invalid installation field format: " + field);
    return value;
  }

  private static void array(JsonNode node, int maximum) {
    require(
        node != null && node.isArray() && node.size() <= maximum,
        "Bounded installation array required");
  }

  private static void boundedTree(JsonNode node, int depth, int[] remaining) {
    require(
        node != null && depth <= 64 && --remaining[0] >= 0,
        "Installation structural bound exceeded");
    if (node.isObject() || node.isArray()) {
      for (JsonNode child : node) boundedTree(child, depth + 1, remaining);
    }
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new IllegalArgumentException(message);
  }
}
