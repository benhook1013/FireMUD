package net.firedevops.firemud.entitymanagement.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import net.firedevops.firemud.entitymanagement.security.JwtAuthInterceptor;
import net.firedevops.firemud.entitymanagement.service.InventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private InventoryService inventoryService;
  @MockitoBean private JwtAuthInterceptor jwtAuthInterceptor;

  @BeforeEach
  void setUpSecurityContext() throws Exception {
    doAnswer(
            invocation -> {
              SessionContext.setContext("test-account", List.of("platformAdmin"), Map.of());
              return true;
            })
        .when(jwtAuthInterceptor)
        .preHandle(any(), any(), any());
  }

  @AfterEach
  void clearSecurityContext() {
    SessionContext.clear();
  }

  @Test
  void listReturnsInventory() throws Exception {
    InventoryEntryDto dto =
        new InventoryEntryDto(1L, 2L, 3L, "Torch", "A small torch", 4, null, null, null);
    when(inventoryService.listInventory(eq(1L), eq(2L), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(dto)));

    mockMvc
        .perform(get("/tenants/1/characters/2/inventory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.content[0].itemName").value("Torch"))
        .andExpect(jsonPath("$.data.content[0].quantity").value(4));
  }

  @Test
  void addAndDeleteUseTenantScopedPath() throws Exception {
    when(inventoryService.addItem(eq(1L), eq(2L), eq(3L), eq(1)))
        .thenReturn(new InventoryEntryDto(1L, 2L, 3L, "Torch", null, 1, null, null, null));

    mockMvc
        .perform(
            post("/tenants/1/characters/2/inventory")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemId\":3,\"quantity\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.itemName").value("Torch"));

    mockMvc
        .perform(delete("/tenants/1/characters/2/inventory/3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }
}
