package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class WorldRuntimeInstallationArtifactTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final String IMAGE = "registry.example/world@" + DIGEST;
  private static final String FIXTURE =
      """
      {
        "schema":"firemud-runtime-installation-inventory/v1",
        "artifactId":"e8975b6d-88ca-4b50-8307-e0f8c8cccd53",
        "installationId":"56b80b22-977d-47bb-89db-6705d84294b1",
        "environment":"staging",
        "generation":"captured-generation",
        "clusterUid":"captured-cluster-uid",
        "namespaceUid":"captured-namespace-uid",
        "namespace":"firemud",
        "sourceCommit":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "renderedManifestDigest":"DIGEST",
        "declarationsDigest":"DIGEST",
        "executor":{"identity":"protected/executor/v1","versionDigest":"DIGEST"},
        "inputBindings":{
          "migrations":[],"configurations":[],"mountedResources":[],
          "secretContracts":[{"name":"identity","version":"captured-v3","digest":"DIGEST"}],
          "externalBindings":[]
        },
        "members":[
          {
            "workloadApiVersion":"v1","workloadKind":"Pod",
            "workloadNamespace":"firemud","workloadName":"world",
            "containerKind":"APPLICATION","containerName":"application","image":"IMAGE",
            "ownerId":"world-management-service","ownership":"RUNTIME_OWNER",
            "catalogSources":[{"sourceId":"world-sql","contract":"world/catalog/v1","epoch":"1","digest":"DIGEST"}],
            "families":[{
              "familyId":"world-state","catalogSourceId":"world-sql","replacementClass":"S3",
              "cleanupContract":{
                "operation":"world/cleanup/v1","requestIdentity":"world/request/v1",
                "acknowledgement":"world/ack/v1","retention":"world/retention/v1"
              }
            }]
          },
          {
            "workloadApiVersion":"v1","workloadKind":"Pod",
            "workloadNamespace":"firemud","workloadName":"world",
            "containerKind":"INIT","containerName":"foreign-sidecar","image":"IMAGE",
            "ownerId":"","ownership":"NO_INSTANCE_STATE","catalogSources":[],"families":[]
          }
        ]
      }
      """
          .replace("IMAGE", IMAGE)
          .replace("DIGEST", DIGEST);

  @Test
  void acceptsTheActualPythonProducerInteroperabilityVectorWithoutReserialization()
      throws Exception {
    java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
    while (root != null && !java.nio.file.Files.exists(root.resolve("settings.gradle.kts"))) {
      root = root.getParent();
    }
    if (root == null) throw new IllegalStateException("Repository fixture root unavailable");
    byte[] emitted =
        java.nio.file.Files.readAllBytes(
            root.resolve("dev-tools/deploy/fixtures/runtime-installation/candidate.json"));
    var artifact = WorldRuntimeInstallationArtifact.parse(emitted);
    artifact.requireResolved();
    assertThat(artifact.exactBytes()).isEqualTo(emitted);
    assertThat(artifact.namespace()).isEqualTo("firemud");
    assertThat(artifact.digest()).isEqualTo(WorldRuntimeFamilyCatalog.sha256(emitted));
  }

  @Test
  void retainsWholeExactArtifactIdentityAndCatalogEvidence() {
    byte[] bytes = bytes(FIXTURE);
    var artifact = WorldRuntimeInstallationArtifact.parse(bytes);

    artifact.requireResolved();
    assertThat(artifact.artifactId())
        .isEqualTo(UUID.fromString("e8975b6d-88ca-4b50-8307-e0f8c8cccd53"));
    assertThat(artifact.installationId())
        .isEqualTo(UUID.fromString("56b80b22-977d-47bb-89db-6705d84294b1"));
    assertThat(artifact.namespace()).isEqualTo("firemud");
    assertThat(artifact.exactBytes()).isEqualTo(bytes);
    assertThat(artifact.digest()).isEqualTo(WorldRuntimeFamilyCatalog.sha256(bytes));
    assertThat(new String(artifact.exactBytes(), StandardCharsets.UTF_8))
        .contains("world/catalog/v1", "world/cleanup/v1", "captured-v3", "foreign-sidecar");
  }

  @Test
  void sourceAndReturnedArraysCannotMutateRetainedEvidence() {
    byte[] original = bytes(FIXTURE);
    byte[] supplied = original.clone();
    var artifact = WorldRuntimeInstallationArtifact.parse(supplied);
    String digest = artifact.digest();
    supplied[0] = 0;
    byte[] returned = artifact.exactBytes();
    returned[1] = 0;

    assertThat(artifact.exactBytes()).isEqualTo(original);
    assertThat(artifact.digest()).isEqualTo(digest);
  }

  @Test
  void changedExactBytesAndInputDigestsChangeArtifactDigest() {
    var original = WorldRuntimeInstallationArtifact.parse(bytes(FIXTURE));
    var whitespace = WorldRuntimeInstallationArtifact.parse(bytes(FIXTURE + "\n"));
    var manifest =
        WorldRuntimeInstallationArtifact.parse(
            changed(root -> root.put("renderedManifestDigest", "sha256:" + "b".repeat(64))));
    var declarations =
        WorldRuntimeInstallationArtifact.parse(
            changed(root -> root.put("declarationsDigest", "sha256:" + "c".repeat(64))));

    assertThat(whitespace.artifactId()).isEqualTo(original.artifactId());
    assertThat(whitespace.digest()).isNotEqualTo(original.digest());
    assertThat(manifest.digest()).isNotEqualTo(original.digest());
    assertThat(declarations.digest()).isNotEqualTo(original.digest());
  }

  @Test
  void rejectsDuplicatesTrailingTokensInvalidUtf8AndNullRoots() {
    List<byte[]> invalid =
        List.of(
            bytes(FIXTURE.replace("\"schema\":", "\"schema\":\"duplicate\",\"schema\":")),
            bytes(FIXTURE.replace("\"epoch\":\"1\"", "\"epoch\":\"1\",\"epoch\":\"2\"")),
            bytes(FIXTURE + " {}"),
            bytes(FIXTURE + " null"),
            bytes("null"),
            bytes("[]"),
            bytes(""),
            new byte[] {(byte) 0xc3, (byte) 0x28},
            bytes(FIXTURE.replace("captured-generation", "\\uD800")));
    for (byte[] value : invalid) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> WorldRuntimeInstallationArtifact.parse(value));
    }
    assertThatIllegalArgumentException()
        .isThrownBy(() -> WorldRuntimeInstallationArtifact.parse(null));
  }

  @Test
  void rejectsUnknownMissingNullAndWrongTypedClosedFields() {
    List<Consumer<ObjectNode>> mutations =
        List.of(
            root -> root.put("authority", "installed"),
            root -> root.remove("declarationsDigest"),
            root -> root.putNull("artifactId"),
            root -> root.put("generation", 1),
            root -> object(root, "executor").put("credential", "unknown"),
            root -> object(root, "inputBindings").remove("mountedResources"),
            root -> object(root, "inputBindings").put("migrations", "missing"),
            root -> object(root, "inputBindings").putArray("unknown"),
            root -> owner(root).put("extra", true),
            root -> source(root).remove("contract"),
            root -> family(root).put("unknown", "field"),
            root -> object(family(root), "cleanupContract").remove("retention"),
            root -> object(family(root), "cleanupContract").putNull("acknowledgement"),
            root -> object(family(root), "cleanupContract").put("requestIdentity", ""));
    for (Consumer<ObjectNode> mutation : mutations) refuses(mutation);
  }

  @Test
  void enforcesFormatsEnumsAndExplicitOwnerEvidence() {
    List<Consumer<ObjectNode>> mutations =
        List.of(
            root -> root.put("schema", "firemud-runtime-installation-inventory/v2"),
            root -> root.put("artifactId", "00000000-0000-0000-0000-000000000000"),
            root -> root.put("installationId", "56B80B22-977D-47BB-89DB-6705D84294B1"),
            root -> root.put("environment", "unknown"),
            root -> root.put("sourceCommit", "main"),
            root -> root.put("namespace", "FireMUD"),
            root -> root.put("renderedManifestDigest", "sha256:" + "A".repeat(64)),
            root -> root.put("generation", "\u00a0captured"),
            root -> owner(root).put("workloadNamespace", "elsewhere"),
            root -> owner(root).put("workloadKind", "Unknown"),
            root -> owner(root).put("workloadApiVersion", "apps/v1"),
            root -> owner(root).put("containerKind", "SIDECAR"),
            root -> owner(root).put("image", "world:latest"),
            root -> owner(root).put("ownership", "UNKNOWN"),
            root -> owner(root).put("ownerId", ""),
            root -> owner(root).putArray("catalogSources"),
            root -> owner(root).put("ownership", "NO_INSTANCE_STATE"),
            root -> source(root).put("epoch", "01"),
            root -> source(root).put("epoch", "0"),
            root -> source(root).put("epoch", 1),
            root -> family(root).put("catalogSourceId", "missing"),
            root -> family(root).put("replacementClass", "S4"));
    for (Consumer<ObjectNode> mutation : mutations) refuses(mutation);
  }

  @Test
  void rejectsUnsortedMembersAndDuplicateCrossReferences() {
    refuses(
        root -> {
          owner(root).put("containerKind", "INIT");
          owner(root).put("containerName", "z-last");
        });
    refuses(root -> members(root).set(1, owner(root).deepCopy()));
    refuses(
        root -> {
          ArrayNode sources = (ArrayNode) owner(root).get("catalogSources");
          sources.add(source(root).deepCopy());
        });
    refuses(
        root -> {
          ArrayNode families = (ArrayNode) owner(root).get("families");
          families.add(family(root).deepCopy());
        });
    refuses(
        root -> {
          ObjectNode second = (ObjectNode) members(root).get(1);
          second.put("ownerId", "world-management-service");
          second.put("ownership", "RUNTIME_OWNER");
          second.set("catalogSources", owner(root).get("catalogSources").deepCopy());
          second.set("families", owner(root).get("families").deepCopy());
        });
    refuses(
        root -> {
          ArrayNode bindings = (ArrayNode) root.get("inputBindings").get("secretContracts");
          bindings.add(bindings.get(0).deepCopy());
        });
  }

  @Test
  void unresolvedAndMissingOwnerSetsRemainParseableButBlockQualification() {
    var unresolved =
        WorldRuntimeInstallationArtifact.parse(
            changed(root -> family(root).put("replacementClass", "UNRESOLVED")));
    assertThat(new String(unresolved.exactBytes(), StandardCharsets.UTF_8)).contains("UNRESOLVED");
    assertThatIllegalArgumentException().isThrownBy(unresolved::requireResolved);

    var noOwner =
        WorldRuntimeInstallationArtifact.parse(
            changed(
                root -> {
                  owner(root).put("ownership", "NO_INSTANCE_STATE");
                  owner(root).put("ownerId", "");
                  owner(root).putArray("catalogSources");
                  owner(root).putArray("families");
                }));
    assertThatIllegalArgumentException().isThrownBy(noOwner::requireResolved);
    var empty = WorldRuntimeInstallationArtifact.parse(changed(root -> root.putArray("members")));
    assertThatIllegalArgumentException().isThrownBy(empty::requireResolved);
  }

  @Test
  void enforcesArtifactFieldAndArrayCeilings() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                WorldRuntimeInstallationArtifact.parse(
                    new byte[WorldRuntimeInstallationArtifact.MAX_BYTES + 1]));
    refuses(root -> root.put("generation", "x".repeat(257)));
    refuses(root -> root.put("generation", "\u00e9".repeat(129)));
    refuses(root -> object(root, "executor").put("identity", "x".repeat(513)));
    refuses(root -> source(root).put("epoch", "1".repeat(20)));
    refuses(
        root -> {
          ArrayNode entries = members(root);
          ObjectNode first = owner(root).deepCopy();
          for (int index = 0; index < 1024; index++) entries.add(first.deepCopy());
        });
    refuses(
        root -> {
          ArrayNode sources = (ArrayNode) owner(root).get("catalogSources");
          for (int index = 0; index < 64; index++) sources.add(source(root).deepCopy());
        });
    refuses(
        root -> {
          ArrayNode families = (ArrayNode) owner(root).get("families");
          for (int index = 0; index < 256; index++) families.add(family(root).deepCopy());
        });
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                WorldRuntimeInstallationArtifact.parse(
                    bytes("[".repeat(100) + "0" + "]".repeat(100))));
  }

  private static void refuses(Consumer<ObjectNode> mutation) {
    byte[] value = changed(mutation);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> WorldRuntimeInstallationArtifact.parse(value));
  }

  private static byte[] changed(Consumer<ObjectNode> mutation) {
    ObjectNode root = (ObjectNode) JSON.readTree(FIXTURE);
    mutation.accept(root);
    return JSON.writeValueAsBytes(root);
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static ObjectNode object(ObjectNode node, String field) {
    return (ObjectNode) node.get(field);
  }

  private static ArrayNode members(ObjectNode root) {
    return (ArrayNode) root.get("members");
  }

  private static ObjectNode owner(ObjectNode root) {
    return (ObjectNode) members(root).get(0);
  }

  private static ObjectNode source(ObjectNode root) {
    return (ObjectNode) owner(root).get("catalogSources").get(0);
  }

  private static ObjectNode family(ObjectNode root) {
    return (ObjectNode) owner(root).get("families").get(0);
  }
}
