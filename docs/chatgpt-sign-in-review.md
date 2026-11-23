# ChatGPT sign-in review — 2026-11-20

The app uses the documented open-source ChatGPT plan flow. The reported browser
hang is consistent with defects in the local callback receiver; the precise
device failure was not captured in a live app log. A successful end-to-end login
still needs verification on the installed app.

## Checked against official documentation

- New registration uses `dynamic_agent_client`, the app name, and a stable host ID.
- Authorization opens the system browser, with fresh state, nonce, and S256 PKCE.
- The listener binds to `127.0.0.1` before opening the browser; the authorization
  and token-exchange redirect URIs are identical and use `/auth/callback`.
- Identity and plan scopes, the resource, and the form-encoded token endpoint
  match the documented direct flow. No client secret or API key is required.
- Callback state and optional issuer are checked. Returning callbacks cannot
  replace the selected registration with a different client ID.
- Identity validation checks the RSA signature against OpenAI JWKS, issuer,
  audience, expiry, nonce, and the existing signed-in subject.
- Credentials and pending PKCE transactions use encrypted app preferences.
- Model discovery uses the account token and the `models` catalog. Inference uses
  `/v1/responses`, `store: false`, `stream: true`, and waits for completion.

Sources:

- [Registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [Identity verification](https://developers.openai.com/siwc/website)
- [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)

## Login fixes in this change

- An idle/preconnect socket or partial HTTP request no longer terminates login.
- Unrelated, stale, and malformed callbacks do not consume the active attempt.
- Waiting is bounded to ten minutes, cancellable, and independent of the AI
  screen's composition lifetime.
- The short-lived transaction survives process recreation in encrypted storage.
  Users can paste the browser callback inside Spark if automatic delivery fails.
  This is an app recovery feature; it uses the same state, endpoint, issuer,
  PKCE, and identity checks as automatic delivery.
- A valid callback retains the issued client ID before attempting code exchange.
  The transaction is consumed once; a failed exchange requires fresh OAuth and
  reuses that registration.
- Errors include a sanitized OAuth error code and an instruction to retry.

Local socket regression tests cover idle connections, partial requests, stale
state, malformed callbacks, issuer/endpoint checks, timeout, and cancellation.
These checks do not substitute for a real account sign-in and token exchange.

## Remaining account-management work outside the callback fix

The implementation should not yet be described as complete against all of the
account-management guidance:

- It stores one account, without a picker or separate records for multiple
  account/workspace registrations.
- Disconnect currently clears local credentials without requesting server-side
  refresh-session revocation, and clears the saved subject/email mapping.
- Refresh requests are not serialized, and terminal refresh-token errors need
  dedicated recovery that retains the issued registration.
- An identity grant without plan permission is rejected instead of retained
  with a clear disabled-plan state and an enable-usage action.

See [Accounts and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
and [Errors and recovery](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery).
