package com.yubai.blog.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SearchQueryNormalizerTest {

    @Test
    void normalizesChineseSynonymsTyposAndWhitespaceDeterministically() {
        assertThat(SearchQueryNormalizer.normalize("  教程   Postgre  ")).isEqualTo("指南 postgresql");
        assertThat(SearchQueryNormalizer.normalize("javascirpt")).isEqualTo("javascript");
    }

    @Test
    void blankInputNeverReachesTheRepository() {
        assertThat(SearchQueryNormalizer.normalize(null)).isEmpty();
        assertThat(SearchQueryNormalizer.normalize("  \n\t ")).isEmpty();
    }
}
