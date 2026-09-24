package net.firedevops.firemud.entitymanagement.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CraftingController.class)
class CraftingControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void getDeniesBeforeReadingUnscopedRecipe() throws Exception {
    mockMvc
        .perform(get("/crafting/recipes/7"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.error.code").value("CRAFTING_UNAVAILABLE"));
  }

  @Test
  void getDeniesMalformedIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(get("/crafting/recipes/not-a-number"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.error.code").value("CRAFTING_UNAVAILABLE"));
  }

  @Test
  void getDeniesZeroIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(get("/crafting/recipes/0"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.error.code").value("CRAFTING_UNAVAILABLE"));
  }

  @Test
  void createDeniesBeforeWritingCallerSuppliedTenantAndItems() throws Exception {
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
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.error.code").value("CRAFTING_UNAVAILABLE"));
  }
}
