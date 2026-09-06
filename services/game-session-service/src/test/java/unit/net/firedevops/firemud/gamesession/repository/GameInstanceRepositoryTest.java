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
import org.assertj.core.api.Assertions;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.RecordMapper;
import org.jooq.SQLDialect;
import org.jooq.SelectConditionStep;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.SelectForUpdateOfStep;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSelectStep;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

    ArgumentCaptor<Condition> conditionCaptor = ArgumentCaptor.forClass(Condition.class);
    verify(from).where(conditionCaptor.capture());
    String renderedCondition =
        DSL.using(SQLDialect.POSTGRES).renderInlined(conditionCaptor.getValue());
    Assertions.assertThat(renderedCondition).contains("\"tenant_id\" = 3").contains("\"id\" = 7");
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

  @Test
  void saveRejectsIncoherentScriptPinTupleBeforeInsertOrUpdate() {
    DSLContext dsl = mock(DSLContext.class);
    GameInstanceRepository repository = new GameInstanceRepository(dsl);

    Object[][] invalidTuples = {
      {
        "patch-1",
        null,
        "request-1",
        "patch, positive epoch, and request id must be present together"
      },
      {null, 1L, "request-1", "patch, positive epoch, and request id must be present together"},
      {"patch-1", 1L, null, "patch, positive epoch, and request id must be present together"},
      {"patch-1", 0L, "request-1", "script pin epoch must be positive when present"},
      {"patch-1", -1L, "request-1", "script pin epoch must be positive when present"}
    };

    for (Object[] tuple : invalidTuples) {
      net.firedevops.firemud.gamesession.entity.GameInstance instance =
          new net.firedevops.firemud.gamesession.entity.GameInstance();
      instance.setId(7L);
      instance.setScriptPatchVersion((String) tuple[0]);
      instance.setScriptPinEpoch((Long) tuple[1]);
      instance.setScriptPatchPinnedControlPlaneRequestId((String) tuple[2]);

      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> repository.save(instance));

      assertEquals("SCRIPT_PIN_STATE_INVALID: " + tuple[3], error.getMessage());
    }

    verifyNoInteractions(dsl);
  }
}
