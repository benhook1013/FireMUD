package net.firedevops.firemud.entitymanagement.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class EntityMutationEffect {
  private Long id;
  private Long tenantId;
  private String effectId;
  private String operationName;
  private String responseType;
  private byte[] responsePayload;
  private String status;
  private Instant createdAt = Instant.now();

  private Instant completedAt;

  public byte[] getResponsePayload() {
    return responsePayload == null ? null : responsePayload.clone();
  }

  public void setResponsePayload(byte[] responsePayload) {
    this.responsePayload = responsePayload == null ? null : responsePayload.clone();
  }
}
