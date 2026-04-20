<div align="center">
  <img src="docs/assets/jaipilot-logo.svg" alt="JAIPilot logo" width="160" />
  <h1>JAIPilot GitHub Action</h1>
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
    <a href="#quick-start"><strong>Quick Start</strong></a>
    ·
    <a href="#how-it-works"><strong>How It Works</strong></a>
  </p>
</div>

<p align="center">
  This repository is focused on the JAIPilot GitHub Action for PR automation.
</p>

<hr />

JAIPilot generates high-quality tests for changed Java production classes in pull requests and pushes the generated changes back to the PR branch.

## Why This Action

- Generates and updates tests for changed Java production classes in a PR
- Commits generated tests back to the PR branch automatically
- Supports Maven and Gradle repositories
- Exposes processed and failed class counts as action outputs

## Prerequisites

1. Install the JAIPilot GitHub App on the target repository.
2. Ensure the app has repository permissions for:
   - `Contents: Read and write`
   - `Pull requests: Read and write`
   - `Metadata: Read-only`
3. Ensure the backend endpoints are deployed (JAIPilot cloud or self-hosted in `jaipilot-functions`):
   - `POST /functions/v1/github-app-webhook`
   - `POST /functions/v1/github-actions-token`

## Quick Start

1. Deploy JAIPilot backend endpoints (`github-app-webhook` and `github-actions-token`).
2. Install the app on a repository.
3. The app automatically creates or updates `.github/workflows/jaipilot-generate.yml`.
4. On every PR (`opened`, `synchronize`, `reopened`), the managed workflow:
   - requests a GitHub OIDC token (`id-token: write`)
   - exchanges it with `POST /functions/v1/github-actions-token` for a short-lived JAIPilot runtime token
   - runs `JAIPilot/jaipilot-cli@action-v1` with `jaipilot-auth-token`

## How It Works

- Detects changed files from PR base branch (or previous commit for push events).
- Filters to non-test `.java` production classes only.
- Generates tests for each changed class.
- Commits and pushes generated tests to the same branch.
- Optionally fails the job when generation errors occur.

## Manual Fallback

If you do not use auto-install yet, use the legacy script-based onboarding flow:

```bash
./scripts/onboard-action-repo.sh --repo <owner/repo> --action-ref action-v1
```

That flow remains supported as a manual fallback and is documented in `docs/github-action-publishing.md`.

## Action Publishing

See [docs/github-action-publishing.md](docs/github-action-publishing.md) for release tagging and publishing flow.

## License

[MIT](LICENSE)
