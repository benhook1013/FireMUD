#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import re
import shutil
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any

NOTICE_FILES = {
    "NOTICE_DEFAULT": "THIRD_PARTY_NOTICES.txt",
    "NOTICE_SUMMARY": "THIRD_PARTY_NOTICE_SUMMARY.txt",
}

RUNTIME_SCOPES = {"required", "optional"}
NON_RUNTIME_SCOPES = {"excluded"}
UNKNOWN_SCOPE = "unknown"


@dataclass(frozen=True)
class Component:
    bom_ref: str
    group: str
    name: str
    version: str
    scope: str
    licenses: tuple[str, ...]
    purl: str
    external_refs: tuple[str, ...]

    @property
    def display_name(self) -> str:
        return f"{self.group}:{self.name}" if self.group else self.name


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Assemble a richer release /licenses directory from ORT reporter output.",
    )
    parser.add_argument(
        "--ort-results",
        required=True,
        type=Path,
        help="Path to the ORT results directory",
    )
    parser.add_argument(
        "--output-dir",
        required=True,
        type=Path,
        help="Path to the /licenses directory to create",
    )
    return parser.parse_args()


def find_required_notice(ort_results: Path, notice_name: str) -> Path:
    matches = sorted(ort_results.rglob(notice_name))
    if not matches:
        raise FileNotFoundError(
            f"Required ORT notice report '{notice_name}' was not found under {ort_results}",
        )
    return matches[0]


def find_required_cyclonedx_json(ort_results: Path) -> Path:
    json_candidates = sorted(ort_results.rglob("*.json"))
    for candidate in json_candidates:
        try:
            payload = json.loads(candidate.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError):
            continue
        if payload.get("bomFormat") == "CycloneDX":
            return candidate
    raise FileNotFoundError(
        f"No CycloneDX JSON report was found under {ort_results}",
    )


def find_optional_cyclonedx_xml(ort_results: Path) -> Path | None:
    xml_candidates = sorted(ort_results.rglob("*.xml"))
    for candidate in xml_candidates:
        if "<bom" in candidate.read_text(encoding="utf-8", errors="ignore")[:512]:
            return candidate
    return None


def sanitize_filename(value: str) -> str:
    collapsed = re.sub(r"[^A-Za-z0-9._-]+", "_", value).strip("._")
    return collapsed or "component"


def normalize_license(entry: dict[str, Any]) -> str | None:
    license_block = entry.get("license") or {}
    for field in ("id", "name"):
        value = license_block.get(field)
        if value:
            return str(value)
    return None


def parse_component(raw: dict[str, Any]) -> Component:
    licenses = tuple(
        sorted(
            {
                license_name
                for license_name in (
                    normalize_license(item) for item in raw.get("licenses", [])
                )
                if license_name
            },
        ),
    )
    external_refs = tuple(
        sorted(
            {
                str(ref.get("url"))
                for ref in raw.get("externalReferences", [])
                if ref.get("url")
            },
        ),
    )
    return Component(
        bom_ref=str(raw.get("bom-ref") or ""),
        group=str(raw.get("group") or ""),
        name=str(raw.get("name") or "unknown"),
        version=str(raw.get("version") or "unknown"),
        scope=str(raw.get("scope") or UNKNOWN_SCOPE),
        licenses=licenses,
        purl=str(raw.get("purl") or ""),
        external_refs=external_refs,
    )


def classify_scope(scope: str) -> str:
    lowered = scope.lower()
    if lowered in RUNTIME_SCOPES:
        return "runtime"
    if lowered in NON_RUNTIME_SCOPES:
        return "non-runtime"
    return "unknown"


def render_component_file(component: Component) -> str:
    lines = [
        f"Package: {component.display_name}",
        f"Version: {component.version}",
        f"Scope: {component.scope}",
    ]
    if component.purl:
        lines.append(f"Package URL: {component.purl}")
    if component.licenses:
        lines.append(f"Licenses: {', '.join(component.licenses)}")
    else:
        lines.append("Licenses: UNKNOWN")
    if component.external_refs:
        lines.append("External References:")
        lines.extend(f"- {url}" for url in component.external_refs)
    return "\n".join(lines) + "\n"


def render_attribution_index(components: list[Component]) -> str:
    grouped: dict[str, list[Component]] = {"runtime": [], "non-runtime": [], "unknown": []}
    for component in components:
        grouped[classify_scope(component.scope)].append(component)

    license_counts = Counter(
        license_name
        for component in components
        for license_name in (component.licenses or ("UNKNOWN",))
    )

    lines = [
        "# FireMUD Third-Party Attribution Index",
        "",
        "This index is generated from the CycloneDX inventory produced during release assembly.",
        "",
        f"- Total components: {len(components)}",
        f"- Runtime-like components: {len(grouped['runtime'])}",
        f"- Non-runtime / excluded components: {len(grouped['non-runtime'])}",
        f"- Unknown-scope components: {len(grouped['unknown'])}",
        "",
        "## License Summary",
        "",
    ]

    for license_name, count in sorted(license_counts.items()):
        lines.append(f"- {license_name}: {count}")

    for scope_name, heading in (
        ("runtime", "Runtime-like Components"),
        ("non-runtime", "Non-Runtime / Excluded Components"),
        ("unknown", "Unknown-Scope Components"),
    ):
        lines.extend(["", f"## {heading}", ""])
        if not grouped[scope_name]:
            lines.append("_None_")
            continue
        for component in sorted(grouped[scope_name], key=lambda item: (item.display_name, item.version)):
            licenses = ", ".join(component.licenses) if component.licenses else "UNKNOWN"
            lines.append(
                f"- `{component.display_name}` `{component.version}` | scope `{component.scope}` | licenses: {licenses}",
            )

    lines.append("")
    return "\n".join(lines)


def render_bundle_readme(components: list[Component]) -> str:
    grouped_counts = Counter(classify_scope(component.scope) for component in components)
    return "\n".join(
        [
            "# FireMUD Release License Bundle",
            "",
            "This directory is generated at release time from ORT output.",
            "",
            "Contents:",
            "- `THIRD_PARTY_NOTICES.txt` – full plain-text notice report",
            "- `THIRD_PARTY_NOTICE_SUMMARY.txt` – summary notice report",
            "- `ATTRIBUTION_INDEX.md` – human-readable package and license index",
            "- `inventory/` – machine-readable CycloneDX BOM artifacts",
            "- `packages/` – one file per detected package, grouped by scope",
            "",
            "Scope grouping:",
            f"- runtime-like: {grouped_counts.get('runtime', 0)}",
            f"- non-runtime/excluded: {grouped_counts.get('non-runtime', 0)}",
            f"- unknown: {grouped_counts.get('unknown', 0)}",
            "",
            "The current release automation derives this bundle from the Gradle and NPM ecosystems scanned by ORT.",
            "",
        ],
    )


def write_package_files(output_dir: Path, components: list[Component]) -> None:
    packages_dir = output_dir / "packages"
    for component in components:
        scope_dir = packages_dir / classify_scope(component.scope)
        scope_dir.mkdir(parents=True, exist_ok=True)
        filename = sanitize_filename(f"{component.display_name}-{component.version}.txt")
        (scope_dir / filename).write_text(
            render_component_file(component),
            encoding="utf-8",
        )


def main() -> None:
    args = parse_args()
    output_dir = args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    for ort_name, output_name in NOTICE_FILES.items():
        source = find_required_notice(args.ort_results, ort_name)
        shutil.copyfile(source, output_dir / output_name)

    cyclonedx_json = find_required_cyclonedx_json(args.ort_results)
    cyclonedx_xml = find_optional_cyclonedx_xml(args.ort_results)

    inventory_dir = output_dir / "inventory"
    inventory_dir.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(cyclonedx_json, inventory_dir / "inventory.cyclonedx.json")
    if cyclonedx_xml:
        shutil.copyfile(cyclonedx_xml, inventory_dir / "inventory.cyclonedx.xml")

    bom = json.loads(cyclonedx_json.read_text(encoding="utf-8"))
    components = [parse_component(raw) for raw in bom.get("components", [])]
    if not components:
        raise ValueError("CycloneDX inventory contained no components; refusing to build an empty /licenses bundle.")

    (output_dir / "inventory" / "inventory.summary.json").write_text(
        json.dumps(
            {
                "componentCount": len(components),
                "runtimeLikeCount": sum(1 for c in components if classify_scope(c.scope) == "runtime"),
                "nonRuntimeCount": sum(1 for c in components if classify_scope(c.scope) == "non-runtime"),
                "unknownScopeCount": sum(1 for c in components if classify_scope(c.scope) == "unknown"),
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )

    write_package_files(output_dir, components)
    (output_dir / "ATTRIBUTION_INDEX.md").write_text(
        render_attribution_index(components),
        encoding="utf-8",
    )
    (output_dir / "README.md").write_text(
        render_bundle_readme(components),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
