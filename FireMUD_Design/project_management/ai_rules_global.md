## 1. General Code Rules

- Provide complete, functional code unless explicitly asked otherwise
- Avoid deprecated or unstable features
- Do not guess unknown APIs or behavior—mark as uncertain
- Restart the server after changes; kill related servers first
- Reuse existing code and patterns before writing new ones
- Prefer simple solutions; avoid duplication by checking for similar logic
- Respect existing architecture; avoid new patterns or tech unless necessary, and remove old code if replaced
- Never mock or stub data in dev or prod—only in tests

## 2. Style and Formatting

- Follow consistent, idiomatic formatting for the language
- Prefer explicit behavior over implicit
- Do not omit boilerplate unless minimal examples are requested
- Keep code clean, modular, and organized
- Refactor files over 300 lines

## 3. Validation and Error Handling

- Warn if assumptions are made (e.g., if inputs are presumed valid)
- Write code with all environments in mind: dev, test, and prod
- Ensure valid syntax
- Include basic error handling (e.g., try/catch, null checks)
- Log errors with enough context for debugging

## 4. Comments and Explanation

- Include comments unless code-only is requested
- Comments should explain what the code does and why
- Write for future contributors, new developers, and your future self
- Keep style clear, friendly, and technically accurate
- Explain design choices and limitations when relevant

## 5. Performance and Security

- Avoid inefficient or unsafe practices (e.g., unbounded recursion, SQL injection)
- Use safe defaults (e.g., prepared statements, escaped inputs)
- Follow security best practices (OAuth2, JWT, RBAC, input validation, rate limiting)

## 6. Refactoring and Review

- When reviewing or refactoring code:
  - Identify bugs, anti-patterns, and improvements
  - Do not change functionality unless requested
  - Avoid touching unrelated code
  - Update or remove outdated comments

## 7. Multi-file Contexts

- When editing multiple files:
  - Reference filenames explicitly
  - Keep inter-file dependencies minimal and clear

## 8. Project Structure

- Use standard directory layout for the tech stack
- Avoid one-off scripts unless reused
