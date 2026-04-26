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
            @JsonAlias("pending_bash_commands") @JsonProperty("pendingBashCommands") List<String> pendingBashCommands
    ) {
    }
}
