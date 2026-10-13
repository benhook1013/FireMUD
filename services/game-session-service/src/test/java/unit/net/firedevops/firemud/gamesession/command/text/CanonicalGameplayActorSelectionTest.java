package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient;
import org.junit.jupiter.api.Test;
import support.net.firedevops.firemud.gamesession.PublishedRealmPolicyEvidenceFixture;

class CanonicalGameplayActorSelectionTest {
  private static final UUID ACCOUNT_UUID =
      PublishedRealmPolicyEvidenceFixture.uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID FIRST_ACTOR_UUID =
      PublishedRealmPolicyEvidenceFixture.uuid("77777777-7777-4777-8777-777777777777");
  private static final UUID SECOND_ACTOR_UUID =
      PublishedRealmPolicyEvidenceFixture.uuid("99999999-9999-4999-8999-999999999999");

  @Test
  void automaticallySelectsTheOnlyActorAndCarriesItsExactSnapshotBinding() {
    var route = PublishedRealmPolicyEvidenceFixture.canonicalPublishedRoute();
    var snapshot =
        PublishedRealmPolicyEvidenceFixture.rosterSnapshot(
            route,
            ACCOUNT_UUID,
            List.of(new CanonicalGameplayRosterClient.RosterActor(FIRST_ACTOR_UUID, "Pilot One")));
    var validated =
        CanonicalGameplayActorSelection.validate(snapshot, ACCOUNT_UUID, route).orElseThrow();

    var selection = CanonicalGameplayActorSelection.select(validated, null);

    assertThat(selection)
        .isInstanceOfSatisfying(
            CanonicalGameplayActorSelection.Selected.class,
            selected -> {
              assertThat(selected.characterUuid()).isEqualTo(FIRST_ACTOR_UUID);
              assertThat(selected.canonicalAccountUuid()).isEqualTo(ACCOUNT_UUID);
              assertThat(selected.rosterSnapshotUuid()).isEqualTo(snapshot.snapshotUuid());
              assertThat(selected.rosterSnapshotDigest()).isEqualTo(snapshot.snapshotDigest());
              assertThat(selected.target()).isEqualTo(snapshot.target());
              assertThat(selected.ordinal()).isEqualTo(1);
            });
  }

  @Test
  void deniesZeroActorsAndRequiresAnOrdinalForMultipleActors() {
    var route = PublishedRealmPolicyEvidenceFixture.canonicalPublishedRoute();
    var emptySnapshot =
        PublishedRealmPolicyEvidenceFixture.rosterSnapshot(route, ACCOUNT_UUID, List.of());
    var emptyRoster =
        CanonicalGameplayActorSelection.validate(emptySnapshot, ACCOUNT_UUID, route).orElseThrow();
    assertThat(CanonicalGameplayActorSelection.select(emptyRoster, null))
        .isEqualTo(
            new CanonicalGameplayActorSelection.Denied(
                CanonicalGameplayActorSelection.Denial.NO_PRESEEDED_ACTOR));

    var snapshot =
        PublishedRealmPolicyEvidenceFixture.rosterSnapshot(
            route,
            ACCOUNT_UUID,
            List.of(
                new CanonicalGameplayRosterClient.RosterActor(FIRST_ACTOR_UUID, "Pilot One"),
                new CanonicalGameplayRosterClient.RosterActor(SECOND_ACTOR_UUID, "Pilot Two")));
    var roster =
        CanonicalGameplayActorSelection.validate(snapshot, ACCOUNT_UUID, route).orElseThrow();
    assertThat(CanonicalGameplayActorSelection.select(roster, null))
        .isEqualTo(
            new CanonicalGameplayActorSelection.SelectionRequired(
                List.of(
                    new CanonicalGameplayActorSelection.Choice(1, "Pilot One"),
                    new CanonicalGameplayActorSelection.Choice(2, "Pilot Two"))));
    assertThat(CanonicalGameplayActorSelection.select(roster, "2"))
        .isInstanceOfSatisfying(
            CanonicalGameplayActorSelection.Selected.class,
            selected -> assertThat(selected.characterUuid()).isEqualTo(SECOND_ACTOR_UUID));
  }

  @Test
  void rejectsUuidNameAndNonCanonicalOrdinalSelectors() {
    var route = PublishedRealmPolicyEvidenceFixture.canonicalPublishedRoute();
    var snapshot =
        PublishedRealmPolicyEvidenceFixture.rosterSnapshot(
            route,
            ACCOUNT_UUID,
            List.of(new CanonicalGameplayRosterClient.RosterActor(FIRST_ACTOR_UUID, "Pilot One")));
    var roster =
        CanonicalGameplayActorSelection.validate(snapshot, ACCOUNT_UUID, route).orElseThrow();

    for (String selector : List.of(FIRST_ACTOR_UUID.toString(), "Pilot One", "01", "+1", "2")) {
      assertThat(CanonicalGameplayActorSelection.select(roster, selector))
          .isInstanceOf(CanonicalGameplayActorSelection.Denied.class);
    }
  }

  @Test
  void rejectsCrossAccountChangedTargetAndMalformedActors() {
    var route = PublishedRealmPolicyEvidenceFixture.canonicalPublishedRoute();
    var actor = new CanonicalGameplayRosterClient.RosterActor(FIRST_ACTOR_UUID, "Pilot One");
    var snapshot =
        PublishedRealmPolicyEvidenceFixture.rosterSnapshot(route, ACCOUNT_UUID, List.of(actor));

    assertThat(CanonicalGameplayActorSelection.validate(snapshot, SECOND_ACTOR_UUID, route))
        .isEmpty();

    CanonicalGameplayRosterTarget changedTarget =
        snapshot.target().toBuilder()
            .setPointerVersion(snapshot.target().getPointerVersion() + 1L)
            .build();
    var changedSnapshot =
        new CanonicalGameplayRosterClient.PreseededRosterSnapshot(
            ACCOUNT_UUID,
            snapshot.snapshotUuid(),
            snapshot.snapshotDigest(),
            changedTarget,
            snapshot.actors());
    assertThat(CanonicalGameplayActorSelection.validate(changedSnapshot, ACCOUNT_UUID, route))
        .isEmpty();

    var duplicateActorSnapshot =
        PublishedRealmPolicyEvidenceFixture.rosterSnapshot(
            route, ACCOUNT_UUID, List.of(actor, actor));
    assertThat(
            CanonicalGameplayActorSelection.validate(duplicateActorSnapshot, ACCOUNT_UUID, route))
        .isEmpty();

    var malformedNameSnapshot =
        PublishedRealmPolicyEvidenceFixture.rosterSnapshot(
            route,
            ACCOUNT_UUID,
            List.of(new CanonicalGameplayRosterClient.RosterActor(FIRST_ACTOR_UUID, " Pilot ")));
    assertThat(CanonicalGameplayActorSelection.validate(malformedNameSnapshot, ACCOUNT_UUID, route))
        .isEmpty();
  }
}
