---
name: jaipilot-generate
description: Generate or repair Java unit tests in this repository using JAIPilot's local Codex workflow.
---

# JAIPilot Generate

Use this skill when the task is to generate or repair Java unit tests for one or more production classes in this repository.

## Workflow

1. Read `AGENTS.md` and `.jaipilot/project-memory.md` before doing any work.
2. Prefer the JAIPilot CLI entrypoints instead of ad hoc prompting:
   - `jaipilot generate <class>`
   - `jaipilot generate --changed`
   - `jaipilot generate --coverage-below <percent>`
   - `jaipilot status`
   - `jaipilot doctor`
3. Treat `src/test/java` as the default write surface.
4. Do not modify production code unless the user explicitly asks for it.
5. Validate generated tests with the module-local build command.

## Expectations

- Use JUnit 5 conventions already present in the repository.
- Respect module boundaries in multi-module Maven or Gradle builds.
- If the generated test fails validation, repair the test before stopping.
- Do not call JAIPilot, Supabase, or any custom backend endpoint for this local workflow.
