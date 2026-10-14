package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.Optional;
import net.firedevops.firemud.gamesession.support.FirstPartyReceivingFixture;
import org.junit.jupiter.api.Test;

class FirstPartyConnectContextResolutionTest {
  @Test
  void completeSessionRetainedBytesAreVerifiedWhenShortRegistryEntryExpires() {
    var fixture = new FirstPartyReceivingFixture();
    var context = fixture.context();
    var session =
        new SessionContext(1L, 22L, null, 0L, 0L, (String) null)
            .withFirstPartyConnectContext(context);
    var registry = mock(FirstPartyConnectContextRegistry.class);
    when(registry.find(1L)).thenReturn(Optional.empty());
    when(registry.requireRetained(context))
        .thenAnswer(invocation -> fixture.service.requireRetained(invocation.getArgument(0)));
    var resolution = FirstPartyConnectContextResolution.resolve(1L, session, registry);
    assertThat(resolution.invalid()).isFalse();
    assertThat(resolution.connectContext()).contains(context);
    when(fixture.routes.readCurrentTarget(FirstPartyReceivingFixture.TENANT)).thenReturn(null);
    assertThat(FirstPartyConnectContextResolution.resolve(1L, session, registry).invalid())
        .isTrue();
  }

  @Test
  void registryCorruptionOrUnavailabilityNeverBecomesAbsence() {
    var registry = mock(FirstPartyConnectContextRegistry.class);
    when(registry.find(1L)).thenThrow(new IllegalStateException("corrupt"));
    var resolution = FirstPartyConnectContextResolution.resolve(1L, null, registry);
    assertThat(resolution.invalid()).isTrue();
    assertThat(resolution.connectContext()).isEmpty();
  }
}
