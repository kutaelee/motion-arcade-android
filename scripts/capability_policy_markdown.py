"""Defense-in-depth prose checks for capability-policy candidate boundaries.

This module intentionally does not decide whether a candidate is authorized.  The
structured authority manifest remains the machine stop gate.  The routines below
normalize a bounded CommonMark subset and report prose that appears to contradict
that gate, so callers can fail closed and ask for review.
"""

from __future__ import annotations

from dataclasses import dataclass
import html
import re
import unicodedata


@dataclass(frozen=True, slots=True)
class ProseFinding:
    """One defense-in-depth candidate-boundary prose finding."""

    line_start: int
    line_end: int
    category: str
    excerpt: str


@dataclass(frozen=True, slots=True)
class _LogicalBlock:
    line_start: int
    line_end: int
    source: str


_FENCE_OPEN = re.compile(r"^ {0,3}(?P<run>`{3,}|~{3,})(?P<info>.*)$")
_LIST_ITEM = re.compile(r"^ {0,3}(?:(?:[-+*])|(?:\d{1,9}[.)]))[ \t]+(.*)$")
_ATX_HEADING = re.compile(r"^ {0,3}#{1,6}(?:[ \t]+|$)(.*)$")
_TABLE_DIVIDER_CELL = re.compile(r"^:?-{1,}:?$")
_HORIZONTAL_RULE = re.compile(
    r"^ {0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$"
)
_LINK_REFERENCE_DEFINITION = re.compile(
    r"^ {0,3}\[(?:\\.|[^\[\]])+\]:[ \t]*"
    r"(?:<[^>\r\n]+>|(?:\\.|[^\s<>()])+)(?:[ \t]+"
    r"(?:\"[^\"\r\n]*\"|'[^'\r\n]*'|\([^()\r\n]*\)))?[ \t]*$"
)
_HYPHENS = str.maketrans(
    {
        "\u2010": "-",
        "\u2011": "-",
        "\u2012": "-",
        "\u2013": "-",
        "\u2014": "-",
        "\u2015": "-",
        "\u2212": "-",
        "\ufe58": "-",
        "\ufe63": "-",
        "\uff0d": "-",
    }
)

_EXPLICIT_NONNORMATIVE = re.compile(
    r"(?ix)^\s*(?:"
    r"histor(?:y|ical(?:\s+(?:record|note|claim|claims|text|example|examples))?)|"
    r"rejected(?:\s+(?:history|revision|revisions|proposal|claim|claims|text|"
    r"prose|counterexample|counterexamples|counterexample\s+fixtures?))?|"
    r"counterexample(?:s|\s+fixtures?)?|"
    r"(?:invalid|forbidden|non[- ]normative)\s+"
    r"(?:claim|claims|text|example|examples|fixture|fixtures)|"
    r"quoted\s+counterexample"
    r")\b"
)
_EXPLICIT_REJECTED_OBJECT = re.compile(
    r"(?is)^\s*(?:the\s+)?(?:claim|statement|sentence|text|example|proposal)\b"
    r".*\b(?:is|was|remains?)\s+"
    r"(?:rejected|invalid|forbidden|non[- ]normative)\b"
)
_META_DIRECTIVE = re.compile(
    r"(?ix)(?:"
    r"\bnever\s+(?:write|assert|say|claim|state|record|report|set|describe)|"
    r"\b(?:do|does|did|must|should|shall|may)\s+not\s+"
    r"(?:write|assert|say|claim|state|record|report|set|describe)|"
    r"\bdon['\u2019]t\s+(?:write|assert|say|claim|state|record|report|set|describe)"
    r")\b[^;.!?]*$"
)
_CONDITIONAL_PREFIX = re.compile(
    r"(?ix)^\s*(?P<kind>before|until|unless|if|whether|after|once|when|provided)\b"
    r"(?P<body>[^,]*)"
)
_CONDITIONAL_SUFFIX = re.compile(
    r"(?ix)^\s*,?\s*(?:(?:only\s+)?(?:after|if|once|unless|until|when|whether)\b|"
    r"provided\s+that\b|as\s+long\s+as\b|subject\s+to\b)"
)
_CURRENT_FALSE_CONDITION = re.compile(
    r"(?ix)\b(?:implementation[\s_-]*authorized\s*(?:is|:|=)\s*false|"
    r"implementation\s+is\s+not\s+authorized)\b"
)
_PAST_TRIGGER = re.compile(
    r"(?ix)\b(?:passed|failed|landed|changed|completed|finished|was|were|had)\b"
)
_DISCOURSE_PREFIX = re.compile(
    r"(?ix)^\s*(?:after\s+all|if\s+anything|once\s+again)\s*,"
)
_LOCAL_NEGATION = re.compile(
    r"(?ix)(?:"
    r"\b(?:no|not|never|cannot|can['\u2019]?t|isn['\u2019]?t|aren['\u2019]?t|"
    r"wasn['\u2019]?t|weren['\u2019]?t|won['\u2019]?t|wouldn['\u2019]?t|"
    r"shouldn['\u2019]?t|mustn['\u2019]?t|may\s+not|must\s+not|should\s+not|"
    r"will\s+not|would\s+not|do(?:es|ne)?\s+not|did\s+not)\b"
    r"(?:\s+(?:ever|currently|now|yet|first|explicitly|actually|formally|be|being)){0,5}|"
    r"\bwithout\b(?:\s+(?:ever|first|being|be|explicitly|actually|formally)){0,5}|"
    r"\b(?:blocked|prevented)\s+from(?:\s+being)?"
    r")\s*$"
)

_STRUCTURED_TRUE = (
    re.compile(
        r"(?ix)\bimplementation[\s_-]*authorized\b[\"']?\s*"
        r"(?:is|:|=)\s*[\"']?true\b"
    ),
    re.compile(r"(?ix)\bimplementation\s+authorization\s+(?:is|:|=)\s+true\b"),
)

_AFFIRMATIVE = r"(?:authorized|approved|accepted|granted|permitted|allowed|cleared)"
_APPROVAL_OBJECT = (
    r"(?:authorization|approval|acceptance|permission|clearance|"
    r"(?:the\s+)?green[ -]?light|(?:a\s+|the\s+)?go[ -]?ahead)"
)
_START = r"(?:start|begin|proceed|move(?:\s+|-)forward|commence)"
_SLICE = r"slice[\s_-]*1[\s_-]*b"
_ACTOR = (
    r"(?:we|i|the\s+team|our\s+team|developers?|engineers?|maintainers?|"
    r"contributors?|the\s+candidate|this\s+candidate)"
)
_AUTHORIZATION = (
    re.compile(
        rf"(?ix)\bimplementation(?:\s+authorization)?\s+"
        rf"(?:is|has\s+been)\s+(?:(?:now|hereby|explicitly|formally)\s+)*"
        rf"{_AFFIRMATIVE}\b"
    ),
    re.compile(
        rf"(?ix)\bauthorization\s+for\s+(?:{_SLICE}\s+)?implementation\s+"
        rf"(?:is|has\s+been)\s+(?:(?:now|hereby|explicitly|formally)\s+)*"
        rf"{_AFFIRMATIVE}\b"
    ),
    re.compile(
        rf"(?ix)\b{_SLICE}(?:\s+implementation)?\s+"
        rf"(?:is|has\s+been)\s+(?:(?:now|hereby|explicitly|formally)\s+)*"
        rf"{_AFFIRMATIVE}\b"
    ),
    re.compile(rf"(?ix)\b{_AFFIRMATIVE}\s+for\s+(?:{_SLICE}\s+)?implementation\b"),
    re.compile(
        rf"(?ix)\bimplementation\s+(?:has|has\s+received|received|gets?|got)\s+"
        rf"{_APPROVAL_OBJECT}\b"
    ),
    re.compile(
        rf"(?ix)\b{_ACTOR}\s+(?:am|is|are|has\s+been|have\s+been)\s+"
        rf"(?:(?:now|hereby|explicitly|formally)\s+)*"
        rf"(?:authorized|cleared|permitted|approved|allowed)\s+to\s+"
        rf"implement\s+{_SLICE}\b"
    ),
    re.compile(
        rf"(?ix)\b{_ACTOR}\s+(?:has|have|has\s+received|have\s+received|received)\s+"
        rf"{_APPROVAL_OBJECT}\s+to\s+implement\s+{_SLICE}\b"
    ),
    re.compile(
        rf"(?ix)\b(?:implementation|{_SLICE}(?:\s+implementation)?)\s+"
        rf"(?:may|can|shall|will)\s+(?:now\s+)?{_START}\b"
    ),
    re.compile(
        rf"(?ix)\b{_SLICE}(?:\s+implementation)?\s+is\s+(?:now\s+)?ready\s+"
        rf"(?:for\s+implementation|to\s+(?:implement|{_START}))\b"
    ),
    re.compile(rf"(?ix)\b(?:start|begin|commence)\s+(?:the\s+)?(?:{_SLICE}\s+)?implementation\b"),
    re.compile(
        rf"(?ix)\b(?:proceed|move(?:\s+|-)forward)\s+with\s+"
        rf"(?:the\s+)?(?:{_SLICE}\s+)?implementation\b"
    ),
    re.compile(
        rf"(?ix)\b{_ACTOR}\s+(?:may|can|shall|will)\s+(?:now\s+)?"
        rf"implement\s+{_SLICE}\b"
    ),
    re.compile(
        rf"(?ix)\b{_ACTOR}\s+(?:may|can|shall|will)\s+(?:now\s+)?"
        rf"(?:proceed|move(?:\s+|-)forward)\s+to\s+implement\s+{_SLICE}\b"
    ),
    re.compile(
        rf"(?ix)\b{_SLICE}(?:\s+implementation)?\s+"
        rf"(?:may|can|shall|will)\s+be\s+implemented\b"
    ),
    re.compile(
        rf"(?ix)\b{_APPROVAL_OBJECT}\s+(?:is\s+|was\s+)?granted\s+"
        rf"(?:for|to)\s+(?:implement\s+)?(?:{_SLICE}\s+)?implementation\b"
    ),
)


def _strip_html_comments(line: str, in_comment: bool) -> tuple[str, bool]:
    kept: list[str] = []
    cursor = 0
    while cursor < len(line):
        if in_comment:
            close = line.find("-->", cursor)
            if close < 0:
                return "".join(kept), True
            cursor = close + 3
            in_comment = False
            continue
        opening = line.find("<!--", cursor)
        if opening < 0:
            kept.append(line[cursor:])
            break
        kept.append(line[cursor:opening])
        for invalid in ("<!-->", "<!--->"):
            if line.startswith(invalid, opening):
                cursor = opening + len(invalid)
                break
        else:
            cursor = opening + 4
            in_comment = True
            continue
    return "".join(kept), in_comment


def _fence_opening(line: str) -> tuple[str, int] | None:
    match = _FENCE_OPEN.match(line)
    if match is None:
        return None
    run = match.group("run")
    info = match.group("info")
    if run[0] == "`" and "`" in info:
        return None
    return run[0], len(run)


def _is_fence_close(line: str, marker: str, opening_length: int) -> bool:
    return (
        re.fullmatch(
            rf" {{0,3}}{re.escape(marker)}{{{opening_length},}}[ \t]*", line
        )
        is not None
    )


def _split_table_cells(line: str) -> list[str]:
    source = line.strip()
    if source.startswith("|"):
        source = source[1:]
    if source.endswith("|") and not source.endswith(r"\|"):
        source = source[:-1]
    cells: list[str] = []
    cell: list[str] = []
    escaped = False
    for character in source:
        if escaped:
            cell.append(character)
            escaped = False
        elif character == "\\":
            cell.append(character)
            escaped = True
        elif character == "|":
            cells.append("".join(cell).strip())
            cell = []
        else:
            cell.append(character)
    cells.append("".join(cell).strip())
    return cells


def _has_unescaped_table_pipe(line: str) -> bool:
    escaped = False
    for character in line:
        if escaped:
            escaped = False
        elif character == "\\":
            escaped = True
        elif character == "|":
            return True
    return False


def _is_table_divider(cells: list[str]) -> bool:
    return bool(cells) and all(_TABLE_DIVIDER_CELL.fullmatch(cell) for cell in cells)


def _is_standalone_code_span(source: str) -> bool:
    stripped = source.strip()
    opening = re.match(r"(`+)", stripped)
    if opening is None:
        return False
    marker = opening.group(1)
    close = stripped.find(marker, len(marker))
    if close < 0:
        return False
    return re.fullmatch(r"\s*[.,;:]?\s*", stripped[close + len(marker) :]) is not None


def _is_standalone_quotation(source: str) -> bool:
    stripped = source.strip()
    pairs = (("\"", "\""), ("'", "'"), ("\u201c", "\u201d"), ("\u2018", "\u2019"))
    for opening, closing in pairs:
        if not (stripped.startswith(opening) and len(stripped) > len(opening)):
            continue
        end = len(stripped) - 1
        while end >= 0 and stripped[end] in ".,;:!?":
            end -= 1
        candidate = stripped[: end + 1]
        if candidate.endswith(closing) and candidate[len(opening) : -len(closing)].strip():
            return True
    return False


_DEFAULT_IGNORABLE_RANGES = (
    (0x034F, 0x034F),
    (0x115F, 0x1160),
    (0x17B4, 0x17B5),
    (0x180B, 0x180F),
    (0x2065, 0x2065),
    (0x3164, 0x3164),
    (0xFE00, 0xFE0F),
    (0xFFA0, 0xFFA0),
    (0x1BCA0, 0x1BCA3),
    (0x1D173, 0x1D17A),
    (0xE0000, 0xE0FFF),
)


def _is_default_ignorable(character: str) -> bool:
    codepoint = ord(character)
    return unicodedata.category(character) == "Cf" or any(
        first <= codepoint <= last for first, last in _DEFAULT_IGNORABLE_RANGES
    )


def _normalize_inline(source: str) -> str:
    value = source
    value = re.sub(r"(?i)<br\s*/?>", " ", value)
    value = html.unescape(value)
    value = unicodedata.normalize("NFKC", value).translate(_HYPHENS)
    # A CommonMark backslash immediately before the physical newline renders as
    # a hard break, not as a visible character.  Preserve physical newlines in
    # logical blocks until this normalization step so the break cannot split a
    # stop-gate claim.
    value = re.sub(r"\\\r?\n", " ", value)
    # Invisible Unicode format controls can otherwise split an English keyword
    # without changing how a reviewer perceives it.  Policy prose does not rely
    # on bidi controls, soft hyphens, joiners, or byte-order marks, so expose the
    # rendered word to the conservative defense-in-depth matcher.
    value = "".join(character for character in value if not _is_default_ignorable(character))
    value = re.sub(r"!\[([^]]*)\]\((?:\\.|[^)])*\)", r"\1", value)
    value = re.sub(r"\[([^]]+)\]\((?:\\.|[^)])*\)", r"\1", value)
    value = re.sub(r"\[([^]]+)\]\[[^]]*\]", r"\1", value)
    value = re.sub(r"!\[([^]]*)\]", r"\1", value)
    value = re.sub(r"\[([^]]+)\]", r"\1", value)
    value = re.sub(
        r"(?is)</?(?:address|article|aside|blockquote|div|footer|h[1-6]|header|"
        r"hr|li|main|nav|ol|p|pre|section|table|tbody|td|tfoot|th|thead|tr|ul)\b[^>]*>",
        " ",
        value,
    )
    value = re.sub(r"<[^>]+>", "", value)

    code_span = re.compile(r"(`+)(.+?)\1")
    while code_span.search(value):
        value = code_span.sub(lambda match: match.group(2).strip(), value)
    value = value.replace("`", "")

    value = value.replace("**", "").replace("__", "").replace("~~", "")
    value = re.sub(r"(?<!\w)[*_](?=\S)", "", value)
    value = re.sub(r"(?<=\S)[*_](?!\w)", "", value)
    value = re.sub(r"\\([\\`*{}\[\]()#+.!_>~-])", r"\1", value)
    value = re.sub(r"[ \t\r\n]+", " ", value)
    return value.strip()


def _logical_blocks(text: str) -> tuple[_LogicalBlock, ...]:
    blocks: list[_LogicalBlock] = []
    parts: list[str] = []
    block_start = 0
    block_end = 0
    block_kind: str | None = None
    in_comment = False
    fence: tuple[str, int] | None = None
    lazy_blockquote = False

    def flush() -> None:
        nonlocal parts, block_start, block_end, block_kind
        if parts:
            source = "\n".join(part.strip() for part in parts if part.strip()).strip()
            if source and not _is_standalone_code_span(source) and not _is_standalone_quotation(source):
                blocks.append(_LogicalBlock(block_start, block_end, source))
        parts = []
        block_start = 0
        block_end = 0
        block_kind = None

    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        if fence is not None:
            if _is_fence_close(raw_line, fence[0], fence[1]):
                fence = None
            continue

        line, in_comment = _strip_html_comments(raw_line, in_comment)
        opening = _fence_opening(line)
        if opening is not None:
            flush()
            fence = opening
            lazy_blockquote = False
            continue

        stripped = line.strip()
        if not stripped:
            flush()
            lazy_blockquote = False
            continue

        if re.match(r"^ {0,3}>", line):
            flush()
            lazy_blockquote = True
            continue
        if lazy_blockquote:
            starts_new_block = bool(
                _LIST_ITEM.match(line)
                or _ATX_HEADING.match(line)
                or (stripped.startswith("|") and "|" in stripped[1:])
                or _HORIZONTAL_RULE.fullmatch(line)
            )
            if not starts_new_block:
                continue
            lazy_blockquote = False

        if line.startswith("\t") or line.startswith("    "):
            # CommonMark indented code cannot interrupt a paragraph.  The same
            # indentation is also ordinary continuation content under a list item.
            if parts and block_kind in ("paragraph", "list"):
                parts.append(line.lstrip())
                block_end = line_number
            else:
                flush()
            continue
        if _HORIZONTAL_RULE.fullmatch(line):
            flush()
            continue
        if _LINK_REFERENCE_DEFINITION.fullmatch(line):
            flush()
            continue

        heading = _ATX_HEADING.match(line)
        if heading is not None:
            flush()
            content = re.sub(r"[ \t]+#+[ \t]*$", "", heading.group(1))
            if content.strip():
                blocks.append(_LogicalBlock(line_number, line_number, content.strip()))
            continue

        list_item = _LIST_ITEM.match(line)
        if list_item is not None:
            flush()
            content = list_item.group(1)
            nested_fence = _fence_opening(content)
            if nested_fence is not None:
                fence = nested_fence
                lazy_blockquote = False
                continue
            parts = [content]
            block_start = line_number
            block_end = line_number
            block_kind = "list"
            continue

        if _has_unescaped_table_pipe(stripped):
            flush()
            cells = _split_table_cells(line)
            if not _is_table_divider(cells):
                blocks.append(_LogicalBlock(line_number, line_number, " ".join(cells)))
            continue

        if parts and block_kind in ("paragraph", "list"):
            parts.append(line.lstrip())
            block_end = line_number
            continue

        parts = [line.strip()]
        block_start = line_number
        block_end = line_number
        block_kind = "paragraph"

    flush()
    return tuple(blocks)


def normalized_logical_blocks(text: str) -> tuple[tuple[int, int, str], ...]:
    """Return rendered logical blocks as ``(first_line, last_line, text)`` tuples.

    Fenced/indented code, blockquotes, HTML comments, and standalone code or quoted
    examples are intentionally absent.  This is a bounded normalization helper, not
    a complete CommonMark renderer.
    """

    result: list[tuple[int, int, str]] = []
    for block in _logical_blocks(text):
        normalized = _normalize_inline(block.source)
        if normalized:
            result.append((block.line_start, block.line_end, normalized))
    return tuple(result)


def markdown_without_html_comments(text: str) -> str:
    """Return source text with HTML comment contents removed, preserving line count."""

    result: list[str] = []
    in_comment = False
    for line in text.splitlines():
        visible, in_comment = _strip_html_comments(line, in_comment)
        result.append(visible)
    return "\n".join(result) + ("\n" if text.endswith(("\n", "\r")) else "")


def _split_clauses(text: str) -> tuple[str, ...]:
    pieces = re.split(
        r"(?i)(?:(?<=[.!?])(?=\s|[A-Z`\[])\s*|\s*;\s*|"
        r"\s*,?\s*\b(?:but|however|while|whereas|although|yet)\b[:,]?\s*|"
        r"\s+\b(?:notwithstanding|aside)\b\s*,?\s*)",
        text,
    )
    result: list[str] = []
    for piece in pieces:
        clause = piece.strip(" \t,")
        if not clause:
            continue
        labelled = _EXPLICIT_NONNORMATIVE.match(clause)
        if (
            labelled
            or _EXPLICIT_REJECTED_OBJECT.match(clause)
            or _META_DIRECTIVE.search(clause)
        ):
            if labelled and re.search(r"(?i)\b(?:include|includes|including|contain|contains)\b", clause):
                result.append(clause)
            else:
                result.extend(
                    segment.strip(" \t,")
                    for segment in re.split(r"(?i)\s+\band\b\s+", clause)
                    if segment.strip(" \t,")
                )
        else:
            result.append(clause)
    return tuple(result)


def _is_nonnormative_clause(clause: str) -> bool:
    return bool(
        _EXPLICIT_NONNORMATIVE.match(clause)
        or _EXPLICIT_REJECTED_OBJECT.match(clause)
        or _is_standalone_code_span(clause)
        or _is_standalone_quotation(clause)
    )


def _claim_is_suppressed(clause: str, match: re.Match[str]) -> bool:
    prefix = clause[: match.start()]
    suffix = clause[match.end() :]
    conditional = _CONDITIONAL_PREFIX.match(clause)
    if conditional is not None:
        kind = conditional.group("kind").casefold()
        body = conditional.group("body")
        if _DISCOURSE_PREFIX.match(clause):
            pass
        elif _CURRENT_FALSE_CONDITION.search(body):
            pass
        elif kind in {"after", "once", "when"} and _PAST_TRIGGER.search(body):
            pass
        else:
            return True
    if _CONDITIONAL_SUFFIX.match(suffix):
        return True
    if _META_DIRECTIVE.search(prefix):
        return True
    local_prefix = re.split(r"[,()]", prefix)[-1][-120:]
    return _LOCAL_NEGATION.search(local_prefix) is not None


def _first_unsuppressed(
    patterns: tuple[re.Pattern[str], ...], clause: str
) -> re.Match[str] | None:
    for pattern in patterns:
        for match in pattern.finditer(clause):
            if not _claim_is_suppressed(clause, match):
                return match
    return None


def _stale_identifier_pattern(current_policy: str) -> str:
    normalized = current_policy.translate(_HYPHENS)
    current = re.fullmatch(r"(?i)capability[\s_-]*v[\s_-]*(\d+)", normalized.strip())
    upper = int(current.group(1)) - 1 if current is not None else 12
    upper = max(0, min(12, upper))
    versions = "|".join(str(version) for version in range(1, upper + 1))
    capability = rf"capability[\s_-]*v[\s_-]*(?:{versions})" if versions else r"(?!)"
    recovery = r"recovery[\s_-]*journal[\s_-]*v[\s_-]*[1-4]"
    store = r"capability[\s_-]*(?:mode[\s_-]*)?store[\s_-]*v[\s_-]*[1-3]"
    return rf"(?:{capability}|{recovery}|{store})"


def _stale_patterns(current_policy: str) -> tuple[re.Pattern[str], ...]:
    stale = _stale_identifier_pattern(current_policy)
    target = rf"(?:this\s+|the\s+)?(?:implementation|policy|contract|{_SLICE})"
    status = r"(?:applicable|binding|authoritative|current|active|normative|operative|effective)"
    return (
        re.compile(rf"(?ix)\b{stale}\b\s+(?:continues?\s+to\s+)?(?:governs?|controls?)\s+{target}\b"),
        re.compile(
            rf"(?ix)\b{stale}\b\s+(?:continues?\s+to\s+)?"
            rf"(?:applies|apply)\b(?:\s+to\s+{target})?"
        ),
        re.compile(
            rf"(?ix)\b{stale}\b\s+(?:is|remains?|continues?\s+as)\s+"
            rf"(?:still\s+)?(?:the\s+)?{status}\b(?:\s+(?:to|for)\s+{target})?"
        ),
        re.compile(
            rf"(?ix)\b{stale}\b\s+(?:is|remains?)\s+(?:still\s+)?"
            rf"(?:the\s+)?(?:governing\s+(?:policy|authority|contract)|"
            rf"source[\s-]+of[\s-]+truth|policy\s+in\s+force|authority\s+in\s+force)\b"
        ),
        re.compile(rf"(?ix)\b{stale}\b\s+(?:is|remains?)\s+(?:still\s+)?in\s+force\b"),
        re.compile(
            rf"(?ix)\b(?:this\s+|the\s+)?implementation\s+"
            rf"(?:is\s+)?(?:governed|controlled)\s+by\s+{stale}\b"
        ),
        re.compile(rf"(?ix)\b(?:this\s+|the\s+)?implementation\s+(?:still\s+)?follows\s+{stale}\b"),
        re.compile(
            rf"(?ix)\b(?:the\s+)?(?:governing|current|active|normative|operative|effective)\s+"
            rf"(?:policy|authority|contract)\s+(?:is|remains?|continues?\s+to\s+be)\s+{stale}\b"
        ),
        re.compile(
            rf"(?ix)\b{stale}\b\s+(?:supersedes|(?:has|have)\s+superseded)\s+"
            rf"(?:this\s+|the\s+)?(?:candidate|policy|authority|contract|capability[\s_-]*v[\s_-]*13)\b"
        ),
    )


def find_candidate_boundary_findings(
    text: str, current_policy: str = "capability-v13"
) -> tuple[ProseFinding, ...]:
    """Find defense-in-depth prose contradictions in candidate-boundary Markdown.

    The result is deliberately a conservative lint signal, not semantic proof and
    not an authorization decision.  Callers must retain a structured false stop gate.
    """

    findings: list[ProseFinding] = []
    stale_patterns = _stale_patterns(current_policy)
    categories = (
        ("defense-in-depth/structured-true", _STRUCTURED_TRUE),
        ("defense-in-depth/authorization", _AUTHORIZATION),
        ("defense-in-depth/stale-governance", stale_patterns),
    )
    for line_start, line_end, block in normalized_logical_blocks(text):
        for clause in _split_clauses(block):
            if _is_nonnormative_clause(clause):
                continue
            for category, patterns in categories:
                match = _first_unsuppressed(patterns, clause)
                if match is not None:
                    findings.append(ProseFinding(line_start, line_end, category, clause))
                    break
    return tuple(dict.fromkeys(findings))


__all__ = (
    "ProseFinding",
    "find_candidate_boundary_findings",
    "markdown_without_html_comments",
    "normalized_logical_blocks",
)
