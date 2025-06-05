# AGENTS Guide for VSCode_Windsurf Repository

This repository contains documentation and design files for the FireMUD Game Platform. Use this guide when performing AI-assisted updates.

## Directory Overview
- `FireMUD/`
  - `README.md` – main project overview
  - `FAQ.md` – frequently asked questions
  - `CONTRIBUTING.md` – placeholder for future contribution guidelines
  - `LICENSE.md` and `NOTICE.md` – legal information
  - `FireMUD_Design/`
    - `architecture/`
      - `infrastructure/` – deployment and gateway docs
      - `microservices/` – one folder per service (e.g., `account-service`, `automation-scripting-service`, `world-management-service`)
      - `service-responsibility-matrix.md`
      - `system-architecture-overview.md`
    - `project-management/`
      - `ai-rules-global.md`
      - `ai-rules-local.md`
      - planning docs such as `core-requirements.md`, `task-list.md`

## AI Coding Guidelines
Consult the following documents before writing or modifying code:
- [ai-rules-global.md](FireMUD/FireMUD_Design/project-management/ai-rules-global.md)
- [ai-rules-local.md](FireMUD/FireMUD_Design/project-management/ai-rules-local.md)

These files describe style, architecture, and best practices for Java/Spring projects. The `.windsurfrules` file at the repository root contains the same content as `ai-rules-local.md`.

## General Notes
- Keep documentation organized within `FireMUD_Design`.
- Use clear commit messages summarizing changes.
- There are currently no automated tests in this repository.

