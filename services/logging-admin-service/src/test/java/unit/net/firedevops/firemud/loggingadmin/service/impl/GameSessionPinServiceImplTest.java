package net.firedevops.firemud.loggingadmin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.GetGameSessionPinConvergenceResponse;
import net.firedevops.firemud.gamesession.v1.GetPinnedScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class GameSessionPinServiceImplTest {
  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void getPinnedScriptPatchVersionReturnsCanonicalPinMetadata() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getPinnedScriptPatchVersion(1L, 7L))
        .thenReturn(
            GetPinnedScriptPatchVersionResponse.newBuilder()
                .setPinnedScriptPatchVersion("patch-9")
                .setPinnedAtMs(Instant.parse("2026-04-22T00:00:00Z").toEpochMilli())
                .setPinnedBy("operator-1")
                .setControlPlaneRequestId("req-99")
                .setPublication(publication())
                .build());
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    GameSessionPinServiceImpl service = new GameSessionPinServiceImpl(gameSessionClient);

    var result = service.getPinnedScriptPatchVersion(1L, 7L);

    assertThat(result.pinnedScriptPatchVersion()).isEqualTo("patch-9");
    assertThat(result.controlPlaneRequestId()).isEqualTo("req-99");
    assertThat(result.publication().versionId()).isEqualTo(17L);
  }

  @Test
  void getGameSessionPinConvergenceRejectsMismatchedTenant() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameSessionPinConvergence(1L, 7L))
        .thenReturn(
            GetGameSessionPinConvergenceResponse.newBuilder()
                .setTenantId("8")
                .setGameInstanceId("7")
                .build());
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    GameSessionPinServiceImpl service = new GameSessionPinServiceImpl(gameSessionClient);

    assertThatThrownBy(() -> service.getGameSessionPinConvergence(1L, 7L))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("500 INTERNAL_SERVER_ERROR");
  }

  @Test
  void getGameSessionPinConvergenceReturnsCanonicalObservation() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameSessionPinConvergence(1L, 7L))
        .thenReturn(
            GetGameSessionPinConvergenceResponse.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("7")
                .setObservedPinnedScriptPatchVersion("patch-9")
                .setLastObservedControlPlaneRequestId("req-99")
                .setObservedAtMs(Instant.parse("2026-04-22T00:00:00Z").toEpochMilli())
                .setIsStale(true)
                .setPublication(publication())
                .build());
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    GameSessionPinServiceImpl service = new GameSessionPinServiceImpl(gameSessionClient);

    var result = service.getGameSessionPinConvergence(1L, 7L);

    assertThat(result.tenantId()).isEqualTo(1L);
    assertThat(result.gameInstanceId()).isEqualTo(7L);
    assertThat(result.stale()).isTrue();
    assertThat(result.publication().scriptPatchVersion()).isEqualTo("patch-9");
  }

  private ScriptPatchPublicationLink publication() {
    return ScriptPatchPublicationLink.newBuilder()
        .setScriptPatchVersion("patch-9")
        .setVersionId(17L)
        .setBaseVersionId(7L)
        .setPublicationState(
            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                .VERSION_LIFECYCLE_STATE_PUBLISHED)
        .setLastChangedAtMs(Instant.parse("2026-04-22T00:00:01Z").toEpochMilli())
        .build();
  }
}
