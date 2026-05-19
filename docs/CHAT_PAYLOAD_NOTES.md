# Chat Payload Notes

Why the Google Chat card sent by this plugin has no buttons, and why every clickable URL lives in the top-level `text` field of the webhook payload.

## TL;DR

Google Chat applies a single security policy to **every** card-mediated click: it rewrites `http://` → `https://` at click time, then calls `window.open()` on the upgraded URL. If your APK server runs HTTP only, every `buttonList.openLink`, `decoratedText.onClick`, and `<a href>` inside a `textParagraph` is effectively a dead link — the click fires, but the browser hits a TLS handshake against a server that doesn't speak HTTPS.

**Top-level `text` URLs are exempt** because Chat renders them as plain `<a href="http://...">` tags at message-render time. When the user clicks, the browser navigates directly — Chat's click handler isn't in the loop, so its scheme-rewrite policy never runs.

## The mechanism, in detail

Inspecting Chat's rendered DOM:

- The URL is **not** stripped out of card widgets. It's stored in a `jsdata` reference blob attached to the widget's wrapper span (e.g. `jsdata="FppS6c;_;$5836"`).
- The widget has a `jsaction="click:h5M12e"` handler. On click, the handler reads the URL from that blob, rewrites the scheme to `https://`, then calls `window.open()`.
- For HTTP-only servers, the resulting HTTPS request fails with `ERR_SSL_PROTOCOL_ERROR` (Chrome) or equivalent — the click fires, but the destination doesn't exist.

The top-level `text` field is a different code path entirely. Chat renders bare URLs in `text` as actual anchor tags with `http://` preserved. The browser handles the click directly; Chat's `jsaction` infrastructure never sees it.

## Test matrix

| Variant | Result | Why |
|---|---|---|
| Top-level `text` with bare URL | works | Browser-direct click; Chat not in the loop |
| `buttonList.button.onClick.openLink` | HTTPS-upgraded → TLS fail | `jsdata` blob + click handler rewrites scheme |
| `decoratedText.onClick.openLink` | HTTPS-upgraded → TLS fail | Same mechanism as `buttonList` |
| `textParagraph` with `<a href="http://...">` | HTTPS-upgraded → TLS fail | DOM has `http://` but click handler intercepts |
| `textParagraph` with plain inline URL | not auto-linked | Card widgets don't run the auto-linkifier |
| `textParagraph` with Slack-style `<url\|label>` | rendered as literal text | Chat doesn't support Slack markdown |

## What this plugin does

- **Top-level `text` field carries every clickable URL** — install, alt-port install (when applicable), direct APK download, and the builds dashboard.
- **The `cardsV2` payload carries metadata only** — branch, commit, message, JIRA ticket, filename, all rendered as `decoratedText` rows with no `onClick`.
- **No `buttonList`** anywhere in the payload.

If your server moves to HTTPS, you can safely re-add `buttonList` — the click path becomes a no-op rewrite (`https://` → `https://`) and buttons will work. Until then, top-level text is the only affordance that doesn't lie.

## How to recover from this kind of bug

When debugging Google Chat (or any Google webapp) DOM, **look for `jsdata` reference blobs, not just direct HTML attributes**. URLs and other state are frequently stored in a global JS object referenced by ID via `jsdata="<key>;_;$<refid>"`. A flat grep through the rendered HTML will miss them — earlier diagnoses concluded "Chat strips the URL" because they only inspected `<a href>` attributes. The URL is there; the click handler just rewrites the scheme before navigation.
