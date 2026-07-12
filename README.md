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

## Features

- JLine-powered interactive shell with command history, contextual Tab completion, and completion menus
- Rich ANSI output with sections, tables, coverage meters, and live phase spinners
- Java class targeting by path, fully qualified name, or simple unique class name
- Batch generation for uncommitted classes
- Batch generation for classes below a coverage threshold
- JaCoCo-based status reporting with a default threshold of `80%`
- Before/after coverage summaries for each run
- Per-class and total agent token usage
- Optional estimated cost reporting from local pricing env vars
- Friendly CLI errors instead of raw stack traces

## Prerequisites

- Java 17+
- Maven or Gradle project
- JaCoCo configured in the project build
- `codex` installed and already authenticated locally

## Install

Install the latest published CLI:

```bash
curl -fsSL https://raw.githubusercontent.com/JAIPilot/jaipilot-cli/main/install.sh | sh
```

Then verify:

```bash
jaipilot --version
```

## Usage

Run bare `jaipilot` to open the interactive shell:

```text
JAIPilot 1.0.2
Interactive shell ready

project           /path/to/repo
build             maven
agent             codex
default coverage  80.0%

-- Commands --------------------------------------------------------------
  /generate <class>           Generate tests for one Java production class.
  /generate all changed       Generate tests for changed or uncommitted production classes.
  /generate all coverage 80   Generate tests for classes below the current threshold.
  /status                     Show the JaCoCo report summary and classes below threshold.
  /doctor                     Check local Codex, build, and JaCoCo prerequisites.
  /help                       Show interactive shell commands.
  /exit                       Close JAIPilot.
```

Direct commands:

```bash
jaipilot generate src/main/java/com/acme/OrderService.java
jaipilot generate com.acme.OrderService
jaipilot generate --changed
jaipilot generate --coverage-below
jaipilot generate --coverage-below 80
jaipilot status
jaipilot status --threshold 90
jaipilot doctor
```

## What `status` Shows

`jaipilot status` reads the current JaCoCo XML report and prints:

- project root and JaCoCo report path
- total line and branch coverage meters
- a table of classes below the threshold
- whether each class already has a corresponding test file

The default threshold is `80%`.

## What `generate` Shows

Each generation run prints:

- a queue table showing target classes, coverage, and current test state
- live progress for Codex, validation, and JaCoCo phases
- the generated or updated test path
- per-class token usage and optional estimated cost
- per-class JaCoCo coverage deltas when available
- a final run summary with total usage, total cost, overall project coverage improvement, and remaining below-threshold classes

## Cost Reporting

Codex CLI exposes token usage, not portable billing. JAIPilot therefore reports exact usage and can optionally estimate cost if you provide pricing locally:

```bash
export JAIPILOT_CODEX_INPUT_COST_PER_MILLION_USD=1.25
export JAIPILOT_CODEX_CACHED_INPUT_COST_PER_MILLION_USD=0.125
export JAIPILOT_CODEX_OUTPUT_COST_PER_MILLION_USD=10
export JAIPILOT_CODEX_REASONING_OUTPUT_COST_PER_MILLION_USD=10
```

If these are not set, JAIPilot marks estimated cost as unavailable and still reports token usage.

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

JAIPilot fills that template with class paths, source code, and existing test code at runtime.

## How It Works

1. JAIPilot resolves one or more Java production classes from your input.
2. It asks `codex` to create or update the corresponding JUnit test under `src/test/java`.
3. It runs the module test command locally.
4. It runs JaCoCo coverage for the target class when available.
5. It prints per-class and aggregate coverage and usage summaries.

## License

[MIT](LICENSE)
