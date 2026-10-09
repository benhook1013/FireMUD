package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import org.junit.jupiter.api.Test;

class RealmPolicyApplicationTest {
  private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID COMMIT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID REQUEST_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID REVISION_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID GENESIS_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID INHERITED_COMMIT_ID =
      UUID.fromString("77777777-7777-4777-8777-777777777777");

  @Test
  void absentInheritedCommitUsesDeterministicZeroLengthByteFrame() {
    RealmPolicyApplication application = freshApplication();

    byte[] expected =
        frames(
            text("game-design-realm-policy-application/v1"),
            text("true"),
            application.genesis().canonicalBytes(),
            text("false"),
            new byte[0],
            text("0"),
            application.snapshot().canonicalBytes());

    byte[] canonicalBytes = application.canonicalBytes();
    assertThat(canonicalBytes).isEqualTo(expected);
    assertThat(canonicalBytes).hasSize(expected.length);
    assertThat(canonicalBytes).isEqualTo(application.canonicalBytes());
  }

  @Test
  void presentInheritedCommitRetainsItsExistingUtf8Frame() {
    RealmPolicyApplication application =
        new RealmPolicyApplication(null, INHERITED_COMMIT_ID, "0", snapshot());

    byte[] expected =
        frames(
            text("game-design-realm-policy-application/v1"),
            text("false"),
            new byte[0],
            text("true"),
            text(INHERITED_COMMIT_ID.toString()),
            text("0"),
            application.snapshot().canonicalBytes());

    assertThat(application.canonicalBytes()).isEqualTo(expected);
  }

  @Test
  void missingBothGenesisAndInheritedCommitAndInvalidRequiredTextAreRejected() {
    RealmPolicySnapshot snapshot = snapshot();

    assertThatThrownBy(() -> new RealmPolicyApplication(null, null, "0", snapshot))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new RealmPolicyApplication(freshGenesis(), null, "invalid", snapshot()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void knownSiblingSourceKindsAreNonPolicyAndUnknownKindsStillDeny() {
    assertThat(RealmPolicySource.isPolicyRevision(revision(BrandingSource.REVISION_KIND)))
        .isFalse();
    assertThat(RealmPolicySource.isPolicyRevision(revision(TemplateConfigSource.REVISION_KIND)))
        .isFalse();
    assertThatThrownBy(() -> RealmPolicySource.isPolicyRevision(revision("UNKNOWN_SOURCE")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported Game Design owner revision kind");
  }

  private static RealmPolicyApplication freshApplication() {
    return new RealmPolicyApplication(freshGenesis(), null, "0", snapshot());
  }

  private static RealmPolicyGenesis freshGenesis() {
    return new RealmPolicyGenesis(target(), GENESIS_ID, "17");
  }

  private static RealmPolicySnapshot snapshot() {
    DraftCommitBinding binding =
        DraftCommitBinding.create(
            target(),
            REQUEST_ID,
            COMMIT_ID,
            "base-commit-0",
            List.of(new RevisionPayload("0", REVISION_ID, Owner.GAME_DESIGN_CONTROL_PLANE, "{}")),
            List.of(
                new AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    RealmPolicySource.SCOPE,
                    VERSION_ID.toString(),
                    RealmPolicySource.SCOPE,
                    "effective",
                    "0")));
    return new RealmPolicySnapshot(binding, "1", List.of());
  }

  private static TargetProof target() {
    return new TargetProof(
        TENANT_ID, VERSION_ID, 23L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW");
  }

  private static RevisionPayload revision(String kind) {
    return new RevisionPayload(
        "0", REVISION_ID, Owner.GAME_DESIGN_CONTROL_PLANE, "{\"revisionKind\":\"" + kind + "\"}");
  }

  private static byte[] frames(byte[]... values) {
    var output = new ByteArrayOutputStream();
    try {
      var data = new DataOutputStream(output);
      for (byte[] value : values) {
        data.writeInt(value.length);
        data.write(value);
      }
      return output.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static byte[] text(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
