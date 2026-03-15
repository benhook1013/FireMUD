package net.firedevops.firemud.gamedesign.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.Arrays;

public record GameAssetDto(
    Long id,
    @NotNull @Size(max = 36) String tenantId,
    @NotNull @Size(max = 255) String fileName,
    @NotNull @Size(max = 100) String contentType,
    byte[] data,
    LocalDateTime createdAt) {

  public GameAssetDto(
      Long id,
      @NotNull @Size(max = 36) String tenantId,
      @NotNull @Size(max = 255) String fileName,
      @NotNull @Size(max = 100) String contentType,
      byte[] data,
      LocalDateTime createdAt) {
    this.id = id;
    this.tenantId = tenantId;
    this.fileName = fileName;
    this.contentType = contentType;
    this.data = data != null ? Arrays.copyOf(data, data.length) : null;
    this.createdAt = createdAt;
  }

  @Override
  public byte[] data() {
    return data != null ? Arrays.copyOf(data, data.length) : null;
  }
}
