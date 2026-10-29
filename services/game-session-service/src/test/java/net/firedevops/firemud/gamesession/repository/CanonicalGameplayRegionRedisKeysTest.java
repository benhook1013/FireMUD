package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalGameplayRegionRedisKeysTest {
  private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID FIRST_GAME_INSTANCE_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SECOND_GAME_INSTANCE_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID REGION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

  @Test
  void projectionIsStableAndIncludesTheFullOwnerScope() {
    String first =
        CanonicalGameplayRegionRedisKeys.tenantRegionTag(
            TENANT_ID, FIRST_GAME_INSTANCE_ID, REGION_ID);
    String exactRetry =
        CanonicalGameplayRegionRedisKeys.tenantRegionTag(
            TENANT_ID, FIRST_GAME_INSTANCE_ID, REGION_ID);
    String secondGame =
        CanonicalGameplayRegionRedisKeys.tenantRegionTag(
            TENANT_ID, SECOND_GAME_INSTANCE_ID, REGION_ID);

    assertThat(first)
        .isEqualTo(
            "r1-11111111111141118111111111111111-22222222222242228222222222222222-44444444444444448444444444444444");
    assertThat(exactRetry).isEqualTo(first);
    assertThat(secondGame).isNotEqualTo(first);
    assertThat(CanonicalGameplayRegionRedisKeys.lease(TENANT_ID, FIRST_GAME_INSTANCE_ID, REGION_ID))
        .isEqualTo("tick-executor-lease:{" + first + "}");
  }

  @Test
  void projectionRejectsNilScopeIdentifiers() {
    assertThatThrownBy(
            () ->
                CanonicalGameplayRegionRedisKeys.tenantRegionTag(
                    TENANT_ID, new UUID(0L, 0L), REGION_ID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gameInstanceId");
  }
}
