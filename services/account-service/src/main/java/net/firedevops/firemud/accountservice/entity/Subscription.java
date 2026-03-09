package net.firedevops.firemud.accountservice.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Entity
@Table(name = "subscription")
public class Subscription {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Getter(
      onMethod_ =
          @SuppressFBWarnings(
              value = "EI_EXPOSE_REP",
              justification = "JPA association is intentionally exposed"))
  @Setter(
      onMethod_ =
          @SuppressFBWarnings(
              value = "EI_EXPOSE_REP2",
              justification = "JPA association stored directly"))
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "account_id", nullable = false)
  private Account account;

  @Column(name = "plan_id", nullable = false, length = 50)
  private String planId;

  @Column(nullable = false, length = 20)
  private String status;

  @Column(name = "started_at", nullable = false)
  private LocalDateTime startedAt;

  @Column(name = "ended_at")
  private LocalDateTime endedAt;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;
}
