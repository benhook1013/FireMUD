package unit.net.firedevops.firemud.gamesession.binding;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import org.junit.jupiter.api.Test;

class CanonicalGameplayBindingIdentityTest {
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID NAMESPACE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID CHARACTER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID GAME_INSTANCE_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID REGION_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID ISSUER_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");

  @Test
  void acceptsCanonicalNonNilSessionUuid() {
    assertThatCode(() -> identity("88888888-8888-4888-8888-888888888888"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsNilSessionUuidConsistentWithV29StorageConstraint() {
    assertThatThrownBy(() -> identity("00000000-0000-0000-0000-000000000000"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sessionId must use canonical UUID text");
  }

  private static CanonicalGameplayBindingIdentity identity(String sessionId) {
    return new CanonicalGameplayBindingIdentity(
        ACCOUNT_ID,
        TENANT_ID,
        NAMESPACE_ID,
        CHARACTER_ID,
        "SHARED",
        GAME_INSTANCE_ID,
        1L,
        sessionId,
        REGION_ID,
        BigInteger.ONE,
        ISSUER_ID,
        BigInteger.ONE,
        BigInteger.ONE,
        BigInteger.ONE,
        BigInteger.ONE);
  }
}
