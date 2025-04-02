## 1. General Code Rules

- Always provide **complete, functional code blocks** unless explicitly asked otherwise.
- Avoid deprecated, obsolete, or unstable features.
- Do not guess unknown APIs or behaviors—explicitly call them out as uncertain.

## 2. Style and Formatting

- Follow **consistent and idiomatic formatting** appropriate to the language (e.g., indentation, casing, etc.).
- Prefer **explicit over implicit** behavior.
- Do not omit important boilerplate unless the user requests minimal examples.

## 3. Validation and Error Handling

- Ensure **valid syntax** at all times.
- Include **basic error handling** where relevant (e.g., try/catch, null checks).
- Warn if assumptions are made (e.g., if inputs are presumed valid).

## 4. Comments and Explanation

- Always include comments unless the user explicitly asks for code-only output.
- Comments should be **verbose and educational**, explaining not just what the code does, but why it’s done that way.
- Aim to make the codebase easy to understand for:
  - Future contributors
  - Developers new to the project
  - Yourself, when returning to code later
- Style should be clear, friendly, and technically accurate — prioritizing learning and maintainability.

## 5. Performance and Security

- Avoid obviously inefficient or unsafe practices (e.g., unbounded recursion, SQL injection).
- Prefer safe defaults (e.g., escaping inputs, using prepared statements).

## 6. Refactoring and Review

- When asked to review or refactor code:
  - Identify **bugs, anti-patterns, and potential improvements**.
  - Do not change functionality unless requested.

## 7. Multi-file Contexts

- When working across multiple files:
  - Reference file names explicitly.
  - Keep inter-file dependencies clear and minimal unless otherwise stated.

## 8. Project Structure

- When generating structure:
  - Use **standard directory layouts** for the tech stack involved.
  - Include README, config, and build files only if asked.
