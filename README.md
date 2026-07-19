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

JAIPilot prefers a repository's Maven or Gradle wrapper and falls back to a globally installed build tool. Coverage-sensitive commands run the clean full suite directly so target selection and status output come from a known-fresh JaCoCo report rather than whichever focused test happened to run last.

## Features

- JLine-powered interactive shell with command history, visible Tab completion menus, and inline history suggestions
- Rich ANSI output with sections, tables, coverage meters, and live phase spinners
- Java class targeting by path, fully qualified name, or simple unique class name
- Isolated parallel batch generation with deterministic, collision-safe test merging
- Allowlisted, isolated repair plus clean full-suite validation and fresh JaCoCo coverage after batch generation
- Codex-driven project preparation for changed-class batches and as a repair fallback when direct coverage refresh fails
- Fresh-by-default JaCoCo status reporting with a default threshold of `80%` and an explicit cached-report mode
- Coverage-based generation from the exact clean full-suite snapshot used to select targets
- Before/after coverage summaries for each run
- Per-class and total agent token usage
- Interactive startup checks that offer to install a newer JAIPilot release or skip it
- Friendly CLI errors instead of raw stack traces

## Prerequisites

- Java 17+
- `codex` installed and already authenticated locally
- A usable repo-local `mvnw` or `gradlew`, or a globally installed `mvn` or `gradle`, for fresh `status` and coverage-based generation
- JaCoCo XML reporting configured in the project build for `status` and coverage-based generation; `status --cached` only requires an existing report
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

When an installed JAIPilot opens its interactive shell, it checks the latest stable GitHub release with a short timeout. If a newer version exists, choose `y` to update now or press Enter to skip. Source builds, non-interactive commands, and failed network checks continue without a prompt. Self-updates preserve custom external launcher locations created with `--bin-dir` or `--prefix`.

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
JAIPilot 1.0.11
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
  /generate <class> --show-logs  Stream live build and Codex logs.
  /status                        Refresh full-suite coverage and show classes below threshold.
  /status --cached               Read the existing JaCoCo report without running tests.
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
jaipilot status --show-logs
jaipilot status --cached
jaipilot doctor
```

## What `status` Shows

By default, `jaipilot status` first deletes recognized JaCoCo XML reports and runs the repository's clean full suite:

- Maven: `<wrapper-or-mvn> -B clean verify`
- Gradle: `<wrapper-or-gradle> --no-daemon clean test jacocoTestReport`
- Gradle aggregation plugin: `<wrapper-or-gradle> --no-daemon clean testCodeCoverageReport`

It then reads only the newly generated report and prints:

- project root and JaCoCo report path
- whether coverage came from a fresh full suite or a cached report
- total line and branch coverage meters
- a table of classes below the threshold
- whether each class already appears to have a likely test file

The test-file indicator is a source-tree naming heuristic, not proof that the test executed or passed. The refreshed JaCoCo counters are the execution-based source of truth.

Use `--show-logs` to stream the coverage-refresh build output. Use `--cached` to skip the build and explicitly read an existing report; JAIPilot warns that cached XML may reflect an older or focused run. A failed, timed-out, or report-less refresh fails the command and removes partial coverage output instead of falling back to stale data. Overlapping refreshes for the same project are rejected so concurrent commands cannot delete or consume each other's report.

JAIPilot discovers XML reports in the default Maven and Gradle coverage locations, including `target/site/jacoco*/`, `target/coverage-reports/**/`, and `build/reports/jacoco/**/`. This includes Gradle's conventional `jacocoTestReport.xml` filename.
For multi-module repositories, configure one aggregate JaCoCo XML report; JAIPilot rejects ambiguous per-module reports rather than assigning coverage to the wrong module.

The default threshold is `80%`.

## What `generate` Shows

Each generation run prints:

- for explicit class targets, a skipped preparation phase so Codex focuses on that class's generated test
- for changed-class batches, a preparation phase where Codex validates build, test, and coverage readiness before target-class generation begins
- for `--coverage-below`, a direct clean full-suite refresh followed by target selection from that exact snapshot; Codex preparation runs only if the direct refresh needs repair
- a queue table showing target classes, coverage, and current test state
- live progress for Codex generation
- optional streamed process logs with `--show-logs`, including readable Codex events, shell failures, and cleaned diagnostics instead of raw JSON
- isolated parallel workers for batch modes, with per-class log prefixes so concurrent output stays readable
- deterministic merging of every Java test touched by a worker; divergent edits to the same path fail instead of overwriting each other
- the generated or updated test path
- per-class token usage
- per-class JaCoCo coverage deltas when a refreshed report is available after the Codex run
- a final run summary with total usage
- for batch modes, isolated allowlisted repair, a clean final full-suite validation, refreshed whole-project coverage, and remaining below-threshold classes

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

- `src/main/resources/prompts/prepare-java-project.md`
- `src/main/resources/prompts/generate-java-tests.md`
- `src/main/resources/prompts/validate-java-test-batch.md`

JAIPilot fills those templates with project or source-class context at runtime.

## How It Works

1. For an explicit `<class>` target, JAIPilot resolves the class directly and skips repository preparation.
2. For changed-class batches, JAIPilot first asks `codex` to prepare the repository so build, test, wrapper, and coverage readiness are checked before target-class generation starts.
3. For coverage-based batches, JAIPilot runs the clean full suite directly first. If that fails, Codex gets one repair/preparation pass before JAIPilot retries the direct refresh.
4. For batch modes, JAIPilot deletes recognized JaCoCo XML and runs the clean full suite directly. Maven uses `-B clean verify`; Gradle uses `--no-daemon clean test jacocoTestReport`, or `clean testCodeCoverageReport` when the aggregation plugin is configured.
5. For `--coverage-below`, JAIPilot selects only below-threshold classes from that exact newly refreshed snapshot. It never falls back to an older report if refresh fails.
6. JAIPilot resolves the remaining Java production classes from your input.
7. For batch modes, it copies the prepared project into isolated temporary sandboxes so multiple Codex runs can proceed in parallel without sharing build output or coverage state.
8. It asks `codex` to create or update the appropriate JUnit test based on the repository's own conventions.
9. Codex is responsible for running focused tests, fixing generated-test failures, and refreshing coverage when practical.
10. JAIPilot merges every touched Java test deterministically and rejects divergent same-path edits from parallel workers.
11. Batch modes let Codex repair only the generated tests in a disposable workspace, merge those allowlisted repairs transactionally, then run the clean build directly in the real project.
12. JAIPilot verifies that every touched generated test executed, requires a newly generated JaCoCo XML report, and then prints repository-level coverage summaries. If Codex repair is unavailable, the direct build remains the source of truth.

## License

[MIT](LICENSE)
