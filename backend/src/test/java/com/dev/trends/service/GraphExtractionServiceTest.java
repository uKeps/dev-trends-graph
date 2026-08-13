package com.dev.trends.service;

import com.dev.trends.service.GraphExtractionService.Article;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A amostragem do prompt existe para caber no limite de tokens por minuto da Groq: se ela
 * devolver artigos demais o request volta 413, e se ela perder fontes a curadoria fica cega
 * para as últimas plataformas da lista concatenada.
 */
class GraphExtractionServiceTest {

    private final GraphExtractionService service =
            new GraphExtractionService(null, null, new ObjectMapper());

    /** Reproduz a ordem real de fetchAllArticles: as fontes chegam concatenadas, não intercaladas. */
    private static List<Article> collected(int hn, int devto, int lobsters, int stackoverflow) {
        List<Article> articles = new ArrayList<>();
        record Source(String platform, int count) {}
        for (Source s : List.of(new Source("hackernews", hn), new Source("devto", devto),
                new Source("lobsters", lobsters), new Source("stackoverflow", stackoverflow))) {
            for (int i = 0; i < s.count(); i++) {
                String id = s.platform() + "-" + i;
                articles.add(new Article(id, "Título " + id, "https://x/" + id, "https://x/" + id, s.platform()));
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
        // Tamanho real de uma rodada: 60 HN + 60 Dev.to + 25 Lobsters + 60 StackOverflow.
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
        // O índice é calculado por passo fracionário; um passo perto de 1.0 é onde ele estouraria.
        assertThat(service.sampleForPrompt(collected(121, 0, 0, 0))).hasSize(120);
    }
}
