package net.firedevops.firemud.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Entity
@Table(name = "revision")
public class Revision {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Getter(onMethod_ = @SuppressFBWarnings("EI_EXPOSE_REP"))
  @Setter(onMethod_ = @SuppressFBWarnings("EI_EXPOSE_REP2"))
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "tenant_id", nullable = false)
  private Game game;

  @Column(nullable = false)
  private Long authorAccountId;

  @Lob
  @Column(nullable = false)
  private String data;

  @Column(nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();
}
