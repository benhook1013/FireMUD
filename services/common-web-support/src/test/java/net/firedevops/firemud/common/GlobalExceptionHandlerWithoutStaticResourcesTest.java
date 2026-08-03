package net.firedevops.firemud.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@ContextConfiguration(classes = WebSliceApplication.class)
@Import(GlobalExceptionHandler.class)
@TestPropertySource(
    properties = {
      "spring.mvc.throw-exception-if-no-handler-found=true",
      "spring.web.resources.add-mappings=false"
    })
class GlobalExceptionHandlerWithoutStaticResourcesTest {
  @Autowired private MockMvc mockMvc;

  @Test
  void unmappedRouteUsesCanonicalNotFoundEnvelopeWithoutStaticResourceMappings() throws Exception {
    mockMvc
        .perform(get("/unmapped-route"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value("ERROR"))
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.error.message").value("Resource not found"));
  }
}
