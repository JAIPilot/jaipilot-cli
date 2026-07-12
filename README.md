<div align="center">
  <img src="docs/assets/jaipilot-logo.svg" alt="JAIPilot logo" width="160" />
  <h1>JAIPilot CLI</h1>
  <p><strong>Generate Java unit tests locally with Codex and track JaCoCo coverage from the terminal.</strong></p>
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
</div>

`jaipilot-cli` is a Java-only local workflow. It does not call any custom backend or hosted service. Test generation comes from the coding agent you already use, starting with `codex`.

JAIPilot does not require a globally installed Maven or Gradle at runtime. It only uses a usable repo-local `mvnw` or `gradlew` when the wrapper script and wrapper properties are both present. If the wrapper is missing or incomplete, JAIPilot still generates tests with Codex and skips local validation and JaCoCo.

## Features

- JLine-powered interactive shell with command history, visible Tab completion menus, and inline history suggestions
- Rich ANSI output with sections, tables, coverage meters, and live phase spinners
- Java class targeting by path, fully qualified name, or simple unique class name
- Isolated parallel batch generation for uncommitted classes
- Isolated parallel batch generation for classes below a coverage threshold
- JaCoCo-based status reporting with a default threshold of `80%`
- Before/after coverage summaries for each run
- Per-class and total agent token usage
- Friendly CLI errors instead of raw stack traces

## Prerequisites

- Java 17+
- `codex` installed and already authenticated locally
- Optional: a usable repo-local `mvnw` or `gradlew` if you want automatic local validation
- Optional: JaCoCo configured in the project build if you want coverage and `status`
- Optional: a SonarQube or SonarQube Cloud project if you want code-smell and quality-gate analysis for this repository

## Install

Install the latest published CLI:

```bash
curl -fsSL https://raw.githubusercontent.com/JAIPilot/jaipilot-cli/main/install.sh | sh
```

Then verify:

```bash
jaipilot --version
```

## SonarQube Analysis

`jaipilot-cli` now generates a JaCoCo XML report during `verify` and includes a pinned Sonar scanner for Maven.

Run a local analysis against a self-hosted SonarQube server with:

```bash
SONAR_TOKEN=your-token \
SONAR_HOST_URL=https://your-sonarqube.example.com \
./mvnw -B verify sonar:sonar
```

If you are using SonarQube Cloud instead, pass your organization:

```bash
SONAR_TOKEN=your-token \
./mvnw -B verify sonar:sonar -Dsonar.organization=your-org
```

GitHub Actions support is configured in `.github/workflows/sonarqube.yml`.

Repository settings expected by that workflow:

- GitHub secret `SONAR_TOKEN`
- GitHub variable `SONAR_HOST_URL` for self-hosted SonarQube, or GitHub variable `SONAR_ORGANIZATION` for SonarQube Cloud
- Optional GitHub variable `SONAR_ENABLE_PULL_REQUEST_ANALYSIS=true` if your Sonar edition supports pull request analysis and you want the workflow to run on `pull_request`

The workflow runs automatically on pushes to `main`, can be triggered manually with `workflow_dispatch`, and skips itself until the required Sonar configuration is present.

## Usage

Run bare `jaipilot` to open the interactive shell:

```text
JAIPilot 1.0.9
Interactive shell ready

project           /path/to/repo
build             maven
agent             codex
default coverage  80.0%

Press Tab to open suggestions and complete commands, options, thresholds, and Java class selectors.

-- Commands --------------------------------------------------------------
  /generate <class>              Generate tests for one Java production class.
  /generate all changed          Generate tests for changed or uncommitted production classes.
  /generate all coverage 80      Generate tests for classes below the current threshold.
  /generate <class> --show-logs  Stream live generation logs for Codex, validation, and JaCoCo.
  /status                        Show the JaCoCo report summary and classes below threshold.
  /doctor                        Check local Codex, build, and JaCoCo prerequisites.
  /help                          Show interactive shell commands.
  /exit                          Close JAIPilot.
```

Direct commands:

```bash
jaipilot generate src/main/java/com/acme/OrderService.java
jaipilot generate com.acme.OrderService
jaipilot generate --changed
jaipilot generate --coverage-below
jaipilot generate --coverage-below 80
jaipilot generate com.acme.OrderService --show-logs
jaipilot status
jaipilot status --threshold 90
jaipilot doctor
```

## What `status` Shows

`jaipilot status` reads the current JaCoCo XML report and prints:

- project root and JaCoCo report path
- total line and branch coverage meters
- a table of classes below the threshold
- whether each class already appears to have a likely test

The default threshold is `80%`.

## What `generate` Shows

Each generation run prints:

- a queue table showing target classes, coverage, and current test state
- live progress for Codex, validation, and JaCoCo phases
- optional streamed process logs with `--show-logs`, including readable Codex event logs instead of raw JSON
- isolated parallel workers for batch modes, with per-class log prefixes so concurrent output stays readable
- the generated or updated test path
- per-class token usage
- per-class JaCoCo coverage deltas when available
- a final run summary with total usage, a fresh whole-project coverage improvement report, and remaining below-threshold classes

## Codex Memory Files

JAIPilot keeps the local workflow explicit so Codex does not need to rediscover conventions on every run:

- `AGENTS.md`
  Stable repo rules and constraints.
- `.jaipilot/project-memory.md`
  Evolving project facts such as build, coverage, and test conventions.
- `.agents/skills/jaipilot-generate/SKILL.md`
  Reusable instructions for the JAIPilot generation workflow.

## Prompt Templates

The bundled Codex prompt templates now live in:

- `src/main/resources/prompts/generate-java-tests.md`

JAIPilot fills that template with source-class context at runtime.

## How It Works

1. JAIPilot resolves one or more Java production classes from your input.
2. For batch modes, it copies the project into isolated temporary sandboxes so multiple Codex runs can proceed in parallel without sharing build output or coverage state.
3. It asks `codex` to create or update the appropriate JUnit test based on the repository's own conventions.
4. It runs the module test command locally.
5. It runs JaCoCo coverage for the target class when available, then refreshes whole-project coverage before printing the final summary.

## License

[MIT](LICENSE)
