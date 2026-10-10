package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import net.firedevops.firemud.common.publication.RealmEntryPolicy.EntryPolicy;
import net.firedevops.firemud.common.publication.RealmEntryPolicy.StateScope;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RealmEntryPolicyTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String DESCRIPTOR_ID = "11111111-1111-4111-8111-111111111111";
  private static final String REVISION_ID = "22222222-2222-4222-8222-222222222222";

  @Test
  void preservesV1CanonicalBytesAndPreseededPolicyResult() {
    String input = policy(1, "PRESEEDED_ONLY", null);
    var parsed = RealmEntryPolicy.parse(input, JSON);
    String expected =
        "{\"entryPolicy\":\"PRESEEDED_ONLY\",\"publicProduction\":true,"
            + "\"realmDisplayName\":\"Realm\",\"realmSlug\":\"realm\","
            + "\"schemaVersion\":1,\"stateScope\":\"SHARED\",\"visible\":true,"
            + "\"worldDisplayName\":\"World\",\"worldSlug\":\"world\"}";

    assertThat(parsed.schemaVersion()).isEqualTo(RealmEntryPolicy.SCHEMA_VERSION);
    assertThat(parsed.entryPolicy()).isEqualTo(EntryPolicy.PRESEEDED_ONLY);
    assertThat(parsed.creationDescriptor()).isNull();
    assertThat(parsed.canonicalJson()).isEqualTo(expected);
    assertThat(parsed.canonicalJson().getBytes(StandardCharsets.UTF_8))
        .containsExactly(expected.getBytes(StandardCharsets.UTF_8));
    assertThat(RealmEntryPolicy.parseCanonical(expected, JSON)).isEqualTo(parsed);
  }

  @Test
  void parsesV2PlayerCreatedPolicyAndDescriptorRoundTrip() {
    var parsed = RealmEntryPolicy.parse(policy(2, "PLAYER_CREATED", descriptor()), JSON);
    var descriptor = parsed.creationDescriptor();

    assertThat(parsed.schemaVersion()).isEqualTo(RealmEntryPolicy.PLAYER_CREATED_SCHEMA_VERSION);
    assertThat(parsed.entryPolicy()).isEqualTo(EntryPolicy.PLAYER_CREATED);
    assertThat(descriptor).isNotNull();
    assertThat(descriptor.schemaVersion()).isEqualTo(PlayerCreationDescriptor.SCHEMA_VERSION);
    assertThat(descriptor.descriptorId()).isEqualTo(DESCRIPTOR_ID);
    assertThat(descriptor.descriptorRevisionId()).isEqualTo(REVISION_ID);
    assertThat(descriptor.inputKind()).isEqualTo(PlayerCreationDescriptor.INPUT_KIND);
    assertThat(descriptor.dependencies()).isEmpty();
    assertThat(RealmEntryPolicy.parseCanonical(parsed.canonicalJson(), JSON)).isEqualTo(parsed);
  }

  @Test
  void descriptorSubstitutionChangesPolicyBytesAndDescriptorDigest() {
    var original = RealmEntryPolicy.parse(policy(2, "PLAYER_CREATED", descriptor()), JSON);
    String substitutedDescriptor =
        descriptor().replace(REVISION_ID, "33333333-3333-4333-8333-333333333333");
    var substituted =
        RealmEntryPolicy.parse(policy(2, "PLAYER_CREATED", substitutedDescriptor), JSON);

    assertThat(substituted.canonicalJson()).isNotEqualTo(original.canonicalJson());
    assertThat(substituted.creationDescriptor().canonicalBytes())
        .isNotEqualTo(original.creationDescriptor().canonicalBytes());
    assertThat(substituted.creationDescriptor().digest())
        .isNotEqualTo(original.creationDescriptor().digest());
  }

  @Test
  void rejectsPolicyVersionAndEntryPolicyMismatches() {
    for (String entryPolicy : List.of("PLAYER_CREATED", "AUTO_PROVISIONED", "UNKNOWN")) {
      assertInvalid(policy(1, entryPolicy, null));
    }
    for (String entryPolicy : List.of("PRESEEDED_ONLY", "AUTO_PROVISIONED", "UNKNOWN")) {
      assertInvalid(policy(2, entryPolicy, descriptor()));
    }
    assertInvalid(policy(3, "PLAYER_CREATED", descriptor()));
    assertInvalid(policy(2, "PLAYER_CREATED", null));
    assertInvalid(policyWithExtraField(2, "PLAYER_CREATED", descriptor()));
  }

  @Test
  void rejectsMalformedOrUnsupportedDescriptorShapes() {
    for (String invalidDescriptor :
        List.of(
            descriptor().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            descriptor().replace("\"inputKind\":\"EMPTY_OBJECT\"", "\"inputKind\":\"OBJECT\""),
            descriptor().replace("\"dependencies\":[]", "\"dependencies\":[\"other\"]"),
            descriptor().replace("\"dependencies\":[]", "\"dependencies\":null"),
            descriptor().replace("\"dependencies\":[]", "\"dependencies\":[],\"unknown\":true"),
            descriptor().replace("\"descriptorRevisionId\":\"" + REVISION_ID + "\",", ""))) {
      assertInvalid(policy(2, "PLAYER_CREATED", invalidDescriptor));
    }
  }

  @Test
  void rejectsNilNoncanonicalAndMalformedDescriptorUuids() {
    for (String invalidId :
        List.of(
            "00000000-0000-0000-0000-000000000000",
            "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
            "1-1-1-1-1")) {
      assertInvalid(policy(2, "PLAYER_CREATED", descriptor().replace(DESCRIPTOR_ID, invalidId)));
      assertInvalid(policy(2, "PLAYER_CREATED", descriptor().replace(REVISION_ID, invalidId)));
    }
  }

  @Test
  void validatesOnlyExplicitCanonicalEmptyObjectCreationInput() {
    var descriptor = PlayerCreationDescriptor.parse(descriptor());
    descriptor.validateCreationInput("{}");

    assertThatThrownBy(() -> descriptor.validateCreationInput(null))
        .isInstanceOf(IllegalArgumentException.class);
    for (String invalidInput : List.of("{ }", "[]", "{\"value\":1}", "{\"unknown\":true}")) {
      assertThatThrownBy(() -> descriptor.validateCreationInput(invalidInput))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void directPolicyConstructionCannotBypassVersionPolicyDescriptorPairing() {
    assertThatThrownBy(
            () ->
                new RealmEntryPolicy(
                    1,
                    "world",
                    "World",
                    "realm",
                    "Realm",
                    true,
                    true,
                    StateScope.SHARED,
                    EntryPolicy.PLAYER_CREATED,
                    null,
                    "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RealmEntryPolicy(
                    1,
                    "world",
                    "World",
                    "realm",
                    "Realm",
                    true,
                    true,
                    StateScope.SHARED,
                    EntryPolicy.PRESEEDED_ONLY,
                    PlayerCreationDescriptor.parse(descriptor()),
                    "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RealmEntryPolicy(
                    2,
                    "world",
                    "World",
                    "realm",
                    "Realm",
                    true,
                    true,
                    StateScope.SHARED,
                    EntryPolicy.PRESEEDED_ONLY,
                    null,
                    "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RealmEntryPolicy(
                    2,
                    "world",
                    "World",
                    "realm",
                    "Realm",
                    true,
                    true,
                    StateScope.SHARED,
                    EntryPolicy.PLAYER_CREATED,
                    null,
                    "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RealmEntryPolicy(
                    3,
                    "world",
                    "World",
                    "realm",
                    "Realm",
                    true,
                    true,
                    StateScope.SHARED,
                    EntryPolicy.PRESEEDED_ONLY,
                    null,
                    "{}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void preservesThe4096BytePolicyInputLimitForBothSchemas() {
    for (String input :
        List.of(policy(1, "PRESEEDED_ONLY", null), policy(2, "PLAYER_CREATED", descriptor()))) {
      String atLimit = padToLimit(input, RealmEntryPolicy.MAX_JSON_BYTES);
      assertThat(atLimit.getBytes(StandardCharsets.UTF_8)).hasSize(RealmEntryPolicy.MAX_JSON_BYTES);
      assertThat(RealmEntryPolicy.parse(atLimit, JSON).canonicalJson()).isNotEmpty();

      String aboveLimit = padToLimit(input, RealmEntryPolicy.MAX_JSON_BYTES + 1);
      assertThat(aboveLimit.getBytes(StandardCharsets.UTF_8))
          .hasSize(RealmEntryPolicy.MAX_JSON_BYTES + 1);
      assertThatThrownBy(() -> RealmEntryPolicy.parse(aboveLimit, JSON))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static void assertInvalid(String input) {
    assertThatThrownBy(() -> RealmEntryPolicy.parse(input, JSON))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String policy(int version, String entryPolicy, String descriptor) {
    return "{\"schemaVersion\":"
        + version
        + ",\"worldSlug\":\"world\",\"worldDisplayName\":\"World\","
        + "\"realmSlug\":\"realm\",\"realmDisplayName\":\"Realm\","
        + "\"visible\":true,\"publicProduction\":true,\"stateScope\":\"SHARED\","
        + "\"entryPolicy\":\""
        + entryPolicy
        + "\""
        + (descriptor == null ? "" : ",\"creationDescriptor\":" + descriptor)
        + "}";
  }

  private static String policyWithExtraField(int version, String entryPolicy, String descriptor) {
    String input = policy(version, entryPolicy, descriptor);
    return input.substring(0, input.length() - 1) + ",\"unknown\":true}";
  }

  private static String descriptor() {
    return "{\"schemaVersion\":1,\"descriptorId\":\""
        + DESCRIPTOR_ID
        + "\",\"descriptorRevisionId\":\""
        + REVISION_ID
        + "\",\"inputKind\":\"EMPTY_OBJECT\",\"dependencies\":[]}";
  }

  private static String padToLimit(String input, int bytes) {
    return " ".repeat(bytes - input.getBytes(StandardCharsets.UTF_8).length) + input;
  }
}
