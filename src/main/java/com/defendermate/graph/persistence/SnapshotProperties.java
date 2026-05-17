package com.defendermate.graph.persistence;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalized configuration for graph persistence, bound from properties
 * prefixed with {@code snapshot}.
 *
 * <p>Defaults:
 * <ul>
 *   <li>{@code snapshot.enabled}          = {@code true}</li>
 *   <li>{@code snapshot.path}             = {@code ./graph-snapshot.json}</li>
 *   <li>{@code snapshot.interval-seconds} = {@code 60}</li>
 * </ul>
 *
 * <p>Example {@code application.properties}:
 * <pre>
 * snapshot.enabled=true
 * snapshot.path=/var/data/graph-snapshot.json
 * snapshot.interval-seconds=30
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "snapshot")
public class SnapshotProperties {

    /** Whether periodic and shutdown snapshotting is active. */
    private boolean enabled = true;

    /**
     * Filesystem path where the snapshot JSON file is written.
     * The parent directory will be created if it does not already exist.
     */
    private String path = "src/main/resources/graph-snapshot.json";

    /**
     * Interval between successive background snapshots, in seconds.
     * A value of {@code 0} disables periodic saving (shutdown save still occurs).
     */
    private int intervalSeconds = 60;
}
