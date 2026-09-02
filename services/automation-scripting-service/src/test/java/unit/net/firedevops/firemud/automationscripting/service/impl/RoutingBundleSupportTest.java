package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.AdmissionPointerControlPlaneEntry;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import org.junit.jupiter.api.Test;

class RoutingBundleSupportTest {
  @Test
  void fromRuntimeStateFailsClosedWhenCurrentPointerAuthorityIsAbsent() {
    GameInstanceRuntimeState runtimeState =
        GameInstanceRuntimeState.newBuilder()
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .setPointerVersion(7L)
            .build();

    assertThat(RoutingBundleSupport.fromRuntimeState(runtimeState).isPresent()).isFalse();
  }

  @Test
  void fromRuntimeStateUsesSingularCurrentPointerAuthority() {
    GameInstanceRuntimeState runtimeState =
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(
                AdmissionPointerControlPlaneEntry.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setTenantId("1")
                    .setGameInstanceId("game-1")
                    .setPointerVersion(7L)
                    .setStateScope("SHARED")
                    .build())
            .build();

    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.fromRuntimeState(runtimeState);

    assertThat(routingBundle.isPresent()).isTrue();
    assertThat(routingBundle.worldSlug()).isEqualTo("demo");
    assertThat(routingBundle.realmSlug()).isEqualTo("production");
    assertThat(routingBundle.parsedPointerVersion()).isEqualTo(7L);
    assertThat(routingBundle.pointerVersion()).isEqualTo("7");
  }

  @Test
  void fromRuntimeStateFailsClosedWhenMultipleCompletePointersArePresent() {
    AdmissionPointerControlPlaneEntry first =
        AdmissionPointerControlPlaneEntry.newBuilder()
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPointerVersion(7L)
            .setStateScope("SHARED")
            .build();
    AdmissionPointerControlPlaneEntry second =
        AdmissionPointerControlPlaneEntry.newBuilder()
            .setWorldSlug("other")
            .setRealmSlug("staging")
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPointerVersion(8L)
            .setStateScope("SHARED")
            .build();
    GameInstanceRuntimeState runtimeState =
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(first)
            .addCurrentAdmissionPointers(second)
            .build();

    assertThat(RoutingBundleSupport.fromRuntimeState(runtimeState).isPresent()).isFalse();
  }

  @Test
  void fromRuntimeStateFailsClosedWhenSingletonPointerIsIncomplete() {
    GameInstanceRuntimeState runtimeState =
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(
                AdmissionPointerControlPlaneEntry.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setPointerVersion(7L)
                    .build())
            .build();

    assertThat(RoutingBundleSupport.fromRuntimeState(runtimeState).isPresent()).isFalse();
  }

  @Test
  void fromRuntimeStateFailsClosedWhenPointerTenantContradictsRuntimeRoot() {
    GameInstanceRuntimeState runtimeState =
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(
                AdmissionPointerControlPlaneEntry.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setTenantId("2")
                    .setGameInstanceId("game-1")
                    .setPointerVersion(7L)
                    .setStateScope("SHARED")
                    .build())
            .build();

    assertThat(RoutingBundleSupport.fromRuntimeState(runtimeState).isPresent()).isFalse();
  }

  @Test
  void fromRuntimeStateFailsClosedWhenPointerInstanceContradictsRuntimeRoot() {
    GameInstanceRuntimeState runtimeState =
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(
                AdmissionPointerControlPlaneEntry.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setTenantId("1")
                    .setGameInstanceId("game-2")
                    .setPointerVersion(7L)
                    .setStateScope("SHARED")
                    .build())
            .build();

    assertThat(RoutingBundleSupport.fromRuntimeState(runtimeState).isPresent()).isFalse();
  }

  @Test
  void fromRuntimeStateFailsClosedWhenPointerScopeContradictsRuntimeRoot() {
    GameInstanceRuntimeState runtimeState =
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(
                AdmissionPointerControlPlaneEntry.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setTenantId("1")
                    .setGameInstanceId("game-1")
                    .setPointerVersion(7L)
                    .setStateScope("ISOLATED")
                    .build())
            .build();

    assertThat(RoutingBundleSupport.fromRuntimeState(runtimeState).isPresent()).isFalse();
  }

  @Test
  void fromRuntimeStateFailsClosedWhenPointerVersionIsNonPositive() {
    GameInstanceRuntimeState runtimeState =
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(
                AdmissionPointerControlPlaneEntry.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setTenantId("1")
                    .setGameInstanceId("game-1")
                    .setPointerVersion(0L)
                    .setStateScope("SHARED")
                    .build())
            .build();

    assertThat(RoutingBundleSupport.fromRuntimeState(runtimeState).isPresent()).isFalse();
  }

  @Test
  void sameRoutingBundleUsesCanonicalSlugsAndNumericPointerVersion() {
    RoutingBundleSupport.RoutingBundle current =
        RoutingBundleSupport.normalize("Demo", "Production", "17");
    RoutingBundleSupport.RoutingBundle persisted =
        RoutingBundleSupport.normalize("demo", "production", "017");

    assertThat(RoutingBundleSupport.sameRoutingBundle(current, persisted)).isTrue();
    assertThat(persisted.pointerVersion()).isEqualTo("017");
  }

  @Test
  void normalizeRejectsMalformedPointerVersionText() {
    assertThatThrownBy(() -> RoutingBundleSupport.normalize("demo", "production", "bad-pointer"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pointerVersion must be numeric");
  }

  @Test
  void normalizeRejectsNonPositivePointerVersionText() {
    assertThatThrownBy(() -> RoutingBundleSupport.normalize("demo", "production", "0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pointerVersion must be positive");
  }
}
