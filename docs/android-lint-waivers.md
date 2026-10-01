# Android lint waivers

Android release lint treats every warning as an error. The checked
`androidApp/lint-baseline.xml` is an exact, temporary waiver for legacy findings that predate the
gate. New findings are not accepted into the baseline during ordinary development.

The baseline currently covers:

- hard-coded and concatenated English text in the existing programmatic Android UI;
- legacy launcher icon shape findings; and
- the intentional fixed-landscape tablet workflow warning.

Security and accessibility findings are not categorically waived. The ADB automation receiver is
declared only in the debug manifest, and touch listeners must either preserve normal view click
handling or document a focused suppression. Rebuild the baseline only as part of an explicit lint
cleanup review, and remove entries as the affected UI or assets are modernized.
