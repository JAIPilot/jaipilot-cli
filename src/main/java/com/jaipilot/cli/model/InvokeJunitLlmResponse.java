package com.jaipilot.cli.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InvokeJunitLlmResponse(
        @JsonAlias("job_id") @JsonProperty("jobId") String jobId,
        @JsonAlias("session_id") @JsonProperty("sessionId") String sessionId
) {
}
