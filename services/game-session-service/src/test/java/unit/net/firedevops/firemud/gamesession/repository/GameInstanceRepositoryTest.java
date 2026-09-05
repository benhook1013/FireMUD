package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.gamesession.jooq.tables.GameInstances.GAME_INSTANCES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.RecordMapper;
import org.jooq.SelectConditionStep;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.SelectForUpdateOfStep;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSelectStep;
import org.junit.jupiter.api.Test;

class GameInstanceRepositoryTest {
  @Test
  void authoritativeInstanceReadUsesTenantQualifiedRowLock() {
    DSLContext dsl = mock(DSLContext.class);
    SelectSelectStep<Record> select = mock(SelectSelectStep.class);
    SelectJoinStep<Record> from = mock(SelectJoinStep.class);
    SelectConditionStep<Record> where = mock(SelectConditionStep.class);
    SelectForUpdateOfStep<Record> locked = mock(SelectForUpdateOfStep.class);
    when(dsl.select(any(SelectFieldOrAsterisk[].class))).thenReturn(select);
    when(select.from(GAME_INSTANCES)).thenReturn(from);
    when(from.where(any(Condition.class))).thenReturn(where);
    when(where.forUpdate()).thenReturn(locked);
    when(locked.fetchOptional(any(RecordMapper.class))).thenReturn(Optional.empty());

    GameInstanceRepository repository = new GameInstanceRepository(dsl);

    assertEquals(Optional.empty(), repository.findByTenantIdAndGameInstanceIdForUpdate(3L, 7L));

    verify(where).forUpdate();
  }

  @Test
  void scriptPinMutationsRejectBlankControlPlaneRequestIdBeforeDatabaseAccess() {
    DSLContext dsl = mock(DSLContext.class);
    GameInstanceRepository repository = new GameInstanceRepository(dsl);

    for (String controlPlaneRequestId : new String[] {null, " "}) {
      IllegalArgumentException applyError =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  repository.applyScriptPin(
                      1L,
                      7L,
                      "SET",
                      "patch-new",
                      controlPlaneRequestId,
                      "operator",
                      "pin",
                      "EXPECT_EPOCH",
                      1L));
      assertEquals("control_plane_request_id is required", applyError.getMessage());

      IllegalArgumentException failureError =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  repository.recordScriptPinFailure(
                      1L,
                      7L,
                      "SET",
                      "patch-new",
                      controlPlaneRequestId,
                      "operator",
                      "authority unavailable",
                      "EXPECT_EPOCH",
                      1L,
                      "SCRIPT_PATCH_AUTHORITY_UNAVAILABLE"));
      assertEquals("control_plane_request_id is required", failureError.getMessage());
    }

    verifyNoInteractions(dsl);
  }
}
