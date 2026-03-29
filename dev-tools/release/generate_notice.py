#!/usr/bin/env python3

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import date
from pathlib import Path


@dataclass(frozen=True)
class NoticeContext:
    copyright_year: int
    release_date: date
    change_date: date


def add_years_safe(value: date, years: int) -> date:
    try:
        return value.replace(year=value.year + years)
    except ValueError:
        # February 29th rolls back to February 28th on non-leap years.
        return value.replace(month=2, day=28, year=value.year + years)


def render_notice(template_text: str, context: NoticeContext) -> str:
    replacements = {
        "{{COPYRIGHT_YEAR}}": str(context.copyright_year),
        "{{RELEASE_DATE}}": context.release_date.isoformat(),
        "{{CHANGE_DATE}}": context.change_date.isoformat(),
    }

    rendered = template_text
    for placeholder, value in replacements.items():
        rendered = rendered.replace(placeholder, value)
    return rendered


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate a release-specific NOTICE file from NOTICE.template.md.",
    )
    parser.add_argument(
        "--template",
        required=True,
        type=Path,
        help="Path to NOTICE.template.md",
    )
    parser.add_argument(
        "--release-date",
        required=True,
        type=date.fromisoformat,
        help="Release publication date in YYYY-MM-DD format",
    )
    parser.add_argument(
        "--output",
        required=True,
        type=Path,
        help="Output path for the generated NOTICE file",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    template_text = args.template.read_text(encoding="utf-8")
    context = NoticeContext(
        copyright_year=args.release_date.year,
        release_date=args.release_date,
        change_date=add_years_safe(args.release_date, 2),
    )

    rendered = render_notice(template_text, context)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")


if __name__ == "__main__":
    main()
