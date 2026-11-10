# App control implementation

Continue the full hands-free goal with typed app operations, not a general arbitrary click API.

1. YouTube play/pause first uses an authorized package-matched media session and verifies playback state. This is the official API path.
2. If no session is available, an owner-enabled AccessibilityService provides a scoped fallback. Only the requested YouTube package is inspected. Resource ID and exact control semantics must agree; ambiguity, a password node, an unexpected foreground app, a changed window/control or a truncated tree stops execution.
3. A fresh tree is resolved immediately before clicking. Afterward the expected control state must appear. UI state alone is PARTIAL; actual PLAYING/PAUSED requires a matching media session observation for SUCCESS. No screenshots, coordinates, arbitrary gestures, screen logs or persisted accessibility content are used.
4. Verify pure selector rejection/boundary behavior, real Android capability and fail-closed paths, builds/lint, then compatible-device positive playback flows. The emulator's outdated YouTube client cannot prove those positive flows.

The accessibility service is system-bound and optional, with no exported command interface. Commands still pass through ActionRegistry and its owner/source/lock policy. Messaging and payment controls need their own typed adapters and authorization; YouTube selectors grant no authority over those apps.
