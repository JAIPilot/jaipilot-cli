Validate the merged Java tests from a JAIPilot batch generation run.

Project root: `{{PROJECT_ROOT}}`

Generated test files that may be repaired:

{{GENERATED_TEST_PATHS}}

Before editing, read these files if they exist:

- AGENTS.md
- .jaipilot/project-memory.md
- .agents/skills/jaipilot-generate/SKILL.md

Objectives

- Run the repository's clean full test suite using the verified command recorded in project memory when available.
- Ensure the build actually discovers and executes the generated tests; inspect build include/exclude conventions and test counts rather than accepting an empty or stale run.
- Generate a fresh JaCoCo XML report from that clean full-suite run.
- If generated tests fail together, repair only the generated test files listed above and rerun the clean full suite.
- Do not stop until the clean full test suite passes and a readable, freshly generated JaCoCo XML report exists.

Rules

- Do not modify production code.
- Do not remove or disable tests, assertions, build checks, or coverage reporting to make validation pass.
- Preserve repository-specific test naming and discovery conventions.
- Do not edit any path outside the generated test file allowlist above. JAIPilot validates this boundary and discards out-of-scope edits.
- Do not commit, stage, push, or release changes.
