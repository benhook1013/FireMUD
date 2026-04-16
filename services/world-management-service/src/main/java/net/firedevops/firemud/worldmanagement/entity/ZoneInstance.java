package net.firedevops.firemud.worldmanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(
    name = "zone_instance",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_zone_instance_tenant_game_zone",
          columnNames = {"tenant_id", "game_instance_id", "zone_instance_id"})
    })
public class ZoneInstance {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(name = "game_instance_id", nullable = false)
  private Long gameInstanceId;

  @Column(name = "zone_instance_id", nullable = false)
  private Long zoneInstanceId;

  @Column(name = "template_zone_id", nullable = false)
  private Long templateZoneId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "region_instance_id", nullable = false)
  private RegionInstance regionInstance;

  @Column(nullable = false, length = 100)
  private String name;

  @Version private int version;
}
