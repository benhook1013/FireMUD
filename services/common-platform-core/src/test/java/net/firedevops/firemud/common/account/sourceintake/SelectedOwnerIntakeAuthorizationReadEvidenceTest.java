package net.firedevops.firemud.common.account.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationReadEvidence.Request;
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

/** Synthetic lookup evidence only; Account authentication establishes the HELD result. */
class SelectedOwnerIntakeAuthorizationReadEvidenceTest {
  private static final UUID OPERATION = id("55555555-5555-4555-8555-555555555555");
  private static final UUID FENCE = id("66666666-6666-4666-8666-666666666666");
  private static final UUID INTAKE = id("77777777-7777-4777-8777-777777777777");
  private static final UUID READ = id("88888888-8888-4888-8888-888888888888");

  @Test
  void requestDerivesOwnerReaderAndRetentionPurposeForBothClosedOwners() {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var binding = binding(owner);
      var request = Request.create("test", binding);

      assertThat(request.schemaVersion()).isEqualTo(1);
      assertThat(request.targetNamespace()).isEqualTo("test");
      assertThat(request.readRequestId())
          .isNotIn(binding.operationId(), binding.fenceId(), binding.intakeRequestId());
      assertThat(request.intendedReader()).isEqualTo(binding.intendedReader());
      assertThat(request.retentionPurpose()).isEqualTo(binding.purpose());
      assertThat(request.binding()).isSameAs(binding);
    }
  }

  @Test
  void rejectsInvalidNamespaceCorrelationVersionAndBindingNamespace() {
    var binding = binding(Owner.ENTITY_MANAGEMENT);
    assertThatThrownBy(() -> new Request(1, "not/a/namespace", READ, binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(2, "test", READ, binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(1, "other", READ, binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(1, "test", OPERATION, binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(1, "test", FENCE, binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(1, "test", INTAKE, binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(1, "test", new UUID(0, 0), binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(1, "test", READ, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static SelectedOwnerIntakeAuthorizationBinding binding(Owner owner) {
    UUID tenant = id("11111111-1111-4111-8111-111111111111");
    UUID actor = id("99999999-9999-4999-8999-999999999999");
    var selected = selected(tenant);
    var scope =
        new SelectedOwnerIntakeSourceReadScope(
            owner, "test", OPERATION, FENCE, INTAKE, actor, selected);
    var content = content(scope);
    return new SelectedOwnerIntakeAuthorizationBinding(content, sources(actor, tenant));
  }

  private static SelectedOwnerIntakeSourceContent content(
      SelectedOwnerIntakeSourceReadScope scope) {
    var out = new ByteArrayOutputStream();
    frame(out, SelectedOwnerIntakeSourceContent.DOMAIN);
    frame(out, scope.canonicalBytes());
    frame(out, scope.digest());
    for (String family :
        List.of(
            "COMMAND", "REALM_POLICY", "ASSET", "GAMEPLAY_RULE", "BRANDING", "TEMPLATE_CONFIG")) {
      byte[] snapshot = snapshot(family, scope.selected());
      frame(out, family);
      frame(out, snapshot);
      frame(out, DraftAuthorizationFenceBinding.digest(snapshot));
    }
    byte[] bytes = out.toByteArray();
    return SelectedOwnerIntakeSourceContent.fromStored(
        bytes, scope, DraftAuthorizationFenceBinding.digest(bytes));
  }

  private static byte[] snapshot(String family, DraftCommitBinding selected) {
    if ("GAMEPLAY_RULE".equals(family)) {
      return new GameplayRuleSelectedSource(
              GameplayRuleManifest.canonical(
                  Map.of(
                      "schema",
                      "game-design-gameplay-rule-source-snapshot/v1",
                      "bindingJson",
                      selected.canonicalJson(),
                      "bindingDigest",
                      selected.digest(),
                      "sourceEpoch",
                      "0",
                      "inheritedCommitId",
                      "",
                      "genesisReceiptId",
                      "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                      "manifestJson",
                      GameplayRuleManifest.explicitEmpty().canonicalJson(),
                      "entries",
                      List.of())))
          .canonicalBytes();
    }
    if ("TEMPLATE_CONFIG".equals(family))
      return new TemplateConfigSourceSnapshot(
              selected, "0", null, id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), List.of())
          .canonicalBytes();
    return SelectedOwnerIntakeSourceTestFixtures.snapshot(family, selected);
  }

  private static List<SourceEvidence> sources(UUID actor, UUID tenant) {
    var values = new ArrayList<SourceEvidence>();
    values.add(evidence(SourceKind.TENANT, tenant.toString(), "tenant"));
    values.add(evidence(SourceKind.ACCOUNT, actor.toString(), "account"));
    values.add(evidence(SourceKind.MEMBERSHIP, actor + "/" + tenant, "membership"));
    return values;
  }

  private static SourceEvidence evidence(SourceKind kind, String scope, String marker) {
    return new SourceEvidence(
        kind, scope, null, "1", null, null, marker.getBytes(StandardCharsets.UTF_8));
  }

  private static DraftCommitBinding selected(UUID tenant) {
    UUID version = id("22222222-2222-4222-8222-222222222222");
    return DraftCommitBinding.create(
        new DraftCommitBinding.TargetProof(
            tenant, version, 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW"),
        id("33333333-3333-4333-8333-333333333333"),
        id("44444444-4444-4444-8444-444444444444"),
        "base-commit-0",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab"),
                Owner.GAME_DESIGN_CONTROL_PLANE,
                SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload())),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                "TEMPLATE_CONFIG",
                version.toString(),
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

  private static UUID id(String value) {
    return UUID.fromString(value);
  }
}
