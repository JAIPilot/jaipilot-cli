Generate or update JUnit tests for one Java production class.

Current class under test: `{{CLASS_UNDER_TEST}}`
Source file: `{{CLASS_FILE}}`

Before editing, read these files if they exist:

- AGENTS.md
- .jaipilot/project-memory.md
- .agents/skills/jaipilot-generate/SKILL.md

Strategy

- Try to generate unit tests for only one class at a time
- To generate unit tests you need to identify dependent classes for the source class.
- Before generating unit tests for the class, understand the code for dependent classes and then proceed to write unit tests
- Determine the correct test file path and class name from the repository's existing conventions. Do not assume JAIPilot has preselected a target test file for you.
- Run the relevant local test command yourself and keep iterating until the generated tests pass.
- Run or refresh coverage yourself and keep iterating until the class under test reaches at least 80% line coverage. Improve branch coverage as much as practical.
- If a test run or coverage run fails, inspect the failure, revise the test, and rerun until the class is green or you are blocked by missing project tooling.
- For multi class unit test generation, each class may run in its own isolated sandbox workspace in parallel.
- Treat the current class as fully independent from any other in-flight generation.

Rules:

- Do not modify production code.
- Follow existing project conventions.
- Improve JaCoCo line and branch coverage for the class under test.
- Keep changes scoped to the current class and avoid shared helper files that would couple parallel runs together.
- Keep tests deterministic and avoid flaky timing/network behavior.
