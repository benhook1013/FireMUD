package net.firedevops.firemud.common;

public record ApiResponse<T>(ResultStatus status, T data, ErrorDetail error) {
  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>(ResultStatus.SUCCESS, data, null);
  }

  public static <T> ApiResponse<T> error(ErrorDetail error) {
    return new ApiResponse<>(ResultStatus.ERROR, null, error);
  }
}
