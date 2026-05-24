package com.jaipilot.cli.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FetchJobResponse(
        String status,
        FetchJobOutput output,
        String error,
        String rawOutput
) {

    public String errorMessage() {
        if (error != null && !error.isBlank()) {
            return error;
        }
        if (rawOutput != null && !rawOutput.isBlank()) {
            return rawOutput;
        }
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FetchJobOutput(
            @JsonAlias("session_id") @JsonProperty("sessionId") String sessionId,
            @JsonAlias("final_test_file_path") @JsonProperty("finalTestFilePath") String finalTestFilePath,
            @JsonAlias("final_test_file") @JsonProperty("finalTestFile") String finalTestFile,
            @JsonAlias("status_message") @JsonProperty("statusMessage") String statusMessage,
            @JsonAlias("pending_bash_commands") @JsonProperty("pendingBashCommands") List<String> pendingBashCommands,
            @JsonAlias("coverage_summary") @JsonProperty("coverageSummary") CoverageSummary coverageSummary
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CoverageSummary(
            CoverageSnapshot before,
            CoverageSnapshot after,
            @JsonAlias("delta_percentage_points") @JsonProperty("deltaPercentagePoints") Double deltaPercentagePoints,
            @JsonAlias("snapshot_count") @JsonProperty("snapshotCount") Integer snapshotCount,
            String text
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CoverageSnapshot(
            String command,
            @JsonAlias("primary_percent") @JsonProperty("primaryPercent") Double primaryPercent,
            @JsonAlias("primary_metric") @JsonProperty("primaryMetric") String primaryMetric,
            @JsonAlias("metric_lines") @JsonProperty("metricLines") List<String> metricLines
    ) {
    }
}
