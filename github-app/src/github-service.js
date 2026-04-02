const API_HEADERS = {
  "x-github-api-version": "2022-11-28"
};

function normalizeText(value) {
  return value.replace(/\r\n/g, "\n").trimEnd();
}

function encodeBase64(value) {
  return Buffer.from(value, "utf8").toString("base64");
}

function decodeBase64(value) {
  return Buffer.from(value, "base64").toString("utf8");
}

function isNotFound(error) {
  return typeof error === "object" && error !== null && "status" in error && error.status === 404;
}

export async function loadRepository(octokit, owner, repo) {
  const response = await octokit.request("GET /repos/{owner}/{repo}", {
    owner,
    repo,
    headers: API_HEADERS
  });
  return response.data;
}

async function loadExistingFile(octokit, owner, repo, path, ref) {
  try {
    const response = await octokit.request("GET /repos/{owner}/{repo}/contents/{path}", {
      owner,
      repo,
      path,
      ref,
      headers: API_HEADERS
    });
    if (Array.isArray(response.data)) {
      throw new Error(`Expected file at ${path} but GitHub returned a directory listing.`);
    }
    return response.data;
  } catch (error) {
    if (isNotFound(error)) {
      return null;
    }
    throw error;
  }
}

export async function upsertWorkflowFile({
  octokit,
  owner,
  repo,
  path,
  defaultBranch,
  commitMessage,
  content
}) {
  const existing = await loadExistingFile(octokit, owner, repo, path, defaultBranch);
  const existingContent =
    existing && existing.encoding === "base64" && typeof existing.content === "string"
      ? decodeBase64(existing.content.replace(/\n/g, ""))
      : "";

  if (existing && normalizeText(existingContent) === normalizeText(content)) {
    return {
      status: "unchanged",
      sha: existing.sha
    };
  }

  const response = await octokit.request("PUT /repos/{owner}/{repo}/contents/{path}", {
    owner,
    repo,
    path,
    message: commitMessage,
    content: encodeBase64(content),
    branch: defaultBranch,
    sha: existing?.sha,
    headers: API_HEADERS
  });

  return {
    status: existing ? "updated" : "created",
    sha: response.data.content?.sha || null
  };
}

export async function dispatchWorkflow({
  octokit,
  owner,
  repo,
  workflowPath,
  ref,
  source
}) {
  const delays = [0, 1500, 3000];
  let lastError = null;

  for (const delayMs of delays) {
    if (delayMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }

    try {
      await octokit.request(
        "POST /repos/{owner}/{repo}/actions/workflows/{workflow_id}/dispatches",
        {
          owner,
          repo,
          workflow_id: workflowPath,
          ref,
          inputs: {
            source,
            ref
          },
          headers: API_HEADERS
        }
      );
      return;
    } catch (error) {
      const status = typeof error === "object" && error !== null && "status" in error
        ? error.status
        : undefined;
      const canRetry = status === 404 || status === 422;
      lastError = error;
      if (!canRetry) {
        throw error;
      }
    }
  }

  throw lastError;
}
