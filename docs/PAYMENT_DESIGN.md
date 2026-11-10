# Payment workflow requirements and evidence

Payment control remains unfinished. The owner's requested scope includes personal transfers using GPay and PhonePe, exact recipient/amount review, legitimate authentication, and observed outcomes. Opening an installed app or constructing a URI does not complete this requirement.

## Verified integration evidence

Google's [Android overview](https://developers.google.com/pay/india/api/android/overview) describes merchant UPI integration and requires verified merchant channels, bank/payment status APIs and unique transaction IDs. Its [in-app payment guide](https://developers.google.com/pay/india/api/android/in-app-payments) shows a package-pinned UPI URI. It explicitly requires checking submitted/succeeded responses with the payment service provider or aggregator before accepting payment success.

These documents establish a merchant integration contract. They do not establish that an arbitrary personal recipient can reliably receive a transfer through an unsigned intent from AIVORA. AIVORA must not manufacture merchant details, assume a personal UPI ID is a verified merchant, or treat a returned intent/status as authoritative settlement evidence. The NPCI PDF linked by Google's guide currently resolves to a general NPCI webpage through the reader; the actual linking specification was not recovered in this verification.

The attempted PhonePe intent documentation URL returned 404. A current first-party personal-payment integration contract has not yet been verified. Community package lists or URI examples alone are insufficient to establish supported personal payment behavior.

## Required implementation

- Typed request with immutable chosen app, explicit recipient identity/UPI address, exact decimal INR amount and optional note. No guessed contact-to-bank mapping, implicit currency conversion or amount inferred from an uncertain transcript.
- Fresh device authentication followed by exact-plan confirmation, normal source/lock checks, and a visible record of uncertain or interrupted attempts. A spoken command never supplies a UPI PIN or banking credential.
- Choose a supported integration or a narrowly scoped, owner-enabled semantic app adapter after inspecting the actual supported app/version. Validate the payment app's displayed recipient and amount before an authorized transition; changed or ambiguous values invalidate approval.
- Never collect/store/type a payment PIN, bypass app authentication, or use an accessibility adapter on credential fields. The existing YouTube adapter does not allow payment-app access.
- Observe explicit states such as requested, app review opened, owner authentication required, pending, failed and unverified. Only authoritative evidence can support completed payment. If the available offline architecture cannot verify settlement, report that limitation and preserve the uncertain attempt so it cannot silently retry.
- Test without real transfers first, including recipient/amount substitution, decimal/Unicode ambiguity, stale approvals, returned-status forgery, duplicate callbacks, process death, network loss and missing apps. Positive personal-transfer acceptance requires a compatible installed client and an explicitly authorized test transaction; no transfer has been attempted in this workspace.

These are remaining acceptance requirements, not implemented features. The offline core and permanent AIVORA/com.chronoit.aivora identity remain unchanged.
