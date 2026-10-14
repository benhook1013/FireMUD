package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorldAuthoredGraphSnapshotCaptureSerializationTest {
  @Test
  void ownedAffectedTuplesKeepStableDefaultJsonBytes() {
    var tuple =
        new WorldAuthoredGraphSnapshot.OwnedAffectedTuple(
            "WORLD_MANAGEMENT", "REGION", "region-1", "", "", "7");

    assertThat(WorldAuthoredGraphSnapshotCapture.encodeTuples(List.of(tuple)))
        .isEqualTo(
            "[{\"owner\":\"WORLD_MANAGEMENT\",\"aggregateType\":\"REGION\","
                + "\"aggregateId\":\"region-1\",\"scopeType\":\"\","
                + "\"scopeId\":\"\",\"expectedEpoch\":\"7\"}]");
  }
}
