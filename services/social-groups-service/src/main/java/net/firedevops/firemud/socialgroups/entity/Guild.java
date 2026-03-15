package net.firedevops.firemud.socialgroups.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "guilds")
public class Guild {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(nullable = false)
  private Long ownerAccountId;

  @Column(nullable = false)
  private Instant createdAt;
}
