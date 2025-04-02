## 1. General Code Rules

- Provide complete, functional code blocks unless explicitly asked otherwise
- Avoid deprecated or unstable features
- Do not guess unknown APIs or behaviors—call them out as uncertain
- Restart the server after making changes; kill related servers first
- Reuse existing code and patterns before introducing new ones
- Prefer simple solutions and avoid duplication by checking for existing logic
- Respect the existing architecture; don’t introduce new patterns or tech unless necessary, and clean up the old implementation if you do
- Never mock or stub data in dev or prod—only in tests

## 2. Style and Formatting

- Follow consistent, idiomatic formatting for the language used
- Prefer explicit behavior over implicit
- Do not omit boilerplate unless minimal examples are requested
- Keep code clean, modular, and organized
- Refactor files that grow over 300 lines

## 3. Validation and Error Handling

- Ensure valid syntax at all times
- Warn if assumptions are made (e.g., if inputs are presumed valid)
- Include basic error handling where appropriate (e.g., try/catch, null checks)
- Write code with all environments in mind: dev, test, and prod
- Log errors with enough context for debugging

## 4. Comments and Explanation

- Include comments unless code-only output is explicitly requested
- Comments should be verbose and educational, explaining what the code does and why
- Make the codebase easy to understand for:
  - Future contributors
  - Developers new to the project
  - Yourself, when revisiting the code
- Use a clear, friendly, and technically accurate style
- Explain design choices and limitations where relevant

## 5. Performance and Security

- Avoid inefficient or unsafe practices (e.g., unbounded recursion, SQL injection)
- Prefer safe defaults such as prepared statements and escaped inputs
- Follow security best practices (OAuth2, JWT, RBAC, input validation, rate limiting)

## 6. Refactoring and Review

- When reviewing or refactoring code:
  - Identify bugs, anti-patterns, or areas for improvement
  - Do not change functionality unless requested
  - Avoid touching unrelated code
  - Update or remove outdated comments

## 7. Multi-file Contexts

- When working across multiple files:
  - Reference file names explicitly
  - Keep inter-file dependencies clear and minimal

## 8. Project Structure

- Use standard directory layouts for the chosen tech stack
- Avoid writing one-off scripts unless they will be reused
