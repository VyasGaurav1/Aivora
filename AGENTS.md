# AIVORA project rules

- Product name is permanently `AIVORA`.
- Android namespace, application ID and root Kotlin package are permanently `com.chronoit.aivora`. Never use a temporary package identifier.
- User-selected `assistantDisplayName` and `wakeName` are separate from product identity; both initially default to `Aivora`.
- Never derive database filenames, providers, intent identifiers, or installation identity from the selected assistant name.
- Read PROJECT_STATE.md and docs/BUILD_SPEC.md before continuing implementation. Branding rules here supersede historical names in the original specification.
- Do not claim voice enrollment, recognition, execution, or authentication succeeds unless its implementation verifies the result.

- Supported Android baseline is Android 11 (API 30), across devices; do not limit installation to particular phone models.
