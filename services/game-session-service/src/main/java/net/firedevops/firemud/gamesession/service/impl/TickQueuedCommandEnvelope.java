package net.firedevops.firemud.gamesession.service.impl;

record TickQueuedCommandEnvelope(
    boolean requiresSoloTick,
    String commandId,
    String command,
    SealedQueueSource sealedQueueSource) {

  TickQueuedCommandEnvelope(boolean requiresSoloTick, String commandId, String command) {
    this(requiresSoloTick, commandId, command, null);
  }

  record SealedQueueSource(
      String sourceKind,
      String sourceState,
      long sourceOrdinal,
      Long sourceDueTickId,
      Long sourceDueAtMs) {}
}
