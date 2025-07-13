package net.firedevops.firemud.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@Entity
@Table(name = "instance")
public class Instance {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "zone_id", nullable = false)
  private Zone zone;

  @Column(name = "owner_account_id")
  private Long ownerAccountId;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();

  @Column(name = "expires_at")
  private LocalDateTime expiresAt;

  @Version private int version;
}
