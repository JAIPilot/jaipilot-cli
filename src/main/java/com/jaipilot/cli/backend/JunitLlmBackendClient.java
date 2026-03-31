package com.jaipilot.cli.backend;

import com.jaipilot.cli.model.FetchJobResponse;
import com.jaipilot.cli.model.InvokeJunitLlmRequest;
import com.jaipilot.cli.model.InvokeJunitLlmResponse;

public interface JunitLlmBackendClient {

    InvokeJunitLlmResponse invoke(InvokeJunitLlmRequest request) throws Exception;

    FetchJobResponse fetchJob(String jobId) throws Exception;
}
