package net.firedevops.firemud.loggingadmin.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.firedevops.firemud.common.GlobalExceptionHandler;
import net.firedevops.firemud.loggingadmin.dto.CreateReportRequest;
import net.firedevops.firemud.loggingadmin.dto.ReportDto;
import net.firedevops.firemud.loggingadmin.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(ReportController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ReportControllerTest {

  @Autowired private MockMvc mockMvc;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private ReportService reportService;

  @Test
  void createReturnsDto() throws Exception {
    CreateReportRequest request = new CreateReportRequest(1L, 7L, 9L, "abuse", "desc");
    when(reportService.createReport(request))
        .thenReturn(new ReportDto(1L, 1L, 7L, 9L, "abuse", "desc", null));

    mockMvc
        .perform(
            post("/reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.targetAccountId").value(9));
  }

  @Test
  void createRejectsZeroTargetAccountIdBeforeDispatch() throws Exception {
    CreateReportRequest request = new CreateReportRequest(1L, 7L, 0L, "abuse", "desc");

    mockMvc
        .perform(
            post("/reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("targetAccountId must be positive"));

    verifyNoInteractions(reportService);
  }
}
