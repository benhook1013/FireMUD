#!/usr/bin/env bash
# Lightweight contracts for architecture/process docs that are easy to drift.
set -euo pipefail

python3 - <<'PY'
import pathlib
import re

root = pathlib.Path(".")
obsolete_public_resume_signature = "`resume(operationId, expectedPhase, scope, maintenanceLockToken, evidenceRef)`"
obsolete_gameplay_session_selector = "session:game:{tenantInstanceTag}"
obsolete_gameplay_character_index = "session:game:index:character:{tenantGameplayTag}:<gameInstanceId>:<characterId>"
obsolete_gameplay_character_index_with_scope = "session:game:index:character:{tenantGameplayTag}:<playableStateNamespaceId>:<playableStateScope>:<characterId>"
maintenance_lock_token_syntax = re.compile(r"--maintenance-lock-token(?![A-Za-z0-9_-])")
maintenance_lock_token_prohibition = re.compile(
    r"(?:"
    r"\b(?:must|may|should|can|does|do|is|are)\s+not\b"
    r"|\bnever\b"
    r"|\b(?:forbid|forbids|forbidden|prohibit|prohibits|prohibited|"
    r"disallow|disallows|disallowed)\b"
    r")(?:\b(?:[eE]\.g\.|[iI]\.e\.|etc\.)|[^.!?\r\n])*--maintenance-lock-token(?![A-Za-z0-9_-])"
    r"|--maintenance-lock-token(?![A-Za-z0-9_-])(?:\b(?:[eE]\.g\.|[iI]\.e\.|etc\.)|[^.!?\r\n])*"
    r"\b(?:forbidden|prohibited|disallowed)\b"
)
fence_start = re.compile(r"^[ \t]*(`{3,}|~{3,})")


def is_sentence_boundary(text, position):
    character = text[position]
    if character in "!?\n":
        return True
    if character != ".":
        return False
    if position + 1 < len(text) and not text[position + 1].isspace():
        return False
    prefix = text[max(0, position - 7) : position + 1].lower()
    return not any(prefix.endswith(abbreviation) for abbreviation in ("e.g.", "i.e.", "etc."))


def sentence_containing(text, position):
    start = position - 1
    while start >= 0 and not is_sentence_boundary(text, start):
        start -= 1
    end = position
    while end < len(text) and not is_sentence_boundary(text, end):
        end += 1
    return text[start + 1 : min(end + 1, len(text))]


def advance_fenced_block_state(line, in_fenced_block, fence_marker, opening_line_number, line_number):
    fence = fence_start.match(line)
    if fence is None:
        return in_fenced_block, fence_marker, opening_line_number

    marker = fence.group(1)
    if not in_fenced_block:
        return True, marker, line_number
    if (
        marker[0] == fence_marker[0]
        and len(marker) >= len(fence_marker)
        and line[fence.end(1) :].strip(" \t\r\n") == ""
    ):
        return False, None, None
    return in_fenced_block, fence_marker, opening_line_number


def has_forbidden_maintenance_lock_token_syntax(text, source_path=None):
    in_fenced_example = False
    fence_marker = None
    opening_line_number = None
    for line_number, line in enumerate(text.splitlines(keepends=True), start=1):
        in_fenced_example, fence_marker, opening_line_number = advance_fenced_block_state(
            line,
            in_fenced_example,
            fence_marker,
            opening_line_number,
            line_number,
        )
        if in_fenced_example:
            if maintenance_lock_token_syntax.search(line):
                return True

    if in_fenced_example:
        source_prefix = f"{source_path}: " if source_path is not None else ""
        raise SystemExit(
            f"{source_prefix}unterminated fenced example opened at line "
            f"{opening_line_number}"
        )

    for match in maintenance_lock_token_syntax.finditer(text):
        if maintenance_lock_token_prohibition.search(
            sentence_containing(text, match.start())
        ) is None:
            return True
    return False

for example in (
    "--maintenance-lock-token <token>",
    "--maintenance-lock-token=<token>",
    "--maintenance-lock-token = <token>",
    "`--maintenance-lock-token`",
    "--maintenance-lock-token,",
):
    if not has_forbidden_maintenance_lock_token_syntax(example):
        raise SystemExit(f"maintenance token syntax fixture was not rejected: {example}")
if has_forbidden_maintenance_lock_token_syntax("--maintenance-lock-token-file <token-file>"):
    raise SystemExit("maintenance token file syntax was incorrectly rejected")
if has_forbidden_maintenance_lock_token_syntax(
    "The public command must not accept `--maintenance-lock-token` as a value."
):
    raise SystemExit("explicit maintenance token prohibition was incorrectly rejected")
if has_forbidden_maintenance_lock_token_syntax(
    "The public command must not accept, e.g., `--maintenance-lock-token` as a value."
):
    raise SystemExit("abbreviated maintenance token prohibition was incorrectly rejected")
if has_forbidden_maintenance_lock_token_syntax(
    "The public command must not accept, i.e., `--maintenance-lock-token` as a value."
):
    raise SystemExit("i.e. maintenance token prohibition was incorrectly rejected")
if has_forbidden_maintenance_lock_token_syntax(
    "The public command must not accept, etc., `--maintenance-lock-token` as a value."
):
    raise SystemExit("etc. maintenance token prohibition was incorrectly rejected")
if not has_forbidden_maintenance_lock_token_syntax(
    "The option is forbidden. `--maintenance-lock-token`"
):
    raise SystemExit("cross-sentence maintenance token prohibition was incorrectly accepted")
if not has_forbidden_maintenance_lock_token_syntax(
    "```text\n--maintenance-lock-token <token>\n```"
):
    raise SystemExit("fenced maintenance token example was incorrectly accepted")
if has_forbidden_maintenance_lock_token_syntax(
    "```text\nsafe example\n````\nThe `--maintenance-lock-token` option is forbidden."
):
    raise SystemExit("longer CommonMark closing fence was not recognized")
if not has_forbidden_maintenance_lock_token_syntax(
    "````text\n```\nThe `--maintenance-lock-token` option is forbidden.\n````"
):
    raise SystemExit("shorter nested fence incorrectly closed the outer fence")
if not has_forbidden_maintenance_lock_token_syntax(
    "```text\n```unsafe --maintenance-lock-token <token>\n```"
):
    raise SystemExit("fence marker with trailing text incorrectly closed the fenced example")
if has_forbidden_maintenance_lock_token_syntax(
    "```text\nsafe example\n```\nThe option is forbidden: --maintenance-lock-token"
):
    raise SystemExit("balanced fence incorrectly kept the outer text fenced")
unterminated_fence_fixture = "preamble\n```text\nsafe example\n"
try:
    has_forbidden_maintenance_lock_token_syntax(unterminated_fence_fixture)
except SystemExit as error:
    if str(error) != "unterminated fenced example opened at line 2":
        raise SystemExit(f"unexpected unterminated fence diagnostic: {error}")
else:
    raise SystemExit("unterminated fence was not rejected")
try:
    has_forbidden_maintenance_lock_token_syntax(
        unterminated_fence_fixture,
        "design/example.md",
    )
except SystemExit as error:
    if str(error) != "design/example.md: unterminated fenced example opened at line 2":
        raise SystemExit(f"unexpected path-aware fence diagnostic: {error}")
else:
    raise SystemExit("unterminated fence with source path was not rejected")

def require_contains(path, snippets):
    text = (root / path).read_text(encoding="utf-8")
    missing = [snippet for snippet in snippets if snippet not in text]
    if missing:
        raise SystemExit(f"{path}: missing required snippets: {missing}")


def require_absent(path, snippets):
    text = (root / path).read_text(encoding="utf-8")
    present = [snippet for snippet in snippets if snippet in text]
    if present:
        raise SystemExit(f"{path}: contains forbidden snippets: {present}")

obsolete_redis_rebind_envelope = re.compile(
    r"\brebind(?:[-_\s]?handle)?[-_\s]?envelope\b",
    re.IGNORECASE,
)

for obsolete_rebind_term in (
    "rebind-envelope",
    "rebind_envelope",
    "rebind envelope",
    "rebindHandleEnvelope",
    "rebind-handle-envelope",
):
    if obsolete_redis_rebind_envelope.search(obsolete_rebind_term) is None:
        raise SystemExit(
            "obsolete Redis rebind-envelope matcher missed "
            f"{obsolete_rebind_term!r}"
        )
for canonical_rebind_term in ("rebindHandle", "rebind-handle", "rebind handle"):
    if obsolete_redis_rebind_envelope.search(canonical_rebind_term):
        raise SystemExit(
            "obsolete Redis rebind-envelope matcher rejected canonical "
            f"{canonical_rebind_term!r}"
        )

for path in (root / "design").rglob("*.md"):
    text = path.read_text(encoding="utf-8")
    if "<deployment-event-id>" in text:
        raise SystemExit(f"{path}: use the canonical <deploymentEventId> path placeholder")
    if obsolete_public_resume_signature in text:
        raise SystemExit(f"{path}: uses obsolete caller-supplied recovery scope")
    if has_forbidden_maintenance_lock_token_syntax(text, path):
        raise SystemExit(
            f"{path}: recovery command examples must not expose "
            "`--maintenance-lock-token` command-line syntax; explicit prose "
            "prohibitions are allowed"
        )

obsolete_envelope_phrases = ("Account-issued envelope", "Account-validated envelope")
decision_history_dir = root / "design/architecture/decisions"

historical_adr_record_name = re.compile(r"adr-\d{4}-.+\.md")
status_heading = re.compile(r"^[ ]{0,3}##[ \t]+Status(?:[ \t]+#+)?[ \t]*$")
historical_status_value = re.compile(r"^(?:Superseded|Withdrawn)\b")
raw_html_closing_tag_only = frozenset(("pre", "script", "style", "textarea"))
raw_html_block_start = re.compile(
    r"^[ ]{0,3}<(?P<closing>/)?(?P<tag>address|article|aside|base|basefont|blockquote|body|caption|center|col|colgroup|dd|details|dialog|dir|div|dl|dt|fieldset|figcaption|figure|footer|form|frame|frameset|h[1-6]|head|header|hr|html|iframe|legend|li|link|main|menu|menuitem|nav|noframes|ol|optgroup|option|p|param|pre|script|search|section|style|summary|table|tbody|td|textarea|tfoot|th|thead|title|tr|track|ul)(?:[ \t>]|/>|$)",
    re.IGNORECASE,
)
raw_html_type7_start = re.compile(
    r"^[ ]{0,3}(?:"
    r"</[A-Za-z][A-Za-z0-9-]*[ \t]*>"
    r"|<[A-Za-z][A-Za-z0-9-]*"
    r"(?:[ \t]+[A-Za-z_:][A-Za-z0-9_.:-]*(?:[ \t]*=[ \t]*"
    r"(?:[^ \t\r\n\"'=<>\x60]+|'[^'\r\n]*'|\"[^\"\r\n]*\"))?)*"
    r"[ \t]*/?>"
    r")[ \t]*(?:\r?\n)?$",
    re.IGNORECASE,
)
raw_html_special_start = re.compile(
    r"^[ ]{0,3}(?:(?P<processing><\?)|(?P<declaration><![A-Z])|(?P<cdata><!\[CDATA\[))",
)
# The NUL sentinel assumes scanned Markdown contains no literal NUL.
html_comment_boundary = "\x00"
atx_heading_with_content = re.compile(r"^[ ]{0,3}#{1,6}[ \t]+\S")


def has_non_whitespace(text):
    return any(not character.isspace() for character in text)


def strip_html_comments(line, in_html_comment):
    visible = []
    cursor = 0
    while cursor < len(line):
        if in_html_comment:
            closing = line.find("-->", cursor)
            if closing == -1:
                return "".join(visible), True
            in_html_comment = False
            cursor = closing + len("-->")
            remainder = line[cursor:]
            if has_non_whitespace(remainder) and (
                not visible or not visible[-1].endswith(html_comment_boundary)
            ):
                visible.append(html_comment_boundary)
            continue

        opening = line.find("<!--", cursor)
        if opening == -1:
            visible.append(line[cursor:])
            break
        prefix = line[cursor:opening]
        visible.append(prefix)
        if has_non_whitespace(prefix) and atx_heading_with_content.match(prefix) is None:
            visible.append(html_comment_boundary)
        in_html_comment = True
        cursor = opening + len("<!--")
    return "".join(visible), in_html_comment


def strip_raw_html_block(
    line, in_raw_html_block, raw_html_block_kind, physical_line_blank
):
    """Hide CommonMark raw HTML blocks from top-level Markdown status parsing."""
    if in_raw_html_block:
        if raw_html_block_kind == "processing":
            return "", True, "?>" not in line, None if "?>" in line else raw_html_block_kind
        if raw_html_block_kind == "declaration":
            return "", True, ">" not in line, None if ">" in line else raw_html_block_kind
        if raw_html_block_kind == "cdata":
            return "", True, "]]>" not in line, None if "]]>" in line else raw_html_block_kind
        if raw_html_block_kind is None or raw_html_block_kind.lower() not in raw_html_closing_tag_only:
            if physical_line_blank:
                return "", True, False, None
        elif re.search(
            rf"</{re.escape(raw_html_block_kind)}[ \t]*>", line, re.IGNORECASE
        ):
            return "", True, False, None
        return "", True, True, raw_html_block_kind

    match = raw_html_block_start.match(line)
    # Type 1-6 blocks take precedence over the generic Type-7 tag form.
    type7 = raw_html_type7_start.match(line) if match is None else None
    special = raw_html_special_start.match(line)
    if match is None and type7 is None and special is None:
        return line, False, False, None
    tag = match.group("tag") if match is not None else None
    if special is not None:
        kind = next(name for name, value in special.groupdict().items() if value is not None)
        terminator = {"processing": "?>", "declaration": ">", "cdata": "]]>"}[kind]
        return "", True, terminator not in line[special.end() :], (
            kind if terminator not in line[special.end() :] else None
        )
    if type7 is not None:
        return "", True, True, None
    if (
        match.group("closing") is not None
        and tag is not None
        and tag.lower() in raw_html_closing_tag_only
    ):
        return "", True, True, None
    if tag is not None and tag.lower() in raw_html_closing_tag_only:
        closing_tag = re.search(
            rf"</{re.escape(tag)}[ \t]*>", line, re.IGNORECASE
        )
        return "", True, closing_tag is None, tag if closing_tag is None else None
    return "", True, True, tag


def iter_visible_markdown_lines(text, include_fenced_content, source_label=None):
    in_fenced_block = False
    fence_marker = None
    opening_line_number = None
    in_html_comment = False
    in_raw_html_block = False
    raw_html_block_kind = None

    for line_number, line in enumerate(text.splitlines(keepends=True), start=1):
        if in_fenced_block:
            if include_fenced_content:
                yield line_number, line
            in_fenced_block, fence_marker, opening_line_number = (
                advance_fenced_block_state(
                    line,
                    in_fenced_block,
                    fence_marker,
                    opening_line_number,
                    line_number,
                )
            )
            continue

        physical_line_blank = not line.strip()
        if in_raw_html_block:
            _, _, in_raw_html_block, raw_html_block_kind = strip_raw_html_block(
                line,
                True,
                raw_html_block_kind,
                physical_line_blank,
            )
            continue
        if not in_html_comment:
            line, consumed, in_raw_html_block, raw_html_block_kind = (
                strip_raw_html_block(line, False, None, physical_line_blank)
            )
            if consumed:
                continue
        line, in_html_comment = strip_html_comments(line, in_html_comment)
        if not line:
            continue

        if include_fenced_content:
            yield line_number, line
        in_fenced_block, fence_marker, opening_line_number = (
            advance_fenced_block_state(
                line,
                in_fenced_block,
                fence_marker,
                opening_line_number,
                line_number,
            )
        )
        if in_fenced_block:
            continue
        if not include_fenced_content:
            yield line_number, line


    if in_fenced_block:
        source_prefix = f"{source_label}: " if source_label is not None else ""
        raise SystemExit(
            f"{source_prefix}unterminated fenced example opened at line {opening_line_number}"
        )


def first_top_level_status_value(text, source_label=None):
    status_heading_found = False
    for _, line in iter_visible_markdown_lines(
        text,
        include_fenced_content=False,
        source_label=source_label,
    ):
        if not status_heading_found:
            if status_heading.match(line.rstrip("\r\n")):
                status_heading_found = True
            continue
        if line.startswith((" ", "\t")):
            continue
        if line.strip():
            return line.replace(html_comment_boundary, "").strip()
    return None


def is_historical_adr_record(path, text):
    status_value = first_top_level_status_value(text, source_label=path)
    return (
        path.parent == decision_history_dir
        and historical_adr_record_name.fullmatch(path.name) is not None
        and status_value is not None
        and historical_status_value.match(status_value) is not None
    )


def reject_obsolete_redis_rebind_envelope(path, text):
    if is_historical_adr_record(path, text):
        return
    if obsolete_redis_rebind_envelope.search(text):
        raise SystemExit(
            f"{path}: design-tree Redis contract must use the opaque rebindHandle, "
            "not rebind-envelope terminology"
        )


def reject_obsolete_envelope_phrases(path, text):
    if is_historical_adr_record(path, text):
        return
    for line_number, line in enumerate(text.splitlines(), start=1):
        for phrase in obsolete_envelope_phrases:
            if phrase in line:
                raise SystemExit(
                    f"{path}:{line_number}: obsolete current-state phrase {phrase!r}"
                )


def reject_obsolete_gameplay_session_selector(path, text):
    if is_historical_adr_record(path, text):
        return
    if obsolete_gameplay_session_selector in text:
        raise SystemExit(
            f"{path}: gameplay session selectors must use the canonical "
            "tenantGameplayTag/gameInstanceId shape"
        )


def reject_obsolete_gameplay_character_index(path, text):
    if is_historical_adr_record(path, text):
        return
    if (
        obsolete_gameplay_character_index in text
        or obsolete_gameplay_character_index_with_scope in text
    ):
        raise SystemExit(
            f"{path}: character-session indexes must use the canonical "
            "playableStateNamespaceId-only controller-key shape"
        )


historical_adr_fixture = decision_history_dir / "adr-9999-history-fixture.md"
historical_adr_fixture_text = (
    "# ADR 9999: Historical Fixture\n\n## Status\n\nSuperseded by ADR 0001\n\n"
    "Account-issued envelope\n"
    "rebind-envelope\n"
    "session:game:{tenantInstanceTag}\n"
    + obsolete_gameplay_character_index
    + "\n"
    + obsolete_gameplay_character_index_with_scope
    + "\n"
)
indented_status_heading_fixture_cases = (
    (
        decision_history_dir / "adr-9990-one-space-status-heading-fixture.md",
        "# ADR 9990: One-Space Status Heading Fixture\n\n"
        " ## Status\n\n"
        "Superseded by ADR 0001\n\n"
        "Account-issued envelope\n"
        "rebind-envelope\n",
    ),
    (
        decision_history_dir / "adr-9989-two-space-status-heading-fixture.md",
        "# ADR 9989: Two-Space Status Heading Fixture\n\n"
        "  ## Status\n\n"
        "Superseded by ADR 0001\n\n"
        "Account-issued envelope\n"
        "rebind-envelope\n",
    ),
    (
        decision_history_dir / "adr-9988-three-space-status-heading-fixture.md",
        "# ADR 9988: Three-Space Status Heading Fixture\n\n"
        "   ## Status\n\n"
        "Superseded by ADR 0001\n\n"
        "Account-issued envelope\n"
        "rebind-envelope\n",
    ),
)
accepted_adr_fixture = decision_history_dir / "adr-9998-accepted-fixture.md"
accepted_adr_fixture_text = (
    "# ADR 9998: Accepted Fixture\n\n"
    "<!--\n"
    "## Status\n\n"
    "Superseded by ADR 0001\n"
    "Account-issued envelope\n"
    "-->\n\n"
    "```markdown\n"
    "## Status\n\n"
    "Superseded by ADR 0001\n"
    "```\n\n"
    "    ## Status\n"
    "    Superseded by ADR 0001\n\n"
    "## Status\n\n"
    "Accepted\n"
    "rebind-envelope\n"
    "session:game:{tenantInstanceTag}\n"
)
raw_html_adr_fixture = decision_history_dir / "adr-9997-raw-html-fixture.md"
raw_html_adr_fixture_text = (
    "# ADR 9997: Raw HTML Fixture\n\n"
    "<div class=\"history\">\n"
    "## Status\n"
    "Superseded by ADR 0001\n"
    "Account-issued envelope\n"
    "</div>\n"
)
tab_indented_raw_html_fixture_text = (
    "# ADR 9979: Tab-Indented Raw HTML Fixture\n\n"
    "\t<div>\n"
    "## Status\n"
    "Superseded by ADR 0001\n"
)
type7_raw_html_status_fixture_text = (
    "# ADR 9982: Type-7 Raw HTML Status Fixture\n\n"
    "<span class=\"history\">\n"
    "## Status\n"
    "Superseded by ADR 0001\n"
    "</span>\n\n"
    "## Status\n\n"
    "Accepted\n"
)
ordinary_raw_html_closing_fixture_text = (
    "# ADR 9991: Ordinary Raw HTML Closing Fixture\n\n"
    "<div>\n"
    "## Hidden Status Inside\n"
    "Superseded by ADR 0001\n"
    "</div>\n"
    "## Hidden Status After Closing\n"
    "Superseded by ADR 0001\n"
    "\n"
    "## Status\n\n"
    "Accepted\n"
)
same_line_raw_html_fixture_text = (
    "# ADR 9989: Same-Line Raw HTML Fixture\n\n"
    "<div></div>\n"
    "## Hidden Status Inside Same-Line Block\n"
    "Superseded by ADR 0001\n"
    "\n"
    "## Status\n\n"
    "Accepted\n"
)
self_closing_raw_html_fixture_text = (
    "# ADR 9988: Self-Closing Raw HTML Fixture\n\n"
    "<div/>\n"
    "## Hidden Status Inside Self-Closing Block\n"
    "Superseded by ADR 0001\n"
    "\n"
    "## Status\n\n"
    "Accepted\n"
)
standalone_slash_raw_html_fixture_text = (
    "# ADR 9980: Standalone Slash Raw HTML Fixture\n\n"
    "<div/not-a-tag>\n"
    "## Status\n"
    "Superseded by ADR 0001\n"
)
extended_type6_raw_html_fixture_text = (
    "# ADR 9987: Extended Type-6 Raw HTML Fixture\n\n"
    "<option>\n"
    "## Hidden Status Inside Extended Type-6 Block\n"
    "Superseded by ADR 0001\n"
    "</option>\n"
    "\n"
    "## Status\n\n"
    "Accepted\n"
)
style_html_adr_fixture_text = (
    "# ADR 9995: Style Raw HTML Fixture\n\n"
    "<style>\n\n"
    "## Status\n"
    "Superseded by ADR 0001\n"
    "</style>\n"
)
script_html_adr_fixture_text = (
    "# ADR 9994: Script Raw HTML Fixture\n\n"
    "<script>\n\n"
    "## Status\n"
    "Superseded by ADR 0001\n"
    "</script>\n"
)
processing_instruction_adr_fixture_text = (
    "# ADR 9993: Processing Instruction Fixture\n\n"
    "<?firemud\n"
    "## Status\n"
    "Superseded by ADR 0001\n"
    "?>\n"
    "## Status\n\n"
    "Accepted\n"
)
cdata_adr_fixture_text = (
    "# ADR 9992: CDATA Fixture\n\n"
    "<![CDATA[\n"
    "## Status\n"
    "Superseded by ADR 0001\n"
    "]]>\n"
    "## Status\n\n"
    "Accepted\n"
)
lowercase_cdata_fixture_text = (
    "<![cdata[\n"
    "## Status\n"
    "Superseded by ADR 0001\n"
)
lowercase_declaration_fixture_text = (
    "<!lowercase>\n"
    "## Status\n"
    "Superseded by ADR 0001\n"
)
comment_only_type6_html_fixture_text = (
    "# ADR 9986: Comment-Only Type-6 Raw HTML Fixture\n\n"
    "<div>\n"
    "<!-- hidden comment -->\n"
    "## Hidden Status After Comment-Only Line\n"
    "Superseded by ADR 0001\n"
    "</div>\n"
    "\n"
    "## Status\n\n"
    "Accepted\n"
)
unclosed_comment_in_type6_html_fixture_text = (
    "# ADR 9984: Unclosed Comment In Type-6 Raw HTML Fixture\n\n"
    "<div>\n"
    "<!-- comment remains unclosed inside the raw block\n"
    "## Hidden Status Inside Raw HTML\n"
    "Superseded by ADR 0001\n"
    "\n"
    "## Status\n\n"
    "Accepted\n"
)
indented_status_value_fixture = decision_history_dir / "adr-9996-indented-status-value-fixture.md"
indented_status_value_fixture_text = (
    "# ADR 9996: Indented Status Value Fixture\n\n"
    "## Status\n\n"
    "    Superseded by ADR 0001\n\n"
    "Accepted\n\n"
    "Account-issued envelope\n"
)
unterminated_fence_adr_fixture = decision_history_dir / "adr-9978-unterminated-fence-fixture.md"
unterminated_fence_adr_fixture_text = (
    "```markdown\n"
    "## Status\n"
    "Superseded by ADR 0001\n"
)
registry_index_fixture = decision_history_dir / "README.md"
if not is_historical_adr_record(historical_adr_fixture, historical_adr_fixture_text):
    raise SystemExit("historical ADR fixture was not recognized as an exempt record")
reject_obsolete_redis_rebind_envelope(
    historical_adr_fixture,
    historical_adr_fixture_text,
)
reject_obsolete_gameplay_session_selector(
    historical_adr_fixture,
    historical_adr_fixture_text,
)
reject_obsolete_gameplay_character_index(
    historical_adr_fixture,
    historical_adr_fixture_text,
)
for fixture_path, fixture_text in indented_status_heading_fixture_cases:
    if not is_historical_adr_record(fixture_path, fixture_text):
        raise SystemExit(
            f"indented historical ADR fixture was not recognized: {fixture_path.name}"
        )
    reject_obsolete_redis_rebind_envelope(fixture_path, fixture_text)
    reject_obsolete_envelope_phrases(fixture_path, fixture_text)
if is_historical_adr_record(registry_index_fixture, obsolete_envelope_phrases[0]):
    raise SystemExit("decision registry/index fixture was incorrectly exempted")
try:
    is_historical_adr_record(
        unterminated_fence_adr_fixture,
        unterminated_fence_adr_fixture_text,
    )
except SystemExit as error:
    expected_diagnostic = (
        f"{unterminated_fence_adr_fixture}: "
        "unterminated fenced example opened at line 1"
    )
    if str(error) != expected_diagnostic:
        raise SystemExit(f"unexpected historical ADR fence diagnostic: {error}")
else:
    raise SystemExit("unterminated historical ADR fence was not rejected")
if first_top_level_status_value(accepted_adr_fixture_text) != "Accepted":
    raise SystemExit("commented, fenced, or indented fake status bypassed the Accepted fixture")
if first_top_level_status_value(raw_html_adr_fixture_text) is not None:
    raise SystemExit("raw HTML block status was incorrectly parsed")
if first_top_level_status_value(tab_indented_raw_html_fixture_text) != "Superseded by ADR 0001":
    raise SystemExit("tab-indented raw HTML block was incorrectly hidden")
for raw_html_pattern, raw_html_line in (
    (raw_html_block_start, "\t<div>\n"),
    (raw_html_type7_start, "\t<span>\n"),
    (raw_html_special_start, "\t<?firemud\n"),
):
    if raw_html_pattern.match(raw_html_line) is not None:
        raise SystemExit("tab-indented raw HTML start was incorrectly accepted")
if first_top_level_status_value(type7_raw_html_status_fixture_text) != "Accepted":
    raise SystemExit("type-7 raw HTML status was incorrectly parsed")
for raw_html_tag in (
    '<span data="<nested > angle brackets">\n',
    "<span data='quoted > angle brackets'>\n",
):
    if raw_html_type7_start.match(raw_html_tag) is None:
        raise SystemExit(
            "quoted angle brackets in a type-7 raw HTML tag were not accepted"
        )
for malformed_raw_html_tag in (
    "<span data=unquoted<value>\n",
    '<span data=">"> trailing text\n',
):
    if raw_html_type7_start.match(malformed_raw_html_tag) is not None:
        raise SystemExit(
            "malformed type-7 raw HTML attribute or trailing text was accepted"
        )
if first_top_level_status_value(ordinary_raw_html_closing_fixture_text) != "Accepted":
    raise SystemExit("ordinary raw HTML block closed before a blank line")
if first_top_level_status_value(same_line_raw_html_fixture_text) != "Accepted":
    raise SystemExit("same-line Type-6 raw HTML block closed before a blank line")
if first_top_level_status_value(self_closing_raw_html_fixture_text) != "Accepted":
    raise SystemExit("self-closing Type-6 raw HTML block closed before a blank line")
if first_top_level_status_value(standalone_slash_raw_html_fixture_text) != "Superseded by ADR 0001":
    raise SystemExit("standalone slash after a Type-6 tag name was incorrectly accepted")
if first_top_level_status_value(extended_type6_raw_html_fixture_text) != "Accepted":
    raise SystemExit("extended Type-6 raw HTML tag was not hidden before a blank line")
if first_top_level_status_value(style_html_adr_fixture_text) is not None:
    raise SystemExit("style raw HTML block status was incorrectly parsed")
if first_top_level_status_value(script_html_adr_fixture_text) is not None:
    raise SystemExit("script raw HTML block status was incorrectly parsed")
if first_top_level_status_value(processing_instruction_adr_fixture_text) != "Accepted":
    raise SystemExit("processing-instruction block hid the following Accepted status")
if first_top_level_status_value(cdata_adr_fixture_text) != "Accepted":
    raise SystemExit("CDATA block hid the following Accepted status")
if first_top_level_status_value(lowercase_cdata_fixture_text) != "Superseded by ADR 0001":
    raise SystemExit("lowercase CDATA was incorrectly treated as Type-5 raw HTML")
if first_top_level_status_value(lowercase_declaration_fixture_text) != "Superseded by ADR 0001":
    raise SystemExit("lowercase declaration was incorrectly treated as Type-4 raw HTML")
if first_top_level_status_value(comment_only_type6_html_fixture_text) != "Accepted":
    raise SystemExit("comment-only Type-6 HTML line incorrectly terminated the block")
if first_top_level_status_value(unclosed_comment_in_type6_html_fixture_text) != "Accepted":
    raise SystemExit("unclosed comment inside Type-6 HTML hid the following Accepted status")
for fixture_id, closing_tag in enumerate(
    ("script", "style", "pre", "textarea"), start=9900
):
    closing_special_tag_fixture_text = (
        f"# ADR {fixture_id}: Closing {closing_tag} Raw HTML Fixture\n\n"
        f"</{closing_tag}>\n"
        "## Hidden Status After Closing Special Tag\n"
        "Superseded by ADR 0001\n"
        "\n"
        "## Status\n\n"
        "Accepted\n"
    )
    if first_top_level_status_value(closing_special_tag_fixture_text) != "Accepted":
        raise SystemExit(
            f"closing {closing_tag} tag did not start an ordinary Type-6 raw HTML block"
        )
if first_top_level_status_value("    ## Status\n    Superseded by ADR 0001\n") is not None:
    raise SystemExit("four-space indented code-block status was incorrectly parsed")
if first_top_level_status_value(indented_status_value_fixture_text) != "Accepted":
    raise SystemExit("indented fake status value bypassed the real Accepted status")
same_line_comment_prefix_fixture_text = (
    "# ADR 9990: Same-Line Comment Prefix Fixture\n\n"
    "<!-- hidden prefix -->## Hidden Status\n"
    "Superseded by ADR 0001\n"
    "## Status\n\n"
    "Accepted\n"
)
if first_top_level_status_value(same_line_comment_prefix_fixture_text) != "Accepted":
    raise SystemExit("same-line HTML comment prefix created a visible status heading")
comment_adjacent_heading_fixture_text = (
    "# ADR 9981: Comment-Adjacent Heading Fixture\n\n"
    "##<!-- hidden --> Status\n"
    "Superseded by ADR 0001\n"
    "##<!-- hidden -->Status\n"
    "Superseded by ADR 0001\n"
    "## Status\n\n"
    "Accepted\n"
)
if first_top_level_status_value(comment_adjacent_heading_fixture_text) != "Accepted":
    raise SystemExit("HTML comment removal created a synthetic ATX status heading")
inline_comment_status_heading_fixture_text = (
    "# ADR 9983: Inline Comment Status Heading Fixture\n\n"
    "## Status <!-- inline comment -->\n"
    "Accepted\n"
)
if first_top_level_status_value(inline_comment_status_heading_fixture_text) != "Accepted":
    raise SystemExit("inline HTML comment invalidated a valid status heading")
comment_adjacent_status_value_fixture = (
    "## Status <!-- inline comment -->\n"
    "Accepted <!-- trailing comment -->\n"
)
if first_top_level_status_value(comment_adjacent_status_value_fixture) != "Accepted":
    raise SystemExit("HTML comment boundary leaked into a returned status value")
reject_obsolete_envelope_phrases(
    historical_adr_fixture,
    historical_adr_fixture_text,
)
try:
    reject_obsolete_redis_rebind_envelope(
        accepted_adr_fixture,
        accepted_adr_fixture_text,
    )
except SystemExit as error:
    if "design-tree Redis contract must use" not in str(error):
        raise SystemExit(f"unexpected Accepted ADR Redis diagnostic: {error}")
else:
    raise SystemExit("Accepted ADR was not checked for obsolete Redis terminology")
try:
    reject_obsolete_gameplay_session_selector(
        accepted_adr_fixture,
        accepted_adr_fixture_text,
    )
except SystemExit as error:
    if "gameplay session selectors" not in str(error):
        raise SystemExit(f"unexpected Accepted ADR selector diagnostic: {error}")
else:
    raise SystemExit("Accepted ADR fixture was not checked for obsolete selectors")
try:
    reject_obsolete_gameplay_character_index(
        accepted_adr_fixture,
        accepted_adr_fixture_text + obsolete_gameplay_character_index,
    )
except SystemExit as error:
    if "character-session indexes" not in str(error):
        raise SystemExit(f"unexpected Accepted ADR character-index diagnostic: {error}")
else:
    raise SystemExit("Accepted ADR fixture was not checked for obsolete character indexes")
try:
    reject_obsolete_gameplay_character_index(
        accepted_adr_fixture,
        accepted_adr_fixture_text + obsolete_gameplay_character_index_with_scope,
    )
except SystemExit as error:
    if "character-session indexes" not in str(error):
        raise SystemExit(f"unexpected Accepted ADR scoped character-index diagnostic: {error}")
else:
    raise SystemExit("Accepted ADR fixture was not checked for obsolete scoped character indexes")
try:
    reject_obsolete_envelope_phrases(
        accepted_adr_fixture,
        accepted_adr_fixture_text,
    )
except SystemExit as error:
    if "obsolete current-state phrase" not in str(error):
        raise SystemExit(f"unexpected Accepted ADR fixture diagnostic: {error}")
else:
    raise SystemExit("Accepted ADR fixture was not checked")
try:
    reject_obsolete_envelope_phrases(
        raw_html_adr_fixture,
        raw_html_adr_fixture_text,
    )
except SystemExit as error:
    if "obsolete current-state phrase" not in str(error):
        raise SystemExit(f"unexpected raw HTML ADR fixture diagnostic: {error}")
else:
    raise SystemExit("raw HTML ADR fixture bypassed obsolete phrase rejection")
try:
    reject_obsolete_envelope_phrases(
        indented_status_value_fixture,
        indented_status_value_fixture_text,
    )
except SystemExit as error:
    if "obsolete current-state phrase" not in str(error):
        raise SystemExit(f"unexpected indented status fixture diagnostic: {error}")
else:
    raise SystemExit("indented status fixture bypassed obsolete phrase rejection")
try:
    reject_obsolete_envelope_phrases(
        registry_index_fixture,
        obsolete_envelope_phrases[0],
    )
except SystemExit as error:
    if "obsolete current-state phrase" not in str(error):
        raise SystemExit(f"unexpected registry/index fixture diagnostic: {error}")
else:
    raise SystemExit("decision registry/index fixture was not checked")

for path in (root / "design").rglob("*.md"):
    text = path.read_text(encoding="utf-8")
    reject_obsolete_redis_rebind_envelope(path, text)
    reject_obsolete_envelope_phrases(path, text)
    reject_obsolete_gameplay_session_selector(path, text)
    reject_obsolete_gameplay_character_index(path, text)

canonical_world_dynamic = "world-dynamic:<tenantId>:room-dynamic:<gameInstanceId>:<roomInstanceId>"
for path in [
    "design/architecture/system-architecture-redis-cache.md",
    "design/architecture/system-architecture-redis-cache-reference.md",
    "design/architecture/system-architecture-redis-cheatsheet.md",
    "design/architecture/microservices/world-management-service/runtime-and-data.md",
]:
    require_contains(path, [canonical_world_dynamic])

require_contains(
    "design/architecture/microservices/game-design-service/asset-storage.md",
    [
        "`EXPORTED_UNATTESTED -> FAILED`",
        "`FAILED -> TOMBSTONED`",
        "`TOMBSTONED -> PURGE_IN_PROGRESS`",
        "`PURGE_IN_PROGRESS -> PURGED`",
        "`PURGE_IN_PROGRESS -> PURGE_FAILED`",
        "`PURGED` is a retained terminal metadata state",
    ],
)
require_contains(
    "design/architecture/system-architecture-asset-store-runbook.md",
    [
        "`EXPORTED_UNATTESTED -> FAILED`",
        "`FAILED -> TOMBSTONED`",
        "`TOMBSTONED -> PURGE_IN_PROGRESS`",
        "`PURGE_IN_PROGRESS -> PURGED`",
        "`PURGE_IN_PROGRESS -> PURGE_FAILED`",
        "`PURGED` remains a retained terminal metadata row",
    ],
)
require_contains(
    "design/architecture/system-architecture-versioning-runtime.md",
    [
        "records the asset artifact as `FAILED`",
        "Moving failed artifact bytes to `TOMBSTONED` remains a separate explicit abandonment/quarantine action",
        "Asset purge must be initiated through CAS-guarded control-plane operations",
    ],
)
require_contains(
    "design/project-management/review-checklists.md",
    [
        "Cross-check findings against relevant domain implementation trackers, canonical design, proto contracts, and current service code",
        "Auth/session reviews must include the gateway, session-behavior, authz route matrix, Account runtime docs, Game Session runtime docs, and the `realm-routing-and-playable-state.md` and `player-access-and-session.md` trackers.",
        "Scripting/runtime reviews must treat `system-architecture-scripting-normative-contract-tables.md` as the first update target",
        "Observability reviews must check architecture docs, reference PromQL, dashboards, and the relevant capability-support docs under `slice-support/` when metric-label policy changes.",
        "## Capability/Tracker Completion Guide",
        "Verify the claimed capability outcome against every public contract it owns: HTTP/OpenAPI, gRPC/proto, event or outbox, and operator-facing contracts where applicable.",
        "Confirm that the named canonical owner in the tracker is the owner in code and that no local fallback or competing authority is silently carrying the behavior.",
        "Prefer narrow unit/integration/cross-service proof over interpreting an unrelated broad test pass as evidence.",
        "If any answer is no, leave the capability incomplete or complete only at its explicitly bounded current boundary.",
    ],
)
require_contains(
    "design/architecture/decisions/adr-0047-logging-admin-as-external-operator-write-ingress.md",
    [
        "`/moderation/actions` is unavailable/gated",
        "The accepted numeric source grammar is ASCII",
        "Game Session session lifecycle (`/sessions*`)",
    ],
)
require_contains(
    "design/architecture/microservices/logging-admin-service/api-contracts.md",
    [
        "`/moderation/actions` is an unavailable/gated human mutation",
        "Game instance session lifecycle remains a current Game Session owner-local route family",
    ],
)
require_contains(
    "design/architecture/system-architecture-authz-route-matrix.md",
    [
        "Operator mutation rows that require an Account-issued authorization reference",
        "`PaymentInstrumentWalletAccount`",
        "`PaymentInstrumentWalletCrossTenant`",
    ],
)
require_contains(
    "design/project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md",
    [
        "no focused consumer-level proof establishes that these values cannot authorize delegated work"
    ],
)
require_contains(
    "design/architecture/system-architecture-cicd.md",
    [
        "built and smoke-tested locally without registry credentials",
        "publish-pr-runtime-images.yml",
        "never checks out or executes PR source",
        "never writes shared cache or branch tags",
    ],
)
require_contains(
    "design/architecture/infrastructure/deployment-environments.md",
    [
        "builds and smoke-tests PR-tagged images without registry credentials",
        "trusted default-branch workflow publishes only the successful fixed head-SHA tags",
    ],
)
canonical_reset_anchor = "[Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence)"
for path in [
    "design/architecture/system-architecture-redis-reset-and-recovery.md",
    "design/architecture/system-architecture-redis-incident-runbook.md",
    "design/architecture/system-architecture-backup-recovery.md",
]:
    require_contains(path, [canonical_reset_anchor])

require_contains(
    "design/architecture/system-architecture-redis-reset-and-recovery.md",
    [
        "`session:auth:token:*` and `session:auth:generation:*`",
        "Region- and tenant-scoped resets preserve those records",
        "`session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`",
        "`session:game:index:character:{tenantGameplayTag}:<playableStateNamespaceId>:<characterId>`",
        "`{tenantId, playableStateNamespaceId, characterId}`",
        "`playableStateScope` binding/routing evidence",
        "Derived gameplay indexes are independent of that choice",
        "The one untagged global account index is an explicit exception",
        "Account-owned idempotent durable generation-mutation/repair operation is the fallback",
        "hot-path staging is prohibited until the canonical recovery release",
    ],
)
require_contains(
    "design/architecture/system-architecture-redis-ops-access.md",
    ["Region- and tenant-scoped coordination resets preserve Account-owned `session:auth:token:<tokenHash>` records"],
)
require_contains(
    "design/architecture/system-architecture-redis.md",
    [
        "The Account-issued handle binds the exact token hash, signed `jti` and `nbf`",
        "Account-validated handle is authoritative for the original signed `nbf`",
    ],
)
require_contains(
    "design/architecture/system-architecture-redis-reset-and-recovery.md",
    [
        "Every cluster-scoped reset requires explicit `--invalidate-sessions`",
        "Cluster scope rejects `--preserve-sessions`",
    ],
)
require_contains(
    "design/architecture/system-architecture-redis-incident-runbook.md",
    [
        "non-admissible provisional renewal",
        "cannot extend the deadline",
        "complete finite positive inventory of the affected canonical key families",
        "read back every expected affected key through those same builders/descriptors",
        "Validation and Runtime Proof",
        "direct execution results must be recorded in PR/CI evidence",
        "supplementary diagnostics",
    ],
)

require_contains(
    "design/architecture/system-architecture-logging-monitoring.md",
    [
        "canonical new-record emit-and-query check is conditional",
        "current fallback records capability `log-queryability-omitted` with result `not_applicable`",
        "for profiles that require indexed queryability, the applicable claim or gate remains closed",
        "any required evidence input is `unknown`, regardless of whether that profile otherwise permits unknown evidence",
        "`unknown` is an explicit non-SLI state",
    ],
)
require_contains(
    "design/architecture/decisions/adr-0160-staged-profile-aware-player-experience-slo-contract.md",
    [
        "any unknown required evidence input",
        "explicit `unknown`/non-SLI",
        "cannot turn an unknown required input into a healthy or breached SLI result",
    ],
)

operations_text = (root / "design/architecture/system-architecture-redis-operations.md").read_text(encoding="utf-8")

# Internal recovery classifications use enum-style values, but the durable
# operation/maintenance-lock compatibilityClass is a serialized contract and
# therefore uses the canonical token for each mode. Keep the boundary explicit
# so CLI/documentation edits cannot silently reintroduce an invalid value.
require_contains(
    "design/architecture/system-architecture-redis-operations.md",
    [
        "CLI `--mode replay-first` maps to internal classification `replay_first` and the serialized operation/maintenance-lock field `compatibilityClass=replay-first`",
        "CLI `--mode session-schema-cleanup` maps to internal and serialized `cleanup` (`compatibilityClass=cleanup`)",
    ],
)
require_contains(
    "design/architecture/system-architecture-redis-ops-access.md",
    [
        "`--mode replay-first` maps to internal classification `replay_first` and persists `compatibilityClass=replay-first`",
        "`--mode session-schema-cleanup` maps to internal and serialized `cleanup` (`compatibilityClass=cleanup`)",
    ],
)
require_contains(
    "design/architecture/system-architecture-redis-incident-runbook.md",
    [
        "`--mode replay-first` maps to internal classification `replay_first` and serialized `compatibilityClass=replay-first`",
        "serialized `compatibilityClass=replay-first` only after controller validation of the internal `replay_first` classification",
        "Escalation atomically upgrades the existing maintenance lock's serialized compatibility class from `compatibilityClass=replay-first` to `compatibilityClass=reset-first`",
    ],
)

serialized_replay_first_pattern = re.compile(
    r"(?<![A-Za-z0-9_.-])[`'\" ]*compatibilityClass[`'\" ]*\s*(?:[=:]|\bis\b)\s*"
    r"[`'\" ]*replay\s*_\s*first\b",
    re.IGNORECASE,
)
serialized_bare_reset_pattern = re.compile(
    r"(?<![A-Za-z0-9_.-])[`'\" ]*compatibilityClass[`'\" ]*\s*(?:[=:]|\bis\b)\s*"
    r"[`'\" ]*reset[`'\" ]*(?![-_A-Za-z0-9])",
    re.IGNORECASE,
)
serialized_reset_first_pattern = re.compile(
    r"(?<![A-Za-z0-9_.-])[`'\" ]*compatibilityClass[`'\" ]*\s*(?:[=:]|\bis\b)\s*"
    r"[`'\" ]*reset\s*_\s*first\b",
    re.IGNORECASE,
)
serialized_bare_reset_upgrade_pattern = re.compile(
    r"upgrades?\s+(?:the\s+)?[`'\" ]*(?:serialized\s+)?"
    r"(?:compatibilityClass|compatibility\s+class|class)[`'\" ]*\s+to\s+"
    r"[`'\" ]*reset[`'\" ]*(?![-_A-Za-z0-9])",
    re.IGNORECASE,
)
serialized_replay_first_fixture_cases = (
    "compatibilityClass=replay_first",
    "compatibilityClass = replay_first",
    'compatibilityClass: "replay_first"',
    '"compatibilityClass": "replay_first"',
    "'compatibilityClass' : 'replay_first'",
    "`compatibilityClass` is `replay_first`",
)
for fixture_text in serialized_replay_first_fixture_cases:
    if serialized_replay_first_pattern.search(fixture_text) is None:
        raise SystemExit(
            f"serialized replay_first fixture was not rejected: {fixture_text!r}"
        )
for fixture_text in (
    "compatibilityClass=replay-first",
    '"compatibilityClass": "replay-first"',
    "`compatibilityClass` is `replay-first`",
):
    if serialized_replay_first_pattern.search(fixture_text) is not None:
        raise SystemExit(
            f"canonical serialized replay-first token was incorrectly rejected: {fixture_text!r}"
        )
for fixture_text in (
    "myCompatibilityClass=replay_first",
    '"myCompatibilityClass": "replay_first"',
    "my-compatibilityClass=replay_first",
    '"my-compatibilityClass": "replay_first"',
    "config.compatibilityClass=replay_first",
    '"config.compatibilityClass": "replay_first"',
):
    if serialized_replay_first_pattern.search(fixture_text) is not None:
        raise SystemExit(
            f"embedded replay_first field was incorrectly recognized: {fixture_text!r}"
        )
for fixture_text in (
    "compatibilityClass=reset",
    '"compatibilityClass": "reset"',
    "`compatibilityClass` is `reset`",
):
    if serialized_bare_reset_pattern.search(fixture_text) is None:
        raise SystemExit(
            f"bare serialized reset compatibility class was not recognized: {fixture_text!r}"
        )
for fixture_text in (
    "compatibilityClass=reset-first",
    '"compatibilityClass": "reset-first"',
):
    if serialized_bare_reset_pattern.search(fixture_text) is not None:
        raise SystemExit(
            f"canonical serialized reset-first token was incorrectly rejected: {fixture_text!r}"
        )
for fixture_text in (
    "myCompatibilityClass=reset",
    '"myCompatibilityClass": "reset"',
    "my-compatibilityClass=reset",
    '"my-compatibilityClass": "reset"',
    "config.compatibilityClass=reset",
    '"config.compatibilityClass": "reset"',
):
    if serialized_bare_reset_pattern.search(fixture_text) is not None:
        raise SystemExit(
            f"embedded bare reset field was incorrectly recognized: {fixture_text!r}"
        )
for fixture_text in (
    "compatibilityClass=reset_first",
    "compatibilityClass = reset _ first",
    'compatibilityClass: "reset_first"',
    '"compatibilityClass": "reset_first"',
    "'compatibilityClass' : 'reset_first'",
    "`compatibilityClass` is `reset_first`",
):
    if serialized_reset_first_pattern.search(fixture_text) is None:
        raise SystemExit(
            f"serialized reset_first fixture was not rejected: {fixture_text!r}"
        )
for fixture_text in (
    "compatibilityClass=reset-first",
    '"compatibilityClass": "reset-first"',
    "`compatibilityClass` is `reset-first`",
):
    if serialized_reset_first_pattern.search(fixture_text) is not None:
        raise SystemExit(
            f"canonical serialized reset-first token was incorrectly rejected: {fixture_text!r}"
        )
for fixture_text in (
    "myCompatibilityClass=reset_first",
    '"myCompatibilityClass": "reset_first"',
    "my-compatibilityClass=reset_first",
    '"my-compatibilityClass": "reset_first"',
    "config.compatibilityClass=reset_first",
    '"config.compatibilityClass": "reset_first"',
):
    if serialized_reset_first_pattern.search(fixture_text) is not None:
        raise SystemExit(
            f"unrelated serialized field identifier was incorrectly rejected: {fixture_text!r}"
        )
for fixture_text in (
    "upgrade the class to `reset`",
    "upgrades the class to `reset`",
    "upgrade the compatibility class to reset",
    "upgrade the serialized compatibility class to `reset`",
    "upgrade `compatibilityClass` to `reset`",
):
    if serialized_bare_reset_upgrade_pattern.search(fixture_text) is None:
        raise SystemExit(
            f"bare reset upgrade fixture was not recognized: {fixture_text!r}"
        )
# A canonical reset-first upgrade must remain a negative fixture for the bare
# reset detector: its hyphenated serialized token is not the internal `reset`
# value that the detector is intended to reject.
for fixture_text in (
    "upgrade the serialized compatibility class to `reset-first`",
):
    if serialized_bare_reset_upgrade_pattern.search(fixture_text) is not None:
        raise SystemExit(
            f"canonical serialized reset-first upgrade was incorrectly rejected: {fixture_text!r}"
        )
for path in [
    "design/architecture/system-architecture-redis-operations.md",
    "design/architecture/system-architecture-redis-incident-runbook.md",
    "design/architecture/system-architecture-redis-ops-access.md",
]:
    serialized_text = (root / path).read_text(encoding="utf-8")
    if serialized_replay_first_pattern.search(serialized_text):
        raise SystemExit(
            f"{path}: replay_first must not be used as serialized compatibilityClass"
        )
    if serialized_bare_reset_pattern.search(serialized_text):
        raise SystemExit(
            f"{path}: bare reset must not be used as serialized compatibilityClass"
        )
    if serialized_reset_first_pattern.search(serialized_text):
        raise SystemExit(
            f"{path}: reset_first must not be used as serialized compatibilityClass"
        )
    if serialized_bare_reset_upgrade_pattern.search(serialized_text):
        raise SystemExit(
            f"{path}: bare reset upgrade must distinguish internal and serialized classes"
        )


def extract_unique_markdown_section(
    text, heading, source_label, include_fenced_content=True
):
    heading_pattern = re.compile(
        rf"^[ ]{{0,3}}##[ \t]+{re.escape(heading)}(?:[ \t]+#+)?[ \t]*(?:\r?\n)?$"
    )
    level_two_heading = re.compile(r"^[ ]{0,3}##(?=[ \t]|(?:\r?\n)?$)")
    sections = []
    current_section = None
    in_fenced_block = False
    fence_marker = None

    for line_number, line in iter_visible_markdown_lines(
        text,
        include_fenced_content=include_fenced_content,
        source_label=source_label,
    ):
        is_heading = not in_fenced_block and level_two_heading.match(line)
        if is_heading:
            if current_section is not None:
                sections.append("".join(current_section))
                current_section = None
            if heading_pattern.match(line):
                current_section = []
        elif current_section is not None:
            current_section.append(line)

        in_fenced_block, fence_marker, _ = advance_fenced_block_state(
            line,
            in_fenced_block,
            fence_marker,
            None,
            line_number,
        )

    if current_section is not None:
        sections.append("".join(current_section))
    if len(sections) != 1:
        raise SystemExit(
            f"{source_label}: expected exactly one {heading!r} section, found {len(sections)}"
        )
    return sections[0].replace(html_comment_boundary, "")


def require_section_contains_text(
    text, heading, snippets, source_label, include_fenced_content=True
):
    section = extract_unique_markdown_section(
        text, heading, source_label, include_fenced_content=include_fenced_content
    )
    missing = [snippet for snippet in snippets if snippet not in section]
    if missing:
        raise SystemExit(
            f"{source_label}: {heading!r} section missing required snippets: {missing}"
        )


def require_section_contains(path, heading, snippets):
    text = (root / path).read_text(encoding="utf-8")
    require_section_contains_text(
        text,
        heading,
        snippets,
        path,
        include_fenced_content=False,
    )


fenced_heading_fixture = (
    "## Canonical Coordination Reset Sequence\n"
    "before\n"
    "```text\n"
    "## Not a real section heading\n"
    "inside the example\n"
    "```\n"
    "after\n"
    "## Following section\n"
)
fenced_heading_section = extract_unique_markdown_section(
    fenced_heading_fixture,
    "Canonical Coordination Reset Sequence",
    "fenced heading fixture",
)
if "## Not a real section heading" not in fenced_heading_section or "after\n" not in fenced_heading_section:
    raise SystemExit("fenced heading fixture was incorrectly treated as a section boundary")

indented_heading_fixture = (
    "  ## Indented heading fixture\n"
    "before\n"
    "   ## Indented boundary\n"
    "after\n"
)
indented_heading_section = extract_unique_markdown_section(
    indented_heading_fixture,
    "Indented heading fixture",
    "indented heading fixture",
)
if indented_heading_section != "before\n":
    raise SystemExit("indented ATX heading boundary was not recognized")

tab_separated_heading_fixture = (
    "## Tab-separated heading fixture\n"
    "before\n"
    "##\tTab-separated boundary\n"
    "after\n"
)
tab_separated_heading_section = extract_unique_markdown_section(
    tab_separated_heading_fixture,
    "Tab-separated heading fixture",
    "tab-separated heading fixture",
)
if tab_separated_heading_section != "before\n":
    raise SystemExit("tab-separated ATX heading boundary was not recognized")

end_after_level_two_fixture = "## End-after-markers fixture\nbefore\n##\nafter\n"
end_after_level_two_section = extract_unique_markdown_section(
    end_after_level_two_fixture,
    "End-after-markers fixture",
    "end-after-level-two fixture",
)
if end_after_level_two_section != "before\n":
    raise SystemExit("ATX heading ending immediately after ## was not recognized")

closing_marker_heading_fixture = (
    "## Canonical Coordination Reset Sequence ##\n"
    "before\n"
    "## Following section\n"
    "after\n"
)
closing_marker_heading_section = extract_unique_markdown_section(
    closing_marker_heading_fixture,
    "Canonical Coordination Reset Sequence",
    "closing marker heading fixture",
)
if closing_marker_heading_section != "before\n":
    raise SystemExit("ATX closing heading markers were not recognized")

inline_comment_section_heading_fixture = (
    "## Inline comment section fixture <!-- inline comment -->\n"
    "before\n"
    "## Following section\n"
    "after\n"
)
inline_comment_section = extract_unique_markdown_section(
    inline_comment_section_heading_fixture,
    "Inline comment section fixture",
    "inline comment section heading fixture",
)
if inline_comment_section != "before\n":
    raise SystemExit("inline HTML comment invalidated a valid section heading")

hidden_required_snippet_fixture = (
    "## Hidden required snippet fixture\n"
    "<!-- hidden comment snippet -->\n"
    "<div>\n"
    "hidden raw HTML snippet\n"
    "</div>\n"
)
try:
    require_section_contains_text(
        hidden_required_snippet_fixture,
        "Hidden required snippet fixture",
        ["hidden comment snippet", "hidden raw HTML snippet"],
        "hidden required snippet fixture",
    )
except SystemExit as error:
    if "missing required snippets" not in str(error):
        raise SystemExit(f"unexpected hidden snippet fixture diagnostic: {error}")
else:
    raise SystemExit("hidden required snippet fixture was incorrectly accepted")

hidden_heading_fixture = (
    "## Hidden heading fixture\n"
    "before\n"
    "<!--\n"
    "## Hidden comment heading\n"
    "-->\n"
    "after\n"
    "## Following section\n"
)
hidden_heading_section = extract_unique_markdown_section(
    hidden_heading_fixture,
    "Hidden heading fixture",
    "hidden heading fixture",
)
if "## Hidden comment heading" in hidden_heading_section or "after\n" not in hidden_heading_section:
    raise SystemExit("hidden heading fixture was not removed before section extraction")

type7_raw_html_heading_fixture = (
    "## Type-7 raw HTML section fixture\n"
    "before\n"
    "</span>\n"
    "## Hidden section inside closing span\n"
    "hidden\n"
    "\n"
    "after\n"
    "## Following section\n"
)
type7_raw_html_heading_section = extract_unique_markdown_section(
    type7_raw_html_heading_fixture,
    "Type-7 raw HTML section fixture",
    "type-7 raw HTML heading fixture",
)
if (
    "## Hidden section inside closing span" in type7_raw_html_heading_section
    or "after\n" not in type7_raw_html_heading_section
):
    raise SystemExit("type-7 raw HTML heading was not removed before section extraction")

unterminated_fenced_section_fixture = (
    "## Unterminated fenced section fixture\n"
    "before\n"
    "\x60\x60\x60text\n"
    "## Clause inside the unterminated fence\n"
    "must not be accepted as section content\n"
)
try:
    extract_unique_markdown_section(
        unterminated_fenced_section_fixture,
        "Unterminated fenced section fixture",
        "unterminated fenced section fixture",
    )
except SystemExit as error:
    if str(error) != "unterminated fenced section fixture: unterminated fenced example opened at line 3":
        raise SystemExit(f"unexpected unterminated section diagnostic: {error}")
else:
    raise SystemExit(
        "section extraction accepted a clause inside an unterminated fenced block"
    )

ordinary_visible_content_fixture = (
    "## Ordinary visible content fixture\n"
    "ordinary visible text <!-- hidden comment --> trailing visible text\n"
    "## Following section\n"
)
ordinary_visible_content = extract_unique_markdown_section(
    ordinary_visible_content_fixture,
    "Ordinary visible content fixture",
    "ordinary visible content fixture",
)
if (
    "ordinary visible text" not in ordinary_visible_content
    or "trailing visible text" not in ordinary_visible_content
):
    raise SystemExit("ordinary visible text was lost around an HTML comment")

synthetic_heading_section_fixture = (
    "## Synthetic heading section fixture\n"
    "before\n"
    "<!-- hidden prefix -->## Hidden boundary\n"
    "hidden content\n"
    "## Following section\n"
)
synthetic_heading_section = extract_unique_markdown_section(
    synthetic_heading_section_fixture,
    "Synthetic heading section fixture",
    "synthetic heading section fixture",
)
if synthetic_heading_section != "before\n## Hidden boundary\nhidden content\n":
    raise SystemExit(
        "synthetic heading detection did not preserve the HTML comment boundary"
    )

unclosed_comment_in_type6_section_fixture = (
    "<div>\n"
    "<!-- comment remains unclosed inside the raw block\n"
    "## Hidden section inside raw HTML\n"
    "hidden\n"
    "\n"
    "## Canonical Coordination Reset Sequence\n"
    "visible after the raw block\n"
    "## Following section\n"
)
unclosed_comment_in_type6_section = extract_unique_markdown_section(
    unclosed_comment_in_type6_section_fixture,
    "Canonical Coordination Reset Sequence",
    "unclosed comment in Type-6 section fixture",
)
if unclosed_comment_in_type6_section != "visible after the raw block\n":
    raise SystemExit("unclosed comment inside Type-6 HTML hid the following section")

canonical_reset_text = extract_unique_markdown_section(
    operations_text,
    "Canonical Coordination Reset Sequence",
    "design/architecture/system-architecture-redis-operations.md",
)
required_reset_contract = [
    "Canonical public operation:",
    "`coordination-maintenance recover --mode reset --scope ... <session-policy-option>`",
    "1. internal pause-and-lock phase",
    "2. internal epoch-bump and scope-safe coordination-reset phase",
    "3. internal ledger-reconciliation phase",
    "4. internal command-convergence phase",
    "5. internal protected-domain cutover-fencing phase",
    "6. external AOF/deployment reset handoff",
    "7. internal metadata-initialization phase",
    "8. internal Account authority and issued-token projection-rebuild phase",
    "9. internal session-policy phase",
    "10. internal post-reset smoke-check phase",
    "11. `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`",
    "12. public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`",
    "13. internal resume-and-success-release phase",
]
cursor = 0
previous = "<start of canonical reset contract>"


def find_clause_matches(text, clause):
    pattern = re.compile(
        rf"(?m)^[ \t]*{re.escape(clause)}(?=[ \t,.;:)]|$).*$"
    )
    return list(pattern.finditer(text))


for clause in required_reset_contract:
    matches = find_clause_matches(canonical_reset_text, clause)
    if not matches:
        raise SystemExit(
            "design/architecture/system-architecture-redis-operations.md: canonical reset contract missing: "
            f"[{clause!r}] after {previous!r}"
        )
    if len(matches) != 1:
        raise SystemExit(
            "design/architecture/system-architecture-redis-operations.md: canonical reset contract clause must match exactly once: "
            f"[{clause!r}], found {len(matches)}"
        )
    match = matches[0]
    if match.start() < cursor:
        raise SystemExit(
            "design/architecture/system-architecture-redis-operations.md: canonical reset contract out of order: "
            f"expected {clause!r} after {previous!r}"
        )
    cursor = match.end()
    previous = clause

for clause in [
    "not a public command",
    "never runs automatically",
    "durable control store outside the target Redis deployment",
]:
    if clause not in canonical_reset_text:
        raise SystemExit(
            "design/architecture/system-architecture-redis-operations.md: "
            f"canonical reset contract missing: [{clause!r}]"
        )

canonical_public_resume_signature = "`resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`"
canonical_public_resume_awaiting_signature = "`resume(operationId, expectedPhase=awaiting_resume, maintenanceLockToken, evidenceRef)`"

for path in [
    "design/architecture/decisions/adr-0015-online-backup-and-environment-wide-cold-start-recovery.md",
    "design/architecture/system-architecture-backup-recovery.md",
    "design/architecture/system-architecture-redis-operations.md",
]:
    require_contains(path, [canonical_public_resume_signature])

require_section_contains(
    "design/architecture/system-architecture-backup-recovery.md",
    "Recovery Controller Continuation",
    [
        canonical_public_resume_signature,
        "The public phase-continuation verb is `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`.",
    ],
)
require_section_contains(
    "design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md",
    "Canonical Recovery Record",
    [
        "[Backup & Disaster Recovery](./system-architecture-backup-recovery.md)",
        "The operation-bound `evidenceRef` supplied through the recovery owner's canonical continuation path",
    ],
)
require_section_contains(
    "design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md",
    "Production Traffic-Open Backup Evidence",
    [
        "`trafficOpenStatus` (`finalized` in the checked-in projection",
        "must not accept a transient traffic-open file as authority",
        "`operationId`, `eventType`, `deploymentEventId`, `preflightReportPath`, `actualRecoveryRecordRef`, `playerFacingTargetBoundary`, and `trafficExposure` must exact-match",
    ],
)
require_section_contains(
    "design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md",
    "Hobby Traffic-Open Evidence",
    [
        "`trafficOpenStatus` (`finalized` in the checked-in projection",
        "`backupComplianceRecordVersion`",
        "`backupComplianceRecordDigest`",
        "must not dereference a mutable current `backup-compliance.yaml` alias",
        "it does not perform or authorize controller continuation or release",
        "cannot be reused for a later event",
    ],
)
require_section_contains(
    "design/architecture/system-architecture-redis-reset-and-recovery.md",
    "Coordination Reset Model",
    [
        "[Backup & Disaster Recovery](./system-architecture-backup-recovery.md#recovery-controller-continuation)",
        "Both gates remain required, operation-bound, and fail closed when incomplete or ambiguous.",
        "Any smoke tick exercised by the reset workflow is synthetic maintenance traffic only.",
        "must not authorize player ingress or real `tickId=0` admission",
        "Reset-local release consequences remain fail-closed",
        "The traffic fence remains active until the recovery owner's canonical lifecycle has complete apply-and-readback evidence",
        "This document does not define the controller's phases or continuation calls.",
    ],
)

for path in (
    "design/operations/deployments/hobby-self-hosted/recovery/README.md",
    "design/operations/deployments/production/recovery/README.md",
    "design/operations/deployments/staging/recovery/README.md",
):
    require_contains(path, [canonical_public_resume_signature])
for path in (
    "design/operations/deployments/production/backup-readiness/README.md",
    "design/operations/deployments/production/traffic-open/README.md",
):
    require_contains(path, [canonical_public_resume_awaiting_signature])

require_contains(
    "design/architecture/system-architecture-redis-ops-access.md",
    [
        "`continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`",
        "A phase failure retains the lock and paused fence.",
        "abandonment does not authorize resume",
    ],
)

require_contains(
    "design/architecture/system-architecture-scripting-contracts.md",
    [
        "always-isolated test-only breaker or gate",
        "no environment, tenant, or request opt-in may cross that boundary",
    ],
)
require_absent(
    "design/architecture/system-architecture-scripting-contracts.md",
    ["separate breaker or explicitly opt-in per environment/tenant"],
)
require_contains(
    "design/architecture/system-architecture-scripting-quotas-and-operations.md",
    [
        "always-isolated test-only breaker or gate",
        "no environment, tenant, or request opt-in may cross that boundary",
    ],
)
require_absent(
    "design/architecture/system-architecture-scripting-quotas-and-operations.md",
    ["separate breaker or explicit opt-in"],
)
require_contains(
    "design/architecture/decisions/adr-0166-attributable-script-breakers-and-tenant-first-fairness.md",
    [
        "For a core script, the Automation & Scripting Service owns the authoritative persisted `breakerState` aggregate",
        "A legacy/read-model `runtimeStatus=DISABLED_DUE_TO_ERRORS`, when exposed, is only a read-only effective-admission projection",
        "it is not persisted breaker authority and does not own transition history or reset",
    ],
)
require_contains(
    "design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md",
    [
        "with mandatory dry-run/test isolation",
        "live-only boundary",
        "ScriptDryRunCapacityService",
        "never invokes `ScriptTenantBudgetService`",
    ],
)
require_absent(
    "design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md",
    [
        "dry-run/test isolation by default",
        "even for dry-run/test work",
    ],
)
require_contains(
    "design/architecture/system-architecture-scripting-normative-contract-tables.md",
    [
        "live-only `ScriptTenantBudgetService` denied after executor claim",
        "This row is live-only (`isDryRun=false`)",
        "materialized dry-run/test capacity denied",
        "ScriptDryRunCapacityService",
        "event-level catch-up candidate ceiling route",
    ],
)
normative_contracts = (
    root / "design/architecture/system-architecture-scripting-normative-contract-tables.md"
).read_text(encoding="utf-8")
dsl_eval_rows = [
    line.strip()
    for line in normative_contracts.splitlines()
    if line.strip().startswith(
        "| `DSL_EVAL` | DSL graph evaluation and sandbox enforcement"
    )
]
if len(dsl_eval_rows) != 1:
    raise SystemExit(
        "system-architecture-scripting-normative-contract-tables.md: expected exactly one DSL_EVAL stage row"
    )
for required_clause in (
    "A resolved handler uses `completed_no_commands` when it validly emits no commands, or its applicable handler failure outcome",
    "`readiness_success` is reserved for the tenant-readiness patch-level owner/projection and is never a resolved-handler `finalOutcome`",
    "`dry_run_completed` is a legacy-only outcome, permitted only when `executionSurface=LEGACY_TRIGGER_DRY_RUN`",
):
    if required_clause not in dsl_eval_rows[0]:
        raise SystemExit(
            "system-architecture-scripting-normative-contract-tables.md: DSL_EVAL outcome contract drifted"
        )
if "DRY_RUN_RESULT" in dsl_eval_rows[0] or "dry_run_success" in dsl_eval_rows[0]:
    raise SystemExit(
        "system-architecture-scripting-normative-contract-tables.md: ADR 0114 preview outcome leaked into DSL_EVAL"
    )
catch_up_rows = [
    line.strip()
    for line in normative_contracts.splitlines()
    if line.strip().startswith("| Recurring/durable-recurring leader failover or short downtime |")
]
if len(catch_up_rows) != 1:
    raise SystemExit(
        "system-architecture-scripting-normative-contract-tables.md: expected exactly one catch-up candidate row"
    )
for required_clause in (
    "this candidate-level route does not invoke `ScriptTenantBudgetService`",
    "For `isDryRun=false`, a tenant-ceiling denial records candidate-audit",
    "increments `automation_script_skips_total` once with `scope=\"tenant\"` and `reason=\"tenant_budget_exceeded\"`",
    "A cluster-ceiling denial records candidate-audit",
    "increments `automation_script_skips_total` once with `scope=\"cluster\"` and `reason=\"cluster_limit_reached\"`",
    "For `isDryRun=true`, neither live-only family is emitted",
    "settled candidate audit and mode-specific resume-window outcome are the complete observability surface",
    "handler-scoped test families are not used because the denial occurs before handler materialization",
):
    if required_clause not in catch_up_rows[0]:
        raise SystemExit(
            "system-architecture-scripting-normative-contract-tables.md: catch-up candidate route drifted"
        )
if "`automation_script_triggers_dropped_total`" in catch_up_rows[0]:
    raise SystemExit(
        "system-architecture-scripting-normative-contract-tables.md: catch-up cluster denial uses ingress-drop metric"
    )
require_section_contains(
    "design/architecture/system-architecture-scripting-control-plane-api.md",
    "Control Plane APIs (Normative)",
    [
        "bounded immutable `lastResetReason`",
        "bounded immutable `resetReason`",
        "`lastResetValidationEvidence`",
        "bounded `validationEvidence`",
        "`resetReason` is absent from lifecycle and trip rows",
        "persisted non-identity `playableStateNamespaceId` evidence",
        "`currentRuntimePlayableStateNamespaceId`",
        "The normalized exact scope is `(tenantId, gameInstanceId, regionId)`, where an omitted or blank `regionId` selects the exact empty/unscoped row and never a wildcard",
        "Normalization is deterministic and applied once at the API boundary",
        "The bounded outcome vocabulary is `APPLIED` (the mode changed) or `ALREADY_APPLIED`",
        "only those outcomes are successful acknowledgements",
        "`statePresent=false`, `admissionMode=NORMAL`, `admissionEpoch=0`",
        "never aggregates or matches regional rows",
    ],
)
require_contains(
    "design/architecture/system-architecture-scripting-control-plane-operations.md",
    [
        "complete affected scope set from the authoritative durable PostgreSQL/runtime owner inventory",
        "omitted or blank `regionId` selects only the durable empty/unscoped row and never aggregates regional rows",
        "durable successful Set acknowledgement (`APPLIED` or `ALREADY_APPLIED`)",
    ],
)
require_contains(
    "design/architecture/system-architecture-scripting-rollout-and-rollback.md",
    [
        "complete affected scope set from the authoritative durable PostgreSQL/runtime inventory",
        "Each Set must return and durably persist a successful `APPLIED` or `ALREADY_APPLIED` request-result acknowledgment",
        "for every exact enumerated scope",
    ],
)
require_contains(
    "design/architecture/system-architecture-tick-incident-runbook.md",
    [
        "may be applied as an initial emergency fence",
        "For any reset or recovery mutation, Automation must be contained before relying on Game Session tick/region containment",
        "complete affected scope set from the authoritative durable PostgreSQL/runtime inventory",
        "live per-scope `SetAutomationAdmissionMode`/`GetAutomationDrainStatus` surfaces are not a recovery authorization",
        "do not yet provide a durable request-result acknowledgement or matching readback identity",
        "deployment-wide Automation containment only with explicit impact approval",
        "durable request-result/fingerprint/acknowledgement readback before recovery proceeds",
    ],
)
require_contains(
    "design/operations/deployments/production/recovery/README.md",
    [
        "not a current recovery authorization",
        "does not yet provide a durable request-result acknowledgement",
        "deployment-wide Automation containment only with explicit impact approval",
        "complete affected-scope enumeration from the durable PostgreSQL/runtime inventory",
        "distinct durable deployment/owner acknowledgement plus authoritative readback",
    ],
)
require_absent(
    "design/architecture/system-architecture-scripting-control-plane-api.md",
    ["`lastResetEvidence`", "`resetEvidence`"],
)
require_contains(
    "design/architecture/microservices/automation-scripting-service/operations.md",
    ["inspect `automation_script_queue_delay_seconds` by bounded priority"],
)
require_contains(
    "design/architecture/system-architecture-scripting-operations-cookbook.md",
    [
        "`automation_script_test_runs_total{result=\"quota_denied\"}`",
        "`automation_script_test_capacity_denied_total{scope=~\"tenant|cluster\"}`",
        "never increments the live `automation_script_triggers_total` family",
    ],
)
require_contains(
    "design/architecture/system-architecture-scripting-normative-contract-tables.md",
    [
        "For the current legacy materialized dry-run handler only, `finalOutcome=dry_run_completed` with `executionSurface=LEGACY_TRIGGER_DRY_RUN` maps to metric `result=dry_run_success`",
        "For every classified non-success Table 2 outcome, metric `result` uses that same canonical outcome value",
    ],
)
require_absent(
    "design/architecture/system-architecture-scripting-operations-cookbook.md",
    ["priority_throttled"],
)
require_contains(
    "design/architecture/system-architecture-scripting-scheduler-and-timers.md",
    [
        "resume-window record per `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, isDryRun>`",
        "its `resumeWindowId` is `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, isDryRun, resumeGeneration>`",
        "retains the authoritative `playableStateNamespaceId` as immutable non-identity scope evidence",
        "missing or mismatched evidence fails closed without changing any canonical identity tuple",
    ],
)
require_contains(
    "design/architecture/system-architecture-scripting-dsl-for-designers.md",
    [
        "`resumeWindowId` identified by `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, isDryRun, resumeGeneration>`",
        "one-tenant, one-mode ID",
    ],
)
require_absent(
    "design/architecture/system-architecture-scripting-dsl-for-designers.md",
    [
        "`resumeWindowId` identified by `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, resumeGeneration>`",
    ],
)
require_contains(
    "design/architecture/decisions/adr-0072-class-specific-timer-durability-and-recovery.md",
    [
        "resume-window record per runtime scope, epoch, and `isDryRun` mode",
        "`resumeWindowId` is the exact tuple `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, isDryRun, resumeGeneration>`",
        "each prior epoch's `OPEN` resume window, independently for each `isDryRun` mode",
    ],
)
require_contains(
    "design/architecture/system-architecture-scripting-normative-contract-tables.md",
    [
        "resume window identified by `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, isDryRun, resumeGeneration>`",
        "Durable compare-and-set creates or reuses one `OPEN` window per runtime scope, epoch, and `isDryRun` mode",
    ],
)
require_contains(
    "design/architecture/decisions/adr-0001-scripting-event-ingress-idempotency-identity.md",
    [
        "The canonical scheduler preimage fields are serialized in this fixed order:",
        "`resumeWindowId` is absent for non-catch-up triggers",
        "Fields marked `when ...` are omitted, not replaced by empty or sentinel values",
    ],
)
adr0001 = (
    root
    / "design/architecture/decisions/adr-0001-scripting-event-ingress-idempotency-identity.md"
).read_text(encoding="utf-8")
preimage_matches = re.findall(
    r"The canonical scheduler preimage fields are serialized in this fixed order: `([^`]+)`",
    adr0001,
)
if len(preimage_matches) != 1:
    raise SystemExit(
        "adr-0001-scripting-event-ingress-idempotency-identity.md: expected exactly one canonical scheduler preimage"
    )
expected_scheduler_preimage = [
    "tenantId",
    "gameInstanceId",
    "playableStateScope",
    "stableOwnerKind",
    "stableOwnerId",
    "regionId",
    "regionEpoch",
    "entityId when targetScopeType=ENTITY",
    "scriptId when core-owned",
    "eventType",
    "eventSchemaVersion",
    "scriptPatchVersion",
    "scriptPinEpoch",
    "scheduleDefinitionId",
    "targetScopeType",
    "targetScopeId",
    "duePoint",
    "isDryRun",
    "triggerMode",
    "pluginId when plugin-owned",
    "pluginVersionId when plugin-owned",
    "bindingId when plugin-owned",
    "pluginActivationEpoch when plugin-owned",
    "resumeWindowId when triggerMode=CATCH_UP",
]
actual_scheduler_preimage = [
    field.strip()
    for field in preimage_matches[0].strip("<>").split(",")
]
if actual_scheduler_preimage != expected_scheduler_preimage:
    raise SystemExit(
        "adr-0001-scripting-event-ingress-idempotency-identity.md: canonical scheduler preimage fields/order drifted"
    )
specialized_inventory = (
    root / "design/project-management/design-alignment/decision-inventory-specialized-runtime.md"
).read_text(encoding="utf-8")
adr0017_reference = re.compile(r"\badr(?:[ \t-]+)?0017(?=\b|-)", re.IGNORECASE)
for adr0017_fixture in (
    "ADR 0017",
    "[ADR-0017](../../architecture/decisions/adr-0017-capability-gated-operational-tracing.md)",
    "../../architecture/decisions/adr-0017-capability-gated-operational-tracing.md",
):
    if adr0017_reference.search(adr0017_fixture) is None:
        raise SystemExit(
            "decision-inventory-specialized-runtime.md: ADR 0017 fixture was not recognized"
        )
trace03_rows = [
    line for line in specialized_inventory.splitlines() if line.startswith("| `TRACE-03` |")
]
if len(trace03_rows) != 1:
    raise SystemExit(
        "decision-inventory-specialized-runtime.md: expected exactly one TRACE-03 row"
    )
if adr0017_reference.search(trace03_rows[0]) is not None:
    raise SystemExit(
        "decision-inventory-specialized-runtime.md: TRACE-03 must not inherit ADR 0017 provenance"
    )
require_contains(
    "design/architecture/system-architecture-scripting.md",
    ["Terminal completed_no_commands"],
)
require_absent(
    "design/architecture/system-architecture-scripting.md",
    ["Terminal no_commands_emitted"],
)
require_contains(
    "design/architecture/decisions/adr-0064-stage-qualified-script-outcomes.md",
    [
        "the live no-command terminal path now records the canonical `completed_no_commands` outcome with focused proof",
        "The live handoff path still needs convergence to `handoff_accepted`",
    ],
)
require_absent(
    "design/architecture/decisions/adr-0064-stage-qualified-script-outcomes.md",
    ["the live taxonomy still needs convergence to `handoff_accepted`/`completed_no_commands`"],
)
require_contains(
    "design/architecture/system-architecture-scripting-normative-contract-tables.md",
    [
        "| `automation_script_queue_delay_seconds` | `service`, `scope`, `script_kind`, `priority` |",
        "canonical priority-starvation signal",
    ],
)
require_contains(
    "design/project-management/implementation-tracking/automation-and-scheduler-runtime.md",
    ["priority-sensitive queue-delay/starvation emission"],
)
require_contains(
    "design/architecture/system-architecture-tick-failures-and-operations.md",
    [
        "bounded batches per complete recovery scope `<tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch>`",
        "fairness cursors and deficit/cost accounting are keyed by the complete recovery scope",
        "service startup for each complete recovery scope",
    ],
)
require_absent(
    "design/architecture/system-architecture-tick-failures-and-operations.md",
    [
        "bounded batches per `<tenantId, gameInstanceId, regionId>`",
        "scheduling across regions rather than draining one region completely",
        "service startup for each region to converge",
    ],
)

print("architecture doc contracts passed")
PY

python3 dev-tools/validation/check-design-capability-allocation.py
python3 dev-tools/validation/test_design_capability_allocation.py
python3 dev-tools/validation/check-adr-review-status.py
python3 dev-tools/validation/test_adr_review_status.py
python3 dev-tools/validation/check-implementation-capability-tracking.py
python3 dev-tools/validation/test_implementation_capability_tracking.py
python3 dev-tools/observability/check-metrics-cardinality.py
python3 dev-tools/validation/test_check_metrics_cardinality.py
python3 dev-tools/validation/check-authz-route-matrix.py
python3 dev-tools/validation/test_check_authz_route_matrix.py
