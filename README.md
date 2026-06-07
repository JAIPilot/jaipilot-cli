<div align="center">
  <img src="docs/assets/jaipilot-logo.svg" alt="JAIPilot logo" width="160" />
  <h1>JAIPilot GitHub App</h1>
  <p><strong>Automatically generate high-coverage Java unit tests on every pull request.</strong></p>
  <p>
    <a href="https://github.com/JAIPilot/jaipilot-cli/actions/workflows/ci.yml">
      <img src="https://github.com/JAIPilot/jaipilot-cli/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI">
    </a>
    <a href="https://github.com/JAIPilot/jaipilot-cli/releases">
      <img src="https://img.shields.io/github/v/release/JAIPilot/jaipilot-cli" alt="Release">
    </a>
    <a href="LICENSE">
      <img src="https://img.shields.io/github/license/JAIPilot/jaipilot-cli" alt="License">
    </a>
  </p>
  <p>
    <a href="#how-it-works"><strong>How It Works</strong></a>
  </p>
</div>

<p align="center">
  This repository is focused on the JAIPilot GitHub App for PR automation.
</p>

## Local CLI Install

Install the latest JAIPilot CLI release with:

```bash
curl -fsSL https://raw.githubusercontent.com/JAIPilot/jaipilot-cli/action-v1/install.sh | sh
```

The installer places `jaipilot` in `~/.local/bin`. Ensure that directory is on your `PATH`, then verify the install:

```bash
jaipilot --version
```

Run the CLI against a class with:

```bash
jaipilot generate <class to create unit test for>
```

<hr />

JAIPilot generates high-quality tests for changed Java production classes in pull requests and pushes the generated changes back to the PR branch.

## Why This App

- Generates and updates tests for changed Java production classes in a PR
- Commits generated tests back to the PR branch automatically
- Supports Maven and Gradle repositories
- Exposes processed and failed class counts as workflow outputs

## Install as GitHub App

1. Install the JAIPilot GitHub App on the target repository.
2. Ensure the app has repository permissions for:
   - `Contents: Read and write`
   - `Pull requests: Read and write`
   - `Metadata: Read-only`
3. Ensure the backend endpoints are deployed (JAIPilot cloud or self-hosted in `jaipilot-functions`):
   - `POST /functions/v1/github-app-webhook`
   - `POST /functions/v1/github-actions-token`

## How It Works

- Resolves JAIPilot auth by preferring GitHub OIDC runtime token exchange when `id-token: write` is available; falls back to `jaipilot-auth-token` / `JAIPILOT_AUTH_TOKEN`.
- Detects changed files from PR base branch (or previous commit for push events).
- Filters to non-test `.java` production classes only.
- Generates tests for each changed class.
- Prints backend-provided coverage summaries in `jaipilot generate` logs when available.
- Commits and pushes generated tests to the same branch.
- Optionally fails the job when generation errors occur.

## License

[MIT](LICENSE)
