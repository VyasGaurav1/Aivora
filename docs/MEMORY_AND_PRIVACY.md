# AIVORA local memory and voice recovery

Latest update: see FIVE_PRIORITIES.md for current-reply local explanations, targeted memory lookup, applied explicit corrections and wake-word speech interruption. These supersede earlier stored-feedback-only behavior.

AIVORA stores up to 80 recent recognized command/reply pairs in its existing encrypted Room memory database. It does not save ambient wake-listening audio. API keys, PINs and passwords are excluded from automatic conversation history when identified by their field names. This filter is a precaution, not a universal sensitive-data detector. Explicit facts saved with "remember that" remain separate from bounded conversation history.

Try these voice commands:

- "Repeat that", "say that again", or "what did you say": repeat the most recently saved reply, including after an app restart.
- "What did we discuss?": read the latest three recorded requests.
- "Remember that my competition is on Friday": save an explicit fact.
- "Speak slower" or "speak faster": save a local speech-speed preference.
- "That was helpful" or "that was wrong": save local feedback. Negative feedback does not automatically create a replacement fact.
- "Forget conversation history": delete the saved conversation pairs.

Settings has a local-memory switch and history controls. Pausing memory uses the existing private-mode setting: new long-term writes stop and conversation readback pauses. Pausing is not deletion; clearing history deletes conversation pairs, while explicit facts and feedback remain separate.

This is retrieval-based local personalization and explicit preference learning, not reinforcement training or fine-tuning the model's weights. Ratings are stored for future local adaptation; current behavior adapts through saved speech speed and explicit saved facts. There is no promise that an arbitrary correction automatically retrains recognition or intent parsing.

Saved-memory replay, personal-fact recall and scheduled reminder speech use offline Indian English synthesis or bundled offline synthesis. Stored history and feedback are not added to Brave queries, uploaded to Gnani, or included in cloud model prompts. Gnani still receives live enabled microphone audio and ordinary response text for synthesis; Brave receives the current search question. These online speech/search flows remain authorized by the user's 7 November clarification. Android app backup is disabled. The app does not identify which person spoke; do not treat all captured statements as verified owner facts.

Wake recovery preserves an outstanding panel ticket when offering a notification fallback, waits up to eight seconds for a session, retries transient speech transport failures with capped backoff, and releases voice ownership after cancelled/expired turns. Authorization/key errors still require correction. Android force-stop, revoked permissions and OEM process cleanup remain platform limitations. This does not establish indefinite background reliability.
