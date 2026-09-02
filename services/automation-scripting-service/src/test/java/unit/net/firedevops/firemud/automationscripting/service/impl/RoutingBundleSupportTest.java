package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

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
  void fromRuntimeStateNormalizesFullPlayableScopeEnumToAuthorityShortName() {
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
                    .setStateScope("playable_state_scope_shared")
                    .build())
            .build();

    assertThat(RoutingBundleSupport.fromRuntimeState(runtimeState).isPresent()).isTrue();
  }

  @Test
  void fromRuntimeStateRejectsUnsupportedPlayableScopeValue() {
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
                    .setStateScope("PLAYABLE_STATE_SCOPE_OTHER")
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
    assertThat(current.worldSlug()).isEqualTo("demo");
    assertThat(current.realmSlug()).isEqualTo("production");
    assertThat(current.pointerVersion()).isEqualTo("17");
    assertThat(persisted.pointerVersion()).isEqualTo("17");
  }

  @Test
  void normalizeFailsClosedForMalformedPointerVersionText() {
    assertThat(RoutingBundleSupport.normalize("Demo", "Production", "bad-pointer").isPresent())
        .isFalse();
  }

  @Test
  void normalizeFailsClosedForNonPositivePointerVersionText() {
    assertThat(RoutingBundleSupport.normalize("Demo", "Production", "0").isPresent()).isFalse();
  }

  @Test
  void normalizeRuntimePointerUsesLowercaseSlugs() {
    RoutingBundleSupport.RoutingBundle bundle =
        RoutingBundleSupport.normalize("DEMO", "PRODUCTION", 17L);

    assertThat(bundle.worldSlug()).isEqualTo("demo");
    assertThat(bundle.realmSlug()).isEqualTo("production");
  }

  @Test
  void normalizeFailsClosedForMalformedSlugsInBothOverloads() {
    for (String malformedSlug :
        new String[] {"demo_world", "demo world", "demo/world", "demo-", "démo"}) {
      assertThat(RoutingBundleSupport.normalize(malformedSlug, "production", 17L).isPresent())
          .isFalse();
      assertThat(RoutingBundleSupport.normalize("demo", malformedSlug, 17L).isPresent()).isFalse();
      assertThat(RoutingBundleSupport.normalize(malformedSlug, "production", "17").isPresent())
          .isFalse();
      assertThat(RoutingBundleSupport.normalize("demo", malformedSlug, "17").isPresent()).isFalse();
    }
  }

  @Test
  void normalizeFailsClosedForOverlengthSlugsInBothOverloads() {
    String maxLengthSlug = "a".repeat(120);
    String overlengthSlug = "a".repeat(121);

    assertThat(RoutingBundleSupport.normalize(maxLengthSlug, "production", 17L).isPresent())
        .isTrue();
    assertThat(RoutingBundleSupport.normalize("demo", maxLengthSlug, 17L).isPresent()).isTrue();
    assertThat(RoutingBundleSupport.normalize(maxLengthSlug, "production", "17").isPresent())
        .isTrue();
    assertThat(RoutingBundleSupport.normalize("demo", maxLengthSlug, "17").isPresent()).isTrue();
    assertThat(RoutingBundleSupport.normalize(overlengthSlug, "production", 17L).isPresent())
        .isFalse();
    assertThat(RoutingBundleSupport.normalize("demo", overlengthSlug, 17L).isPresent()).isFalse();
    assertThat(RoutingBundleSupport.normalize(overlengthSlug, "production", "17").isPresent())
        .isFalse();
    assertThat(RoutingBundleSupport.normalize("demo", overlengthSlug, "17").isPresent()).isFalse();
  }
}
