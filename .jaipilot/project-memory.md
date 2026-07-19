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
  Refresh clean full-suite coverage, then generate tests only for classes below the threshold in that exact snapshot.
- `jaipilot status`
  Refresh clean full-suite coverage, then show JaCoCo totals, test-file presence, and classes below the threshold.
- `jaipilot status --cached`
  Explicitly read existing JaCoCo XML without running tests; warn that it may reflect an older or focused run.
- `jaipilot status --show-logs`
  Stream build output while refreshing clean full-suite coverage.
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
- Fresh coverage invalidates recognized JaCoCo XML first, then runs `<wrapper-or-mvn> -B clean verify` for Maven, `<wrapper-or-gradle> --no-daemon clean test jacocoTestReport` for standard Gradle, or `<wrapper-or-gradle> --no-daemon clean testCodeCoverageReport` for Gradle aggregation.
- A failed, timed-out, unreadable, or report-less refresh removes partial output and never falls back to stale JaCoCo XML.
- Coverage refreshes use a project-scoped cross-process lock; overlapping status or generation refreshes fail before invalidation.
- `status` refreshes clean full-suite coverage by default; `status --cached` is the explicit no-build escape hatch and must display a stale/focused-report warning.
- Status test-file presence is a naming heuristic only; fresh JaCoCo counters, not the presence label, prove execution coverage.
- Explicit class generation skips the repository preparation pass so Codex focuses on the requested class and its generated test.
- Explicit class generation reports target-class coverage only; focused JaCoCo runs can overwrite whole-project totals with single-test execution data.
- Changed-class batches prepare the repository before the direct clean refresh.
- Coverage-based batches run the direct clean refresh first and invoke Codex preparation only as a repair fallback, so zero-miss runs do not spend agent tokens.
- Coverage-based generation selects below-threshold classes from the exact immutable snapshot returned by that refresh, not by rereading mutable report files later.
- Parallel workers return every touched Java test; JAIPilot merges non-conflicting outputs deterministically and rejects divergent same-path edits.
- JAIPilot disables nested Codex agents inside workers because the CLI already owns batch parallelism.
- After batch merging, Codex may repair only allowlisted generated tests in a disposable workspace; JAIPilot transactionally merges those repairs, always runs the clean build directly, checks every touched generated test report, and requires fresh JaCoCo XML before reporting success.
- Codex is expected to determine the final test file path and class name from repository conventions.
- Codex is expected to own the test-run, fix, and coverage loop during generation.
- The interactive shell stores history at `~/.jaipilot/history`.
- Installed interactive shells check for a newer stable GitHub release at startup and offer update-now or skip; source builds and non-interactive commands do not check.
- Self-updates install a new versioned payload through the bundled installer while preserving any custom external bin launcher.
- The CLI UX favors structured sections, tables, coverage meters, cleaned Codex diagnostics, and spinners over raw tool logs or JSON.
- Coverage discovery accepts XML under `target/site/jacoco*/`, `target/coverage-reports/**/`, and `build/reports/jacoco/**/`, including Gradle's `jacocoTestReport.xml`.
- Multi-module coverage requires one recognized Maven or Gradle aggregate XML; ambiguous per-module report sets fail instead of producing incorrect target selection.
