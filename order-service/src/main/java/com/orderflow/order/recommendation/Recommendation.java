package com.orderflow.order.recommendation;

/**
 * One suggested product.
 *
 * @param sku            suggested SKU
 * @param score          for {@link Reason#BOUGHT_TOGETHER}: summed Jaccard similarity between the
 *                       suggestion's order set and each seed SKU's order set (0..1 per seed); for
 *                       {@link Reason#POPULAR}: 0
 * @param coOccurrences  number of orders in which the suggestion appeared with a seed SKU
 *                       (for POPULAR: number of orders containing it)
 * @param reason         why it was suggested
 */
public record Recommendation(String sku, double score, long coOccurrences, Reason reason) {

    public enum Reason {
        /** Appears in the same orders as the seed SKU(s). */
        BOUGHT_TOGETHER,
        /** Cold-start filler: most-ordered SKUs overall, used when there is too little co-purchase data. */
        POPULAR
    }
}
