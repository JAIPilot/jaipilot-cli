JAIPilot – One-Click AI Agent for Java Unit Testing, AI-Powered Unit Tests for Java — generate, run, and fix tests automatically inside VSCode.

JAIPilot – One-Click AI Agent for Java Unit Testing creates complete, context-aware JUnit tests in seconds — right inside your IDE.

- Understands your code — analyzes method logic, parameters, dependencies, and edge cases to generate meaningful tests
- One-click generation — right-click any class or method to instantly create high-coverage, runnable JUnit tests
- Autonomous refinement — automatically finds the test class file location, runs tests, detects failures, and generates or fixes tests until they pass, including fixing current test cases with compilation or execution failures
- Optimized AI models — dynamically picks the best AI model per class for speed and accuracy
- Seamless JUnit integration — works out-of-the-box with your existing Java projects

Skip the repetitive test writing. Let JAIPilot handle the heavy lifting — so you can focus on real engineering.
Join other developers using JAIPilot to ship faster, test smarter, and build with confidence.

Start with free credits
Free credits are included to generate your first tests (no credit card required).

Generate tests for a class
Right-click any Java class and choose Generate Tests with JAIPilot.

Cancel a running operation
If generation is running, use the status bar action `Cancel JAIPilot` or run `Cancel JAIPilot Generation` from the Command Palette.

Manage usage
Manage account and credits at [jaipilot.com/account](https://jaipilot.com/account).

Documentation URL
https://jaipilot.com

Privacy Policy
https://jaipilot.com/privacy_policy

<h2>How JAIPilot Works</h2>

<p>JAIPilot is an VSCode plugin that uses generative AI to instantly create complete, executable JUnit test classes for your Java code. It handles everything — understanding your code context, adding mocks, fixing errors — so you can focus on development.</p>

<h3>1. Configuration &amp; Setup</h3>
<p>After installing the extension from the VS Code Marketplace, open <strong>JAIPilot Settings</strong> and configure:</p>
<ul>
  <li><strong>JAIPilot License</strong> — required to unlock generation</li>
  <li>You automatically receive <strong>$5 worth of free credits</strong> to try out JAIPilot when you start</li>
</ul>

<h3>2. Context Extraction</h3>
<p>When you right-click a class or method and select <strong>“Generate Tests”</strong>, JAIPilot automatically:</p>
<ul>
  <li>Extracts the complete source of the Class Under Test (CUT)</li>
  <li>Collects all <strong>public method signatures</strong></li>
  <li>Recursively gathers all input and return types (POJOs, collections, etc.)</li>
  <li>Builds a clean, self-contained context for the AI</li>
</ul>

<h3>3. Mock Detection &amp; Handling</h3>
<p>To ensure runnable, isolated tests:</p>
<ul>
  <li>JAIPilot detects dependencies (services, repositories, helpers)</li>
  <li>Automatically adds <code>@Mock</code>, <code>@InjectMocks</code>, and appropriate <code>Mockito.when(...)</code> / <code>doReturn(...)</code> calls</li>
  <li>No manual mocking or setup needed</li>
</ul>

<h3>4. AI-Powered Test Generation</h3>
<p>JAIPilot sends a structured prompt to its AI backend containing:</p>
<ul>
  <li>The Class Under Test</li>
  <li>Extracted supporting context (including POJOs)</li>
  <li>Instructions to produce a complete, runnable JUnit test class with setup and mocks</li>
</ul>

<h3>5. Autonomous Self-Correction Loop</h3>
<p>After the AI generates a test class:</p>
<ul>
  <li>JAIPilot saves and compiles it</li>
  <li>If compilation or runtime issues occur, it:
    <ul>
      <li>Captures the error output</li>
      <li>Feeds it back to the AI</li>
      <li>Requests corrections until the code passes</li>
    </ul>
  </li>
</ul>

<h3>6. Final Output</h3>
<ul>
  <li>Verified test class is saved in your configured test directory</li>
  <li>All dependencies are mocked, tests follow best practices</li>
  <li>You get a high-coverage, production-ready test suite in seconds — zero manual effort</li>
</ul>
