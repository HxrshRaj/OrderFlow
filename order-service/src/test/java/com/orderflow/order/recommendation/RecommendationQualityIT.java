package com.orderflow.order.recommendation;

import com.orderflow.order.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the recommendation SQL against real PostgreSQL on <b>synthetic</b> order history built so
 * that the right answer is known. The key scenario: one very popular item ({@code EVAL-N1}) sits
 * in most orders, so a naive "most often seen together" ranking recommends it for everything. The
 * Jaccard ranking should instead recommend each product's genuine partner. This proves the SQL
 * and ranking behave as designed; it says nothing about quality on real customers.
 */
class RecommendationQualityIT extends AbstractPostgresIntegrationTest {

    private static final String CUSTOMER = "eval-history";
    private static final int PAIRS = 5;

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RecommendationService service;

    @BeforeEach
    void rebuildSyntheticHistory() {
        jdbc.update("DELETE FROM orders WHERE customer_id = ?", CUSTOMER);
        for (int i = 1; i <= PAIRS; i++) {
            // 40 orders contain A_i. 30 of them also contain the popular N1; 15 also contain B_i.
            for (int k = 0; k < 40; k++) {
                java.util.ArrayList<String> skus = new java.util.ArrayList<>(List.of("EVAL-A" + i));
                if (k < 30) skus.add("EVAL-N1");
                if (k >= 25) skus.add("EVAL-B" + i);
                insertOrder("CONFIRMED", skus);
            }
        }
        // N1 ends up in 310 orders in total (150 above + 160 here); the filler pairs it with one-off junk.
        for (int k = 0; k < 160; k++) {
            insertOrder("SHIPPED", List.of("EVAL-N1", "EVAL-J" + k));
        }
    }

    @Test
    void recommends_the_real_partner_not_the_popular_item() {
        for (int i = 1; i <= PAIRS; i++) {
            List<Recommendation> top = service.recommend(List.of("EVAL-A" + i), 1);
            assertThat(top).hasSize(1);
            assertThat(top.get(0).sku()).as("partner of A%d", i).isEqualTo("EVAL-B" + i);
            assertThat(top.get(0).reason()).isEqualTo(Recommendation.Reason.BOUGHT_TOGETHER);
        }
    }

    @Test
    void a_raw_co_occurrence_count_would_have_picked_the_popular_item_instead() {
        // Guards the test itself: if this fails, the scenario no longer distinguishes the two rankings.
        for (int i = 1; i <= PAIRS; i++) {
            String byCount = jdbc.queryForObject("""
                    SELECT b.sku FROM order_item a JOIN order_item b ON a.order_fk = b.order_fk AND a.sku <> b.sku
                     WHERE a.sku = ? AND a.order_fk IN (SELECT id FROM orders WHERE customer_id = ?)
                     GROUP BY b.sku ORDER BY COUNT(*) DESC, b.sku LIMIT 1
                    """, String.class, "EVAL-A" + i, CUSTOMER);
            assertThat(byCount).isEqualTo("EVAL-N1");
        }
    }

    @Test
    void cart_with_two_items_ranks_by_combined_similarity_and_never_suggests_cart_items() {
        List<Recommendation> result = service.recommend(List.of("EVAL-A1", "EVAL-B1"), 5);
        assertThat(result).extracting(Recommendation::sku).doesNotContain("EVAL-A1", "EVAL-B1");
        assertThat(result.get(0).sku()).isEqualTo("EVAL-N1");
    }

    @Test
    void rejected_and_cancelled_orders_are_not_evidence() {
        for (int k = 0; k < 10; k++) {
            insertOrder("REJECTED", List.of("EVAL-A1", "EVAL-GHOST"));
            insertOrder("CANCELLED", List.of("EVAL-A1", "EVAL-GHOST"));
        }
        assertThat(service.recommend(List.of("EVAL-A1"), 20)).extracting(Recommendation::sku)
                .doesNotContain("EVAL-GHOST");
    }

    @Test
    void pairs_seen_fewer_than_min_support_times_are_ignored() {
        insertOrder("CONFIRMED", List.of("EVAL-A2", "EVAL-ONCE"));
        assertThat(service.recommend(List.of("EVAL-A2"), 20)).extracting(Recommendation::sku)
                .doesNotContain("EVAL-ONCE");
    }

    @Test
    void unknown_sku_falls_back_to_popular_items_and_says_so() {
        List<Recommendation> result = service.recommend(List.of("EVAL-NEVER-ORDERED"), 3);
        assertThat(result).hasSize(3);
        assertThat(result).allMatch(r -> r.reason() == Recommendation.Reason.POPULAR);
        assertThat(result.get(0).sku()).isEqualTo("EVAL-N1");
    }

    @Test
    void demo_seed_history_recommends_the_keyboard_for_a_mouse() {
        assertThat(service.recommend(List.of("SKU-MOUSE"), 2)).extracting(Recommendation::sku)
                .first().isEqualTo("SKU-KEYBOARD");
    }

    private void insertOrder(String status, List<String> skus) {
        Long id = jdbc.queryForObject("""
                INSERT INTO orders (order_number, reservation_id, customer_id, status, total_amount)
                VALUES ('EV-' || nextval('order_number_seq'), gen_random_uuid(), ?, ?, 0) RETURNING id
                """, Long.class, CUSTOMER, status);
        for (String sku : skus) {
            jdbc.update("INSERT INTO order_item (order_fk, sku, quantity, unit_price) VALUES (?, ?, 1, 1)", id, sku);
        }
    }
}
