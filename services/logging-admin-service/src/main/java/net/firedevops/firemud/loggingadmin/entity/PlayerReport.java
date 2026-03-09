package net.firedevops.firemud.loggingadmin.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "player_reports")
public class PlayerReport {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long reporterAccountId;

  @Column private Long targetAccountId;

  @Column(nullable = false, length = 20)
  private String type;

  @Column(nullable = false, length = 255)
  private String description;

  @Column(nullable = false)
  private Instant createdAt;
}
