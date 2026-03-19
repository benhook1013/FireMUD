package net.firedevops.firemud.common.saga.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "saga_instance", schema = "saga")
public class SagaInstance {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "saga_name", nullable = false, length = 100)
  private String sagaName;

  @Column(nullable = false, length = 20)
  private String state;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
