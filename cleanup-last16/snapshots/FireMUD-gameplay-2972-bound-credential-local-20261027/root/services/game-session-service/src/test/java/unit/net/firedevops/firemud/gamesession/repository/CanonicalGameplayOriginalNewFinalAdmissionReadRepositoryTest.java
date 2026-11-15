package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.lettuce.core.RedisClient;
import java.time.Clock;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.Selector;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

/** Controlled boundary fixtures for the read-only repository's configured namespace binding. */
class CanonicalGameplayOriginalNewFinalAdmissionReadRepositoryTest {
  private static final String NAMESPACE = "game-session-final-read-test";
  private static final String HASH = "a".repeat(64);

  @Test
  void mismatchedRequestedNamespaceFailsBeforeSqlRedisOrTagLookup() {
    DSLContext dsl = mock();
    RedisClient redis = mock();
    CanonicalGameplayTenantGameplayTagRepository tags = mock();
    var repository =
        new CanonicalGameplayOriginalNewFinalAdmissionReadRepository(
            dsl, redis, tags, NAMESPACE, Clock.systemUTC());

    assertThatThrownBy(() -> repository.readActualOriginalFinalAdmission(selector(), "other"))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);

    verifyNoInteractions(dsl, redis, tags);
  }

  private static Selector selector() {
    return new Selector(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        1L,
        HASH,
        HASH,
        1L,
        1L,
        HASH,
        HASH,
        HASH,
        HASH);
  }
}
