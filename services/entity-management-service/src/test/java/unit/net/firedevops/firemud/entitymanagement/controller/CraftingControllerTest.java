package net.firedevops.firemud.entitymanagement.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import net.firedevops.firemud.entitymanagement.dto.CraftingIngredientDto;
import net.firedevops.firemud.entitymanagement.dto.CraftingRecipeDto;
import net.firedevops.firemud.entitymanagement.service.CraftingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CraftingController.class)
class CraftingControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CraftingService craftingService;

  @Test
  void getReturnsRecipe() throws Exception {
    when(craftingService.getRecipe(7L))
        .thenReturn(
            new CraftingRecipeDto(
                7L, 1L, "Torch", 14L, 1, List.of(new CraftingIngredientDto(3L, 1))));

    mockMvc
        .perform(get("/crafting/recipes/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.name").value("Torch"));
  }

  @Test
  void getRejectsMalformedIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(get("/crafting/recipes/not-a-number"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("id must be numeric"));

    verifyNoInteractions(craftingService);
  }

  @Test
  void getRejectsZeroIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(get("/crafting/recipes/0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("id must be positive"));

    verifyNoInteractions(craftingService);
  }

  @Test
  void createReturnsRecipe() throws Exception {
    when(craftingService.createRecipe(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0, CraftingRecipeDto.class));

    mockMvc
        .perform(
            post("/crafting/recipes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "id": 7,
                      "tenantId": 1,
                      "name": "Torch",
                      "resultItemId": 14,
                      "resultQuantity": 1,
                      "ingredients": [{"itemId": 3, "quantity": 1}]
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.resultItemId").value(14));
  }
}
