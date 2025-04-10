# All-Project AI Rules

## 1. General Code Rules

- Provide complete, functional code unless code-only is requested
- Avoid deprecated or unstable features
- Respect existing architecture; avoid new patterns or tech unless necessary, and remove old code if replaced
- Mark unknown APIs or behavior as uncertain; don’t guess
- Restart servers after changes; kill related ones first
- Reuse existing code and patterns before writing new logic
- Prefer simple solutions; avoid duplication by checking for existing logic
- Never mock or stub data in dev or prod—only in tests

## 2. Style and Formatting

- Follow consistent, idiomatic formatting for the language (indentation, casing, etc.)
- Prefer explicit over implicit behavior
- Include boilerplate unless minimal examples are requested
- Keep code clean, modular, and well organized
- Refactor files over 300 lines

## 3. Validation and Error Handling

- Warn if assumptions are made (e.g., if inputs are presumed valid)
- Write code with all environments in mind: dev, test, and prod
- Ensure valid syntax
- Include basic error handling (e.g., try/catch, null checks)
- Log errors with enough context for debugging

## 4. Comments and Explanation

- Include comments unless code-only is requested
- Explain both what the code does and why
- Write for future contributors, new developers, and your future self
- Explain design choices and limitations when relevant
- Use clear, friendly, technically accurate style

## 5. Performance and Security

- Avoid inefficient or unsafe practices (e.g., unbounded recursion, SQL injection)
- Use safe defaults (e.g., prepared statements, escaped inputs)
- Follow best practices for auth and security (OAuth2, JWT, RBAC, validation, rate limiting)

## 6. Refactoring and Review

- When reviewing or refactoring code:
  - Identify bugs, anti-patterns, and improvements
  - Do not change functionality unless requested
  - Avoid touching unrelated code
  - Update or remove outdated comments

## 7. Multi-file Contexts

- When editing multiple files:
  - Reference filenames explicitly
  - Keep cross-file dependencies clear and minimal

## 8. Project Structure

- Use standard directory layout for the tech stack
- Avoid one-off scripts unless reused
