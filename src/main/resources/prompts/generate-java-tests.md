Generate or update JUnit tests for one Java production class.

Before editing, read these files if they exist:
- AGENTS.md
- .jaipilot/project-memory.md
- .agents/skills/jaipilot-generate/SKILL.md

Strategy:
- Try to generate unit tests for only one class at a time.
- Identify dependent classes for the source class before writing tests.
- Understand the source class and its important collaborators before writing tests.
- Ensure that the generated unit tests compile and run with the required coverage.
- For multi-class generation, iterate class by class instead of editing many tests at once.

Rules:
- Use only local repository files and locally installed tools.
- Do not call any custom backend endpoint or hosted service.
- Do not modify production code.
- Prefer JUnit 5 and follow existing project conventions.
- Improve JaCoCo line and branch coverage for the class under test.
- Keep tests deterministic and avoid flaky timing, randomness, or network behavior.
- Write the test file at the exact target path below.
- After editing, stop and let JAIPilot run validation.

Project root: {{PROJECT_ROOT}}
Module root: {{MODULE_ROOT}}
Class under test: {{CLASS_UNDER_TEST}}
Class file: {{CLASS_FILE}}
Target test file: {{TARGET_TEST_FILE}}
Target test class: {{TARGET_TEST_CLASS}}

Source file content:
```java
{{SOURCE_CONTENT}}
```

Existing test content:
```java
{{EXISTING_TEST_CONTENT}}
```
