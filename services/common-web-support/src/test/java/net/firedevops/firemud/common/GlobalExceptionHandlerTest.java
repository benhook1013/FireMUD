package net.firedevops.firemud.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@WebMvcTest
@Import(GlobalExceptionHandler.class)
@TestPropertySource(
    properties = {
      "spring.web.resources.add-mappings=false",
      "spring.mvc.throw-exception-if-no-handler-found=true"
    })
class GlobalExceptionHandlerTest {
  @Autowired private MockMvc mockMvc;

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class WebSliceApplication {}

  @Test
  void handleExceptionDoesNotExposeRawMessage() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();

    ApiResponse<ErrorDetail> response =
        handler.handleException(new RuntimeException("boom")).getBody();

    assertEquals("INTERNAL_ERROR", response.error().code());
    assertEquals("Internal server error", response.error().message());
  }

  @Test
  void handleBindExceptionNormalizesNumericTypeMismatch() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    BeanPropertyBindingResult bindingResult =
        new BeanPropertyBindingResult(new Object(), "request");
    bindingResult.addError(
        new FieldError(
            "request",
            "pointerVersion",
            "abc",
            false,
            new String[] {"typeMismatch"},
            null,
            "Failed to convert property value of type 'java.lang.String' to required type"
                + " 'java.lang.Long' for property 'pointerVersion'; For input string: \"abc\""));

    ApiResponse<ErrorDetail> response =
        handler.handleBindException(new BindException(bindingResult)).getBody();

    Assertions.assertNotNull(response);
    assertEquals("INVALID_ARGUMENT", response.error().code());
    assertEquals("pointerVersion must be numeric", response.error().message());
  }

  @Test
  void handleBindExceptionNormalizesPositiveConstraintMessage() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    BeanPropertyBindingResult bindingResult =
        new BeanPropertyBindingResult(new Object(), "request");
    bindingResult.addError(
        new FieldError(
            "request",
            "tenantId",
            0L,
            false,
            new String[] {"Positive"},
            null,
            "must be greater than 0"));

    ApiResponse<ErrorDetail> response =
        handler.handleBindException(new BindException(bindingResult)).getBody();

    Assertions.assertNotNull(response);
    assertEquals("INVALID_ARGUMENT", response.error().code());
    assertEquals("tenantId must be positive", response.error().message());
  }

  @Test
  void handleResponseStatusPreservesCanonicalNotFoundEnvelope() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();

    ApiResponse<ErrorDetail> response =
        handler
            .handleResponseStatus(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Missing thing"))
            .getBody();

    Assertions.assertNotNull(response);
    assertEquals("NOT_FOUND", response.error().code());
    assertEquals("Missing thing", response.error().message());
  }

  @Test
  void handleResponseStatusNormalizesBadRequestToInvalidArgument() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();

    ApiResponse<ErrorDetail> response =
        handler
            .handleResponseStatus(
                new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "pointerVersion must be positive"))
            .getBody();

    Assertions.assertNotNull(response);
    assertEquals("INVALID_ARGUMENT", response.error().code());
    assertEquals("pointerVersion must be positive", response.error().message());
  }

  @Test
  void handleNoResourceFoundPreservesNotFoundEnvelope() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();

    var responseEntity =
        handler.handleNoResourceFound(
            new NoResourceFoundException(HttpMethod.POST, "/reports", "reports"));
    ApiResponse<ErrorDetail> response = responseEntity.getBody();

    assertEquals(HttpStatus.NOT_FOUND, responseEntity.getStatusCode());
    Assertions.assertNotNull(response);
    assertEquals("NOT_FOUND", response.error().code());
    assertEquals("Resource not found", response.error().message());
  }

  @Test
  void unmappedPostUsesCanonicalNotFoundEnvelope() throws Exception {
    mockMvc
        .perform(post("/reports"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value("ERROR"))
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.error.message").value("Resource not found"));
  }
}
