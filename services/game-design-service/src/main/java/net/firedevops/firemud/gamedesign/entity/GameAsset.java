package net.firedevops.firemud.gamedesign.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@Entity
@Table(name = "game_assets")
public class GameAsset {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Column(nullable = false, length = 255)
  private String fileName;

  @Column(nullable = false, length = 100)
  private String contentType;

  @Lob
  @Column(nullable = false)
  private byte[] data;

  @Column(nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();

  /**
   * Returns a defensive copy of the asset data to avoid exposing the internal representation.
   *
   * @return copy of the asset data or {@code null} if no data is present
   */
  public byte[] getData() {
    return data == null ? null : data.clone();
  }

  /**
   * Stores a defensive copy of the provided data to protect the internal representation.
   *
   * @param data raw asset bytes
   */
  public void setData(byte[] data) {
    this.data = data == null ? null : data.clone();
  }
}
