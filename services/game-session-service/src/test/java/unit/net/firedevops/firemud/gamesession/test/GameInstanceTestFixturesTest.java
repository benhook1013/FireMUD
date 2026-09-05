package net.firedevops.firemud.gamesession.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class GameInstanceTestFixturesTest {
  @Mock private JdbcTemplate jdbc;

  @Test
  void insertRunningGameInstanceSeedsCoherentScriptPinTuple() {
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);

    assertThat(GameInstanceTestFixtures.insertRunningGameInstance(jdbc, 1L, 41L, 7L)).isEqualTo(1L);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> argumentsCaptor = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForObject(sqlCaptor.capture(), eq(Long.class), argumentsCaptor.capture());

    assertThat(sqlCaptor.getValue())
        .contains(
            "script_patch_version",
            "script_pin_epoch",
            "script_patch_pinned_control_plane_request_id");
    assertThat(argumentsCaptor.getValue())
        .containsExactly(
            1L,
            "0.1.0",
            "initial",
            1L,
            "test-fixture-initial",
            7L,
            "stub-launch-descriptor",
            7L,
            700L,
            700L,
            "genrev:test:7",
            null,
            41L,
            "ACTIVE");
  }
}
