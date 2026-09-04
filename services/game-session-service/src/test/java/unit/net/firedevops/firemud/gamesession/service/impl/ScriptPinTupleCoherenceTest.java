package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import net.firedevops.firemud.gamesession.service.ScriptPinTupleCoherence;
import org.junit.jupiter.api.Test;

class ScriptPinTupleCoherenceTest {
  @Test
  void acceptsFullyUnpinnedTuple() {
    ScriptPinTupleCoherence.requireCoherent(null, null, null);
  }

  @Test
  void acceptsBlankValuesAsTheUnpinnedTuple() {
    ScriptPinTupleCoherence.requireCoherent("  ", null, "\t");
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

  @Test
  void rejectsPatchWithoutTheCompletePinTuple() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ScriptPinTupleCoherence.requireCoherent("patch-1", null, null))
        .withMessage(
            "SCRIPT_PIN_STATE_INVALID: patch, positive epoch, and request id must be present together");
  }

  @Test
  void rejectsEpochWithoutTheCompletePinTuple() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ScriptPinTupleCoherence.requireCoherent(null, 1L, null))
        .withMessage(
            "SCRIPT_PIN_STATE_INVALID: patch, positive epoch, and request id must be present together");
  }

  @Test
  void rejectsRequestIdWithoutTheCompletePinTuple() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ScriptPinTupleCoherence.requireCoherent(null, null, "request-1"))
        .withMessage(
            "SCRIPT_PIN_STATE_INVALID: patch, positive epoch, and request id must be present together");
  }

  @Test
  void rejectsBlankRequestIdFromAnOtherwisePinnedTuple() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ScriptPinTupleCoherence.requireCoherent("patch-1", 1L, "  "))
        .withMessage(
            "SCRIPT_PIN_STATE_INVALID: patch, positive epoch, and request id must be present together");
  }
}
