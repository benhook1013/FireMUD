package net.firedevops.firemud.common.saga.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "saga_step")
public class SagaStep {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "instance_id", nullable = false)
  private Long instanceId;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(nullable = false, length = 20)
  private String status;

  @Column(nullable = false)
  private int attempt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
