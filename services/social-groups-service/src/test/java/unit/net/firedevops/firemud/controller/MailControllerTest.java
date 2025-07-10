package net.firedevops.firemud.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.firedevops.firemud.dto.MailMessageDto;
import net.firedevops.firemud.dto.SendMailRequest;
import net.firedevops.firemud.service.MailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MailController.class)
class MailControllerTest {
  @Autowired private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private MailService mailService;

  @Test
  void sendMailReturnsDto() throws Exception {
    SendMailRequest request = new SendMailRequest(1L, 2L, 3L, "hello", "test body");
    MailMessageDto response = new MailMessageDto(1L, 1L, 2L, 3L, "hello", "test body", null, null);
    when(mailService.sendMail(request)).thenReturn(response);

    mockMvc
        .perform(
            post("/mail")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.subject").value("hello"));
  }
}
