from __future__ import annotations

from dataclasses import FrozenInstanceError
import unittest

from scripts.capability_policy_markdown import (
    ProseFinding,
    find_candidate_boundary_findings,
    markdown_without_html_comments,
    normalized_logical_blocks,
)


class CapabilityPolicyMarkdownTest(unittest.TestCase):
    def assert_one(
        self,
        source: str,
        category: str,
        excerpt: str,
        lines: tuple[int, int] = (1, 1),
    ) -> None:
        self.assertEqual(
            (ProseFinding(lines[0], lines[1], category, excerpt),),
            find_candidate_boundary_findings(source),
        )

    def test_finding_is_immutable(self) -> None:
        finding = ProseFinding(1, 2, "defense-in-depth/authorization", "example")
        with self.assertRaises(FrozenInstanceError):
            finding.excerpt = "changed"  # type: ignore[misc]

    def test_comment_stripping_preserves_visible_source_and_line_count(self) -> None:
        source = "visible <!-- hidden\nstill hidden --> text\n<!--> claim\n"
        self.assertEqual(
            "visible \n text\n claim\n",
            markdown_without_html_comments(source),
        )

    def test_review12_commonmark_bypasses_are_normalized(self) -> None:
        fixtures = (
            (
                "Implementation is **authorized**.\n",
                "defense-in-depth/authorization",
                "Implementation is authorized.",
                (1, 1),
            ),
            (
                "Implementation is [authorized](https://example.invalid/claim).\n",
                "defense-in-depth/authorization",
                "Implementation is authorized.",
                (1, 1),
            ),
            (
                "Implementation is [authorized].\n",
                "defense-in-depth/authorization",
                "Implementation is authorized.",
                (1, 1),
            ),
            (
                "Implementation is [authorized][].\n",
                "defense-in-depth/authorization",
                "Implementation is authorized.",
                (1, 1),
            ),
            (
                "`implementation_authorized` is **`true`**.\n",
                "defense-in-depth/structured-true",
                "implementation_authorized is true.",
                (1, 1),
            ),
            (
                "Implementation is\nauthorized.\n",
                "defense-in-depth/authorization",
                "Implementation is authorized.",
                (1, 2),
            ),
            (
                "Implementation is\\\nauthorized.\n",
                "defense-in-depth/authorization",
                "Implementation is authorized.",
                (1, 2),
            ),
            (
                "Implementation is auth\u200borized.\n",
                "defense-in-depth/authorization",
                "Implementation is authorized.",
                (1, 1),
            ),
            (
                "- Slice 1B may\n  start.\n",
                "defense-in-depth/authorization",
                "Slice 1B may start.",
                (1, 2),
            ),
            (
                "| Implementation is | authorized. |\n",
                "defense-in-depth/authorization",
                "Implementation is authorized.",
                (1, 1),
            ),
            (
                "Implementation is | authorized.\n--- | ---\n",
                "defense-in-depth/authorization",
                "Implementation is authorized.",
                (1, 1),
            ),
            (
                "- Implementation is\n    authorized.\n",
                "defense-in-depth/authorization",
                "Implementation is authorized.",
                (1, 2),
            ),
            (
                "Implementation is\n    authorized.\n",
                "defense-in-depth/authorization",
                "Implementation is authorized.",
                (1, 2),
            ),
            (
                "Slice 1B may<br>start.\n",
                "defense-in-depth/authorization",
                "Slice 1B may start.",
                (1, 1),
            ),
            (
                "Capability-v9 **governs** this implementation.\n",
                "defense-in-depth/stale-governance",
                "Capability-v9 governs this implementation.",
                (1, 1),
            ),
            (
                "Implementation is auth<span></span>orized.\n",
                "defense-in-depth/authorization",
                "Implementation is authorized.",
                (1, 1),
            ),
            (
                "\uff29\uff4d\uff50\uff4c\uff45\uff4d\uff45\uff4e\uff54\uff41\uff54\uff49\uff4f\uff4e "
                "\uff49\uff53 \uff41\uff55\uff54\uff48\uff4f\uff52\uff49\uff5a\uff45\uff44.\n",
                "defense-in-depth/authorization",
                "Implementation is authorized.",
                (1, 1),
            ),
        )
        for source, category, excerpt, lines in fixtures:
            with self.subTest(source=source):
                self.assert_one(source, category, excerpt, lines)

    def test_semantic_paraphrase_and_spaced_identifier_families(self) -> None:
        fixtures = (
            "We are authorized to implement Slice 1B.",
            "The team is cleared to implement Slice 1B.",
            "Developers are permitted to implement Slice 1B.",
            "We received approval to implement Slice 1B.",
            "The team has permission to implement Slice 1B.",
            "Slice 1B implementation may commence.",
            "Capability v 9 remains applicable to this implementation.",
            "Recovery Journal V4 is binding for this implementation.",
            "Capability Mode Store V3 is the source of truth for this implementation.",
            "This implementation follows capability\u2014v 12.",
            "Capability-v8 controls this implementation.",
            "Capability-v7 continues to apply to this implementation.",
            "Capability-v6 is authoritative for this implementation.",
            "The current authority is Capability-v5.",
            "Capability-v4 is active for this implementation.",
            "Capability-v3 is normative for this implementation.",
            "Capability-v2 is operative for this implementation.",
            "Capability-v1 is effective for this implementation.",
            "Capability-v12 supersedes the candidate policy.",
            "Capability-v12 has superseded the candidate policy.",
            "Capability_v12 governs this implementation.",
            "Capability-v12 is the source-of-truth for this implementation.",
            "Slice 1B can be implemented.",
            "The team may proceed to implement Slice 1B.",
        )
        for fixture in fixtures:
            with self.subTest(fixture=fixture):
                findings = find_candidate_boundary_findings(fixture + "\n")
                self.assertEqual(1, len(findings))
                self.assertIn(
                    findings[0].category,
                    (
                        "defense-in-depth/authorization",
                        "defense-in-depth/stale-governance",
                    ),
                )
                self.assertEqual(fixture.replace("\u2014", "-"), findings[0].excerpt)

    def test_review12_mixed_history_lines_do_not_hide_positive_clause(self) -> None:
        fixtures = (
            (
                "History is closed; Implementation is authorized.",
                "Implementation is authorized.",
            ),
            (
                "The claim is rejected, but implementation is authorized.",
                "implementation is authorized.",
            ),
            (
                "Rejected proposal notwithstanding, the team may implement Slice 1B.",
                "the team may implement Slice 1B.",
            ),
            (
                "Historical record aside, implementation is authorized.",
                "implementation is authorized.",
            ),
            (
                "The claim was rejected, while implementation is authorized.",
                "implementation is authorized.",
            ),
            (
                "Never write stale text, while implementation is authorized.",
                "implementation is authorized.",
            ),
            (
                "History is closed and implementation is authorized.",
                "implementation is authorized.",
            ),
        )
        for source, excerpt in fixtures:
            with self.subTest(source=source):
                self.assert_one(
                    source + "\n",
                    "defense-in-depth/authorization",
                    excerpt,
                )

    def test_truthful_future_conditions_and_meta_denials_are_allowed(self) -> None:
        fixtures = (
            "Implementation may start only after exact-commit review passes.",
            "Before implementation is authorized, the stop gate remains closed.",
            "Unless implementation is authorized, Slice 1B remains blocked.",
            "If implementation is authorized, the manifest must first change.",
            "Whether implementation is authorized remains unknown.",
            "Never write: Implementation is authorized.",
            "Do not assert capability-v11 is current.",
            "Implementation may begin only if the exact-commit audit passes.",
            "Implementation may commence only once a separate transition lands.",
            "Until implementation is authorized, no Slice 1B work may start.",
            "If exact-commit review passes, implementation may start.",
            "Once a separate transition lands, the team may implement Slice 1B.",
            "Implementation may start, if exact-commit review passes.",
            "Implementation may start provided that exact-commit review passes.",
        )
        for fixture in fixtures:
            with self.subTest(fixture=fixture):
                self.assertEqual((), find_candidate_boundary_findings(fixture + "\n"))

    def test_condition_or_denial_does_not_mask_later_positive_clause(self) -> None:
        fixtures = (
            (
                "Before implementation is authorized, the gate stays closed; Implementation is authorized.",
                "Implementation is authorized.",
            ),
            (
                "Do not assert capability-v11 is current; Capability-v9 governs this implementation.",
                "Capability-v9 governs this implementation.",
            ),
            (
                "Implementation is not authorized; the team may implement Slice 1B.",
                "the team may implement Slice 1B.",
            ),
            (
                "Capability-v9 does not apply; Capability-v8 remains in force.",
                "Capability-v8 remains in force.",
            ),
        )
        for source, excerpt in fixtures:
            with self.subTest(source=source):
                finding = find_candidate_boundary_findings(source + "\n")
                self.assertEqual(1, len(finding))
                self.assertEqual(excerpt, finding[0].excerpt)

        for source in (
            "If implementation_authorized=false, implementation is authorized.",
            "After the exact-commit review passed, implementation may start.",
            "After all, implementation is authorized.",
            "If anything, implementation is authorized.",
            "Once again, implementation is authorized.",
        ):
            with self.subTest(source=source):
                finding = find_candidate_boundary_findings(source + "\n")
                self.assertEqual(1, len(finding))
                self.assertEqual("defense-in-depth/authorization", finding[0].category)

    def test_commonmark_fence_marker_length_and_info_rules(self) -> None:
        four_backticks = (
            "````text\n"
            "```\n"
            "Implementation is authorized.\n"
            "````\n"
            "Implementation is not authorized.\n"
        )
        info_bearing_would_be_close = (
            "```text\n"
            "``` still-code\n"
            "Implementation is authorized.\n"
            "```\n"
        )
        tilde_info_with_backtick = (
            "~~~language`variant\n"
            "Implementation is authorized.\n"
            "~~~\n"
        )
        for source in (
            four_backticks,
            info_bearing_would_be_close,
            tilde_info_with_backtick,
        ):
            with self.subTest(source=source):
                self.assertEqual((), find_candidate_boundary_findings(source))

        nested_list_fence = (
            "- ```text\n"
            "  Implementation is authorized.\n"
            "  ```\n"
        )
        self.assertEqual((), find_candidate_boundary_findings(nested_list_fence))

    def test_reference_definitions_and_malformed_markup_are_scoped_fail_closed(self) -> None:
        self.assertEqual(
            (),
            find_candidate_boundary_findings(
                "[Implementation is authorized.]: https://example.invalid/\n"
            ),
        )
        positives = (
            "[Implementation is authorized.]: not a valid url\n",
            "[Implementation is authorized.]: <unterminated\n",
            "<!--> Implementation is authorized.\n",
            "<!---> Implementation is authorized.\n",
        )
        for source in positives:
            with self.subTest(source=source):
                self.assertEqual(1, len(find_candidate_boundary_findings(source)))

    def test_default_ignorable_and_compatibility_forms_do_not_split_claims(self) -> None:
        fixtures = (
            "Implementation is auth\u034forized.",
            "Implementation is auth\ufe0forized.",
            "Implementation is auth\u180borized.",
            "Implementation is \U0001d41a\U0001d42e\U0001d42d\U0001d421\U0001d428\U0001d42b\U0001d422\U0001d433\U0001d41e\U0001d41d.",
            "Capability-v\uff11\uff12 governs this implementation.",
        )
        for source in fixtures:
            with self.subTest(source=source):
                self.assertEqual(1, len(find_candidate_boundary_findings(source + "\n")))

    def test_backtick_info_invalidity_does_not_create_a_fence(self) -> None:
        source = "```language`variant\nImplementation is authorized.\n```\n"
        findings = find_candidate_boundary_findings(source)
        self.assertEqual(1, len(findings))
        self.assertEqual(
            "languagevariant Implementation is authorized.", findings[0].excerpt
        )

    def test_comments_code_quotes_and_labelled_fixture_objects_are_excluded(self) -> None:
        fixtures = (
            "<!-- Implementation is authorized. -->\n",
            "Before <!-- Implementation is authorized. --> the gate remains closed.\n",
            "    Implementation is authorized.\n",
            "\tCapability-v9 governs this implementation.\n",
            "> Implementation is authorized.\n  authorized continuation\n",
            "`Implementation is authorized.`\n",
            '"Implementation is authorized."\n',
            "\u201cImplementation is authorized.\u201d\n",
            "History: Implementation is authorized.\n",
            "Rejected claim: Slice 1B may start.\n",
            "Counterexample: Capability-v9 governs this implementation.\n",
            "The statement \"Implementation is authorized.\" was rejected.\n",
            "- Rejected counterexample fixtures include `Implementation is authorized.`,\n"
            "  and `implementation_authorized` is `true`.\n",
        )
        for fixture in fixtures:
            with self.subTest(fixture=fixture):
                self.assertEqual((), find_candidate_boundary_findings(fixture))

    def test_negative_mirrors_are_allowed(self) -> None:
        fixtures = (
            "Implementation is not authorized.",
            "No implementation may start from this ledger.",
            "Slice 1B may not start.",
            "Not authorized for implementation.",
            "Without being authorized for implementation, Slice 1B remains blocked.",
            "This candidate cannot be authorized for implementation.",
            "Implementation is not permitted.",
            "Do not proceed with Slice 1B implementation.",
            "The team may not implement Slice 1B.",
            "Capability-v9 does not control this implementation.",
            "Capability-v9 does not apply.",
            "Capability-v9 is no longer in force.",
            "Capability-v9 is not authoritative for this implementation.",
            "This implementation does not follow Capability-v9.",
            "Capability-v12 does not supersede the candidate policy.",
            "Capability-v12 was superseded by capability-v13.",
        )
        for fixture in fixtures:
            with self.subTest(fixture=fixture):
                self.assertEqual((), find_candidate_boundary_findings(fixture + "\n"))

    def test_case_punctuation_inline_code_and_unicode_contractions(self) -> None:
        positives = (
            (
                "IMPLEMENTATION IS AUTHORIZED!",
                "defense-in-depth/authorization",
            ),
            (
                "`Implementation_Authorized`: `TRUE`?",
                "defense-in-depth/structured-true",
            ),
            (
                "CAPABILITY\u2011V12 IS CURRENT.",
                "defense-in-depth/stale-governance",
            ),
        )
        for source, category in positives:
            with self.subTest(source=source):
                result = find_candidate_boundary_findings(source + "\n")
                self.assertEqual(1, len(result))
                self.assertEqual(category, result[0].category)

        denials = (
            "Implementation isn\u2019t authorized.",
            "This candidate can\u2019t be authorized for implementation.",
            "The team shouldn\u2019t proceed with Slice 1B implementation.",
        )
        for source in denials:
            with self.subTest(source=source):
                self.assertEqual((), find_candidate_boundary_findings(source + "\n"))

    def test_multiple_positive_clauses_produce_separate_findings(self) -> None:
        source = (
            "Implementation is authorized; Capability-v9 governs this implementation. "
            "However, the team may implement Slice 1B.\n"
        )
        self.assertEqual(
            (
                ProseFinding(
                    1,
                    1,
                    "defense-in-depth/authorization",
                    "Implementation is authorized",
                ),
                ProseFinding(
                    1,
                    1,
                    "defense-in-depth/stale-governance",
                    "Capability-v9 governs this implementation.",
                ),
                ProseFinding(
                    1,
                    1,
                    "defense-in-depth/authorization",
                    "the team may implement Slice 1B.",
                ),
            ),
            find_candidate_boundary_findings(source),
        )

    def test_current_candidate_document_snippets_are_allowed(self) -> None:
        source = """
The structured stop gate remains `implementation_authorized=false`.
Capability-v12 is permanently rejected evidence and is not current.
Capability-v13 supersedes capability-v12 as the candidate under review.
RecoveryJournalV4 was rejected and is not the governing contract.
CapabilityModeStoreV3 is historical; CapabilityModeStoreV4 remains the candidate store.
Before implementation is authorized, exact-commit review must pass.
Do not assert that capability-v12 is authoritative.
"""
        self.assertEqual((), find_candidate_boundary_findings(source))

    def test_current_policy_parameter_excludes_that_capability_version(self) -> None:
        self.assertEqual(
            (),
            find_candidate_boundary_findings(
                "Capability-v12 is current.\n", current_policy="capability-v12"
            ),
        )
        self.assert_one(
            "Capability-v11 is current.\n",
            "defense-in-depth/stale-governance",
            "Capability-v11 is current.",
        )

    def test_logical_block_helper_reports_joined_ranges_and_normalized_cells(self) -> None:
        source = (
            "A soft\nwrapped paragraph.\n\n"
            "- A list\n  continuation.\n\n"
            "| **left** | [right](https://example.invalid/) |\n"
            "| --- | --- |\n\n"
            "plain | cells\n"
            "--- | ---\n"
        )
        self.assertEqual(
            (
                (1, 2, "A soft wrapped paragraph."),
                (4, 5, "A list continuation."),
                (7, 7, "left right"),
                (10, 10, "plain cells"),
            ),
            normalized_logical_blocks(source),
        )


if __name__ == "__main__":
    unittest.main()
