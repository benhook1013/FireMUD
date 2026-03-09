package net.firedevops.firemud.worldmanagement.entity;

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

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "JPA association is intentionally exposed")
  public Region getRegion() {
    return region;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "JPA association stored directly")
  public void setRegion(Region region) {
    this.region = region;
  }
}
