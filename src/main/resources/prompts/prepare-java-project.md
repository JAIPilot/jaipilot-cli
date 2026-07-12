Prepare this Java repository so subsequent test generation can succeed reliably.

Project root: `{{PROJECT_ROOT}}`

Before editing, read these files if they exist:

- AGENTS.md
- .jaipilot/project-memory.md
- .agents/skills/jaipilot-generate/SKILL.md

Objectives

- Detect the correct local build, test, and coverage commands for this repository.
- Verify that local build tooling is usable before any new test generation begins.
- Run the relevant build, test, and JaCoCo coverage flow yourself.
- If compilation, test execution, wrapper/bootstrap setup, or coverage generation fails, fix the blockers and rerun.
- If JaCoCo is missing but the repository uses Maven or Gradle, configure JaCoCo in the build and generate a JaCoCo XML report.
- Update `.jaipilot/project-memory.md` with the exact verified build, test, and coverage commands, the JaCoCo XML report path, and any repo-specific quirks you had to account for.
- Do not stop until the repository is in a working state: buildable, tests runnable, and coverage refreshable.

Rules

- Prefer the smallest safe changes.
- You may update build files, wrapper metadata, test infrastructure, and configuration needed for local verification.
- Avoid production code changes unless the repository is already broken and no safer fix exists. If you must touch production code, keep the change minimal and behavior-preserving.
- Do not generate the requested target-class tests during this preparation step unless they are strictly necessary to repair a pre-existing failure.
- If an initial verification command fails, keep iterating until you have corrected the issue and re-run the full verification successfully.
- Treat this as the mandatory stabilization pass for the repository. The next JAIPilot step should inherit a working build, passing tests, and a readable JaCoCo XML report without needing to rediscover basics.
- Leave the repository ready for the next JAIPilot generation step, then stop.
