package net.firedevops.firemud.common.account.sourceintake;

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
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceTestFixtures;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import org.junit.jupiter.api.Test;

/** Synthetic integrity fixtures do not establish Account authority, source census, or receipts. */
class SelectedOwnerIntakeAuthorizationBindingTest {
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
  void roundTripsBothClosedOwnerDomainsWithExactCanonicalFrameAndCompleteContent() {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var content = content(owner, ACTOR);
      var binding = new SelectedOwnerIntakeAuthorizationBinding(content, unsortedSources(ACTOR));
      byte[] expected = encode(binding, binding.sources());
      byte[] observed = binding.canonicalBytes();

      assertThat(observed).isEqualTo(expected);
      assertThat(binding.schema()).isEqualTo(schema(owner));
      assertThat(binding.purpose()).isEqualTo(purpose(owner));
      assertThat(binding.intendedReader()).isEqualTo(reader(owner, "test"));
      assertThat(binding.digest()).isEqualTo(digest(expected));
      assertThat(binding.sources().stream().map(SourceEvidence::key))
          .containsExactly(
              "ACCOUNT:" + ACTOR, "MEMBERSHIP:" + ACTOR + "/" + TENANT, "TENANT:" + TENANT);

      var restored = SelectedOwnerIntakeAuthorizationBinding.fromStored(expected);
      assertThat(restored.canonicalBytes()).isEqualTo(expected);
      assertThat(restored.digest()).isEqualTo(binding.digest());
      assertThat(restored.content().canonicalBytes()).isEqualTo(content.canonicalBytes());
      assertThat(restored.content().digest()).isEqualTo(content.digest());
      assertThat(restored.content().scope()).isEqualTo(content.scope());
      assertThat(restored.owner()).isEqualTo(owner);
      assertThat(restored.targetNamespace()).isEqualTo("test");
      assertThat(restored.operationId()).isEqualTo(OPERATION);
      assertThat(restored.fenceId()).isEqualTo(FENCE);
      assertThat(restored.intakeRequestId()).isEqualTo(INTAKE);
      assertThat(restored.actorAccountId()).isEqualTo(ACTOR);
      assertThat(restored.selected()).isEqualTo(content.scope().selected());
      assertThat(restored.tenantId()).isEqualTo(TENANT);
      assertThat(restored.versionId()).isEqualTo(VERSION);
      for (String family : FAMILIES) {
        assertThat(restored.content().snapshotBytes(family))
            .isEqualTo(content.snapshotBytes(family));
      }
    }
  }

  @Test
  void preservesDefensiveCopiesOfSourceListAndCanonicalContent() {
    var content = content(Owner.ENTITY_MANAGEMENT, ACTOR);
    var input = new ArrayList<>(unsortedSources(ACTOR));
    var binding = new SelectedOwnerIntakeAuthorizationBinding(content, input);
    byte[] expected = binding.canonicalBytes();
    byte[] exposed = binding.canonicalBytes();
    exposed[0] ^= 0x7f;
    input.clear();
    byte[] exposedEvidence = binding.sources().getFirst().evidence();
    exposedEvidence[0] ^= 0x7f;
    byte[] exposedContent = binding.content().canonicalBytes();
    exposedContent[0] ^= 0x7f;

    assertThat(binding.canonicalBytes()).isEqualTo(expected);
    assertThat(binding.content().canonicalBytes()).isNotEqualTo(exposedContent);
    assertThat(binding.sources()).hasSize(3);
    assertThat(binding.sources().getFirst().evidence()).isNotEqualTo(exposedEvidence);
    assertThatThrownBy(() -> binding.sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsChangedPurposeReaderAndContentDigests() {
    var binding =
        new SelectedOwnerIntakeAuthorizationBinding(
            content(Owner.AUTOMATION_SCRIPTING, ACTOR), unsortedSources(ACTOR));
    byte[] encoded = binding.canonicalBytes();

    assertInvalid(
        encode(
            binding,
            binding.sources(),
            binding.schema(),
            binding.content().canonicalBytes(),
            binding.content().digest(),
            binding.intendedReader(),
            "ENTITY_INTAKE_RETENTION",
            "3",
            null));
    assertInvalid(
        encode(
            binding,
            binding.sources(),
            binding.schema(),
            binding.content().canonicalBytes(),
            binding.content().digest(),
            reader(Owner.ENTITY_MANAGEMENT, "test"),
            binding.purpose(),
            "3",
            null));
    assertInvalid(
        encode(
            binding,
            binding.sources(),
            binding.schema(),
            binding.content().canonicalBytes(),
            "sha256:" + "0".repeat(64),
            binding.intendedReader(),
            binding.purpose(),
            "3",
            null));

    byte[] invalidFrame = encoded.clone();
    ByteBuffer.wrap(invalidFrame).putInt(0, Integer.MAX_VALUE);
    assertInvalid(invalidFrame);
    byte[] invalidUtf8 = encoded.clone();
    invalidUtf8[4] = (byte) 0xc3;
    invalidUtf8[5] = 0x28;
    assertInvalid(invalidUtf8);
  }

  @Test
  void rejectsActorMismatchAndSourceEvidenceForAnotherActorOrTenant() {
    var original =
        new SelectedOwnerIntakeAuthorizationBinding(
            content(Owner.ENTITY_MANAGEMENT, ACTOR), unsortedSources(ACTOR));
    UUID otherActor = id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab");
    UUID otherTenant = id("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    assertThatThrownBy(
            () ->
                new SelectedOwnerIntakeAuthorizationBinding(
                    content(Owner.ENTITY_MANAGEMENT, otherActor), unsortedSources(ACTOR)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SelectedOwnerIntakeAuthorizationBinding(
                    original.content(),
                    List.of(
                        evidence(SourceKind.ACCOUNT, ACTOR.toString(), "changed"),
                        evidence(SourceKind.TENANT, otherTenant.toString(), "tenant"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SelectedOwnerIntakeAuthorizationBinding(
                    original.content(),
                    List.of(
                        evidence(SourceKind.ACCOUNT, otherActor.toString(), "actor"),
                        evidence(SourceKind.TENANT, TENANT.toString(), "tenant"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresNonemptyDistinctSourceEvidence() {
    var content = content(Owner.ENTITY_MANAGEMENT, ACTOR);
    SourceEvidence account = evidence(SourceKind.ACCOUNT, ACTOR.toString(), "account");

    assertThatThrownBy(() -> new SelectedOwnerIntakeAuthorizationBinding(content, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new SelectedOwnerIntakeAuthorizationBinding(content, List.of(account, account)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsCountOrderTrailingAndUnknownSchemaChanges() {
    var binding =
        new SelectedOwnerIntakeAuthorizationBinding(
            content(Owner.ENTITY_MANAGEMENT, ACTOR), unsortedSources(ACTOR));
    List<SourceEvidence> sorted = binding.sources();
    byte[] canonical = binding.canonicalBytes();
    byte[] reversed =
        encode(
            binding,
            reversed(sorted),
            binding.schema(),
            binding.content().canonicalBytes(),
            binding.content().digest(),
            binding.intendedReader(),
            binding.purpose(),
            "3",
            null);

    assertInvalid(
        encode(
            binding,
            sorted,
            binding.schema(),
            binding.content().canonicalBytes(),
            binding.content().digest(),
            binding.intendedReader(),
            binding.purpose(),
            "2",
            null));
    assertInvalid(
        encode(
            binding,
            sorted,
            binding.schema(),
            binding.content().canonicalBytes(),
            binding.content().digest(),
            binding.intendedReader(),
            binding.purpose(),
            "03",
            null));
    assertInvalid(reversed);
    assertInvalid(append(canonical, new byte[] {1}));
    assertInvalid(
        encode(
            binding,
            sorted,
            "account-entity-intake-authorization/v2",
            binding.content().canonicalBytes(),
            binding.content().digest(),
            binding.intendedReader(),
            binding.purpose(),
            "3",
            null));
    assertThatThrownBy(() -> SelectedOwnerIntakeAuthorizationBinding.fromStored(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeAuthorizationBinding.fromStored(
                    new byte[SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void changedValidSourceEvidenceChangesDigestAndReadsBackExactly() {
    var content = content(Owner.AUTOMATION_SCRIPTING, ACTOR);
    var original = new SelectedOwnerIntakeAuthorizationBinding(content, unsortedSources(ACTOR));
    var changedSources = new ArrayList<>(unsortedSources(ACTOR));
    changedSources.set(0, evidence(SourceKind.TENANT, TENANT.toString(), "changed tenant source"));
    var changed = new SelectedOwnerIntakeAuthorizationBinding(content, changedSources);

    assertThat(changed.digest()).isNotEqualTo(original.digest());
    assertThat(
            SelectedOwnerIntakeAuthorizationBinding.fromStored(changed.canonicalBytes())
                .canonicalBytes())
        .isEqualTo(changed.canonicalBytes());
  }

  private static SelectedOwnerIntakeSourceContent content(Owner owner, UUID actor) {
    var scope =
        new SelectedOwnerIntakeSourceReadScope(
            owner, "test", OPERATION, FENCE, INTAKE, actor, selected());
    byte[] bytes = encodeContent(scope, snapshots(scope.selected()));
    return SelectedOwnerIntakeSourceContent.fromStored(bytes, scope, digest(bytes));
  }

  private static List<SourceEvidence> unsortedSources(UUID actor) {
    return List.of(
        evidence(SourceKind.TENANT, TENANT.toString(), "tenant"),
        evidence(SourceKind.ACCOUNT, actor.toString(), "account"),
        evidence(SourceKind.MEMBERSHIP, actor + "/" + TENANT, "membership"));
  }

  private static List<SourceEvidence> reversed(List<SourceEvidence> values) {
    var reversed = new ArrayList<>(values);
    java.util.Collections.reverse(reversed);
    return reversed;
  }

  private static SourceEvidence evidence(SourceKind kind, String scope, String value) {
    return new SourceEvidence(
        kind, scope, null, "1", null, null, value.getBytes(StandardCharsets.UTF_8));
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

  private static byte[] encodeContent(
      SelectedOwnerIntakeSourceReadScope scope, List<FamilySnapshot> snapshots) {
    var out = new ByteArrayOutputStream();
    frame(out, SelectedOwnerIntakeSourceContent.DOMAIN);
    frame(out, scope.canonicalBytes());
    frame(out, scope.digest());
    for (FamilySnapshot snapshot : snapshots) {
      frame(out, snapshot.family());
      frame(out, snapshot.bytes());
      frame(out, digest(snapshot.bytes()));
    }
    return out.toByteArray();
  }

  private static byte[] encode(
      SelectedOwnerIntakeAuthorizationBinding binding, List<SourceEvidence> sources) {
    return encode(
        binding,
        sources,
        binding.schema(),
        binding.content().canonicalBytes(),
        binding.content().digest(),
        binding.intendedReader(),
        binding.purpose(),
        Integer.toString(sources.size()),
        null);
  }

  private static byte[] encode(
      SelectedOwnerIntakeAuthorizationBinding binding,
      List<SourceEvidence> sources,
      String schema,
      byte[] content,
      String contentDigest,
      String reader,
      String purpose,
      String count,
      byte[] trailing) {
    var out = new ByteArrayOutputStream();
    frame(out, schema);
    frame(out, content);
    frame(out, contentDigest);
    frame(out, reader);
    frame(out, purpose);
    frame(out, count);
    for (SourceEvidence source : sources) frame(out, source.canonicalBytes());
    if (trailing != null) out.writeBytes(trailing);
    return out.toByteArray();
  }

  private static void assertInvalid(byte[] bytes) {
    assertThatThrownBy(() -> SelectedOwnerIntakeAuthorizationBinding.fromStored(bytes))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static DraftCommitBinding selected() {
    return DraftCommitBinding.create(
        new DraftCommitBinding.TargetProof(
            TENANT, VERSION, 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW"),
        REQUEST,
        COMMIT,
        "base-commit-0",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                REVISION,
                Owner.GAME_DESIGN_CONTROL_PLANE,
                SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload())),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                "TEMPLATE_CONFIG",
                VERSION.toString(),
                "TEMPLATE_CONFIG",
                "ALL",
                "0")));
  }

  private static String schema(Owner owner) {
    return owner == Owner.ENTITY_MANAGEMENT
        ? "account-entity-intake-authorization/v1"
        : "account-automation-intake-authorization/v1";
  }

  private static String purpose(Owner owner) {
    return owner == Owner.ENTITY_MANAGEMENT
        ? "ENTITY_INTAKE_RETENTION"
        : "AUTOMATION_INTAKE_RETENTION";
  }

  private static String reader(Owner owner, String namespace) {
    return "spiffe://firemud/ns/"
        + namespace
        + "/sa/"
        + (owner == Owner.ENTITY_MANAGEMENT
            ? "entity-management-service"
            : "automation-scripting-service");
  }

  private static void frame(ByteArrayOutputStream out, String value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static void frame(ByteArrayOutputStream out, byte[] value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static String digest(byte[] bytes) {
    return DraftAuthorizationFenceBinding.digest(bytes);
  }

  private static byte[] append(byte[] value, byte[] suffix) {
    byte[] joined = Arrays.copyOf(value, value.length + suffix.length);
    System.arraycopy(suffix, 0, joined, value.length, suffix.length);
    return joined;
  }

  private static UUID id(String value) {
    return UUID.fromString(value);
  }

  private record FamilySnapshot(String family, byte[] bytes) {}
}
