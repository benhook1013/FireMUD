package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ScriptPinTupleCoherenceTest {
  @Test
  void acceptsFullyUnpinnedTuple() {
    ScriptPinTupleCoherence.requireCoherent(null, null, null);
  }

  @Test
  void acceptsFullyPinnedTuple() {
    ScriptPinTupleCoherence.requireCoherent("patch-1", 1L, "request-1");
  }

  @Test
  void rejectsZeroEpochEvenWhenTheOtherPinFieldsAreAbsent() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ScriptPinTupleCoherence.requireCoherent(null, 0L, null))
        .withMessage("SCRIPT_PIN_STATE_INVALID: script pin epoch must be positive when present");
  }

  @Test
  void rejectsNegativeEpochEvenWhenTheOtherPinFieldsAreAbsent() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ScriptPinTupleCoherence.requireCoherent(null, -1L, null))
        .withMessage("SCRIPT_PIN_STATE_INVALID: script pin epoch must be positive when present");
  }
}
