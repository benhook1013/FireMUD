package unit.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantSnapshot;
import org.junit.jupiter.api.Test;

class GameSessionRetainedTenantSnapshotTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String NAMESPACE = "retained-snapshot-test";
  private static final String TENANT_ID = "7";
  private static final String SNAPSHOT_DIGEST =
      "sha256:66c92bb8e82371c03d24d9cfaa14c7e7c033b8a4a53d081bc05214cf501e22a1";
  private static final String CHANGED_SNAPSHOT_DIGEST =
      "sha256:5652fc676448cc7dc8e45453f2577915d6ded0a0f1b00bbf1ee84b59673b27e6";
  private static final String IRRELEVANT_DIGEST = "sha256:" + "0".repeat(64);

  @Test
  void emitsCanonicalOrderedUnicodeAndLargeCounterVectorAndBindsChangedFields() {
    String canonicalJson = snapshotJson("Café 🐉");
    GameSessionRetainedTenantSnapshot snapshot =
        GameSessionRetainedTenantSnapshot.fromCanonicalJson(
            NAMESPACE, TENANT_ID, canonicalJson, SNAPSHOT_DIGEST);

    assertThat(snapshot.canonicalJson())
        .startsWith("{\"backfillIssues\":[],\"instances\":[")
        .contains("\"runtime_version\":\"Café 🐉\"")
        .contains("\"row_version\":\"9223372036854775807\"");
    assertThat(snapshot.canonicalJson().indexOf("\"id\":\"2\""))
        .isLessThan(snapshot.canonicalJson().indexOf("\"id\":\"10\""));
    assertThat(snapshot.evidenceDigest()).isEqualTo(SNAPSHOT_DIGEST);
    assertThat(
            GameSessionRetainedTenantSnapshot.fromCanonicalJson(
                NAMESPACE, TENANT_ID, snapshot.canonicalJson(), snapshot.evidenceDigest()))
        .isEqualTo(snapshot);

    GameSessionRetainedTenantSnapshot changed =
        GameSessionRetainedTenantSnapshot.fromCanonicalJson(
            NAMESPACE, TENANT_ID, snapshotJson("Café 🐲"), CHANGED_SNAPSHOT_DIGEST);
    assertThat(changed.evidenceDigest()).isNotEqualTo(snapshot.evidenceDigest());
    assertThatThrownBy(
            () ->
                GameSessionRetainedTenantSnapshot.fromCanonicalJson(
                    NAMESPACE, TENANT_ID, snapshot.canonicalJson(), IRRELEVANT_DIGEST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest");
  }

  @Test
  void rejectsEmptyUnknownMalformedAndDuplicateMemberEvidence() {
    ObjectNode empty = envelope();
    assertThatThrownBy(() -> revalidate(empty))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no retained tenant evidence");

    ObjectNode unknown = envelopeWithInstances("Café 🐉");
    unknown.put("unexpected", "evidence");
    assertThatThrownBy(() -> revalidate(unknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("undeclared");

    ObjectNode malformedNumeric = envelopeWithInstances("Café 🐉");
    ((ObjectNode) malformedNumeric.withArray("instances").get(0)).put("id", "01");
    assertThatThrownBy(() -> revalidate(malformedNumeric))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical decimal");

    ObjectNode malformedUuid = envelopeWithInstances("Café 🐉");
    ObjectNode pointer = pointer("2", "not-a-uuid");
    malformedUuid.withArray("pointers").add(pointer);
    assertThatThrownBy(() -> revalidate(malformedUuid))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lowercase canonical UUID");

    ObjectNode missingInstance = envelopeWithInstances("Café 🐉");
    missingInstance
        .withArray("pointers")
        .add(pointer("999", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"));
    assertThatThrownBy(() -> revalidate(missingInstance))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no retained tenant instance");

    ObjectNode unresolvedBackfill = envelopeWithInstances("Café 🐉");
    unresolvedBackfill.withArray("backfillIssues").add(JSON.createObjectNode());
    assertThatThrownBy(() -> revalidate(unresolvedBackfill))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("backfillIssues must be empty");

    String valid = snapshotJson("Café 🐉");
    String duplicateMemberJson =
        valid.replace(
            "\"targetNamespace\":\"" + NAMESPACE + "\"",
            "\"targetNamespace\":\"" + NAMESPACE + "\",\"targetNamespace\":\"" + NAMESPACE + "\"");
    assertThatThrownBy(
            () ->
                GameSessionRetainedTenantSnapshot.fromCanonicalJson(
                    NAMESPACE, TENANT_ID, duplicateMemberJson, SNAPSHOT_DIGEST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed");
  }

  @Test
  void rejectsNonCanonicalNamespaceAndLegacyTenantKeys() {
    String valid = snapshotJson("runtime");
    assertThatThrownBy(
            () ->
                GameSessionRetainedTenantSnapshot.fromCanonicalJson(
                    "Invalid-Namespace", TENANT_ID, valid, IRRELEVANT_DIGEST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical DNS label");
    assertThatThrownBy(
            () ->
                GameSessionRetainedTenantSnapshot.fromCanonicalJson(
                    NAMESPACE, "07", valid, IRRELEVANT_DIGEST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical decimal");
    assertThatThrownBy(
            () ->
                GameSessionRetainedTenantSnapshot.fromCanonicalJson(
                    NAMESPACE, "9223372036854775808", valid, IRRELEVANT_DIGEST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive BIGINT");
  }

  private static GameSessionRetainedTenantSnapshot revalidate(ObjectNode projection) {
    return GameSessionRetainedTenantSnapshot.fromCanonicalJson(
        NAMESPACE, TENANT_ID, canonicalJson(projection), IRRELEVANT_DIGEST);
  }

  private static String snapshotJson(String runtimeVersion) {
    return canonicalJson(envelopeWithInstances(runtimeVersion));
  }

  private static ObjectNode envelopeWithInstances(String runtimeVersion) {
    ObjectNode envelope = envelope();
    ArrayNode instances = JSON.createArrayNode();
    instances.add(instance("2", "old-runtime", "0"));
    instances.add(instance("10", runtimeVersion, "9223372036854775807"));
    envelope.set("instances", instances);
    return envelope;
  }

  private static ObjectNode envelope() {
    ObjectNode envelope = JSON.createObjectNode();
    envelope.put("targetNamespace", NAMESPACE);
    envelope.put("schemaVersion", 1);
    envelope.put("legacyGameSessionTenantId", TENANT_ID);
    envelope.set("instances", JSON.createArrayNode());
    envelope.set("pointers", JSON.createArrayNode());
    envelope.set("sharedNamespaces", JSON.createArrayNode());
    envelope.set("backfillIssues", JSON.createArrayNode());
    envelope.set("pointerEvents", JSON.createArrayNode());
    envelope.set("preparedUpgrades", JSON.createArrayNode());
    return envelope;
  }

  private static ObjectNode instance(String id, String runtimeVersion, String rowVersion) {
    ObjectNode instance = JSON.createObjectNode();
    instance.put("id", id);
    instance.put("tenant_id", TENANT_ID);
    instance.put("runtime_version", runtimeVersion);
    instance.putNull("script_patch_version");
    instance.put("owner_account_id", "9");
    instance.put("status", "STOPPED");
    instance.put("row_version", rowVersion);
    instance.putNull("game_template_id");
    instance.putNull("launch_descriptor_id");
    instance.putNull("version_id");
    instance.putNull("release_bundle_id");
    instance.putNull("version_state_epoch");
    instance.putNull("generation_config_revision");
    instance.putNull("remap_set_id");
    instance.putNull("script_patch_pinned_control_plane_request_id");
    instance.putNull("script_pin_epoch");
    return instance;
  }

  private static ObjectNode pointer(String instanceId, String realmId) {
    ObjectNode pointer = JSON.createObjectNode();
    pointer.put("id", "1");
    pointer.put("tenant_id", TENANT_ID);
    pointer.put("game_instance_id", instanceId);
    pointer.put("world_slug", "north-star");
    pointer.put("realm_slug", "one-realm");
    pointer.put("pointer_version", "1");
    pointer.put("catalog_revision", "1");
    pointer.put("visible", true);
    pointer.put("requires_character_selection", false);
    pointer.put("state_scope", "ISOLATED");
    pointer.put("character_creation_policy", "DISABLED");
    pointer.put("public_production_realm", false);
    pointer.put("realm_id", realmId);
    pointer.put("playable_state_namespace_id", "11111111-2222-4333-8444-555555555555");
    return pointer;
  }

  private static String canonicalJson(ObjectNode projection) {
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(projection)),
          StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new AssertionError("Unable to build canonical snapshot test vector", exception);
    }
  }
}
