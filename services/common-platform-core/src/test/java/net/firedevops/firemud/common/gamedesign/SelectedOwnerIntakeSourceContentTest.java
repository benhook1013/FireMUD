package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import org.junit.jupiter.api.Test;

/**
 * Synthetic integrity fixtures only; these do not prove genuine owner rows, census, or authority.
 */
class SelectedOwnerIntakeSourceContentTest {
  private static final UUID TENANT = id("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION = id("22222222-2222-4222-8222-222222222222");
  private static final UUID REQUEST = id("33333333-3333-4333-8333-333333333333");
  private static final UUID COMMIT = id("44444444-4444-4444-8444-444444444444");
  private static final UUID REVISION = id("55555555-5555-4555-8555-555555555555");
  private static final UUID OPERATION = id("66666666-6666-4666-8666-666666666666");
  private static final UUID FENCE = id("77777777-7777-4777-8777-777777777777");
  private static final UUID INTAKE = id("88888888-8888-4888-8888-888888888888");
  private static final UUID ACTOR = id("99999999-9999-4999-8999-999999999999");
  private static final UUID GENESIS = id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final List<String> FAMILIES =
      List.of("COMMAND", "REALM_POLICY", "ASSET", "GAMEPLAY_RULE", "BRANDING", "TEMPLATE_CONFIG");

  @Test
  void roundTripsBothOwnerDomainsAndPreservesDefensiveExactFamilyBytes() {
    DraftCommitBinding selected = binding(COMMIT);
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var scope = scope(owner, selected, INTAKE);
      List<FamilySnapshot> snapshots = snapshots(selected);
      byte[] encoded = encode(scope, snapshots);
      var content = SelectedOwnerIntakeSourceContent.fromStored(encoded, scope, digest(encoded));
      byte[] expected = snapshots.getFirst().bytes().clone();
      byte[] expectedContent = encoded.clone();
      byte[] exposedContent = content.canonicalBytes();
      byte[] exposedSnapshot = content.snapshotBytes("COMMAND");
      exposedContent[0] ^= 0x7f;
      exposedSnapshot[0] ^= 0x7f;
      encoded[0] ^= 0x7f;

      assertThat(content.scope()).isEqualTo(scope);
      assertThat(content.canonicalBytes()).isEqualTo(expectedContent);
      assertThat(content.canonicalBytes()).isNotEqualTo(exposedContent);
      assertThat(content.digest()).isEqualTo(digest(encode(scope, snapshots)));
      assertThat(content.snapshotBytes("COMMAND")).isEqualTo(expected);
      assertThat(content.commandSource().binding()).isEqualTo(selected);
      assertThat(content.commandSource().definitions()).isEmpty();
      assertThat(content.commandSource().canonicalBytes())
          .containsExactly(content.snapshotBytes("COMMAND"));
      assertThat(content.realmPolicySource().binding()).isEqualTo(selected);
      assertThat(content.realmPolicySource().policies()).isEmpty();
      assertThat(content.realmPolicySource().canonicalBytes())
          .containsExactly(content.snapshotBytes("REALM_POLICY"));
      assertThat(content.assetSource().binding()).isEqualTo(selected);
      assertThat(content.assetSource().inheritedCommitId()).isNull();
      assertThat(content.assetSource().genesisReceiptId()).isEqualTo(GENESIS);
      assertThat(content.assetSource().items()).isEmpty();
      assertThat(content.assetSource().canonicalBytes())
          .containsExactly(content.snapshotBytes("ASSET"));
      assertThat(content.brandingSource().binding()).isEqualTo(selected);
      assertThat(content.brandingSource().inheritedCommitId()).isNull();
      assertThat(content.brandingSource().genesisReceiptId()).isEqualTo(GENESIS);
      assertThat(content.brandingSource().items()).isEmpty();
      assertThat(content.brandingSource().canonicalBytes())
          .containsExactly(content.snapshotBytes("BRANDING"));
      assertThat(content.gameplaySource().binding()).isEqualTo(selected);
      assertThat(content.templateConfigSource().binding()).isEqualTo(selected);
      assertThatThrownBy(() -> content.snapshotBytes("UNKNOWN"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void requiresExpectedScopeDigestAndEveryScopeFieldToMatch() {
    DraftCommitBinding selected = binding(COMMIT);
    var expectedScope = scope(Owner.ENTITY_MANAGEMENT, selected, INTAKE);
    byte[] original = encode(expectedScope, snapshots(selected));
    byte[] changedActor =
        encode(
            scope(Owner.ENTITY_MANAGEMENT, selected, id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab")),
            snapshots(selected));
    byte[] changedOwner =
        encode(scope(Owner.AUTOMATION_SCRIPTING, selected, INTAKE), snapshots(selected));
    byte[] changedSelection =
        encode(
            scope(
                Owner.ENTITY_MANAGEMENT,
                binding(id("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")),
                INTAKE),
            snapshots(selected));

    assertInvalid(original, expectedScope, "sha256:" + "0".repeat(64));
    assertInvalid(changedActor, expectedScope, digest(changedActor));
    assertInvalid(changedOwner, expectedScope, digest(changedOwner));
    assertInvalid(changedSelection, expectedScope, digest(changedSelection));
    assertInvalid(
        encode(expectedScope, snapshots(selected), "sha256:" + "0".repeat(64), Map.of(), null),
        expectedScope,
        null);
    assertThatThrownBy(
            () -> SelectedOwnerIntakeSourceContent.fromStored(original, expectedScope, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsChangedSelectedBindingEvenWhenInnerAndOuterHashesAreRecomputed() {
    DraftCommitBinding selected = binding(COMMIT);
    DraftCommitBinding substituted = binding(id("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
    var scope = scope(Owner.AUTOMATION_SCRIPTING, selected, INTAKE);
    List<FamilySnapshot> altered = snapshots(selected);
    altered.set(
        0,
        new FamilySnapshot(
            "COMMAND", SelectedOwnerIntakeSourceTestFixtures.snapshot("COMMAND", substituted)));
    byte[] forged = encode(scope, altered);

    assertInvalid(forged, scope, digest(forged));
  }

  @Test
  void rejectsFamilyChangesAgainstThePreviouslyExpectedOuterDigest() {
    DraftCommitBinding selected = binding(COMMIT);
    var scope = scope(Owner.ENTITY_MANAGEMENT, selected, INTAKE);
    byte[] original = encode(scope, snapshots(selected));
    List<FamilySnapshot> altered = snapshots(selected);
    altered.set(
        0,
        new FamilySnapshot(
            "COMMAND", SelectedOwnerIntakeSourceTestFixtures.snapshot("COMMAND", selected, 1)));
    byte[] changed = encode(scope, altered);

    assertInvalid(changed, scope, digest(original));
  }

  @Test
  void rejectsWrongFamilyDigestAndNoncanonicalOrInvalidJsonContent() {
    DraftCommitBinding selected = binding(COMMIT);
    var scope = scope(Owner.ENTITY_MANAGEMENT, selected, INTAKE);
    List<FamilySnapshot> valid = snapshots(selected);

    assertInvalid(
        encode(scope, valid, null, Map.of("COMMAND", "sha256:" + "0".repeat(64)), null),
        scope,
        null);

    List<FamilySnapshot> noncanonical = new ArrayList<>(valid);
    noncanonical.set(
        0,
        new FamilySnapshot(
            "COMMAND",
            (" " + new String(valid.getFirst().bytes(), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8)));
    assertInvalid(encode(scope, noncanonical), scope, null);

    List<FamilySnapshot> duplicate = new ArrayList<>(valid);
    String bindingJson = selected.canonicalJson();
    String duplicateJson =
        "{\"bindingJson\":"
            + quote(bindingJson)
            + ",\"bindingJson\":"
            + quote(bindingJson)
            + ",\"bindingDigest\":"
            + quote(selected.digest())
            + "}";
    duplicate.set(0, new FamilySnapshot("COMMAND", duplicateJson.getBytes(StandardCharsets.UTF_8)));
    assertInvalid(encode(scope, duplicate), scope, null);

    List<FamilySnapshot> emptyFamily = new ArrayList<>(valid);
    emptyFamily.set(0, new FamilySnapshot("COMMAND", new byte[0]));
    assertInvalid(encode(scope, emptyFamily), scope, null);

    List<FamilySnapshot> invalidUtf8 = new ArrayList<>(valid);
    invalidUtf8.set(0, new FamilySnapshot("COMMAND", new byte[] {(byte) 0xc3, 0x28}));
    assertInvalid(encode(scope, invalidUtf8), scope, null);
  }

  @Test
  void requiresTextualBindingFieldsAndTypedGameplayAndTemplateSnapshots() {
    DraftCommitBinding selected = binding(COMMIT);
    var scope = scope(Owner.ENTITY_MANAGEMENT, selected, INTAKE);
    List<FamilySnapshot> invalidBinding = new ArrayList<>(snapshots(selected));
    invalidBinding.set(
        0,
        new FamilySnapshot(
            "COMMAND",
            GameplayRuleManifest.canonical(
                    Map.of("bindingJson", 1, "bindingDigest", selected.digest()))
                .getBytes(StandardCharsets.UTF_8)));
    assertInvalid(encode(scope, invalidBinding), scope, null);

    List<FamilySnapshot> missingBinding = new ArrayList<>(snapshots(selected));
    missingBinding.set(
        0,
        new FamilySnapshot(
            "COMMAND",
            GameplayRuleManifest.canonical(Map.of("bindingJson", selected.canonicalJson()))
                .getBytes(StandardCharsets.UTF_8)));
    assertInvalid(encode(scope, missingBinding), scope, null);

    List<FamilySnapshot> invalidGameplay = new ArrayList<>(snapshots(selected));
    invalidGameplay.set(
        3,
        new FamilySnapshot(
            "GAMEPLAY_RULE", opaqueSnapshot("GAMEPLAY_RULE", selected, "typed-invalid")));
    assertInvalid(encode(scope, invalidGameplay), scope, null);

    List<FamilySnapshot> invalidTemplate = new ArrayList<>(snapshots(selected));
    invalidTemplate.set(
        5,
        new FamilySnapshot(
            "TEMPLATE_CONFIG", opaqueSnapshot("TEMPLATE_CONFIG", selected, "typed-invalid")));
    assertInvalid(encode(scope, invalidTemplate), scope, null);
  }

  @Test
  void rejectsMalformedTypedCommandPolicyAssetAndBrandingSnapshots() {
    DraftCommitBinding selected = binding(COMMIT);
    var scope = scope(Owner.ENTITY_MANAGEMENT, selected, INTAKE);
    for (int index : List.of(0, 1, 2, 4)) {
      List<FamilySnapshot> invalid = new ArrayList<>(snapshots(selected));
      FamilySnapshot original = invalid.get(index);
      String field = index == 0 ? "definitions" : index == 1 ? "policies" : "items";
      String malformed =
          new String(original.bytes(), StandardCharsets.UTF_8)
              .replaceFirst("\"" + field + "\":\\[\\]", "\"" + field + "\":{}");
      invalid.set(
          index, new FamilySnapshot(original.family(), malformed.getBytes(StandardCharsets.UTF_8)));
      assertInvalid(encode(scope, invalid), scope, null);
    }
  }

  @Test
  void rejectsChangedTypedSourceBindingWhenEnvelopeDigestsAreRecomputed() {
    DraftCommitBinding selected = binding(COMMIT);
    DraftCommitBinding substituted = binding(id("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
    var scope = scope(Owner.AUTOMATION_SCRIPTING, selected, INTAKE);
    for (int index : List.of(0, 1, 2, 4)) {
      List<FamilySnapshot> altered = new ArrayList<>(snapshots(selected));
      FamilySnapshot original = altered.get(index);
      String changed =
          new String(original.bytes(), StandardCharsets.UTF_8)
              .replace(escaped(selected.canonicalJson()), escaped(substituted.canonicalJson()))
              .replace(selected.digest(), substituted.digest());
      altered.set(
          index, new FamilySnapshot(original.family(), changed.getBytes(StandardCharsets.UTF_8)));
      assertInvalid(encode(scope, altered), scope, null);
    }
  }

  @Test
  void rejectsOpaqueSelectedGameDesignRevisionWithOtherwiseTypedSnapshotEnvelope() {
    DraftCommitBinding selected = binding(COMMIT, "{\"source\":1}");
    var scope = scope(Owner.ENTITY_MANAGEMENT, selected, INTAKE);
    List<FamilySnapshot> typedSnapshots = snapshots(selected);

    assertInvalid(encode(scope, typedSnapshots), scope, null);
  }

  @Test
  void rejectsUnknownOrderDuplicateMissingTruncatedTrailingAndOversizedFrames() {
    DraftCommitBinding selected = binding(COMMIT);
    var scope = scope(Owner.AUTOMATION_SCRIPTING, selected, INTAKE);
    List<FamilySnapshot> valid = snapshots(selected);

    List<FamilySnapshot> swapped = new ArrayList<>(valid);
    swapped.set(0, new FamilySnapshot("REALM_POLICY", valid.get(0).bytes()));
    swapped.set(1, new FamilySnapshot("COMMAND", valid.get(1).bytes()));
    assertInvalid(encode(scope, swapped), scope, null);

    List<FamilySnapshot> duplicate = new ArrayList<>(valid);
    duplicate.set(1, new FamilySnapshot("COMMAND", valid.get(1).bytes()));
    assertInvalid(encode(scope, duplicate), scope, null);
    assertInvalid(encode(scope, valid.subList(0, 5)), scope, null);

    byte[] encoded = encode(scope, valid);
    assertInvalid(encode(scope, valid, null, Map.of(), new byte[] {1}), scope, null);
    assertInvalid(Arrays.copyOf(encoded, encoded.length - 1), scope, null);

    byte[] oversizedFrame = encoded.clone();
    ByteBuffer.wrap(oversizedFrame).putInt(0, Integer.MAX_VALUE);
    assertInvalid(oversizedFrame, scope, null);

    byte[] oversizedEnvelope = new byte[SelectedOwnerIntakeSourceContent.MAX_BYTES + 1];
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceContent.fromStored(
                    oversizedEnvelope, scope, "sha256:" + "0".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static List<FamilySnapshot> snapshots(DraftCommitBinding selected) {
    var gameplay =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                Map.of(
                    "schema", "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson", selected.canonicalJson(),
                    "bindingDigest", selected.digest(),
                    "sourceEpoch", "0",
                    "inheritedCommitId", "",
                    "genesisReceiptId", GENESIS.toString(),
                    "manifestJson", GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries", List.of())));
    var template = new TemplateConfigSourceSnapshot(selected, "0", null, GENESIS, List.of());
    var result = new ArrayList<FamilySnapshot>();
    for (String family : FAMILIES) {
      byte[] bytes =
          switch (family) {
            case "GAMEPLAY_RULE" -> gameplay.canonicalBytes();
            case "TEMPLATE_CONFIG" -> template.canonicalBytes();
            default -> SelectedOwnerIntakeSourceTestFixtures.snapshot(family, selected);
          };
      result.add(new FamilySnapshot(family, bytes));
    }
    return result;
  }

  private static byte[] opaqueSnapshot(String family, DraftCommitBinding selected, String marker) {
    return GameplayRuleManifest.canonical(
            Map.of(
                "schema",
                "synthetic-test-only/v1",
                "bindingJson",
                selected.canonicalJson(),
                "bindingDigest",
                selected.digest(),
                "family",
                family,
                "marker",
                marker))
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String escaped(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static byte[] encode(
      SelectedOwnerIntakeSourceReadScope scope, List<FamilySnapshot> snapshots) {
    return encode(scope, snapshots, null, Map.of(), null);
  }

  private static byte[] encode(
      SelectedOwnerIntakeSourceReadScope scope,
      List<FamilySnapshot> snapshots,
      String scopeDigest,
      Map<String, String> familyDigests,
      byte[] trailing) {
    var out = new ByteArrayOutputStream();
    frame(out, SelectedOwnerIntakeSourceContent.DOMAIN);
    frame(out, scope.canonicalBytes());
    frame(out, scopeDigest == null ? scope.digest() : scopeDigest);
    for (FamilySnapshot snapshot : snapshots) {
      frame(out, snapshot.family());
      frame(out, snapshot.bytes());
      frame(out, familyDigests.getOrDefault(snapshot.family(), digest(snapshot.bytes())));
    }
    if (trailing != null) out.writeBytes(trailing);
    return out.toByteArray();
  }

  private static void assertInvalid(
      byte[] bytes, SelectedOwnerIntakeSourceReadScope expectedScope, String expectedDigest) {
    String digest = expectedDigest == null ? digest(bytes) : expectedDigest;
    assertThatThrownBy(
            () -> SelectedOwnerIntakeSourceContent.fromStored(bytes, expectedScope, digest))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static SelectedOwnerIntakeSourceReadScope scope(
      Owner owner, DraftCommitBinding selected, UUID intakeRequestId) {
    return new SelectedOwnerIntakeSourceReadScope(
        owner, "test", OPERATION, FENCE, intakeRequestId, ACTOR, selected);
  }

  private static DraftCommitBinding binding(UUID commitId) {
    return binding(commitId, SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload());
  }

  private static DraftCommitBinding binding(UUID commitId, String revisionPayload) {
    return DraftCommitBinding.create(
        new DraftCommitBinding.TargetProof(
            TENANT, VERSION, 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW"),
        REQUEST,
        commitId,
        "base-commit-0",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0", REVISION, Owner.GAME_DESIGN_CONTROL_PLANE, revisionPayload)),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                "TEMPLATE_CONFIG",
                VERSION.toString(),
                "TEMPLATE_CONFIG",
                "ALL",
                "0")));
  }

  private static void frame(ByteArrayOutputStream out, String value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static void frame(ByteArrayOutputStream out, byte[] value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static String digest(byte[] value) {
    return DraftAuthorizationFenceBinding.digest(value);
  }

  private static String quote(String value) {
    return GameplayRuleManifest.tree(GameplayRuleManifest.canonical(Map.of("value", value)))
        .get("value")
        .toString();
  }

  private static UUID id(String value) {
    return UUID.fromString(value);
  }

  private record FamilySnapshot(String family, byte[] bytes) {}
}
