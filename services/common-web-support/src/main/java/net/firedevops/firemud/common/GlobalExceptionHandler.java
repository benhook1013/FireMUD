package net.firedevops.firemud.common;

import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleIllegalArgument(
      IllegalArgumentException ex) {
    ErrorDetail detail = new ErrorDetail("INVALID_ARGUMENT", ex.getMessage());
    return new ResponseEntity<>(ApiResponse.error(detail), HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleResponseStatus(ResponseStatusException ex) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    String code =
        switch (status) {
          case BAD_REQUEST -> "INVALID_ARGUMENT";
          case FORBIDDEN -> "PERMISSION_DENIED";
          default -> status.name();
        };
    String message = ex.getReason() == null ? status.getReasonPhrase() : ex.getReason();
    ErrorDetail detail = new ErrorDetail(code, message);
    return new ResponseEntity<>(ApiResponse.error(detail), status);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex) {
    return invalidArgument(firstFieldErrorMessage(ex.getBindingResult().getFieldError()));
  }

  @ExceptionHandler(BindException.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleBindException(BindException ex) {
    return invalidArgument(bindFieldErrorMessage(ex.getBindingResult().getFieldError()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleMethodArgumentTypeMismatch(
      MethodArgumentTypeMismatchException ex) {
    String fieldName = ex.getName();
    String message =
        isNumericType(ex.getRequiredType())
            ? fieldName + " must be numeric"
            : fieldName + " is invalid";
    return invalidArgument(message);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleMissingServletRequestParameter(
      MissingServletRequestParameterException ex) {
    return invalidArgument(ex.getParameterName() + " is required");
  }

  @ExceptionHandler(ServletRequestBindingException.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleServletRequestBinding(
      ServletRequestBindingException ex) {
    return invalidArgument("Request binding is invalid");
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex) {
    return invalidArgument("Request body is malformed");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleException(Exception ex) {
    ErrorDetail detail = new ErrorDetail("INTERNAL_ERROR", "Internal server error");
    return new ResponseEntity<>(ApiResponse.error(detail), HttpStatus.INTERNAL_SERVER_ERROR);
  }

  private ResponseEntity<ApiResponse<ErrorDetail>> invalidArgument(String message) {
    ErrorDetail detail = new ErrorDetail("INVALID_ARGUMENT", message);
    return new ResponseEntity<>(ApiResponse.error(detail), HttpStatus.BAD_REQUEST);
  }

  private String firstFieldErrorMessage(FieldError fieldError) {
    if (fieldError == null) {
      return "Request body is invalid";
    }
    String defaultMessage =
        normalizeConstraintMessage(Objects.toString(fieldError.getDefaultMessage(), "is invalid"));
    return fieldError.getField() + " " + defaultMessage;
  }

  private String bindFieldErrorMessage(FieldError fieldError) {
    if (fieldError == null) {
      return "Request binding is invalid";
    }
    if (looksLikeTypeMismatch(fieldError)) {
      return isNumericTypeMismatch(fieldError)
          ? fieldError.getField() + " must be numeric"
          : fieldError.getField() + " is invalid";
    }
    return firstFieldErrorMessage(fieldError);
  }

  private boolean isNumericType(Class<?> requiredType) {
    if (requiredType == null) {
      return false;
    }
    return Number.class.isAssignableFrom(requiredType)
        || requiredType == byte.class
        || requiredType == short.class
        || requiredType == int.class
        || requiredType == long.class
        || requiredType == float.class
        || requiredType == double.class;
  }

  private boolean isNumericTypeMismatch(FieldError fieldError) {
    String defaultMessage = Objects.toString(fieldError.getDefaultMessage(), "");
    return defaultMessage.contains("java.lang.Byte")
        || defaultMessage.contains("java.lang.Short")
        || defaultMessage.contains("java.lang.Integer")
        || defaultMessage.contains("java.lang.Long")
        || defaultMessage.contains("java.lang.Float")
        || defaultMessage.contains("java.lang.Double")
        || defaultMessage.contains(" byte")
        || defaultMessage.contains(" short")
        || defaultMessage.contains(" int")
        || defaultMessage.contains(" long")
        || defaultMessage.contains(" float")
        || defaultMessage.contains(" double");
  }

  private boolean looksLikeTypeMismatch(FieldError fieldError) {
    if ("typeMismatch".equals(fieldError.getCode())) {
      return true;
    }
    String defaultMessage = Objects.toString(fieldError.getDefaultMessage(), "");
    return defaultMessage.contains("Failed to convert property value of type")
        || defaultMessage.contains("failed to convert value of type");
  }

  private String normalizeConstraintMessage(String defaultMessage) {
    return "must be greater than 0".equals(defaultMessage) ? "must be positive" : defaultMessage;
  }
}
