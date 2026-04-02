import http from "node:http";
import "dotenv/config";
import { App } from "octokit";
import { createNodeMiddleware } from "@octokit/webhooks";

import { config } from "./config.js";
import { loadRepository, upsertWorkflowFile, dispatchWorkflow } from "./github-service.js";
import { renderWorkflowTemplate } from "./workflow-template.js";

const actionUses = `${config.actionRepo}@${config.actionRef}`;
const workflowContent = renderWorkflowTemplate(actionUses);

const app = new App({
  appId: config.appId,
  privateKey: config.privateKey,
  webhooks: {
    secret: config.webhookSecret
  }
});

function logInfo(message, metadata = {}) {
  const suffix = Object.keys(metadata).length ? ` ${JSON.stringify(metadata)}` : "";
  console.log(`[jaipilot-app] ${message}${suffix}`);
}

function logError(message, error, metadata = {}) {
  const payload = {
    ...metadata,
    error: error instanceof Error ? error.message : String(error)
  };
  console.error(`[jaipilot-app] ${message} ${JSON.stringify(payload)}`);
}

async function ensureWorkflow({ installationId, owner, repo, reason }) {
  const octokit = await app.getInstallationOctokit(installationId);
  const repository = await loadRepository(octokit, owner, repo);

  if (repository.archived || repository.disabled) {
    logInfo("Skipping archived or disabled repository.", { owner, repo, reason });
    return {
      owner,
      repo,
      defaultBranch: repository.default_branch,
      workflowStatus: "skipped"
    };
  }

  const result = await upsertWorkflowFile({
    octokit,
    owner,
    repo,
    path: config.workflowPath,
    defaultBranch: repository.default_branch,
    commitMessage: config.workflowCommitMessage,
    content: workflowContent
  });

  logInfo("Workflow file processed.", {
    owner,
    repo,
    reason,
    status: result.status,
    branch: repository.default_branch
  });

  return {
    owner,
    repo,
    defaultBranch: repository.default_branch,
    workflowStatus: result.status
  };
}

async function maybeDispatch({
  installationId,
  owner,
  repo,
  ref,
  source,
  enabled
}) {
  if (!enabled) {
    return;
  }

  const octokit = await app.getInstallationOctokit(installationId);
  await dispatchWorkflow({
    octokit,
    owner,
    repo,
    workflowPath: config.workflowPath,
    ref,
    source
  });

  logInfo("Workflow dispatch triggered.", { owner, repo, ref, source });
}

async function bootstrapRepositories(installationId, repositories, reason) {
  for (const repository of repositories) {
    const owner = repository.owner.login;
    const repo = repository.name;
    try {
      const ensured = await ensureWorkflow({
        installationId,
        owner,
        repo,
        reason
      });

      await maybeDispatch({
        installationId,
        owner,
        repo,
        ref: ensured.defaultBranch,
        source: reason,
        enabled: config.dispatchOnInstall
      });
    } catch (error) {
      logError("Failed to bootstrap repository.", error, {
        owner,
        repo,
        reason
      });
    }
  }
}

app.webhooks.on("installation.created", async ({ payload }) => {
  if (!config.bootstrapOnInstall) {
    logInfo("installation.created received; bootstrap on install disabled.");
    return;
  }
  await bootstrapRepositories(
    payload.installation.id,
    payload.repositories ?? [],
    "installation.created"
  );
});

app.webhooks.on("installation_repositories.added", async ({ payload }) => {
  if (!config.bootstrapOnInstall) {
    logInfo("installation_repositories.added received; bootstrap on install disabled.");
    return;
  }
  await bootstrapRepositories(
    payload.installation.id,
    payload.repositories_added ?? [],
    "installation_repositories.added"
  );
});

for (const action of ["opened", "synchronize", "reopened"]) {
  app.webhooks.on(`pull_request.${action}`, async ({ payload }) => {
    const installationId = payload.installation?.id;
    if (!installationId) {
      logInfo("Skipping pull_request event without installation context.");
      return;
    }

    const owner = payload.repository.owner.login;
    const repo = payload.repository.name;

    if (config.bootstrapOnPullRequest) {
      try {
        await ensureWorkflow({
          installationId,
          owner,
          repo,
          reason: `pull_request.${action}`
        });
      } catch (error) {
        logError("Failed to ensure workflow on pull_request event.", error, {
          owner,
          repo,
          action
        });
      }
    }

    if (!config.dispatchOnPullRequest) {
      return;
    }

    if (payload.pull_request.head.repo.full_name !== payload.repository.full_name) {
      logInfo("Skipping workflow dispatch for fork pull request.", {
        owner,
        repo,
        action,
        headRepo: payload.pull_request.head.repo.full_name,
        baseRepo: payload.repository.full_name
      });
      return;
    }

    try {
      await maybeDispatch({
        installationId,
        owner,
        repo,
        ref: payload.pull_request.head.ref,
        source: `pull_request.${action}`,
        enabled: true
      });
    } catch (error) {
      logError("Failed to dispatch workflow from pull_request event.", error, {
        owner,
        repo,
        action,
        ref: payload.pull_request.head.ref
      });
    }
  });
}

app.webhooks.onError((error) => {
  logError("Webhook processing error.", error);
});

const webhookMiddleware = createNodeMiddleware(app.webhooks, {
  path: config.webhookPath
});

const server = http.createServer((req, res) => {
  if (req.method === "GET" && req.url === config.healthPath) {
    res.statusCode = 200;
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify({ status: "ok" }));
    return;
  }
  webhookMiddleware(req, res);
});

server.listen(config.port, () => {
  logInfo("GitHub App server started.", {
    port: config.port,
    webhookPath: config.webhookPath,
    actionUses,
    workflowPath: config.workflowPath,
    bootstrapOnInstall: config.bootstrapOnInstall,
    bootstrapOnPullRequest: config.bootstrapOnPullRequest,
    dispatchOnInstall: config.dispatchOnInstall,
    dispatchOnPullRequest: config.dispatchOnPullRequest
  });
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    logInfo(`Received ${signal}; shutting down.`);
    server.close(() => process.exit(0));
  });
}
