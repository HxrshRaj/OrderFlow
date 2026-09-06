package com.orderflow.inventory.web;

import com.orderflow.inventory.domain.InventoryItem;
import com.orderflow.inventory.service.InventoryItemNotFoundException;
import com.orderflow.inventory.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    InventoryService inventoryService;

    @Test
    void list_returns_items_with_on_hand_derived() throws Exception {
        InventoryItem item = new InventoryItem("SKU-A", "Thing", 7);
        when(inventoryService.listAll()).thenReturn(List.of(item));

        mockMvc.perform(get("/api/v1/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value("SKU-A"))
                .andExpect(jsonPath("$[0].availableQuantity").value(7))
                .andExpect(jsonPath("$[0].onHandQuantity").value(7));
    }

    @Test
    void create_returns_201() throws Exception {
        when(inventoryService.create("SKU-A", "Thing", 10)).thenReturn(new InventoryItem("SKU-A", "Thing", 10));

        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"SKU-A","name":"Thing","quantity":10}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SKU-A"));
    }

    @Test
    void adjust_with_zero_delta_is_rejected_without_calling_the_service() throws Exception {
        mockMvc.perform(patch("/api/v1/inventory/{sku}", "SKU-A")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantityDelta":0}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(inventoryService);
    }

    @Test
    void adjust_delegates_to_the_service() throws Exception {
        when(inventoryService.adjustStock(eq("SKU-A"), anyInt()))
                .thenReturn(new InventoryItem("SKU-A", "Thing", 15));

        mockMvc.perform(patch("/api/v1/inventory/{sku}", "SKU-A")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantityDelta":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(15));

        verify(inventoryService).adjustStock("SKU-A", 5);
    }

    @Test
    void get_unknown_sku_returns_404() throws Exception {
        when(inventoryService.getBySku("ghost")).thenThrow(new InventoryItemNotFoundException("ghost"));

        mockMvc.perform(get("/api/v1/inventory/{sku}", "ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"));
    }
}
