package net.firedevops.firemud.loggingadmin.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "feature_flag")
public class FeatureFlag {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(nullable = false)
  private boolean enabled;
}
