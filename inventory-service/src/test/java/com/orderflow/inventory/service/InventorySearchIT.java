package com.orderflow.inventory.service;

import com.orderflow.inventory.domain.InventoryItem;
import com.orderflow.inventory.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the search SQL against real PostgreSQL (full-text + pg_trgm), not a mock. */
class InventorySearchIT extends AbstractPostgresIntegrationTest {

    @Autowired
    InventoryService service;

    private List<String> skus(String q) {
        return service.search(q, false, 20).stream().map(InventoryItem::getSku).toList();
    }

    @Test
    void prefix_matches_a_partial_word() {
        assertThat(skus("mou")).contains("SKU-MOUSE", "SKU-MOUSE-ERGO", "SKU-MOUSEPAD")
                .doesNotContain("SKU-MONITOR");
    }

    @Test
    void tolerates_a_typo() {
        assertThat(skus("keybord")).contains("SKU-KEYBOARD");
        assertThat(skus("wirless mouse")).contains("SKU-MOUSE");
    }

    @Test
    void multi_word_query_requires_every_word_or_is_close_to_the_name() {
        List<String> result = skus("usb c hub");
        assertThat(result).first().isEqualTo("SKU-USBHUB");
    }

    @Test
    void best_match_is_ranked_first() {
        assertThat(skus("webcam").get(0)).isIn("SKU-WEBCAM", "SKU-WEBCAMCOVER", "SKU-RINGLIGHT");
        assertThat(skus("monitor arm").get(0)).isEqualTo("SKU-MONITORARM");
    }

    @Test
    void matches_on_sku() {
        assertThat(skus("sku-lastunit")).containsExactly("SKU-LASTUNIT");
    }

    @Test
    void nonsense_returns_nothing() {
        assertThat(skus("zzqqxx")).isEmpty();
    }

    @Test
    void in_stock_only_hides_sold_out_items() {
        assertThat(skus("drawing tablet")).contains("SKU-OOS-TABLET");
        assertThat(service.search("drawing tablet", true, 20)).isEmpty();
    }

    @Test
    void sql_injection_text_is_harmless() {
        assertThat(service.search("'; DROP TABLE inventory_item; --", false, 20)).isEmpty();
        assertThat(service.listAll()).isNotEmpty();
    }

    @Test
    void empty_query_lists_by_name_and_limit_is_clamped() {
        assertThat(service.search("", false, 5)).hasSize(5);
        assertThat(service.search("", false, 1000).size()).isLessThanOrEqualTo(50);
        assertThat(service.search("mouse", false, 0)).hasSize(1);
    }
}
