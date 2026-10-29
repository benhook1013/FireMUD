package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.Selector;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

  @ParameterizedTest
  @CsvSource({
    "REQUIRED,,,20,true",
    "REQUIRED,member,,20,false",
    "REQUIRED,,10,10,false",
    "REQUIRED,,,10,false",
    "PRESENT_AWAITING_COVERAGE,member,10,10,true",
    "PRESENT_AWAITING_COVERAGE,other,10,10,false",
    "PRESENT_AWAITING_COVERAGE,,10,10,false",
    "PRESENT_AWAITING_COVERAGE,member,,20,false",
    "PRESENT_AWAITING_COVERAGE,member,0,0,false",
    "PRESENT_AWAITING_COVERAGE,member,-1,-1,false",
    "PRESENT_AWAITING_COVERAGE,member,10,20,false",
    "ACKNOWLEDGED,member,10,10,false"
  })
  void originalAccountProjectionRequiresExactSupportedShapeAndRevisionCorrelation(
      String state,
      String member,
      String originalRevision,
      String projectionRevision,
      boolean accepted) {
    Record repair = mock();
    Record obligation = mock();
    when(repair.get("original_projection_state", String.class)).thenReturn(state);
    when(repair.get("original_projection_member", String.class)).thenReturn(member);
    when(repair.get("original_projection_inventory_revision", BigDecimal.class))
        .thenReturn(originalRevision == null ? null : new BigDecimal(originalRevision));
    when(repair.get("completed_inventory_revision", BigDecimal.class))
        .thenReturn(BigDecimal.valueOf(20));
    when(obligation.get("projection_inventory_revision", BigDecimal.class))
        .thenReturn(new BigDecimal(projectionRevision));

    assertThat(
            CanonicalGameplayOriginalNewFinalAdmissionReadRepository
                .originalAccountProjectionMatchesRepair(obligation, repair, "member"))
        .isEqualTo(accepted);
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
