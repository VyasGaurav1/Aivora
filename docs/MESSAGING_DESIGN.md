# Named messaging apps

The full goal remains contact resolution → selected conversation → exact message → authorized send → observed result. Draft handoff is the first implemented stage, not completion of that goal.

Implemented:

- `message Rahul on WhatsApp that I'll be late` and the WhatsApp Business variant retain an explicit app enum, contact ID/name/number and exact body.
- Number ambiguity asks the owner. A full international number is required; country codes are never inferred. Changed contacts/permission loss are checked again before handoff and during scheduled-target checks.
- Supported click-to-chat links use the [official WhatsApp format](https://faq.whatsapp.com/5913398998672934). The URL is built with encoded path/query components and pinned to the selected installed client. No browser, SMS or other app fallback is attempted.
- Policy requires confirmation of the exact draft plan; an unverified speaker cannot authorize it. Named apps are checked before asking to connect. The online-task and scheduling network requirements apply.
- An app handoff returns REQUIRES_USER_INTERACTION and explicitly says AIVORA has not sent the message. No Send button, hidden API or background bulk sender is used.

Remaining:

- Compatible installed/account-ready clients and actual populated recipient/body acceptance.
- A separate typed send action with exact target/composer state verification, single-use approval and observed post-send state. Existing YouTube accessibility access grants no WhatsApp authority.
- Delivery/read status must not be inferred from an empty composer, a successful Intent call or a queued message. Never retry uncertain sends automatically.
- Compatible versions/languages, cancellation, app/account changes, lock state and physical voice workflows.

Tests use fixtures and missing-client refusal; no real recipient is messaged.
