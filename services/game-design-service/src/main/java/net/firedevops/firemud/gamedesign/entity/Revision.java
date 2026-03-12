package net.firedevops.firemud.gamedesign.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@Entity
@Table(name = "revision")
public class Revision {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Column(nullable = false)
  private Long authorAccountId;

  @Lob
  @Column(nullable = false)
  private String data;

  @Column(nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();
}
