import { ChildProcessWithoutNullStreams, spawn } from "child_process";
import * as fs from "fs";
import * as https from "https";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";

const COMMAND_ID = "jaipilot.generateForJavaClass";
const CANCEL_COMMAND_ID = "jaipilot.cancelCurrentRun";
const OUTPUT_CHANNEL_NAME = "JAIPilot";
const DEFAULT_CLI_PATH = "jaipilot";
const RUN_IN_PROGRESS_CONTEXT_KEY = "jaipilot.runInProgress";
const ACTION_RELEASE_TAG = "action-v1";
const INSTALL_SCRIPT_URL = `https://raw.githubusercontent.com/JAIPilot/jaipilot-cli/${ACTION_RELEASE_TAG}/install.sh`;
const INSTALL_COMMAND = `curl -fsSL ${INSTALL_SCRIPT_URL} | sh`;
const LATEST_RELEASE_API_URL = "https://api.github.com/repos/JAIPilot/jaipilot-cli/releases/latest";
const RELEASES_API_URL = "https://api.github.com/repos/JAIPilot/jaipilot-cli/releases?per_page=100";
const LOCAL_BIN_DIR = path.join(os.homedir(), ".local", "bin");
const LOCAL_CLI_PATH = path.join(LOCAL_BIN_DIR, "jaipilot");
const CLI_RELEASE_TAG_PATTERN = /^v\d+\.\d+\.\d+(?:\.\d+)*$/;
const VERSION_PATTERN = /\bv?(\d+(?:\.\d+)*(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?)\b/;

type ParsedVersion = {
  core: number[];
  prerelease: string[] | null;
};

type ActiveRun = {
  label: string;
  child: ChildProcessWithoutNullStreams;
  outputChannel: vscode.OutputChannel;
  terminationRequested: boolean;
  killTimer: NodeJS.Timeout | null;
};

let activeRun: ActiveRun | null = null;
let cancelStatusBarItem: vscode.StatusBarItem | null = null;

export function activate(context: vscode.ExtensionContext): void {
  const outputChannel = vscode.window.createOutputChannel(OUTPUT_CHANNEL_NAME);
  context.subscriptions.push(outputChannel);

  cancelStatusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
  cancelStatusBarItem.name = "JAIPilot Cancel";
  cancelStatusBarItem.text = "$(stop-circle) Cancel JAIPilot";
  cancelStatusBarItem.tooltip = "Cancel the active JAIPilot operation";
  cancelStatusBarItem.command = CANCEL_COMMAND_ID;
  cancelStatusBarItem.hide();
  context.subscriptions.push(cancelStatusBarItem);

  void setRunInProgress(false);

  const cancelDisposable = vscode.commands.registerCommand(CANCEL_COMMAND_ID, async () => {
    if (!requestActiveRunCancellation("command", true)) {
      vscode.window.showInformationMessage("No active JAIPilot operation to cancel.");
    }
  });
  context.subscriptions.push(cancelDisposable);

  const generateDisposable = vscode.commands.registerCommand(
    COMMAND_ID,
    async (resource?: vscode.Uri, resources?: vscode.Uri[]) => {
      if (activeRun) {
        const choice = await vscode.window.showWarningMessage(
          `JAIPilot is already running (${activeRun.label}).`,
          "Cancel Current Run",
          "Keep Running"
        );
        if (choice === "Cancel Current Run") {
          requestActiveRunCancellation("command", true);
        }
        return;
      }

      const target = resolveTargetUri(resource, resources);
      if (!target) {
        vscode.window.showErrorMessage("Select a Java class file to run JAIPilot.");
        return;
      }

      const validationError = validateTarget(target);
      if (validationError) {
        vscode.window.showWarningMessage(validationError);
        return;
      }

      const config = vscode.workspace.getConfiguration("jaipilot");
      const cliPath = config.get<string>("cliPath", DEFAULT_CLI_PATH).trim();
      const additionalGenerateArgs = sanitizeArgs(
        config.get<unknown>("additionalGenerateArgs", [])
      );

      if (!cliPath) {
        vscode.window.showErrorMessage("Setting `jaipilot.cliPath` cannot be empty.");
        return;
      }

      const updated = await autoUpdateCli(outputChannel, cliPath);
      if (!updated) {
        return;
      }

      await runGenerate(target, outputChannel, cliPath, additionalGenerateArgs);
    }
  );

  context.subscriptions.push(generateDisposable);
}

function resolveTargetUri(
  resource?: vscode.Uri,
  resources?: vscode.Uri[]
): vscode.Uri | undefined {
  if (resource?.scheme === "file") {
    return resource;
  }

  if (Array.isArray(resources) && resources.length > 0) {
    const firstFileResource = resources.find((uri) => uri.scheme === "file");
    if (firstFileResource) {
      return firstFileResource;
    }
  }

  const activeUri = vscode.window.activeTextEditor?.document.uri;
  if (activeUri?.scheme === "file") {
    return activeUri;
  }

  return undefined;
}

function validateTarget(target: vscode.Uri): string | undefined {
  const filePath = target.fsPath;
  if (path.extname(filePath) !== ".java") {
    return "JAIPilot generation only supports .java files.";
  }

  if (isTestClass(filePath)) {
    return "Selected file looks like a test class. Pick a production Java class.";
  }

  return undefined;
}

function isTestClass(filePath: string): boolean {
  const normalizedPath = filePath.replace(/\\/g, "/");
  const fileName = path.basename(normalizedPath);

  if (normalizedPath.includes("/src/test/")) {
    return true;
  }

  return /(Test|Tests|IT)\.java$/i.test(fileName);
}

function registerActiveRun(
  label: string,
  child: ChildProcessWithoutNullStreams,
  outputChannel: vscode.OutputChannel
): void {
  if (activeRun?.killTimer) {
    clearTimeout(activeRun.killTimer);
  }

  activeRun = {
    label,
    child,
    outputChannel,
    terminationRequested: false,
    killTimer: null
  };

  cancelStatusBarItem?.show();
  void setRunInProgress(true);
}

function clearActiveRun(child: ChildProcessWithoutNullStreams): void {
  if (!activeRun || activeRun.child !== child) {
    return;
  }

  if (activeRun.killTimer) {
    clearTimeout(activeRun.killTimer);
  }

  activeRun = null;
  cancelStatusBarItem?.hide();
  void setRunInProgress(false);
}

function requestActiveRunCancellation(source: "command" | "progress", showMessage: boolean): boolean {
  if (!activeRun) {
    return false;
  }

  if (activeRun.terminationRequested) {
    return true;
  }

  activeRun.terminationRequested = true;
  activeRun.outputChannel.show(true);
  if (source === "command") {
    activeRun.outputChannel.appendLine(
      `[JAIPilot] Cancel requested from command. Terminating ${activeRun.label}...`
    );
  } else {
    activeRun.outputChannel.appendLine(
      `[JAIPilot] Cancellation requested from progress notification. Terminating ${activeRun.label}...`
    );
  }

  if (showMessage) {
    vscode.window.showInformationMessage(`Cancelling JAIPilot ${activeRun.label}...`);
  }

  activeRun.child.kill("SIGTERM");
  activeRun.killTimer = setTimeout(() => {
    if (!activeRun || activeRun.child.killed) {
      return;
    }
    activeRun.outputChannel.appendLine(
      `[JAIPilot] ${activeRun.label} did not exit after SIGTERM. Sending SIGKILL...`
    );
    activeRun.child.kill("SIGKILL");
  }, 2000);

  return true;
}

async function setRunInProgress(value: boolean): Promise<void> {
  await vscode.commands.executeCommand("setContext", RUN_IN_PROGRESS_CONTEXT_KEY, value);
}

async function autoUpdateCli(
  outputChannel: vscode.OutputChannel,
  cliPath: string
): Promise<boolean> {
  if (process.platform === "win32") {
    vscode.window.showErrorMessage(
      "JAIPilot auto-update currently supports macOS/Linux only. Install the latest JAIPilot CLI manually before running."
    );
    return false;
  }

  if (cliPath !== DEFAULT_CLI_PATH) {
    outputChannel.show(true);
    outputChannel.appendLine(
      `[JAIPilot] Skipping CLI auto-update because "jaipilot.cliPath" is custom: ${cliPath}`
    );
    outputChannel.appendLine("");
    return true;
  }

  const resolvedCliPath = resolveCliPath(cliPath);
  const env = buildCliEnv(cliPath);
  const [installedVersion, latestVersion] = await Promise.all([
    resolveInstalledCliVersion(resolvedCliPath, env),
    resolveLatestReleaseVersion()
  ]);

  outputChannel.show(true);
  if (installedVersion && latestVersion) {
    const comparison = compareSemanticVersions(installedVersion, latestVersion);
    if (comparison >= 0) {
      outputChannel.appendLine(
        `[JAIPilot] CLI is up to date (installed: ${installedVersion}, latest: ${latestVersion}).`
      );
      outputChannel.appendLine("");
      return true;
    }

    outputChannel.appendLine(
      `[JAIPilot] CLI update available (installed: ${installedVersion}, latest: ${latestVersion}).`
    );
  } else if (installedVersion && !latestVersion) {
    outputChannel.appendLine(
      `[JAIPilot] Installed CLI version: ${installedVersion}. Could not resolve latest release version; skipping update.`
    );
    outputChannel.appendLine("");
    return true;
  } else if (!installedVersion && !latestVersion) {
    outputChannel.appendLine(
      "[JAIPilot] Could not resolve installed or latest CLI version. Attempting install..."
    );
  } else {
    outputChannel.appendLine("[JAIPilot] JAIPilot CLI not found. Installing latest version...");
  }

  outputChannel.show(true);
  outputChannel.appendLine("[JAIPilot] Updating JAIPilot CLI to latest version...");
  outputChannel.appendLine(`[JAIPilot] ${INSTALL_COMMAND}`);
  outputChannel.appendLine("");

  return vscode.window.withProgress(
    {
      location: vscode.ProgressLocation.Notification,
      title: "JAIPilot: Updating CLI to latest version",
      cancellable: true
    },
    async (_progress, token) =>
      new Promise<boolean>((resolve) => {
        const child = spawn("sh", ["-c", INSTALL_COMMAND], {
          env: process.env,
          shell: false
        });
        registerActiveRun("CLI update", child, outputChannel);

        let finished = false;
        const complete = (result: boolean): void => {
          if (finished) {
            return;
          }
          finished = true;
          resolve(result);
        };

        token.onCancellationRequested(() => {
          requestActiveRunCancellation("progress", false);
        });

        child.stdout.on("data", (data: Buffer) => {
          outputChannel.append(data.toString());
        });

        child.stderr.on("data", (data: Buffer) => {
          outputChannel.append(data.toString());
        });

        child.on("error", (error: NodeJS.ErrnoException) => {
          clearActiveRun(child);
          vscode.window.showErrorMessage(`Failed to update JAIPilot CLI: ${error.message}`);
          outputChannel.appendLine(`[JAIPilot] Update error: ${error.message}`);
          complete(false);
        });

        child.on("close", (code, signal) => {
          clearActiveRun(child);
          if (signal) {
            vscode.window.showWarningMessage(`JAIPilot CLI update was stopped (${signal}).`);
            outputChannel.appendLine(`[JAIPilot] CLI update terminated by signal: ${signal}`);
            complete(false);
            return;
          }

          if (code === 0) {
            outputChannel.appendLine("[JAIPilot] CLI update completed.");
            complete(true);
            return;
          }

          vscode.window.showErrorMessage(
            `JAIPilot CLI update failed with exit code ${code}. Check the "${OUTPUT_CHANNEL_NAME}" output.`
          );
          outputChannel.appendLine(`[JAIPilot] CLI update exited with code ${code}.`);
          complete(false);
        });
      })
  );
}

async function runGenerate(
  target: vscode.Uri,
  outputChannel: vscode.OutputChannel,
  cliPath: string,
  additionalGenerateArgs: string[]
): Promise<void> {
  const workspaceFolder = vscode.workspace.getWorkspaceFolder(target);
  const cwd = workspaceFolder?.uri.fsPath ?? path.dirname(target.fsPath);
  const args = ["generate", ...additionalGenerateArgs, target.fsPath];
  const resolvedCliPath = resolveCliPath(cliPath);
  const env = buildCliEnv(cliPath);

  outputChannel.show(true);
  outputChannel.appendLine(`[JAIPilot] Running command in ${cwd}`);
  outputChannel.appendLine(`[JAIPilot] ${resolvedCliPath} ${args.join(" ")}`);
  outputChannel.appendLine("");

  await vscode.window.withProgress(
    {
      location: vscode.ProgressLocation.Notification,
      title: `JAIPilot: Generating tests for ${path.basename(target.fsPath)}`,
      cancellable: true
    },
    async (_progress, token) => {
      await new Promise<void>((resolve) => {
        const child = spawn(resolvedCliPath, args, {
          cwd,
          env,
          shell: false
        });
        registerActiveRun("test generation", child, outputChannel);

        let finished = false;
        const complete = (onComplete: () => void): void => {
          if (finished) {
            return;
          }
          finished = true;
          onComplete();
        };

        token.onCancellationRequested(() => {
          requestActiveRunCancellation("progress", false);
        });

        child.stdout.on("data", (data: Buffer) => {
          outputChannel.append(data.toString());
        });

        child.stderr.on("data", (data: Buffer) => {
          outputChannel.append(data.toString());
        });

        child.on("error", (error: NodeJS.ErrnoException) => {
          clearActiveRun(child);
          if (error.code === "ENOENT") {
            vscode.window.showErrorMessage(
              `JAIPilot CLI not found: ${resolvedCliPath}. Update setting "jaipilot.cliPath".`
            );
          } else {
            vscode.window.showErrorMessage(`Failed to run JAIPilot: ${error.message}`);
          }
          outputChannel.appendLine(`[JAIPilot] Process error: ${error.message}`);
          complete(resolve);
        });

        child.on("close", (code, signal) => {
          clearActiveRun(child);
          if (signal) {
            vscode.window.showWarningMessage(`JAIPilot was stopped (${signal}).`);
            outputChannel.appendLine(`[JAIPilot] Process terminated by signal: ${signal}`);
            complete(resolve);
            return;
          }

          if (code === 0) {
            vscode.window.showInformationMessage("JAIPilot test generation complete.");
            outputChannel.appendLine("[JAIPilot] Completed successfully.");
            complete(resolve);
            return;
          }

          vscode.window.showErrorMessage(
            `JAIPilot failed with exit code ${code}. Check the "${OUTPUT_CHANNEL_NAME}" output.`
          );
          outputChannel.appendLine(`[JAIPilot] Exited with code ${code}.`);
          complete(resolve);
        });
      });
    }
  );
}

function resolveCliPath(cliPath: string): string {
  if (cliPath === DEFAULT_CLI_PATH && fs.existsSync(LOCAL_CLI_PATH)) {
    return LOCAL_CLI_PATH;
  }

  return cliPath;
}

function buildCliEnv(cliPath: string): NodeJS.ProcessEnv {
  if (cliPath !== DEFAULT_CLI_PATH) {
    return process.env;
  }

  const currentPath = process.env.PATH ?? "";
  const pathEntries = currentPath.split(path.delimiter).filter((entry) => entry.length > 0);
  if (pathEntries.includes(LOCAL_BIN_DIR)) {
    return process.env;
  }

  const mergedPath = currentPath
    ? `${LOCAL_BIN_DIR}${path.delimiter}${currentPath}`
    : LOCAL_BIN_DIR;

  return {
    ...process.env,
    PATH: mergedPath
  };
}

async function resolveInstalledCliVersion(
  cliPath: string,
  env: NodeJS.ProcessEnv
): Promise<string | null> {
  return new Promise((resolve) => {
    const child = spawn(cliPath, ["--version"], {
      env,
      shell: false
    });

    let output = "";
    child.stdout.on("data", (data: Buffer) => {
      output += data.toString();
    });
    child.stderr.on("data", (data: Buffer) => {
      output += data.toString();
    });

    child.on("error", () => resolve(null));
    child.on("close", (code) => {
      if (code !== 0) {
        resolve(null);
        return;
      }

      resolve(extractSemanticVersion(output));
    });
  });
}

async function resolveLatestReleaseVersion(): Promise<string | null> {
  const latestReleaseBody = await fetchGithubJson(LATEST_RELEASE_API_URL);
  const latestReleaseVersion =
    latestReleaseBody !== null ? parseSemanticVersionFromSingleRelease(latestReleaseBody) : null;
  if (latestReleaseVersion) {
    return latestReleaseVersion;
  }

  const releasesBody = await fetchGithubJson(RELEASES_API_URL);
  if (releasesBody === null) {
    return null;
  }

  return parseLatestSemanticReleaseVersion(releasesBody);
}

async function fetchGithubJson(url: string): Promise<string | null> {
  return new Promise((resolve) => {
    const request = https.get(
      url,
      {
        headers: {
          Accept: "application/vnd.github+json",
          "User-Agent": "jaipilot-vscode"
        }
      },
      (response) => {
        const statusCode = response.statusCode ?? 0;
        if (statusCode < 200 || statusCode >= 300) {
          response.resume();
          resolve(null);
          return;
        }

        let body = "";
        response.setEncoding("utf8");
        response.on("data", (chunk: string) => {
          body += chunk;
        });
        response.on("end", () => {
          resolve(body);
        });
      }
    );

    request.on("error", () => resolve(null));
    request.setTimeout(10000, () => {
      request.destroy();
      resolve(null);
    });
  });
}

function parseSemanticVersionFromSingleRelease(body: string): string | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    return null;
  }

  return extractSemanticReleaseTag(parsed);
}

function parseLatestSemanticReleaseVersion(body: string): string | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    return null;
  }

  if (!Array.isArray(parsed)) {
    return null;
  }

  // GitHub releases API returns releases in reverse-chronological order.
  // Return the first semantic tag to avoid selecting older tags like `v1`
  // over newer patch-series releases like `v0.3.34`.
  for (const entry of parsed) {
    const version = extractSemanticReleaseTag(entry);
    if (version) {
      return version;
    }
  }
  return null;
}

function extractSemanticReleaseTag(entry: unknown): string | null {
  if (!entry || typeof entry !== "object") {
    return null;
  }

  const candidate = entry as {
    tag_name?: unknown;
    draft?: unknown;
    prerelease?: unknown;
  };

  if (candidate.draft === true || candidate.prerelease === true) {
    return null;
  }

  if (typeof candidate.tag_name !== "string" || !CLI_RELEASE_TAG_PATTERN.test(candidate.tag_name)) {
    return null;
  }

  return candidate.tag_name.slice(1);
}

function extractSemanticVersion(rawVersion: string): string | null {
  const match = rawVersion.match(VERSION_PATTERN);
  if (!match || !match[1]) {
    return null;
  }

  return match[1];
}

function compareSemanticVersions(left: string, right: string): number {
  const leftVersion = parseSemanticVersion(left);
  const rightVersion = parseSemanticVersion(right);
  if (!leftVersion || !rightVersion) {
    return left.localeCompare(right);
  }

  const segmentCount = Math.max(leftVersion.core.length, rightVersion.core.length);
  for (let index = 0; index < segmentCount; index += 1) {
    const leftSegment = leftVersion.core[index] ?? 0;
    const rightSegment = rightVersion.core[index] ?? 0;
    if (leftSegment !== rightSegment) {
      return leftSegment - rightSegment;
    }
  }

  return comparePrereleaseIdentifiers(leftVersion.prerelease, rightVersion.prerelease);
}

function parseSemanticVersion(version: string): ParsedVersion | null {
  const normalized = version.replace(/^v/, "").split("+", 1)[0] ?? "";
  const prereleaseDelimiterIndex = normalized.indexOf("-");
  const corePart =
    prereleaseDelimiterIndex === -1
      ? normalized
      : normalized.slice(0, prereleaseDelimiterIndex);
  const prereleasePart =
    prereleaseDelimiterIndex === -1
      ? null
      : normalized.slice(prereleaseDelimiterIndex + 1);

  const coreSegments = corePart.split(".");
  if (coreSegments.length === 0 || coreSegments.some((segment) => !/^\d+$/.test(segment))) {
    return null;
  }

  const parsedCore = coreSegments.map((segment) => Number(segment));
  if (parsedCore.some((segment) => !Number.isSafeInteger(segment))) {
    return null;
  }

  const parsedPrerelease =
    prereleasePart && prereleasePart.length > 0 ? prereleasePart.split(".") : null;
  if (
    parsedPrerelease &&
    parsedPrerelease.some((identifier) => !/^[0-9A-Za-z-]+$/.test(identifier))
  ) {
    return null;
  }

  return {
    core: parsedCore,
    prerelease: parsedPrerelease
  };
}

function comparePrereleaseIdentifiers(
  left: string[] | null,
  right: string[] | null
): number {
  if (!left && !right) {
    return 0;
  }
  if (!left) {
    return 1;
  }
  if (!right) {
    return -1;
  }

  const itemCount = Math.max(left.length, right.length);
  for (let index = 0; index < itemCount; index += 1) {
    const leftIdentifier = left[index];
    const rightIdentifier = right[index];
    if (leftIdentifier === undefined) {
      return -1;
    }
    if (rightIdentifier === undefined) {
      return 1;
    }
    if (leftIdentifier === rightIdentifier) {
      continue;
    }

    const leftNumeric = /^\d+$/.test(leftIdentifier);
    const rightNumeric = /^\d+$/.test(rightIdentifier);
    if (leftNumeric && rightNumeric) {
      const leftValue = Number(leftIdentifier);
      const rightValue = Number(rightIdentifier);
      if (leftValue !== rightValue) {
        return leftValue - rightValue;
      }
      continue;
    }
    if (leftNumeric) {
      return -1;
    }
    if (rightNumeric) {
      return 1;
    }

    return leftIdentifier.localeCompare(rightIdentifier);
  }

  return 0;
}

function sanitizeArgs(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }

  return value
    .filter((entry): entry is string => typeof entry === "string")
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
}

export function deactivate(): void {
  // No-op.
}
