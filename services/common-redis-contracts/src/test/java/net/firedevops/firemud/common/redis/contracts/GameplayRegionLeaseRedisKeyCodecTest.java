package net.firedevops.firemud.common.redis.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameplayRegionLeaseRedisKeyCodecTest {
  @Test
  void usesFullScopeAndBuildsOneSlotForLeaseAndMetadata() {
    var keys = GameplayRegionLeaseRedisKeyCodec.keys(7L, 19L, "room:starter-village");

    assertThat(keys.tenantRegionTag()).matches("[0-9a-f]{64}");
    assertThat(keys.leaseKey()).isEqualTo("tick-executor-lease:{" + keys.tenantRegionTag() + "}");
    assertThat(keys.metadataKey()).isEqualTo("tick:{" + keys.tenantRegionTag() + "}:meta");
    assertThat(keys.sessionBindingPrefix())
        .isEqualTo("tick:{" + keys.tenantRegionTag() + "}:session-binding:");
    UUID entityId = UUID.fromString("90000000-0000-4000-8000-000000000009");
    assertThat(keys.sessionBindingKey(entityId)).isEqualTo(keys.sessionBindingPrefix() + entityId);
    assertThat(keys.sessionBindingPendingKey(entityId))
        .isEqualTo(keys.sessionBindingKey(entityId) + ":pending")
        .isNotEqualTo(keys.sessionBindingKey(entityId));
    assertThat(GameplayRegionLeaseRedisKeyCodec.tenantRegionTag(7L, 19L, "room:other"))
        .isNotEqualTo(keys.tenantRegionTag());
    assertThat(GameplayRegionLeaseRedisKeyCodec.tenantRegionTag(7L, 20L, "room:starter-village"))
        .isNotEqualTo(keys.tenantRegionTag());
    assertThat(GameplayRegionLeaseRedisKeyCodec.tenantRegionTag(8L, 19L, "room:starter-village"))
        .isNotEqualTo(keys.tenantRegionTag());
    GameplayRegionLeaseRedisKeyCodec.requireOneRegionSlot(keys);
  }

  @Test
  void rejectsInvalidOrPartialScope() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GameplayRegionLeaseRedisKeyCodec.keys(0L, 1L, "room:a"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GameplayRegionLeaseRedisKeyCodec.keys(1L, 0L, "room:a"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GameplayRegionLeaseRedisKeyCodec.keys(1L, 1L, " room:a"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GameplayRegionLeaseRedisKeyCodec.keys(1L, 1L, "room:" + (char) 0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GameplayRegionLeaseRedisKeyCodec.keys(1L, 1L, ""));
    var keys = GameplayRegionLeaseRedisKeyCodec.keys(1L, 1L, "region:a");
    assertThatIllegalArgumentException().isThrownBy(() -> keys.sessionBindingKey(new UUID(0L, 0L)));
  }
}
