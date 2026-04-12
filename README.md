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

1. Get a JAIPilot license key from `https://jaipilot.com`.
2. In the target repository, open `Settings -> Secrets and variables -> Actions`.
3. Create a secret named `JAIPILOT_LICENSE_KEY`.
4. Ensure your workflow job has `contents: write` permission so the action can push generated commits.

## Quick Start

```yaml
name: JAIPilot Generate Tests

on:
  pull_request:
    types: [opened, synchronize, reopened]

jobs:
  jaipilot:
    runs-on: ubuntu-latest
    permissions:
      contents: write
      pull-requests: write

    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          ref: ${{ github.head_ref }}

      - name: Run JAIPilot
        uses: JAIPilot/jaipilot-cli@action-v1
        with:
          jaipilot-license-key: ${{ secrets.JAIPILOT_LICENSE_KEY }}
```

## How It Works

- Detects changed files from PR base branch (or previous commit for push events).
- Filters to non-test `.java` production classes only.
- Generates tests for each changed class.
- Commits and pushes generated tests to the same branch.
- Optionally fails the job when generation errors occur.

## Action Publishing

See [docs/github-action-publishing.md](docs/github-action-publishing.md) for release tagging and publishing flow.

## License

[MIT](LICENSE)
