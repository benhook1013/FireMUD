package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "entity_mutation_effects")
public class EntityMutationEffect {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false, length = 128)
  private String effectId;

  @Column(nullable = false, length = 80)
  private String operationName;

  @Column(length = 255)
  private String responseType;

  @Lob
  @Basic(fetch = FetchType.LAZY)
  private byte[] responsePayload;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  private Instant completedAt;

  public byte[] getResponsePayload() {
    return responsePayload == null ? null : responsePayload.clone();
  }

  public void setResponsePayload(byte[] responsePayload) {
    this.responsePayload = responsePayload == null ? null : responsePayload.clone();
  }
}
