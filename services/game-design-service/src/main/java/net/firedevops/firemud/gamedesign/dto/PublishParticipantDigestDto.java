package net.firedevops.firemud.gamedesign.dto;

public record PublishParticipantDigestDto(
    String participantKey,
    String scopeValue,
    Long baseVersionId,
    String appliedCommitId,
    String contentDigest,
    Integer digestSchemaVersion,
    String errorCode,
    String errorMessage) {
  /**
   * Preserve the full-version construction shape while the typed patch scope is introduced.
   * Full-version digests have no base version and therefore keep this field null.
   */
  public PublishParticipantDigestDto(
      String participantKey,
      String scopeValue,
      String appliedCommitId,
      String contentDigest,
      Integer digestSchemaVersion,
      String errorCode,
      String errorMessage) {
    this(
        participantKey,
        scopeValue,
        null,
        appliedCommitId,
        contentDigest,
        digestSchemaVersion,
        errorCode,
        errorMessage);
  }

  public boolean succeeded() {
    return errorCode == null || errorCode.isBlank();
  }
}
