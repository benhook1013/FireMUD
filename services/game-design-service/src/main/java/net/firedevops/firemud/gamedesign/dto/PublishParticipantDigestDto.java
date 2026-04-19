package net.firedevops.firemud.gamedesign.dto;

public record PublishParticipantDigestDto(
    String participantKey,
    String scopeValue,
    String appliedCommitId,
    String contentDigest,
    Integer digestSchemaVersion,
    String errorCode,
    String errorMessage) {
  public boolean succeeded() {
    return errorCode == null || errorCode.isBlank();
  }
}
