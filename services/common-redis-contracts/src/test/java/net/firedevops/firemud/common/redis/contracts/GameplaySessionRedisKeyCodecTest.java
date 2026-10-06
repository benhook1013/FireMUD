package net.firedevops.firemud.common.redis.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameplaySessionRedisKeyCodecTest {
  private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID NAMESPACE = UUID.fromString("00000000-0000-0000-0000-000000000004");
  private static final UUID CHARACTER = UUID.fromString("00000000-0000-0000-0000-000000000005");

  @Test
  void derivesStableOpaqueVersionedTagAndDocumentedKeyPrefixes() {
    var first =
        GameplaySessionRedisKeyCodec.keys(TENANT, 47L, SESSION, NAMESPACE, CHARACTER, ACCOUNT);
    var retry =
        GameplaySessionRedisKeyCodec.keys(TENANT, 47L, SESSION, NAMESPACE, CHARACTER, ACCOUNT);

    assertThat(first).isEqualTo(retry);
    assertThat(first.tenantGameplayTag()).matches("gpt1-[0-9a-f]{64}");
    assertThat(first.sessionKey()).startsWith("session:game:{" + first.tenantGameplayTag() + "}:");
    assertThat(first.characterIndexKey())
        .startsWith("session:game:index:character:{" + first.tenantGameplayTag() + "}:");
    assertThat(first.accountTenantIndexKey())
        .startsWith("session:game:index:account-tenant:{" + first.tenantGameplayTag() + "}:");
    assertThat(first.tenantIndexKey())
        .isEqualTo("session:game:index:tenant:{" + first.tenantGameplayTag() + "}");
    GameplaySessionRedisKeyCodec.requireOneTenantGameplaySlot(first);
  }

  @Test
  void separatesTenantSlotsAndRejectsMalformedIdentity() {
    UUID otherTenant = UUID.fromString("00000000-0000-0000-0000-000000000006");
    var first =
        GameplaySessionRedisKeyCodec.keys(TENANT, 47L, SESSION, NAMESPACE, CHARACTER, ACCOUNT);
    var other =
        GameplaySessionRedisKeyCodec.keys(otherTenant, 47L, SESSION, NAMESPACE, CHARACTER, ACCOUNT);

    assertThat(other.tenantGameplayTag()).isNotEqualTo(first.tenantGameplayTag());
    assertThatThrownBy(
            () ->
                GameplaySessionRedisKeyCodec.keys(
                    new UUID(0L, 0L), 47L, SESSION, NAMESPACE, CHARACTER, ACCOUNT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                GameplaySessionRedisKeyCodec.keys(
                    TENANT, 0L, SESSION, NAMESPACE, CHARACTER, ACCOUNT))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
