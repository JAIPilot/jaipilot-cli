Generate or update JUnit tests for one Java production class.

Current class under test: `{{CLASS_UNDER_TEST}}`
Source file: `{{CLASS_FILE}}`

Before editing, read these files if they exist:

- AGENTS.md
- .jaipilot/project-memory.md
- .agents/skills/jaipilot-generate/SKILL.md

Strategy

- Try to generate unit tests for only one class at a time
- Reuse the verified build, test, and coverage commands recorded in `.jaipilot/project-memory.md` when present.
- To generate unit tests you need to identify dependent classes for the source class.
- Before generating unit tests for the class, understand the code for dependent classes and then proceed to write unit tests
- Determine the correct test file path and class name from the repository's existing conventions. Do not assume JAIPilot has preselected a target test file for you.
- Use a dedicated test file for this target class, and verify that its name matches the build's test-discovery includes (for example `*Test.java` versus `*Tests.java`). Avoid shared suite files that another parallel target might also update.
- Run the relevant local test command yourself, preferring a focused command for the generated test or class under test, and keep iterating until that target test passes.
- Refresh coverage when a reliable focused coverage command is available, and improve line and branch coverage for the class under test as much as practical.
- If a test run or coverage run fails because of unrelated existing failures, report that blocker instead of fixing unrelated code or tests.
- Do not run or repair the entire repository unless no focused validation path exists.
- For multi class unit test generation, each class may run in its own isolated sandbox workspace in parallel.
- Treat the current class as fully independent from any other in-flight generation.

Rules:

- Do not modify production code.
- Do not modify unrelated tests, build files, wrappers, or configuration.
- Follow existing project conventions.
- Improve JaCoCo line and branch coverage for the class under test.
- Keep changes scoped to the current class and avoid shared helper files that would couple parallel runs together.
- Do not delete or rename existing test files during an isolated generation run.
- Keep tests deterministic and avoid flaky timing/network behavior.
