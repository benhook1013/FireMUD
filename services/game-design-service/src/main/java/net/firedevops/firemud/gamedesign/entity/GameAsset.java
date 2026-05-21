package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class GameAsset {
  private Long id;
  private String tenantId;
  private String fileName;
  private String contentType;
  private byte[] data;
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
