package unit.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantSnapshot;
import org.junit.jupiter.api.Test;

class GameSessionRetainedTenantSnapshotTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String NAMESPACE = "retained-snapshot-test";
  private static final String TENANT_ID = "7";
  private static final String SNAPSHOT_DIGEST_DOMAIN =
      "game-session/retained-tenant-identity-snapshot/v2";
  private static final String SNAPSHOT_DIGEST =
      "sha256:7aa718259dd0dbf7395f62691013c3fdd2fa0f9d21e9280090ae8bc8ddb7c757";
  private static final String CHANGED_SNAPSHOT_DIGEST =
      "sha256:ef6e4dd638dfd2892fe219fc84489f7c3a97a312c0a19831878e723627bebb94";
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
  void retainsRawAndCanonicalOwnerSlotsIndependentlyAndBindsBothToTheDigest() {
    ObjectNode projection = envelopeWithInstances("runtime");
    ArrayNode instances = projection.withArray("instances");
    ObjectNode mixedOwner = (ObjectNode) instances.get(0);
    mixedOwner.put("owner_account_uuid", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    ObjectNode canonicalOnlyOwner = (ObjectNode) instances.get(1);
    canonicalOnlyOwner.putNull("owner_account_id");
    canonicalOnlyOwner.put("owner_account_uuid", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    instances.add(instance("20", "legacy-owner", "20"));

    GameSessionRetainedTenantSnapshot snapshot = snapshot(projection);
    JsonNode rows;
    try {
      rows = JSON.readTree(snapshot.canonicalJson()).get("instances");
    } catch (IOException exception) {
      throw new AssertionError("Unable to read canonical snapshot test vector", exception);
    }
    assertThat(rows.get(0).get("owner_account_id").textValue()).isEqualTo("9");
    assertThat(rows.get(0).get("owner_account_uuid").textValue())
        .isEqualTo("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    assertThat(rows.get(1).get("owner_account_id").isNull()).isTrue();
    assertThat(rows.get(1).get("owner_account_uuid").textValue())
        .isEqualTo("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    assertThat(rows.get(2).get("owner_account_id").textValue()).isEqualTo("9");
    assertThat(rows.get(2).get("owner_account_uuid").isNull()).isTrue();

    ObjectNode changedOwner = projection.deepCopy();
    ((ObjectNode) changedOwner.withArray("instances").get(0))
        .put("owner_account_uuid", "ffffffff-ffff-4fff-8fff-ffffffffffff");
    GameSessionRetainedTenantSnapshot changedSnapshot = snapshot(changedOwner);
    assertThat(changedSnapshot.evidenceDigest()).isNotEqualTo(snapshot.evidenceDigest());
  }

  @Test
  void rejectsMissingMalformedNilAndLegacyVersionOwnerEvidence() {
    ObjectNode missingSlot = envelopeWithInstances("runtime");
    ((ObjectNode) missingSlot.withArray("instances").get(0)).remove("owner_account_uuid");
    assertThatThrownBy(() -> revalidate(missingSlot))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing or undeclared fields");

    ObjectNode bothMissing = envelopeWithInstances("runtime");
    ObjectNode ownerless = (ObjectNode) bothMissing.withArray("instances").get(0);
    ownerless.putNull("owner_account_id");
    assertThatThrownBy(() -> revalidate(bothMissing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("raw or canonical owner account identity");

    ObjectNode malformedUuid = envelopeWithInstances("runtime");
    ((ObjectNode) malformedUuid.withArray("instances").get(0))
        .put("owner_account_uuid", "EEEEEEEE-EEEE-4EEE-8EEE-EEEEEEEEEEEE");
    assertThatThrownBy(() -> revalidate(malformedUuid))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lowercase canonical UUID");

    ObjectNode nilUuid = envelopeWithInstances("runtime");
    ((ObjectNode) nilUuid.withArray("instances").get(0))
        .put("owner_account_uuid", "00000000-0000-0000-0000-000000000000");
    assertThatThrownBy(() -> revalidate(nilUuid))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nil UUID");

    ObjectNode malformedRawId = envelopeWithInstances("runtime");
    ObjectNode rawIdRow = (ObjectNode) malformedRawId.withArray("instances").get(0);
    rawIdRow.put("owner_account_id", "09");
    rawIdRow.put("owner_account_uuid", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    assertThatThrownBy(() -> revalidate(malformedRawId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical decimal");

    ObjectNode oldSchema = envelopeWithInstances("runtime");
    oldSchema.put("schemaVersion", 1);
    for (JsonNode row : oldSchema.withArray("instances")) {
      ((ObjectNode) row).remove("owner_account_uuid");
    }
    assertThatThrownBy(() -> revalidate(oldSchema))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schemaVersion must be integer 2");
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
    envelope.put("schemaVersion", 2);
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
    instance.putNull("owner_account_uuid");
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

  private static GameSessionRetainedTenantSnapshot snapshot(ObjectNode projection) {
    String canonicalJson = canonicalJson(projection);
    return GameSessionRetainedTenantSnapshot.fromCanonicalJson(
        NAMESPACE, TENANT_ID, canonicalJson, expectedDigest(canonicalJson));
  }

  private static String expectedDigest(String canonicalJson) {
    try {
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      updateFrame(hash, SNAPSHOT_DIGEST_DOMAIN);
      updateFrame(hash, canonicalJson);
      return "sha256:" + HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError("SHA-256 unavailable for snapshot test vector", exception);
    }
  }

  private static void updateFrame(MessageDigest hash, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    hash.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    hash.update((byte) ':');
    hash.update(bytes);
  }
}
