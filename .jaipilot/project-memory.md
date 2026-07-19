# JAIPilot Project Memory

This file is the durable local memory for Codex-driven JAIPilot runs in this repository.

## Current Workflow Direction

- Prefer the backend-free local workflow.
- Use `codex` as the generation engine.
- Use the JAIPilot CLI as the orchestrator.
- Do not depend on any custom backend or hosted service for local test generation.

## Command Surface

- `jaipilot`
  Opens the interactive shell.
- `jaipilot generate <class>`
  Generate tests for one class without broad repository preparation.
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

- preferred build, test, and coverage commands per build tool
- confirmed JaCoCo XML report path
- coverage report discovery rules
- mocking and fixture conventions
- known module-specific quirks
- stable examples of good generated tests

## Current Known Defaults

- Maven wrapper is preferred only when `mvnw` and `.mvn/wrapper/maven-wrapper.properties` both exist.
- Gradle wrapper is preferred only when `gradlew` and `gradle/wrapper/gradle-wrapper.properties` both exist.
- Default coverage threshold is `80%`.
- Explicit class generation skips the repository preparation pass so Codex focuses on the requested class and its generated test.
- Explicit class generation reports target-class coverage only; focused JaCoCo runs can overwrite whole-project totals with single-test execution data.
- Batch and coverage modes invalidate stale JaCoCo XML before preparation, then require Codex to regenerate it before target selection.
- Parallel workers return every touched Java test; JAIPilot merges non-conflicting outputs deterministically and rejects divergent same-path edits.
- JAIPilot disables nested Codex agents inside workers because the CLI already owns batch parallelism.
- After batch merging, Codex may repair only allowlisted generated tests in a disposable workspace; JAIPilot transactionally merges those repairs, always runs the clean build directly, checks every touched generated test report, and requires fresh JaCoCo XML before reporting success.
- Codex is expected to determine the final test file path and class name from repository conventions.
- Codex is expected to own the test-run, fix, and coverage loop during generation.
- The interactive shell stores history at `~/.jaipilot/history`.
- The CLI UX favors structured sections, tables, coverage meters, cleaned Codex diagnostics, and spinners over raw tool logs or JSON.
- Coverage discovery currently looks for JaCoCo XML reports under:
  - `target/site/jacoco/jacoco.xml`
  - `target/site/jacoco-*/jacoco.xml`
  - `target/coverage-reports/**/jacoco.xml`
  - `build/reports/jacoco/**/jacoco.xml`
