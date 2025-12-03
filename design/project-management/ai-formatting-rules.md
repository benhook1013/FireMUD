# AI Formatting Rules

This document captures formatting conventions that AI tooling should apply when editing documentation in this repository.

## Headings

- Do not use emojis in headings (any `#`/`##`/`###` titles). Emojis are allowed in body text and callouts but make anchor links harder to reference when used in headings.

## Line Wrapping

- Do not manually hard-wrap prose lines to a fixed column; let lines flow naturally and rely on editors/viewers to wrap text.

## Sorted Lists

- When you sort lists (for example, bullet lists or index sections), use case-insensitive alphabetical ordering so `AGENTS.md` and `build.gradle.kts` appear in a natural Windows-style order.

## Link Callouts

- When adding short cross-reference notes (e.g., “See also …”), prefer a blockquote line using the arrow link style: `> 🔗 See [Document](./path.md) for details.` Keep phrasing consistent across docs.
- This arrow style is meant for inline callouts added within or after a section; keep the more conventional bullet lists for standard “Related Documentation” sections at the bottom of most docs so they stay easy to scan.

## Service Names

- When editing architecture docs, prefer full service names on first mention (for example, “Spring Cloud Gateway”, “TCP Proxy Service”, “Game Session Service”) instead of shortened forms like “gateway” or “proxy”. Shortened names are acceptable later in a paragraph when the reference is unambiguous, but explicit names should be the default.
