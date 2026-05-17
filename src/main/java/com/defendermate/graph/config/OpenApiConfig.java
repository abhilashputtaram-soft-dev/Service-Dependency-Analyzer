package com.defendermate.graph.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configures the OpenAPI 3 specification metadata surfaced at {@code /v3/api-docs}
 * and rendered by Swagger UI at {@code /swagger-ui.html}.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI serviceDepAnalyzerOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Service Dependency Analyzer API")
                        .description(
                                "REST API for querying and managing an in-memory directed graph of service " +
                                "dependencies driven by a real-time event ingestion pipeline.\n\n" +
                                "**Graph Queries** expose read-only analytical operations: reachability traversal, " +
                                "shortest-path, cycle detection, and critical service ranking.\n\n" +
                                "**Health** provides per-service operational metrics (p95 latency, failure rate, " +
                                "timeout rate) over a configurable look-back window.\n\n" +
                                "**Ingestion** accepts bulk random-event generation and single hand-crafted events " +
                                "for direct graph manipulation.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("DefenderMate Engineering")
                                .email("engineering@defendermate.com")))
                .tags(List.of(
                        new Tag()
                                .name("Graph Queries")
                                .description("Read-only graph traversal and structural analysis — " +
                                        "reachability, shortest path, cycles, and critical service ranking"),
                        new Tag()
                                .name("Health")
                                .description("Per-service operational health metrics over a sliding time window: " +
                                        "p95 latency, error rate, and timeout rate"),
                        new Tag()
                                .name("Ingestion")
                                .description("Event ingestion pipeline — publish random events in bulk or " +
                                        "hand-craft a single event for direct graph manipulation")));
    }
}
