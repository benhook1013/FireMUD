package net.firedevops.firemud.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "zone")
public class Zone {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "region_id", nullable = false)
  private Region region;

  @Column(nullable = false, length = 100)
  private String name;

  @Version private int version;
}
