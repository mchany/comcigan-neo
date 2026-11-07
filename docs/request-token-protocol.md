# Page request token and search notice rows

## Observed change

On 2026-11-07 the official student page (`http://comci.net:4082/st`) assigned a
page-issued hexadecimal credential to `window.__HourRequestToken`. Both
`QAjaxSchool` and `QAjaxTimetable` sent it in the `X-Hour-Token` request header.
The same encoded query returned HTTP 403 without the header and an authenticated
`enc5` response with the newly issued credential. The existing request encoding,
response authentication and decryption remained usable.

The encrypted response for the school query `경기` contained ten school rows and
the notice row `[0,"알림","없으면 추가 검색하세요",0]`. The notice is not a school:
using its selector to calculate the dynamic school-code column points to the
message at index 2, causing the entire search to fail. An exact-name query did
not contain this notice, explaining why some searches worked after adding the
token while broader queries still failed.

Upstream reference: <https://github.com/hegelty/pycomcigan/pull/12>.

## Client behavior

- Read the literal page token only when both request helpers advertise the
  supported header expression. Reject missing, ambiguous or unsupported token
  declarations and header expressions. Do not execute remote JavaScript.
- Send the credential only on school and timetable queries, never on `/st`.
  Keep it in the existing in-memory service-info cache. Diagnostic strings for
  the credential and `ServiceCodes` redact its value.
- On HTTP 403 or a protocol/authentication structure error, invalidate the
  cached service information and repeat the operation once with a fresh page.
  A second rejection invalidates the cache and reaches the caller. Do not retry
  HTTP 429, other HTTP failures, or a failure while loading the page itself.
  The token lifetime has not been measured; no new lifetime is assumed.
- Distinguish request rejection and rate limiting in the user-facing message.
- Exclude four-element notice rows whose selector and final value are zero
  before calculating the school-code column. Continue validating real rows;
  do not silently discard malformed school records.
- Preserve older pages that have no token header and the existing `enc5`
  authentication/decryption and bounded source-decoding behavior.

## Regression coverage

Offline tests cover token extraction and redaction, invalid/ambiguous literals,
matching header use in both helpers, encrypted notice-only and empty results,
legacy rows, and rejection of malformed school rows. A local HTTP server tests
the actual client boundary: headers on search and both timetable weeks, no header
on page loads, cached-token rotation, bounded persistent-403 behavior, no retry
for 429/500/page failures, and compatibility with token-free pages.

The transport fixtures supplement the existing independently generated fixed
cryptographic vectors; they do not replace those authentication/decryption tests.

Release version: `1.0.4` / versionCode `14`. The existing `-dontobfuscate`, code
optimization and resource shrinking settings are retained.

## Release verification (2026-11-07)

- Base: main `6e8541df714e310aca16afa102504edb808d3527`.
- All 56 JVM tests (model, Comcigan and NEIS) passed; Release Lint and the
  optimized release build passed.
- The production Kotlin client found `중일고등학교` and parsed 29 lessons for
  2026-11-02 and 34 lessons for 2026-11-09 (grade 1, class 1).
- The signed APK matched the existing production certificate, passed APK
  alignment verification, was non-debuggable and contained the configured NEIS
  key. Private signing/build input files were absent, and the effective R8
  configuration contained `-dontobfuscate`.
- A connected Samsung SM-A336N (Android 16) accepted an in-place update from
  versionCode 13 to 14. The original school and grade 2/class 1 selection
  remained intact.
- On that device, broad `경기` search, exact-name search, an empty-result query,
  grade/class preview, manual refresh of both weeks, and week switching passed.
  The app process remained alive throughout. No new Java/native crash or ANR
  entry appeared in the device's application exit history during verification.

These are point-in-time checks; the server's credential expiry interval was not
measured. Rotation/rejection limits are covered by the local HTTP tests above.
No live token values or signing inputs are recorded in this document.
