package com.orderflow.order.recommendation;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * SQL for item-to-item "bought together" recommendations. Only CONFIRMED and SHIPPED orders
 * count: rejected, cancelled and not-yet-held orders are not evidence of what people buy.
 */
@Repository
public class RecommendationRepository {

    /** Candidate SKUs that share orders with any seed SKU, scored by summed Jaccard similarity. */
    private static final String BOUGHT_TOGETHER = """
            WITH eligible AS (
                SELECT DISTINCT oi.order_fk, oi.sku
                  FROM order_item oi
                  JOIN orders o ON o.id = oi.order_fk
                 WHERE o.status IN ('CONFIRMED', 'SHIPPED')
            ),
            sku_orders AS (
                SELECT sku, COUNT(*) AS n FROM eligible GROUP BY sku
            ),
            pairs AS (
                SELECT a.sku AS seed, b.sku AS candidate, COUNT(*) AS co
                  FROM eligible a
                  JOIN eligible b ON b.order_fk = a.order_fk AND b.sku <> a.sku
                 WHERE a.sku IN (:seeds) AND b.sku NOT IN (:seeds)
                 GROUP BY a.sku, b.sku
                HAVING COUNT(*) >= :minSupport
            )
            SELECT p.candidate AS sku,
                   SUM(p.co::float8 / (sa.n + sc.n - p.co)) AS score,
                   SUM(p.co)                                 AS co_occurrences
              FROM pairs p
              JOIN sku_orders sa ON sa.sku = p.seed
              JOIN sku_orders sc ON sc.sku = p.candidate
             GROUP BY p.candidate
             ORDER BY score DESC, co_occurrences DESC, p.candidate
             LIMIT :limit
            """;

    private static final String POPULAR = """
            SELECT oi.sku, COUNT(DISTINCT oi.order_fk) AS n
              FROM order_item oi
              JOIN orders o ON o.id = oi.order_fk
             WHERE o.status IN ('CONFIRMED', 'SHIPPED')
               AND oi.sku NOT IN (:excluded)
             GROUP BY oi.sku
             ORDER BY n DESC, oi.sku
             LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RecommendationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code seeds} must be non-empty. */
    public List<Recommendation> boughtTogether(Collection<String> seeds, int minSupport, int limit) {
        return jdbc.query(BOUGHT_TOGETHER,
                new MapSqlParameterSource()
                        .addValue("seeds", seeds)
                        .addValue("minSupport", minSupport)
                        .addValue("limit", limit),
                (rs, i) -> new Recommendation(rs.getString("sku"), rs.getDouble("score"),
                        rs.getLong("co_occurrences"), Recommendation.Reason.BOUGHT_TOGETHER));
    }

    /** {@code excluded} must be non-empty (pass a sentinel when there is nothing to exclude). */
    public List<Recommendation> popular(Collection<String> excluded, int limit) {
        return jdbc.query(POPULAR,
                new MapSqlParameterSource().addValue("excluded", excluded).addValue("limit", limit),
                (rs, i) -> new Recommendation(rs.getString("sku"), 0.0, rs.getLong("n"),
                        Recommendation.Reason.POPULAR));
    }
}
