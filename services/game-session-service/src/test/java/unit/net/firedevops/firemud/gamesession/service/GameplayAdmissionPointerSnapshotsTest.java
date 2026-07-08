package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;

class GameplayAdmissionPointerSnapshotsTest {
  @Test
  void admittedRoutingBundleReturnsNormalizedBundleWhenRoutingClaimsAreComplete() {
    SessionContext completeContext =
        new SessionContext(
            22L, 41L, 0L, null, 123L, null, 1L, "R-1021", null, null, 1L, "world", "realm", 17L,
            null);

    GameplayAdmissionPointerSnapshots.AdmittedRoutingBundle bundle =
        GameplayAdmissionPointerSnapshots.admittedRoutingBundle(completeContext);

    assertThat(bundle.isPresent()).isTrue();
    assertThat(bundle.worldSlug()).isEqualTo("world");
    assertThat(bundle.realmSlug()).isEqualTo("realm");
    assertThat(bundle.pointerVersion()).isEqualTo("17");
    assertThat(GameplayAdmissionPointerSnapshots.hasPartialAdmittedRoutingBundle(completeContext))
        .isFalse();
  }

  @Test
  void admittedRoutingBundleReturnsEmptyForPartialRoutingClaims() {
    SessionContext partialRouting =
        new SessionContext(
            22L, 41L, 0L, null, 123L, null, 1L, "R-1021", null, null, 1L, "world", null, 17L, null);

    GameplayAdmissionPointerSnapshots.AdmittedRoutingBundle bundle =
        GameplayAdmissionPointerSnapshots.admittedRoutingBundle(partialRouting);

    assertThat(bundle)
        .isEqualTo(new GameplayAdmissionPointerSnapshots.AdmittedRoutingBundle(null, null, null));
    assertThat(GameplayAdmissionPointerSnapshots.hasPartialAdmittedRoutingBundle(partialRouting))
        .isTrue();
  }

  @Test
  void admittedRoutingBundleReturnsEmptyWhenRoutingClaimsAreAbsent() {
    SessionContext missingRouting =
        new SessionContext(
            22L, 41L, 0L, null, 123L, null, 1L, "R-1021", null, null, 1L, null, null, 0L, null);

    GameplayAdmissionPointerSnapshots.AdmittedRoutingBundle bundle =
        GameplayAdmissionPointerSnapshots.admittedRoutingBundle(missingRouting);

    assertThat(bundle)
        .isEqualTo(new GameplayAdmissionPointerSnapshots.AdmittedRoutingBundle(null, null, null));
    assertThat(GameplayAdmissionPointerSnapshots.hasPartialAdmittedRoutingBundle(missingRouting))
        .isFalse();
  }

  @Test
  void requireAdmittedRoutingBundleRejectsPartialRoutingClaimsWithCallerSpecificMessage() {
    SessionContext partialRouting =
        new SessionContext(
            22L, 41L, 0L, null, 123L, null, 1L, "R-1021", null, null, 1L, "world", null, 17L, null);

    assertThatThrownBy(
            () ->
                GameplayAdmissionPointerSnapshots.requireAdmittedRoutingBundle(
                    partialRouting, "Game Logic request"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Incomplete admitted routing bundle on session context for Game Logic request");
  }

  @Test
  void requireAdmittedRoutingBundleRejectsMissingRoutingClaimsWithCallerSpecificMessage() {
    SessionContext missingRouting =
        new SessionContext(
            22L, 41L, 0L, null, 123L, null, 1L, "R-1021", null, null, 1L, null, null, 0L, null);

    assertThatThrownBy(
            () ->
                GameplayAdmissionPointerSnapshots.requireAdmittedRoutingBundle(
                    missingRouting, "Entity Management request"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "Missing admitted routing bundle on session context for Entity Management request");
  }

  @Test
  void matchesCurrentRuntimeTargetRejectsWhenPointerContextIsNonPositive() {
    GameplayAdmissionPointerSnapshot pointer =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "Demo",
            "production",
            "Production",
            1L,
            11L,
            7L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW");

    assertThat(
            GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(
                List.of(pointer), 1L, 11L, "demo", "production", 0L))
        .isFalse();
  }

  @Test
  void matchesCurrentRuntimeTargetRequiresCompleteRoutingSlugInputs() {
    GameplayAdmissionPointerSnapshot pointer =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "Demo",
            "production",
            "Production",
            1L,
            11L,
            7L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW");

    assertThat(
            GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(
                List.of(pointer), 1L, 11L, " ", "production", 7L))
        .isFalse();
  }

  @Test
  void matchesCurrentRuntimeTargetRejectsIfPointerBundleIsNotSingular() {
    GameplayAdmissionPointerSnapshot currentPointer =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "Demo",
            "production",
            "Production",
            1L,
            11L,
            7L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW");
    GameplayAdmissionPointerSnapshot stalePointer =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "Demo",
            "production",
            "Production",
            1L,
            11L,
            6L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW");

    assertThat(
            GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(
                List.of(currentPointer, stalePointer), 1L, 11L, "demo", "production", 7L))
        .isFalse();
  }

  @Test
  void matchesCurrentRuntimeTargetReturnsTrueForExactMatch() {
    GameplayAdmissionPointerSnapshot pointer =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "Demo",
            "production",
            "Production",
            1L,
            11L,
            7L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW");

    assertThat(
            GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(
                List.of(pointer), 1L, 11L, "demo", "production", 7L))
        .isTrue();
  }

  @Test
  void matchesCurrentRuntimeTargetAcceptsCaseInsensitiveWorldAndRealmIdentity() {
    GameplayAdmissionPointerSnapshot pointer =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "Demo",
            "production",
            "Production",
            1L,
            11L,
            7L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW");

    assertThat(
            GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(
                List.of(pointer), 1L, 11L, "DEMO", "PRODUCTION", 7L))
        .isTrue();
  }

  @Test
  void matchesCurrentRuntimeTargetRejectsWhenPlayableStateScopeDoesNotMatch() {
    GameplayAdmissionPointerSnapshot pointer =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "Demo",
            "production",
            "Production",
            1L,
            11L,
            7L,
            true,
            true,
            false,
            "OPEN",
            "ALLOW_NEW");

    assertThat(
            GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(
                List.of(pointer), 1L, 11L, "demo", "production", 7L, "SHARED"))
        .isFalse();
  }

  @Test
  void matchesCurrentRuntimeTargetAcceptsBlankPlayableStateScopeAsStateAgnostic() {
    GameplayAdmissionPointerSnapshot pointer =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "Demo",
            "production",
            "Production",
            1L,
            11L,
            7L,
            true,
            true,
            false,
            "OPEN",
            "ALLOW_NEW");

    assertThat(
            GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(
                List.of(pointer), 1L, 11L, "demo", "production", 7L, " "))
        .isTrue();
    assertThat(
            GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(
                List.of(pointer), 1L, 11L, "demo", "production", 7L, null))
        .isTrue();
  }

  @Test
  void singularCompletePointerFailsClosedWhenMixedCompleteAndIncompleteRowsShareRuntime() {
    GameplayAdmissionPointerSnapshot completePointer =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "Demo",
            "production",
            "Production",
            1L,
            11L,
            7L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW");
    GameplayAdmissionPointerSnapshot incompletePointer =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "Demo",
            "event",
            "Event",
            1L,
            11L,
            0L,
            true,
            false,
            false,
            "SHARED",
            "ALLOW_NEW");

    assertThat(
            GameplayAdmissionPointerSnapshots.singularCompletePointer(
                List.of(completePointer, incompletePointer)))
        .isEmpty();
  }

  @Test
  void singularCompletePointerReturnsOnlyCompleteSingleRuntimePointer() {
    GameplayAdmissionPointerSnapshot pointer =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "Demo",
            "production",
            "Production",
            1L,
            11L,
            7L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW");

    assertThat(GameplayAdmissionPointerSnapshots.singularCompletePointer(List.of(pointer)))
        .contains(pointer);
  }

  @Test
  void sameBootstrapRouteRejectsWhenRoutingBundleCompletenessDiffers() {
    SessionContext existing = bootstrapShell(22L, 1L, "demo", "production", 7L);
    SessionContext incoming = bootstrapShell(22L, 1L, "demo", "production", 0L);

    assertThat(GameplayAdmissionPointerSnapshots.sameBootstrapRoute(existing, incoming)).isFalse();
  }

  @Test
  void sameBootstrapRouteRejectsWhenWorldOrRealmIdentityChanges() {
    SessionContext existing = bootstrapShell(22L, 1L, "demo", "production", 7L);
    SessionContext incomingWorldChange = bootstrapShell(22L, 1L, "sandbox", "production", 7L);
    SessionContext incomingRealmChange = bootstrapShell(22L, 1L, "demo", "event", 7L);

    assertThat(GameplayAdmissionPointerSnapshots.sameBootstrapRoute(existing, incomingWorldChange))
        .isFalse();
    assertThat(GameplayAdmissionPointerSnapshots.sameBootstrapRoute(existing, incomingRealmChange))
        .isFalse();
  }

  @Test
  void sameBootstrapRouteAcceptsCaseInsensitiveWorldAndRealmIdentity() {
    SessionContext existing = bootstrapShell(22L, 1L, "demo", "production", 7L);
    SessionContext incoming = bootstrapShell(22L, 1L, "DEMO", "PRODUCTION", 7L);

    assertThat(GameplayAdmissionPointerSnapshots.sameBootstrapRoute(existing, incoming)).isTrue();
  }

  @Test
  void sameBootstrapRouteRejectsFirstPartyConnectContextWhenRoutingIdentityChanges() {
    FirstPartyConnectContext existing =
        new FirstPartyConnectContext(
            123L, 22L, "demo", "production", 1L, 7L, "scope-1", "jti-1", "req-1", "gw-1");

    assertThat(
            GameplayAdmissionPointerSnapshots.sameBootstrapRoute(
                existing, 22L, 1L, "sandbox", "production", 7L))
        .isFalse();
  }

  @Test
  void sameBootstrapRouteAcceptsFirstPartyConnectContextWithCaseInsensitiveRoutingIdentity() {
    FirstPartyConnectContext existing =
        new FirstPartyConnectContext(
            123L, 22L, "demo", "production", 1L, 7L, "scope-1", "jti-1", "req-1", "gw-1");

    assertThat(
            GameplayAdmissionPointerSnapshots.sameBootstrapRoute(
                existing, 22L, 1L, "DEMO", "PRODUCTION", 7L))
        .isTrue();
  }

  private static SessionContext bootstrapShell(
      long tenantId,
      long bootstrapGameInstanceId,
      String worldSlug,
      String realmSlug,
      long pointerVersion) {
    return new SessionContext(
        1L,
        tenantId,
        0L,
        null,
        0L,
        null,
        0L,
        null,
        null,
        null,
        bootstrapGameInstanceId,
        worldSlug,
        realmSlug,
        pointerVersion,
        null,
        null,
        null);
  }
}
