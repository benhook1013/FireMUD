package net.firedevops.firemud.gamedesign.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Data
@Entity
@Table(name = "revision")
public class Revision {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Column(nullable = false)
  private Long versionId;

  @Column(nullable = false)
  private Long authorAccountId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String data;

  @Column(nullable = false, length = 64)
  private String revisionKind;

  @Column(length = 128)
  private String logicalRevisionId;

  @Column(nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();
}
