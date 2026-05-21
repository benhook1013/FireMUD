package net.firedevops.firemud.common.saga.persistence;

import java.time.Instant;
import lombok.Data;

@Data
public class SagaInstance {
  private Long id;

  private String sagaName;

  private String state;

  private Instant createdAt;

  private Instant updatedAt;
}
