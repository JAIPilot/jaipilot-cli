# JAIPilot Project Memory

This file is the durable local memory for Codex-driven JAIPilot runs in this repository.

## Current Workflow Direction

- Prefer the backend-free local workflow.
- Use `codex` as the generation engine.
- Use the JAIPilot CLI as the orchestrator.
- Do not depend on Supabase or JAIPilot custom backend calls for local test generation.

## Command Surface

- `jaipilot`
  Opens the interactive shell.
- `jaipilot generate <class>`
  Generate tests for one class.
- `jaipilot generate --changed`
  Generate tests for uncommitted Java production classes.
- `jaipilot generate --coverage-below 80`
  Generate tests for classes below a coverage threshold using an existing JaCoCo XML report.
- `jaipilot status`
  Show current JaCoCo totals, test presence, and classes below the threshold.
- `jaipilot doctor`
  Check Codex, build, git, and coverage prerequisites.

## Memory To Keep Fresh

Update this file when any of these change:

- preferred test command per build tool
- coverage report discovery rules
- mocking and fixture conventions
- known module-specific quirks
- stable examples of good generated tests

## Current Known Defaults

- Maven wrapper is preferred when `mvnw` exists.
- Gradle wrapper is preferred when `gradlew` exists.
- Default coverage threshold is `80%`.
- Coverage discovery currently looks for JaCoCo XML reports under:
  - `target/site/jacoco/jacoco.xml`
  - `build/reports/jacoco/**/jacoco.xml`
- Optional estimated-cost reporting is driven by:
  - `JAIPILOT_CODEX_INPUT_COST_PER_MILLION_USD`
  - `JAIPILOT_CODEX_CACHED_INPUT_COST_PER_MILLION_USD`
  - `JAIPILOT_CODEX_OUTPUT_COST_PER_MILLION_USD`
  - `JAIPILOT_CODEX_REASONING_OUTPUT_COST_PER_MILLION_USD`
