package net.firedevops.firemud.loggingadmin.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "moderation_actions")
public class ModerationAction {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long accountId;

  @Column(nullable = false, length = 20)
  private String action;

  @Column(length = 255)
  private String reason;

  @Column(nullable = false)
  private Instant createdAt;

  @Column private Instant expiresAt;
}
