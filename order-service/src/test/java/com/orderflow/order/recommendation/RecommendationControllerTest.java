package com.orderflow.order.recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RecommendationController.class)
class RecommendationControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    RecommendationService service;

    @Test
    void passes_repeated_sku_params_and_limit_to_the_service() throws Exception {
        when(service.recommend(List.of("SKU-A", "SKU-B"), 3)).thenReturn(
                List.of(new Recommendation("SKU-C", 0.75, 9, Recommendation.Reason.BOUGHT_TOGETHER)));

        mockMvc.perform(get("/api/v1/recommendations?sku=SKU-A&sku=SKU-B&limit=3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value("SKU-C"))
                .andExpect(jsonPath("$[0].reason").value("BOUGHT_TOGETHER"))
                .andExpect(jsonPath("$[0].coOccurrences").value(9));
        verify(service).recommend(List.of("SKU-A", "SKU-B"), 3);
    }

    @Test
    void works_without_any_sku_and_defaults_the_limit_to_five() throws Exception {
        when(service.recommend(null, 5)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/recommendations")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }
}
