# Privacy Policy

Husk collects nothing. There is no analytics SDK, no crash reporter, no telemetry, no account, and
no server operated by me. You can verify this: the dependency list in
[app/build.gradle](app/build.gradle) has no Firebase, no Google Play Services, and nothing that
phones home.

This document says what the app touches and where it goes, because "we respect your privacy" is
worth nothing without the list.

## What stays on your device

All of it. Specifically:

1. **Contacts and call log.** Read by the dialer to show and search your contacts. Read at the
   moment you open the dialer, never copied, never uploaded, never cached to disk.
2. **Notifications.** Read by the notification listener to show the line on the home screen and the
   panel. Held in memory only, and only while the notification is active.
3. **App usage statistics.** Read to show your screen time and to order recently launched apps. The
   numbers are computed on device and never leave it.
4. **Your settings, hidden apps, renamed apps, and blocked apps list.** Stored in Android's private
   `SharedPreferences` for this app, which no other app can read.
5. **Accessibility service.** Used for one thing, the double tap to lock gesture. It is optional and
   off by default. It reads no screen content and stores nothing.

## What leaves your device

Two features make network requests. Both are optional and both are off until you turn them on.

### Reading list

If you pick at least one topic, Husk requests:

1. `https://substack.com/api/v1/category/public/{id}/all` for the public topic leaderboard.
2. The public RSS feed of each publication it picks from that leaderboard.

These requests carry no account, no identifier and no personal data. They do carry a user agent
string, `Mozilla/5.0 (Linux; Android) Husk`, and your IP address, which is unavoidable for any HTTP
request. Substack is a third party with its own privacy policy, and this fork has no control over
what they log.

Note: this is the reason an F-Droid listing for Husk carries the NonFreeNet flag. Turn the reading
list off and no request is ever made.

### Per-app internet blocking

This one reads worse than it is, so read carefully.

Blocking apps runs a local `VpnService`. Android shows the VPN key icon and warns you that the app
"can monitor network traffic", because that is what the VPN API grants in general. Husk does not
use it that way. The service accepts the traffic of blocked apps and drops it on the floor. It has
no remote endpoint, it does not inspect payloads, it does not log destinations, and there is no
server for the traffic to reach even if it wanted one. The relevant code is one file,
[BlockerVpnService.kt](app/src/main/java/com/munjed/husk/helper/BlockerVpnService.kt), and it is
short enough to read in full.

Note: Android permits exactly one active VPN. While blocking is on, a real VPN cannot run.

## What is never collected

No advertising ID. No location. No message content. No browsing history. No contact upload. No
crash dumps. Nothing is sold, because there is nothing collected to sell.

## Changes

This policy lives in the repository. Its history is the changelog, and `git log PRIVACY.md` is the
only version of it that cannot be quietly rewritten.

## Contact

Open an issue at [github.com/munjed-ab/husk](https://github.com/munjed-ab/husk/issues).
