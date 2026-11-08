# Published timetable periods and calendar weeks

## Reproduction

The [2026-11-08 Parser Health job](https://github.com/mchany/comcigan-neo/actions/runs/42440899969/job/128609709928)
completed school search, then failed at the client's week-start equality guard.
On that Sunday the calendar helper expected 2026-11-02 / 2026-11-09, but the
authenticated server responses contained 2026-11-09 / 2026-11-16. Both responses
decrypted and parsed successfully: grade 1/class 1 of the test school had 34 and
21 current lessons respectively. A mismatched date was incorrectly reported as
an unpublished timetable.

The official page populates its period selector from `일자자료`. The observed
catalog was `[[1,"26-11-09 ~ 26-11-14"],[2,"26-11-16 ~ 26-11-21"]]` with `오늘r=1`.
Its school-selection function initially requests selector 1; subsequent selections
use each catalog entry's selector, not a locally calculated calendar-week number.
The server's exact weekly rollover time has not been measured.

## Behavior

- Load selector 1 as the official page does and read the fresh period catalog.
  Pick the first two published periods on or after the local calendar week's
  Monday, ordered by date. The existing “이번 주” / “다음 주” controls select these
  first and second published periods. The existing grid headers display the
  actual response dates. UI components, labels and layout are unchanged. If a
  second period is absent the existing unpublished message is shown.
- Request the selected entry's actual identifier, including nonconsecutive
  identifiers. Reuse the initial response when it already represents that entry;
  otherwise make one additional timetable request. Do not cache routing metadata
  separately from fresh responses, so server rollover can be discovered on refresh.
- Validate date ranges, unique positive identifiers, unique Monday start dates,
  the response's membership in the catalog and the optional `오늘r` reference.
  Malformed metadata is a format error. Responses without this metadata retain
  the earlier calendar-week compatibility path.
- Keep the response-date guard. A response for another period or a Seoul date
  change during the request refreshes service information and retries at most
  once, then reports a period-change error, not an unpublished timetable.
- Persist optional catalog metadata in snapshot JSON. Read the newest catalog
  for the selected class to resolve both cache slots, then select the snapshot
  with the exact matching start date. Old JSON without metadata remains readable;
  there is no Room schema change or destructive migration.
- Use the same period resolution for foreground refresh and cache.
  Cache freshness also expires at Seoul midnight, even inside five minutes.
  The shared calendar `weekStart()` and meal date rules are unchanged. Widgets
  and daily notifications still select lessons by the actual lesson date.
- Keep request tokens, authenticated enc5 decoding, legacy compatibility,
  `-dontobfuscate`, code optimization and resource shrinking enabled as before.

## Verification

Regression coverage includes Sunday and Monday with independent fixed date
fixtures, nonconsecutive identifiers, unordered catalogs, missing second periods,
wrong-period responses and bounded retry, malformed metadata, year/century
boundaries, cache remapping across catalog changes, legacy JSON, serialization,
and midnight freshness invalidation. Existing protocol and crypto tests remain.

Release build: `1.0.5` / versionCode `15`, based on main
`b6f801f34400e4819e822bd721a9d743a25a73b5`. The higher code allows an in-place
update over the prior 1.0.4/code-14 APK.

### Release checks (2026-11-08)

- 67 JVM tests passed. Release Lint reported 0 errors (4 existing warnings).
  Updated Android smoke tests compiled with the isolated `emulatorSmoke` setting;
  production release assembly used the normal application ID.
- The final APK passed the original release-certificate check, alignment,
  non-debuggable/package/version checks and embedded NEIS-key verification.
  Private build/signing input files were excluded; effective R8 configuration
  retained `-dontobfuscate`.
- On Samsung SM-A336N / Android 16, in-place installation preserved the user's
  school/class settings. Broad/exact/empty school searches, class preview,
  refresh of both published periods and week switching passed.
- Cold restart restored the catalog and the correct cached period. No new
  Java/native crash or ANR appeared in application exit history during final
  verification. The UI source directory has no changes from the base commit;
  the original controls and layout were also checked on the device.
- Final signed APK SHA-256:
  `7ceacb131c023b249f3e154f34bd7dfeb9396d6c2a58636ccdbb40753b635bff`.

No live request tokens, signing credentials or private build inputs are recorded.
