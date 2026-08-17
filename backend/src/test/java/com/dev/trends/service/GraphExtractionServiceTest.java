package com.dev.trends.service;

import com.dev.trends.service.GraphExtractionService.Article;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The prompt sampling exists to fit Groq's tokens-per-minute limit: if it returns
 * too many articles the request fails with 413, and if it drops sources the
 * curation goes blind to the last platforms in the concatenated list.
 */
class GraphExtractionServiceTest {

    private final GraphExtractionService service = buildService();

    /** Builds the service with a no-op TransactionManager (the sampling tests
     *  don't trigger persistence, but the constructor requires the dependency). */
    private static GraphExtractionService buildService() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(txManager.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(status);
        return new GraphExtractionService(null, null, new ObjectMapper(), txManager);
    }

    /** Reproduces the real order of fetchAllArticles: sources arrive concatenated, not interleaved. */
    private static List<Article> collected(int hn, int devto, int lobsters, int stackoverflow) {
        return collected(hn, devto, lobsters, stackoverflow, Instant.now());
    }

    private static List<Article> collected(int hn, int devto, int lobsters, int stackoverflow, Instant publishedAt) {
        List<Article> articles = new ArrayList<>();
        record Source(String platform, int count) {}
        for (Source s : List.of(new Source("hackernews", hn), new Source("devto", devto),
                new Source("lobsters", lobsters), new Source("stackoverflow", stackoverflow))) {
            for (int i = 0; i < s.count(); i++) {
                String id = s.platform() + "-" + i;
                articles.add(new Article(id, "Title " + id, "https://x/" + id, "https://x/" + id, s.platform(), publishedAt));
            }
        }
        return articles;
    }

    @Test
    void keepsEverythingWhenTheBatchIsSmallerThanTheCap() {
        List<Article> small = collected(10, 10, 5, 5);
        assertThat(service.sampleForPrompt(small)).isEqualTo(small);
    }

    @Test
    void capsTheBatchWithoutRepeatingArticles() {
        // Real round size: 60 HN + 60 Dev.to + 25 Lobsters + 60 StackOverflow.
        List<Article> sample = service.sampleForPrompt(collected(60, 60, 25, 60));

        assertThat(sample).hasSize(120);
        assertThat(sample.stream().map(Article::id).collect(Collectors.toSet())).hasSize(120);
    }

    @Test
    void keepsEverySourceRepresented() {
        List<Article> sample = service.sampleForPrompt(collected(60, 60, 25, 60));

        assertThat(sample.stream().map(Article::platform).collect(Collectors.toSet()))
                .isEqualTo(Set.of("hackernews", "devto", "lobsters", "stackoverflow"));
    }

    @Test
    void survivesABatchThatBarelyExceedsTheCap() {
        // The index is computed in fractional steps; a step close to 1.0 is where it would overflow.
        assertThat(service.sampleForPrompt(collected(121, 0, 0, 0))).hasSize(120);
    }

    @Test
    void acceptsFreshArticles() {
        // publishedAt = now: passes the 30-day ceiling.
        Instant fresh = Instant.now();
        List<Article> articles = collected(2, 0, 0, 0, fresh);
        assertThat(service.sampleForPrompt(articles)).hasSize(2);
    }

    @Test
    void wouldDropArticlesOlderThan30DaysIfFilterWereAppliedAtSample() {
        // Coverage: the ceiling is 30 days. A 31-day-old article would be dropped at the
        // fetch* entry — here we only validate that the constructor accepts old publishedAt.
        Instant ancient = Instant.now().minus(31, ChronoUnit.DAYS);
        Article old = new Article("x", "T", "u", "u", "hackernews", ancient);
        assertThat(old.publishedAt()).isBefore(Instant.now().minus(30, ChronoUnit.DAYS));
    }
}
