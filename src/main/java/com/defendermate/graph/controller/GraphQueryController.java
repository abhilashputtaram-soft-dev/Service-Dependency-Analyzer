package com.defendermate.graph.controller;

import com.defendermate.graph.exception.InvalidKValueException;
import com.defendermate.graph.exception.InvalidRequestException;
import com.defendermate.graph.exception.InvalidShortestPathException;
import com.defendermate.graph.exception.InvalidWindowFormatException;
import com.defendermate.graph.graph.GraphQueryService;
import com.defendermate.graph.model.dto.CriticalServicesResponse;
import com.defendermate.graph.model.dto.CyclesResponse;
import com.defendermate.graph.model.dto.DependentsResponse;
import com.defendermate.graph.model.dto.HealthMetrics;
import com.defendermate.graph.model.dto.ReachableResponse;
import com.defendermate.graph.model.dto.ShortestPathResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST controller exposing read-only analytical queries against the service dependency graph.
 *
 * <p>All endpoints are prefixed with {@code /api/graph}. Business logic lives entirely in
 * {@link GraphQueryService}; this controller is responsible only for HTTP mapping and
 * input validation. Invalid inputs are signalled by throwing domain exceptions which are
 * translated to structured JSON error responses by the centralized
 * {@link com.defendermate.graph.exception.GlobalExceptionHandler}.
 *
 * <p>Each endpoint logs the query at INFO on entry and at DEBUG on completion (with result
 * size and latency). Queries exceeding {@link #SLOW_QUERY_WARN_MS} are logged at WARN.
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
@Tag(name = "Graph Queries", description = "Analytical read-only queries against the in-memory service dependency graph")
public class GraphQueryController {

    /** Accepted window formats: a positive integer followed by {@code m} (minutes) or {@code h} (hours). */
    private static final Pattern WINDOW_PATTERN = Pattern.compile("^(\\d+)([mh])$");

    /**
     * Query execution time in milliseconds above which a WARN log is emitted.
     * Allows operators to identify unexpectedly slow graph traversals without
     * enabling DEBUG logging globally.
     */
    private static final long SLOW_QUERY_WARN_MS = 1_000;

    private final GraphQueryService queryService;

    public GraphQueryController(GraphQueryService queryService) {
        this.queryService = queryService;
    }

    // -------------------------------------------------------------------------
    // Reachability
    // -------------------------------------------------------------------------

    /**
     * Returns every downstream service reachable from {@code service} by following
     * outgoing dependency edges, together with the DFS-first path to each one.
     *
     * @param service the source service name (path variable)
     * @return {@code 200 OK} with a {@link ReachableResponse}
     * @throws InvalidRequestException if {@code service} is blank
     */
    @Operation(
            summary = "List all downstream reachable services",
            description = "Traverses outgoing dependency edges with DFS from the given service and returns " +
                    "every reachable downstream service together with the path taken to reach it. " +
                    "Returns an empty list when the service is unknown or has no outgoing edges.",
            tags = {"Graph Queries"})
    @ApiResponse(responseCode = "200", description = "Reachability result (empty paths list when service is a leaf)",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ReachableResponse.class)))
    @ApiResponse(responseCode = "400", description = "Service name is blank",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/reachable/{service}")
    public ResponseEntity<ReachableResponse> reachable(
            @Parameter(description = "Source service name", example = "checkout-api", required = true)
            @PathVariable String service) {
        log.info("Query received query=reachable service={}", service);
        if (service.isBlank()) {
            throw new InvalidRequestException("service must not be blank");
        }
        long startMs = System.currentTimeMillis();
        ReachableResponse result = queryService.reachable(service);
        logQueryCompleted("reachable", startMs, "reachableCount", result.getTotalReachableServices());
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // Dependents
    // -------------------------------------------------------------------------

    /**
     * Returns all upstream services that directly or transitively depend on {@code service},
     * together with the path from each dependent to {@code service}.
     *
     * @param service the target service name (path variable)
     * @return {@code 200 OK} with a {@link DependentsResponse}
     * @throws InvalidRequestException if {@code service} is blank
     */
    @Operation(
            summary = "List all upstream dependent services",
            description = "Traverses incoming dependency edges with reverse-DFS from the given service and returns " +
                    "every upstream service that directly or transitively depends on it, " +
                    "together with its traversal path. Returns an empty list when no services depend on the target.",
            tags = {"Graph Queries"})
    @ApiResponse(responseCode = "200", description = "Dependents result (empty list when no upstream services exist)",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = DependentsResponse.class)))
    @ApiResponse(responseCode = "400", description = "Service name is blank",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/dependents/{service}")
    public ResponseEntity<DependentsResponse> dependents(
            @Parameter(description = "Target service name to find upstream dependents for",
                    example = "payment-service", required = true)
            @PathVariable String service) {
        log.info("Query received query=dependents service={}", service);
        if (service.isBlank()) {
            throw new InvalidRequestException("service must not be blank");
        }
        long startMs = System.currentTimeMillis();
        DependentsResponse result = queryService.dependents(service);
        logQueryCompleted("dependents", startMs, "dependentsCount", result.getTotalDependents());
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // Shortest path
    // -------------------------------------------------------------------------

    /**
     * Returns the shortest dependency path from {@code source} to {@code target}
     * using Dijkstra's algorithm over unit-weight edges.
     *
     * @param source the originating service name
     * @param target the destination service name
     * @return {@code 200 OK} with a {@link ShortestPathResponse}
     * @throws InvalidRequestException      if either name is blank
     * @throws InvalidShortestPathException if source and target are identical
     */
    @Operation(
            summary = "Find the shortest dependency path between two services",
            description = "Applies Dijkstra's algorithm over edge avgLatencyMs weights to find the minimum-latency " +
                    "dependency path from source to target. Returns reachable=false and an empty path list when " +
                    "no path exists. Source and target must be different service names.",
            tags = {"Graph Queries"})
    @ApiResponse(responseCode = "200",
            description = "Shortest path result; reachable=false with empty path when target is unreachable",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ShortestPathResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "Either name is blank, or source and target are identical",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/shortest-path")
    public ResponseEntity<ShortestPathResponse> shortestPath(
            @Parameter(description = "Source service name (must differ from target)",
                    example = "checkout-api", required = true)
            @RequestParam String source,
            @Parameter(description = "Target service name (must differ from source)",
                    example = "bank-gateway", required = true)
            @RequestParam String target) {

        log.info("Query received query=shortest-path source={} target={}", source, target);
        if (source.isBlank()) {
            throw new InvalidRequestException("source must not be blank");
        }
        if (target.isBlank()) {
            throw new InvalidRequestException("target must not be blank");
        }
        if (source.equals(target)) {
            throw new InvalidShortestPathException("source and target must not be identical");
        }
        long startMs = System.currentTimeMillis();
        ShortestPathResponse result = queryService.shortestPath(source, target);
        logQueryCompleted("shortest-path", startMs, "reachable", result.isReachable());
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // Critical services
    // -------------------------------------------------------------------------

    /**
     * Returns the top-{@code k} services ranked by the number of upstream dependents,
     * i.e. the services whose failure would affect the most other services.
     *
     * @param k the number of critical services to return (must be &gt; 0)
     * @return {@code 200 OK} with a {@link CriticalServicesResponse}
     * @throws InvalidKValueException if {@code k} is zero or negative
     */
    @Operation(
            summary = "Rank the top-K most critical services",
            description = "Returns the K services with the highest number of direct upstream dependents, " +
                    "i.e. the services whose failure would impact the most other services. " +
                    "Ties are broken by total impacted request traffic. k must be greater than 0.",
            tags = {"Graph Queries"})
    @ApiResponse(responseCode = "200", description = "Top-K critical services ordered by dependent count",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = CriticalServicesResponse.class)))
    @ApiResponse(responseCode = "400", description = "k is zero or negative",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/critical-services")
    public ResponseEntity<CriticalServicesResponse> criticalServices(
            @Parameter(description = "Number of top critical services to return; must be > 0",
                    example = "5", required = true)
            @RequestParam int k) {
        log.info("Query received query=critical-services k={}", k);
        if (k <= 0) {
            throw new InvalidKValueException("k must be greater than 0");
        }
        long startMs = System.currentTimeMillis();
        CriticalServicesResponse result = queryService.criticalServices(k);
        logQueryCompleted("critical-services", startMs, "returnedCount", result.getCriticalServices().size());
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // Cycles
    // -------------------------------------------------------------------------

    /**
     * Detects all distinct dependency cycles in the graph.
     *
     * @return {@code 200 OK} with a {@link CyclesResponse} containing every unique cycle
     *         found; the list is empty when the graph is acyclic
     */
    @Operation(
            summary = "Detect all circular dependency cycles",
            description = "Runs Tarjan's or DFS-based cycle detection across the full graph and returns every " +
                    "distinct circular dependency found. The cycle path always repeats the entry service as " +
                    "the last element to make the closure explicit. Returns an empty list when the graph is acyclic.",
            tags = {"Graph Queries"})
    @ApiResponse(responseCode = "200",
            description = "All distinct cycles; totalCycles=0 and empty list when the graph is acyclic",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = CyclesResponse.class)))
    @GetMapping("/cycles")
    public ResponseEntity<CyclesResponse> cycles() {
        log.info("Query received query=cycles");
        long startMs = System.currentTimeMillis();
        CyclesResponse result = queryService.cycles();
        logQueryCompleted("cycles", startMs, "cycleCount", result.getCycles().size());
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // Health
    // -------------------------------------------------------------------------

    /**
     * Returns an operational health snapshot for {@code service} over the specified
     * look-back {@code window}, including p95 latency, failure rate, and timeout rate.
     *
     * <p>Supported window formats: {@code 5m}, {@code 10m}, {@code 1h}, etc.
     * (a positive integer followed by {@code m} for minutes or {@code h} for hours).
     *
     * @param service the service name to analyse (path variable)
     * @param window  look-back window string, e.g. {@code "5m"} or {@code "1h"}
     * @return {@code 200 OK} with a {@link HealthMetrics}
     * @throws InvalidRequestException      if {@code service} is blank
     * @throws InvalidWindowFormatException if {@code window} does not match the accepted format
     */
    @Operation(
            summary = "Get operational health metrics for a service",
            description = "Computes p95 latency, error rate, and timeout rate for the given service over a " +
                    "configurable look-back window. Returns zeroed metrics when no telemetry data exists " +
                    "for the service in the window.\n\n" +
                    "**Window format:** a positive integer followed by `m` (minutes) or `h` (hours). " +
                    "Examples: `5m`, `30m`, `1h`, `2h`.",
            tags = {"Health"})
    @ApiResponse(responseCode = "200", description = "Health metrics for the service over the requested window",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = HealthMetrics.class)))
    @ApiResponse(responseCode = "400",
            description = "Service name is blank, or window format is invalid (must match `<int>m` or `<int>h`)",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/health/{service}")
    public ResponseEntity<HealthMetrics> health(
            @Parameter(description = "Service name to analyse", example = "payment-service", required = true)
            @PathVariable String service,
            @Parameter(description = "Look-back window duration — positive integer followed by m (minutes) " +
                    "or h (hours). Examples: 5m, 30m, 1h, 2h", example = "5m", required = true)
            @RequestParam String window) {

        log.info("Query received query=health service={} window={}", service, window);
        if (service.isBlank()) {
            throw new InvalidRequestException("service must not be blank");
        }
        Duration duration = parseWindow(window);
        if (duration == null) {
            throw new InvalidWindowFormatException("window must be valid duration format");
        }
        long startMs = System.currentTimeMillis();
        HealthMetrics result = queryService.health(service, duration);
        logQueryCompleted("health", startMs, "p95LatencyMs", result.getP95LatencyMs());
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // Private helper
    // -------------------------------------------------------------------------

    /**
     * Parses a human-readable window string into a {@link Duration}.
     *
     * <p>Accepts an integer followed by {@code m} (minutes) or {@code h} (hours),
     * e.g. {@code "5m"}, {@code "30m"}, {@code "2h"}.
     *
     * @param window raw window string from the request
     * @return the parsed {@link Duration}, or {@code null} if the format is invalid
     */
    private Duration parseWindow(String window) {
        if (window == null || window.isBlank()) {
            return null;
        }
        Matcher m = WINDOW_PATTERN.matcher(window.trim());
        if (!m.matches()) {
            return null;
        }
        long value = Long.parseLong(m.group(1));
        if (value == 0) {
            return null;
        }
        return "h".equals(m.group(2)) ? Duration.ofHours(value) : Duration.ofMinutes(value);
    }

    /**
     * Logs query completion at DEBUG, or at WARN if the query exceeded
     * {@link #SLOW_QUERY_WARN_MS}.
     *
     * @param query    short name of the query endpoint
     * @param startMs  wall-clock time when the query execution started
     * @param resultKey  label for the primary result metric (e.g. "reachableCount")
     * @param resultValue value of that metric
     */
    private void logQueryCompleted(String query, long startMs, String resultKey, Object resultValue) {
        long durationMs = System.currentTimeMillis() - startMs;
        if (durationMs > SLOW_QUERY_WARN_MS) {
            log.warn("Slow query detected query={} durationMs={} {}={}",
                    query, durationMs, resultKey, resultValue);
        } else {
            log.debug("Query completed query={} durationMs={} {}={}",
                    query, durationMs, resultKey, resultValue);
        }
    }
}
