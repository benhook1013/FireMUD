package net.firedevops.firemud.loggingadmin.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "log_events")
public class LogEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false, length = 50)
  private String type;

  @Column(nullable = false, length = 255)
  private String message;

  @Column(nullable = false)
  private Instant timestamp;

  @Column private Long accountId;
}
