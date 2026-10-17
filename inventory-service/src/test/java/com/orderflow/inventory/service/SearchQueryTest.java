package com.orderflow.inventory.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchQueryTest {

    @Test
    void builds_a_prefix_tsquery_per_token() {
        SearchQuery q = SearchQuery.parse("USB C hub");
        assertThat(q.text()).isEqualTo("usb c hub");
        assertThat(q.tsQuery()).isEqualTo("usb:* & c:* & hub:*");
    }

    @Test
    void strips_tsquery_operators_wildcards_and_quotes() {
        SearchQuery q = SearchQuery.parse("mouse' | !(keyboard) & %_\\");
        assertThat(q.text()).isEqualTo("mouse keyboard");
        assertThat(q.tsQuery()).isEqualTo("mouse:* & keyboard:*");
    }

    @Test
    void blank_null_and_punctuation_only_input_is_empty() {
        assertThat(SearchQuery.parse(null).isEmpty()).isTrue();
        assertThat(SearchQuery.parse("   ").isEmpty()).isTrue();
        assertThat(SearchQuery.parse("!!! --- ???").isEmpty()).isTrue();
    }

    @Test
    void collapses_repeated_whitespace() {
        assertThat(SearchQuery.parse("  web   cam ").text()).isEqualTo("web cam");
    }
}
