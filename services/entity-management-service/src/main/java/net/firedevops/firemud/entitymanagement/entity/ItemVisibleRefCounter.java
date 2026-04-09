package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(
    name = "item_visible_ref_counters",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_item_visible_ref_counters_token",
          columnNames = {"tenant_id", "visible_ref_token"})
    })
public class ItemVisibleRefCounter {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "visible_ref_token", nullable = false, length = 128)
  private String visibleRefToken;

  @Column(name = "next_sequence", nullable = false)
  private Long nextSequence;

  @Version private int version;
}
