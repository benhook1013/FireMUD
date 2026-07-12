package net.firedevops.firemud.gamesession.entity;

import java.time.Instant;
import lombok.Data;

/** A durable replayable output entry for one character's bounded resume transcript. */
@Data
public class ResumeTranscriptEntry {
  private Long id;
  private Long tenantId;
  private Long gameInstanceId;
  private Long characterId;
  private String protocolText;
  private int lineCount;
  private int byteSize;
  private Instant appendedAt;
  private Instant expiresAt;
  private String outputKind;
  private String replayPolicy;
  private String briefRenderPolicy;
  private String payloadType;
  private String payloadJson;
}
