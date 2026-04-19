package net.firedevops.firemud.worldmanagement.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@Entity
@Table(name = "world_event")
public class WorldEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(name = "game_instance_id", nullable = false)
  private Long gameInstanceId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "region_instance_id")
  private RegionInstance regionInstance;

  @Column(nullable = false, length = 50)
  private String eventType;

  @Column(name = "event_data")
  private String eventData;

  @Column(nullable = false)
  private LocalDateTime executeAt;

  @Column(nullable = false)
  private boolean processed = false;

  private LocalDateTime processedAt;

  @Version private int version;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "JPA association is intentionally exposed")
  public RegionInstance getRegionInstance() {
    return regionInstance;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "JPA association stored directly")
  public void setRegionInstance(RegionInstance regionInstance) {
    this.regionInstance = regionInstance;
  }
}
