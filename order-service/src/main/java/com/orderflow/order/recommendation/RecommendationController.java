package com.orderflow.order.recommendation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/recommendations")
@Tag(name = "Recommendations", description = "Frequently-bought-together suggestions from order history")
public class RecommendationController {

    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Suggest products to add",
            description = "Pass the SKUs already in the cart as repeated `sku` parameters. Returns "
                    + "BOUGHT_TOGETHER suggestions ranked by order-set similarity, topped up with POPULAR "
                    + "items when history is thin. SKUs in the cart are never suggested. `limit` is capped at 20.")
    public List<Recommendation> recommend(@RequestParam(name = "sku", required = false) List<String> skus,
                                          @RequestParam(name = "limit", defaultValue = "5") int limit) {
        return service.recommend(skus, limit);
    }
}
