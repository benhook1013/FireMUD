package net.firedevops.firemud.gamesession.service;

public interface MovementEffectIdempotencyService {
  MoveEffectApplyResult apply(
      String effectId, SessionContext expectedContext, String destinationRoomInstanceId);

  record MoveEffectApplyResult(MoveEffectApplyStatus status, SessionContext context) {}

  enum MoveEffectApplyStatus {
    APPLIED,
    REPLAYED,
    CONFLICT,
    NOT_FOUND
  }
}
