package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import org.junit.jupiter.api.Test;

class SelectedOwnerIntakeSourceExportTest {
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

  @Test
  void framesTheScopeAndAllSixFamiliesInFixedOrder() {
    var selected = binding(COMMIT, CommandSource.deletePayload("absent-command"));
    var scope = scope(Owner.ENTITY_MANAGEMENT, selected);
    var sources = sources(selected, "0");
    var export = SelectedOwnerIntakeSourceExport.create(scope, sources);

    var expected = new ByteArrayOutputStream();
    frame(expected, SelectedOwnerIntakeSourceExport.DOMAIN);
    frame(expected, scope.canonicalBytes());
    frame(expected, scope.digest());
    frameFamily(expected, "COMMAND", sources.command().canonicalBytes());
    frameFamily(expected, "REALM_POLICY", sources.policy().canonicalBytes());
    frameFamily(expected, "ASSET", sources.asset().canonicalBytes());
    frameFamily(expected, "GAMEPLAY_RULE", sources.gameplay().canonicalBytes());
    frameFamily(expected, "BRANDING", sources.branding().orElseThrow().canonicalBytes());
    frameFamily(
        expected, "TEMPLATE_CONFIG", sources.templateConfig().orElseThrow().canonicalBytes());

    assertThat(export.canonicalBytes()).isEqualTo(expected.toByteArray());
    assertThat(export.digest())
        .isEqualTo(DraftAuthorizationFenceBinding.digest(expected.toByteArray()));
    assertThat(export.sources()).isSameAs(sources);
    assertThat(export.scope()).isEqualTo(scope);
  }

  @Test
  void sharedConsumerDecodesExactCompleteExportsForBothOwners() {
    var selected = binding(COMMIT, CommandSource.deletePayload("absent-command"));
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var scope = scope(owner, selected);
      var sources = sources(selected, "0");
      var exported = SelectedOwnerIntakeSourceExport.create(scope, sources);

      var content =
          SelectedOwnerIntakeSourceContent.fromStored(
              exported.canonicalBytes(), scope, exported.digest());

      assertThat(content.scope()).isEqualTo(scope);
      assertThat(content.canonicalBytes()).isEqualTo(exported.canonicalBytes());
      assertThat(content.digest()).isEqualTo(exported.digest());
      assertThat(content.snapshotBytes("COMMAND")).isEqualTo(sources.command().canonicalBytes());
      assertThat(content.snapshotBytes("REALM_POLICY"))
          .isEqualTo(sources.policy().canonicalBytes());
      assertThat(content.snapshotBytes("ASSET")).isEqualTo(sources.asset().canonicalBytes());
      assertThat(content.snapshotBytes("GAMEPLAY_RULE"))
          .isEqualTo(sources.gameplay().canonicalBytes());
      assertThat(content.snapshotBytes("BRANDING"))
          .isEqualTo(sources.branding().orElseThrow().canonicalBytes());
      assertThat(content.snapshotBytes("TEMPLATE_CONFIG"))
          .isEqualTo(sources.templateConfig().orElseThrow().canonicalBytes());
      assertThat(content.gameplaySource().binding()).isEqualTo(selected);
      assertThat(content.templateConfigSource().binding()).isEqualTo(selected);
    }
  }

  @Test
  void contentIsDeterministicAndCanonicalBytesAreDefensivelyExposed() {
    var selected = binding(COMMIT, CommandSource.deletePayload("absent-command"));
    var first =
        SelectedOwnerIntakeSourceExport.create(
            scope(Owner.AUTOMATION_SCRIPTING, selected), sources(selected, "0"));
    var replay =
        SelectedOwnerIntakeSourceExport.create(
            scope(Owner.AUTOMATION_SCRIPTING, selected), sources(selected, "0"));
    byte[] exposed = first.canonicalBytes();
    exposed[0] ^= 0x7f;

    assertThat(first.canonicalBytes()).isEqualTo(replay.canonicalBytes());
    assertThat(first.digest()).isEqualTo(replay.digest());
  }

  @Test
  void rejectsDifferentSelectedBindingAndChangedFamilyContent() {
    var selected = binding(COMMIT, CommandSource.deletePayload("absent-command"));
    var sources = sources(selected, "0");
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceExport.create(
                    scope(
                        Owner.ENTITY_MANAGEMENT,
                        binding(
                            id("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                            CommandSource.deletePayload("absent-command"))),
                    sources))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("COMMAND");

    var original =
        SelectedOwnerIntakeSourceExport.create(scope(Owner.ENTITY_MANAGEMENT, selected), sources);
    var changed =
        SelectedOwnerIntakeSourceExport.create(
            scope(Owner.ENTITY_MANAGEMENT, selected), sources(selected, "1"));
    assertThat(changed.canonicalBytes()).isNotEqualTo(original.canonicalBytes());
    assertThat(changed.digest()).isNotEqualTo(original.digest());
  }

  @Test
  void missingBrandingOrTemplateSourceIsNotAnEmptyFamily() {
    var selected = binding(COMMIT, CommandSource.deletePayload("absent-command"));
    var complete = sources(selected, "0");
    var missingBranding =
        new GameDesignSourceRepository.SynchronizedSources(
            complete.command(),
            complete.policy(),
            complete.asset(),
            complete.gameplay(),
            Optional.empty(),
            complete.templateConfig());
    var missingTemplate =
        new GameDesignSourceRepository.SynchronizedSources(
            complete.command(),
            complete.policy(),
            complete.asset(),
            complete.gameplay(),
            complete.branding(),
            Optional.empty());

    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceExport.create(
                    scope(Owner.ENTITY_MANAGEMENT, selected), missingBranding))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("branding");
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceExport.create(
                    scope(Owner.AUTOMATION_SCRIPTING, selected), missingTemplate))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("template-config");
  }

  @Test
  void rejectsExportLargerThanEightMib() {
    var selected = binding(COMMIT, "x".repeat(1_400_000));

    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSourceExport.create(
                    scope(Owner.ENTITY_MANAGEMENT, selected), sources(selected, "0")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("8 MiB");
  }

  static GameDesignSourceRepository.SynchronizedSources sources(
      DraftCommitBinding binding, String commandEpoch) {
    var command =
        new CommandSnapshot(binding, commandEpoch, null, "sha256:" + "a".repeat(64), List.of());
    var policy = new RealmPolicySnapshot(binding, "0", List.of());
    var asset = new AssetSnapshot(binding, "0", null, GENESIS, List.of());
    var gameplay = new GameplayRuleSnapshot(binding, "0", null, GENESIS, List.of());
    var branding = new BrandingSourceSnapshot(binding, "0", null, GENESIS, List.of());
    var template = new TemplateConfigSourceSnapshot(binding, "0", null, GENESIS, List.of());
    return new GameDesignSourceRepository.SynchronizedSources(
        command, policy, asset, gameplay, Optional.of(branding), Optional.of(template));
  }

  static SelectedOwnerIntakeSourceReadScope scope(Owner owner, DraftCommitBinding selected) {
    return new SelectedOwnerIntakeSourceReadScope(
        owner, "test", OPERATION, FENCE, INTAKE, ACTOR, selected);
  }

  static DraftCommitBinding binding(UUID commitId, String payload) {
    return DraftCommitBinding.create(
        new TargetProof(TENANT, VERSION, 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW"),
        REQUEST,
        commitId,
        "base-commit-0",
        List.of(new RevisionPayload("0", REVISION, Owner.GAME_DESIGN_CONTROL_PLANE, payload)),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                "TEMPLATE_CONFIG",
                VERSION.toString(),
                "TEMPLATE_CONFIG",
                "ALL",
                "0")));
  }

  private static void frameFamily(ByteArrayOutputStream out, String family, byte[] bytes) {
    frame(out, family);
    frame(out, bytes);
    frame(out, DraftAuthorizationFenceBinding.digest(bytes));
  }

  private static void frame(ByteArrayOutputStream out, String value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static void frame(ByteArrayOutputStream out, byte[] value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static UUID id(String value) {
    return UUID.fromString(value);
  }
}
