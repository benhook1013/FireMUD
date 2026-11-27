package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import org.junit.jupiter.api.Test;

/** Real nested codecs over explicitly synthetic source/application/release and lease fixtures. */
class WorldCanonicalPlayerAdmissionHoldEvidenceTest {
  private static final String ID = "11111111-1111-4111-8111-111111111111";
  private static final String ACCOUNT = "22222222-2222-4222-8222-222222222222";

  @Test
  void closedCanonicalRoundtripPreservesEveryOriginalNestedByteAndDigest() throws Exception {
    var original = hold();
    byte[] bytes = original.canonicalBytes();
    var decoded = WorldCanonicalPlayerAdmissionHoldEvidence.fromCanonical(bytes, original.sha256());
    assertThat(decoded).isEqualTo(original);
    assertThat(decoded.canonicalBytes()).containsExactly(bytes);
    assertThat(decoded.request().lease().canonicalJson())
        .isEqualTo(original.request().lease().canonicalJson());
    assertThat(decoded.request().lease().sha256()).isEqualTo(original.request().lease().sha256());
    assertThat(decoded.worldEvidence().canonicalBytes())
        .containsExactly(original.worldEvidence().canonicalBytes());
    assertThat(decoded.diagnosticExpiresAtMillis()).isEqualTo(original.diagnosticExpiresAtMillis());
    bytes[0] ^= 1;
    assertThat(original.canonicalBytes()).isEqualTo(decoded.canonicalBytes());
  }

  @Test
  void rejectsUnknownMissingDuplicateAndNoncanonicalOuterBytes() throws Exception {
    var original = hold();
    var root = JSON.readTree(original.canonicalBytes());
    var unknown = root.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) unknown).put("unknown", "value");
    assertRejected(canonical(unknown));
    var missing = root.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) missing).remove("schemaVersion");
    assertRejected(canonical(missing));
    var nestedUnknown = root.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) nestedUnknown.get("request")).put("unknown", "value");
    assertRejected(canonical(nestedUnknown));
    String text = new String(original.canonicalBytes(), StandardCharsets.UTF_8);
    assertRejected(
        text.replace("\"schemaVersion\":\"1\"", "\"schemaVersion\":\"1\",\"schemaVersion\":\"1\"")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected((" " + text).getBytes(StandardCharsets.UTF_8));
    assertRejected((text + "{}").getBytes(StandardCharsets.UTF_8));
    assertRejected(
        text.replace("\"schemaVersion\":\"1\"", "\"schemaVersion\":1")
            .getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void substitutedLeaseDigestOriginalScopeWorldEpochAndOpaqueIdentityFailClosed() throws Exception {
    var original = hold();
    var root = JSON.readTree(original.canonicalBytes());
    var leaseDigest = root.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) leaseDigest.get("request"))
        .put("accountLeaseSha256", "b".repeat(64));
    assertRejected(canonical(leaseDigest));
    var changedCarrier = new LinkedHashMap<>(original.request().lease().carrier());
    var changedScope = new LinkedHashMap<>(scope(changedCarrier));
    changedScope.put("worldSlug", "another-world");
    changedCarrier.put("bindingScope", changedScope);
    var changedLease = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changedCarrier);
    var changed = root.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) changed.get("request"))
        .put("accountLeaseJson", changedLease.canonicalJson());
    ((tools.jackson.databind.node.ObjectNode) changed.get("request"))
        .put("accountLeaseSha256", changedLease.sha256());
    assertRejected(canonical(changed));
    var epoch = root.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) epoch.get("request"))
        .put("expectedLifecycleEpoch", "4");
    assertRejected(canonical(epoch));
    var uuid = root.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) uuid)
        .put("holdId", "00000000-0000-1000-8000-000000000001");
    assertRejected(canonical(uuid));
    assertThatThrownBy(
            () ->
                WorldCanonicalPlayerAdmissionHoldEvidence.fromCanonical(
                    original.canonicalBytes(), "c".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exactDecimalStringsPreserveHighAndMaximumLongsAndRejectEveryOtherShape() throws Exception {
    var original = hold();
    for (long value : List.of(9_007_199_254_740_993L, Long.MAX_VALUE)) {
      var world = original.worldEvidence();
      var preciseWorld =
          new WorldCanonicalInstanceLifecycleEvidence(
              world.request(),
              world.launchBinding(),
              world.startLocation(),
              world.runtimeRoomInstanceId(),
              "ACTIVE",
              value,
              value,
              world.captureId(),
              world.graphSha256(),
              world.preparationInputDigest(),
              world.operationalRegionAssignments());
      var precise =
          new WorldCanonicalPlayerAdmissionHoldEvidence(
              original.holdId(),
              original.holdFence(),
              new WorldCanonicalPlayerAdmissionHoldEvidence.Request(
                  original.request().lease(), value, value),
              preciseWorld);
      assertThat(WorldCanonicalPlayerAdmissionHoldEvidence.parseCanonical(precise.canonicalBytes()))
          .isEqualTo(precise);
    }
    for (String invalid : List.of("01", "-1", "+1", "1.0", "1e0", " 1", "9223372036854775808")) {
      var changed = JSON.readTree(original.canonicalBytes());
      ((tools.jackson.databind.node.ObjectNode) changed.get("request"))
          .put("expectedRowVersion", invalid);
      assertRejected(canonical(changed));
    }
    var numeric = JSON.readTree(original.canonicalBytes());
    ((tools.jackson.databind.node.ObjectNode) numeric.get("request"))
        .put("expectedLifecycleEpoch", 3);
    assertRejected(canonical(numeric));
    var zeroEpoch = JSON.readTree(original.canonicalBytes());
    ((tools.jackson.databind.node.ObjectNode) zeroEpoch.get("request"))
        .put("expectedLifecycleEpoch", "0");
    assertRejected(canonical(zeroEpoch));
  }

  @Test
  void rejectsUtf8Base64ByteAndNestingBoundsWithoutMockedNestedDecoders() throws Exception {
    assertRejected(new byte[] {(byte) 0xc3, 0x28});
    assertRejected(new byte[WorldCanonicalPlayerAdmissionHoldEvidence.MAX_EVIDENCE_BYTES + 1]);
    assertRejected("{\"request\":{\"a\":{\"b\":{\"c\":{}}}}}".getBytes(StandardCharsets.UTF_8));
    var original = hold();
    var invalidBase64 = JSON.readTree(original.canonicalBytes());
    ((tools.jackson.databind.node.ObjectNode) invalidBase64)
        .put("worldLifecycleEvidenceBase64", "%%%");
    assertRejected(canonical(invalidBase64));
    var oversizedLifecycle = JSON.readTree(original.canonicalBytes());
    ((tools.jackson.databind.node.ObjectNode) oversizedLifecycle)
        .put(
            "worldLifecycleEvidenceBase64",
            Base64.getEncoder()
                .encodeToString(
                    new byte[WorldCanonicalPlayerAdmissionHoldEvidence.MAX_LIFECYCLE_BYTES + 1]));
    assertRejected(canonical(oversizedLifecycle));
    var corruptedWorld = JSON.readTree(original.canonicalBytes());
    ((tools.jackson.databind.node.ObjectNode) corruptedWorld)
        .put(
            "worldLifecycleEvidenceBase64",
            Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)));
    assertRejected(canonical(corruptedWorld));
  }

  private static void assertRejected(byte[] bytes) throws Exception {
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    assertThatThrownBy(() -> WorldCanonicalPlayerAdmissionHoldEvidence.fromCanonical(bytes, digest))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static WorldCanonicalPlayerAdmissionHoldEvidence hold() throws Exception {
    var world = AuthoringFixtures.lifecycleEvidence("ACTIVE", 3L);
    var carrier = carrier();
    var boundScope = new LinkedHashMap<>(scope(carrier));
    boundScope.put("worldSlug", world.request().worldSlug());
    boundScope.put(
        "playableStateNamespaceId", world.request().playableStateNamespaceId().toString());
    carrier.put("bindingScope", boundScope);
    var lease = AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
    return new WorldCanonicalPlayerAdmissionHoldEvidence(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new WorldCanonicalPlayerAdmissionHoldEvidence.Request(
            lease, world.lifecycleEpoch(), world.rowVersion()),
        world);
  }

  private static final tools.jackson.databind.ObjectMapper JSON =
      new tools.jackson.databind.ObjectMapper();

  private static byte[] canonical(tools.jackson.databind.JsonNode value) throws Exception {
    return net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
        JSON.writeValueAsString(value));
  }

  static Map<String, Object> carrier() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    value.put("schemaVersion", "1");
    value.put("mode", "PUBLIC_PRODUCTION");
    value.put("targetNamespace", "test");
    value.put("callerWorkload", "spiffe://firemud/ns/test/sa/game-session-service");
    value.put("requestId", ID);
    value.put("leaseId", ACCOUNT);
    value.put("leaseFence", "1");
    value.put("leaseKind", "NEW_BINDING");
    Map<String, Object> scope = new LinkedHashMap<>();
    for (String key :
        List.of(
            "tenantId",
            "realmId",
            "playableStateNamespaceId",
            "gameInstanceId",
            "characterId",
            "sessionId",
            "regionId")) scope.put(key, ID);
    scope.put("accountId", ACCOUNT);
    scope.put("worldSlug", "world");
    scope.put("realmSlug", "realm");
    scope.put("playableStateScope", "SHARED");
    for (String key :
        List.of("bindingGeneration", "catalogRevision", "pointerVersion", "regionEpoch"))
      scope.put(key, "9007199254740993");
    value.put("bindingScope", scope);
    value.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration",
            "1",
            "accountAuthorityGeneration",
            "1",
            "tenantAuthorityGeneration",
            Map.of(ID, "1"),
            "membershipAuthorityGeneration",
            Map.of(ID, "1"),
            "privateRealmGrantVersions",
            List.of()));
    value.put("issuanceFence", "1");
    value.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState",
            "ACTIVE",
            "membershipVersion",
            Map.of(ID, "1"),
            "membershipAuthorityGeneration",
            "1"));
    value.put(
        "outboxCheckpoints",
        List.of(
            checkpoint("account/" + ACCOUNT, "0"),
                checkpoint("issuer/firemud-account-service", "0"),
            checkpoint("membership/" + ACCOUNT + "/" + ID, "1"), checkpoint("tenant/" + ID, "1")));
    long now = System.currentTimeMillis();
    value.put(
        "tokenIdentityEvidence",
        Map.ofEntries(
            Map.entry("accountId", ACCOUNT),
            Map.entry("operationId", ID),
            Map.entry("issuanceRequestId", ID),
            Map.entry("tokenJti", ID),
            Map.entry("tokenSHA256", "a".repeat(64)),
            Map.entry("tokenGeneration", "1"),
            Map.entry("issuanceFence", "1"),
            Map.entry("tokenIdentityFence", "1"),
            Map.entry("tokenProfile", "game-session-account-delegation"),
            Map.entry("issuedAt", Long.toString(now / 1000)),
            Map.entry("notBefore", Long.toString(now / 1000)),
            Map.entry("expiresAt", Long.toString(now / 1000 + 300))));
    value.put("evaluatedAt", Long.toString(now));
    value.put("expiresAt", Long.toString(now + 15000));
    return value;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> scope(Map<String, Object> carrier) {
    return (Map<String, Object>) carrier.get("bindingScope");
  }

  private static Map<String, Object> checkpoint(String suffix, String sequence) {
    return Map.of(
        "outboxStreamKey", "account:auth-authority:v1:" + suffix, "outboxSequence", sequence);
  }
}
