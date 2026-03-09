package net.firedevops.firemud.worldmanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "room")
public class Room {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "region_id", nullable = false)
  private Region region;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "JPA relationship is intentionally exposed")
  public Region getRegion() {
    return region;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "JPA relationship is stored directly")
  public void setRegion(Region region) {
    this.region = region;
  }

  @Column(nullable = false, length = 100)
  private String name;

  @Column(length = 255)
  private String description;

  @Version private int version;
}
