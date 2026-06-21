package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import org.junit.jupiter.api.Test;

class GameplayAdmissionPointerSnapshotsTest {
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
}
