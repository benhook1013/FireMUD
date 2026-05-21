package net.firedevops.firemud.common.saga.persistence;

import java.time.Instant;
import lombok.Data;

@Data
public class SagaStep {
  private Long id;

  private Long instanceId;

  private String name;

  private String status;

  private int attempt;

  private Instant createdAt;

  private Instant updatedAt;
}
