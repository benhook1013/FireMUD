package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;

class TriggerScriptEventRequestFactoryTest {
  @Test
  void convertsNullScriptPatchVersionToEmptyProtoValueForUnpinnedTuple() {
    TriggerScriptEventRequestFactory.CommonFields fields =
        new TriggerScriptEventRequestFactory.CommonFields(
            "tenant",
            "instance",
            "region",
            1L,
            "entity",
            "onCommand",
            "v1",
            null,
            0L,
            null,
            "event-1",
            false,
            TriggerMode.TRIGGER_MODE_NORMAL,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "snapshot",
            "{}");

    assertEquals(
        "", TriggerScriptEventRequestFactory.builder(fields, null).getScriptPatchVersion());
  }

  @Test
  void rejectsNullScriptPatchVersionWhenPinEpochIsPositive() {
    TriggerScriptEventRequestFactory.CommonFields fields =
        new TriggerScriptEventRequestFactory.CommonFields(
            "tenant",
            "instance",
            "region",
            1L,
            "entity",
            "onCommand",
            "v1",
            null,
            1L,
            "request-1",
            "event-1",
            false,
            TriggerMode.TRIGGER_MODE_NORMAL,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "snapshot",
            "{}");

    assertThrows(
        IllegalArgumentException.class,
        () -> TriggerScriptEventRequestFactory.builder(fields, null));
  }
}
