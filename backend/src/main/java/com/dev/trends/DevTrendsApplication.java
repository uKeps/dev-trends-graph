package com.dev.trends;

import com.dev.trends.service.GraphExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@SpringBootApplication
@EnableScheduling
public class DevTrendsApplication {

    private static final Logger log = LoggerFactory.getLogger(DevTrendsApplication.class);

    private final GraphExtractionService graphExtractionService;

    public DevTrendsApplication(GraphExtractionService graphExtractionService) {
        this.graphExtractionService = graphExtractionService;
    }

    public static void main(String[] args) {
        SpringApplication.run(DevTrendsApplication.class, args);
    }

    /**
     * Executa o pipeline de ingestão automaticamente a cada 6 horas.
     * No plano Free do Render, o serviço hiberna após inatividade,
     * então o scheduler garante que os dados sejam atualizados periodicamente.
     */
    @Scheduled(fixedRateString = "PT6H", initialDelayString = "PT2M")
    public void scheduledIngestion() {
        log.info("[Scheduler] Iniciando ingestão agendada multi-fonte...");
        try {
            var result = graphExtractionService.runIngestionPipeline();
            log.info("[Scheduler] Ingestão concluída. Nós: {}, Arestas: {}",
                    result.nodes().size(), result.edges().size());
        } catch (Exception e) {
            log.error("[Scheduler] Falha na ingestão agendada: {}", e.getMessage(), e);
        }
    }
}
