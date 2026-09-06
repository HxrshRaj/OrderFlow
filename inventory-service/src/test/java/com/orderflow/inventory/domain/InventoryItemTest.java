package com.orderflow.inventory.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class InventoryItemTest {

    @Test
    void restock_increases_available_quantity() {
        InventoryItem item = new InventoryItem("SKU-1", "Thing", 5);

        item.applyDelta(3);

        assertThat(item.getAvailableQuantity()).isEqualTo(8);
    }

    @Test
    void negative_adjustment_within_bounds_is_allowed() {
        InventoryItem item = new InventoryItem("SKU-1", "Thing", 5);

        item.applyDelta(-5);

        assertThat(item.getAvailableQuantity()).isZero();
    }

    @Test
    void adjustment_that_would_go_below_zero_is_rejected() {
        InventoryItem item = new InventoryItem("SKU-1", "Thing", 5);

        assertThatExceptionOfType(IllegalStockAdjustmentException.class)
                .isThrownBy(() -> item.applyDelta(-6));

        assertThat(item.getAvailableQuantity()).as("state is unchanged on rejection").isEqualTo(5);
    }

    @Test
    void constructor_rejects_negative_starting_quantity() {
        assertThatIllegalArgumentException().isThrownBy(() -> new InventoryItem("SKU-1", "Thing", -1));
    }
}
