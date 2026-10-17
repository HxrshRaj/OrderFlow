package com.orderflow.order.recommendation;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static com.orderflow.order.recommendation.Recommendation.Reason.BOUGHT_TOGETHER;
import static com.orderflow.order.recommendation.Recommendation.Reason.POPULAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationServiceTest {

    private final RecommendationRepository repository = mock(RecommendationRepository.class);
    private final RecommendationService service = new RecommendationService(repository, 2);

    @Test
    void returns_bought_together_when_there_is_enough_history() {
        List<Recommendation> together = List.of(
                new Recommendation("B", 0.9, 10, BOUGHT_TOGETHER),
                new Recommendation("C", 0.5, 6, BOUGHT_TOGETHER));
        when(repository.boughtTogether(anyCollection(), eq(2), eq(2))).thenReturn(together);

        assertThat(service.recommend(List.of("A"), 2)).isEqualTo(together);
        verify(repository, never()).popular(anyCollection(), anyInt());
    }

    @Test
    void tops_up_with_popular_items_and_excludes_cart_and_already_suggested_skus() {
        when(repository.boughtTogether(anyCollection(), eq(2), eq(3)))
                .thenReturn(List.of(new Recommendation("B", 0.9, 10, BOUGHT_TOGETHER)));
        when(repository.popular(anyCollection(), eq(2)))
                .thenReturn(List.of(new Recommendation("P1", 0, 40, POPULAR), new Recommendation("P2", 0, 30, POPULAR)));

        List<Recommendation> result = service.recommend(List.of("A"), 3);

        assertThat(result).extracting(Recommendation::sku).containsExactly("B", "P1", "P2");
        assertThat(result).extracting(Recommendation::reason).containsExactly(BOUGHT_TOGETHER, POPULAR, POPULAR);
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(repository).popular(captor.capture(), eq(2));
        assertThat(captor.getValue()).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void empty_cart_skips_the_pairing_query_and_uses_popular_only() {
        when(repository.popular(anyCollection(), eq(5)))
                .thenReturn(List.of(new Recommendation("P1", 0, 40, POPULAR)));

        assertThat(service.recommend(List.of(), 5)).extracting(Recommendation::reason).containsExactly(POPULAR);
        assertThat(service.recommend(null, 5)).hasSize(1);
        verify(repository, never()).boughtTogether(anyCollection(), anyInt(), anyInt());
    }

    @Test
    void blank_and_duplicate_skus_are_ignored_and_limit_is_clamped() {
        when(repository.boughtTogether(anyCollection(), eq(2), eq(RecommendationService.MAX_LIMIT)))
                .thenReturn(List.of());
        when(repository.popular(anyCollection(), eq(RecommendationService.MAX_LIMIT))).thenReturn(List.of());

        service.recommend(List.of("A", " A ", "", "  "), 1000);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(repository).boughtTogether(captor.capture(), eq(2), eq(RecommendationService.MAX_LIMIT));
        assertThat(captor.getValue()).containsExactly("A");
        assertThat(service.recommend(List.of("A"), -3)).isEmpty();
    }
}
