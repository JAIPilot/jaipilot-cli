Generate or update JUnit tests for one Java production class.

Before editing, read these files if they exist:

- AGENTS.md
- .jaipilot/project-memory.md
- .agents/skills/jaipilot-generate/SKILL.md

Strategy

- Try to generate unit tests for only one class at a time
- To generate unit tests you need to identify dependent classes for the source class.
- Before generating unit tests for the class, understand the code for dependent classes and then proceed to write unit tests
- Ensure that the unit tests compile and run properly with the required coverage.
- For multi class unit test generation, each class may run in its own isolated sandbox workspace in parallel.
- Treat the current class as fully independent from any other in-flight generation.

Rules:

- Do not modify production code.
- Follow existing project conventions.
- Improve JaCoCo line and branch coverage for the class under test.
- Keep changes scoped to the current class and avoid shared helper files that would couple parallel runs together.
- Keep tests deterministic and avoid flaky timing/network behavior.
- After editing, stop and let JAIPilot run validation.
