package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

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
            .addCurrentAdmissionPointers(
                AdmissionPointerControlPlaneEntry.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setPointerVersion(7L)
                    .build())
            .build();

    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.fromRuntimeState(runtimeState);

    assertThat(routingBundle.isPresent()).isTrue();
    assertThat(routingBundle.worldSlug()).isEqualTo("demo");
    assertThat(routingBundle.realmSlug()).isEqualTo("production");
    assertThat(routingBundle.parsedPointerVersion()).isEqualTo(7L);
  }
}
