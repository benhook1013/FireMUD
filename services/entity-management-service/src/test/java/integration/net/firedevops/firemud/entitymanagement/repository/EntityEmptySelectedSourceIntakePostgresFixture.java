package net.firedevops.firemud.entitymanagement.repository;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerEmptySourceInputs;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.entitymanagement.testing.ControlledSelectedSourceIntakeFixtureBuilder;

/** Integration-test facade preserving the selected-source fixture's established defaults. */
public final class EntityEmptySelectedSourceIntakePostgresFixture {
  static final String NAMESPACE = "test";
  static final UUID TENANT = id("11111111-1111-4111-8111-111111111111");
  static final UUID VERSION = id("22222222-2222-4222-8222-222222222222");
  static final UUID ACTOR = id("99999999-9999-4999-8999-999999999999");

  private EntityEmptySelectedSourceIntakePostgresFixture() {}

  public static Fixture create(String sourceEpoch) {
    return create(sourceEpoch, selected(id("44444444-4444-4444-8444-444444444444")));
  }

  public static Fixture create(String sourceEpoch, DraftCommitBinding selected) {
    return create(sourceEpoch, selected, NAMESPACE);
  }

  public static Fixture create(String sourceEpoch, DraftCommitBinding selected, String namespace) {
    var shared =
        ControlledSelectedSourceIntakeFixtureBuilder.create(sourceEpoch, selected, namespace);
    return new Fixture(
        shared.authorization(), shared.freezeEvidence(), shared.worldEvidence(), shared.inputs());
  }

  static Fixture withFreshWorldReadCorrelation(Fixture original) {
    var request =
        SelectedOwnerWorldInventoryReadEvidence.create(
            NAMESPACE, original.authorization(), original.freezeEvidence());
    var evidence =
        new SelectedOwnerWorldInventoryReadEvidence(request, original.worldEvidence().inventory());
    return new Fixture(
        original.authorization(),
        original.freezeEvidence(),
        evidence,
        new SelectedOwnerEmptySourceInputs(original.authorization(), evidence));
  }

  private static DraftCommitBinding selected(UUID selectedCommitId) {
    return DraftCommitBinding.create(
        target(),
        id("33333333-3333-4333-8333-333333333333"),
        selectedCommitId,
        "base-commit-0",
        List.of(
            new RevisionPayload(
                "0", id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab"), Owner.WORLD_MANAGEMENT, "{}")),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.WORLD_MANAGEMENT, "REGION", "region-1", "AGGREGATE", "region-1", "0")));
  }

  private static TargetProof target() {
    return new TargetProof(TENANT, VERSION, 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW");
  }

  private static UUID id(String value) {
    return UUID.fromString(value);
  }

  public record Fixture(
      SelectedOwnerIntakeAuthorizationBinding authorization,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence,
      SelectedOwnerWorldInventoryReadEvidence worldEvidence,
      SelectedOwnerEmptySourceInputs inputs) {}
}
