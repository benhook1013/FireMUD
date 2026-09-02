package net.firedevops.firemud.automationscripting.service;

import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;

public interface ScriptGameplayCommandHandoffService {
  HandoffResult handoff(ScriptWorkItem workItem, EmittedCommand command);

  /**
   * Begins the thread-local aggregate fan-out scope, deferring parent terminalization while the
   * evaluator attempts all emitted siblings. Callers must pair this call with {@link
   * #endAggregateFanout(ScriptWorkItem)} in a {@code finally} block, including when a sibling
   * handoff throws. The scope is not depth-counted: re-entering it for the same work item replaces
   * its admission snapshot, and one end call clears that work item's scope.
   */
  default void beginAggregateFanout(ScriptWorkItem workItem) {}

  /**
   * Ends the thread-local aggregate fan-out scope established by {@link
   * #beginAggregateFanout(ScriptWorkItem)}. This operation is idempotent for an inactive or null
   * work item; callers should invoke it exactly once from the paired {@code finally} block because
   * the scope is not depth-counted.
   */
  default void endAggregateFanout(ScriptWorkItem workItem) {}

  record EmittedCommand(
      String commandText,
      String targetEntityId,
      String targetGameInstanceId,
      String targetRegionId,
      Long targetRegionEpoch,
      boolean requiresSoloTick,
      long dueTickId,
      int ordinal) {}

  /**
   * Handoff outcome. Classification and durable reason mapping use the bounded machine-readable
   * outcome and errorCode fields only; errorMessage is diagnostic-only and must not affect routing,
   * retry, cancellation, or persisted state.
   */
  record HandoffResult(
      boolean accepted,
      String outcome,
      String commandId,
      String remoteCoordinatorId,
      String remoteFollowupId,
      String errorCode,
      String errorMessage) {
    public HandoffResult(
        boolean accepted,
        String outcome,
        String commandId,
        String remoteCoordinatorId,
        String remoteFollowupId,
        String errorCode) {
      this(accepted, outcome, commandId, remoteCoordinatorId, remoteFollowupId, errorCode, "");
    }
  }
}
