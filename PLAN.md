# Husk

A minimalist Android launcher. Text instead of icons, gestures instead of screens, nothing that phones home.

The name: a husk is the shell left after the thing inside is gone. That is the whole product thesis. The launcher holds your apps and contributes nothing of its own, no feed, no suggestions, no ads, no account.

- Base: fork of [Olauncher](https://github.com/tanujnotes/Olauncher). Upstream is kept as the git remote `upstream`, so their fixes stay pullable.
- applicationId: `com.munjed.husk`. The internal package stays `app.olauncher` on purpose, renaming it would make every upstream merge a conflict and buys nothing a user can see.
- License: **GPL-3.0**, inherited from Olauncher, not chosen. Copyleft, so distributing a build means shipping the source with it. An earlier draft of this plan said MIT, that was wrong and it is the kind of wrong that matters.
- Distribution: APK + F-Droid first, Play Store later (see Risk 1)
- Cost to run: 0. No server, no analytics, no account.

---

## 1. What the MVP is

Five things. If one of them is missing it is not shippable, if a sixth appears it is not MVP.

1. **Home screen.** Clock, date, and up to 6 user-assigned slots rendered as plain text labels. Tap fires the slot.
2. **App drawer.** Swipe up on home. Alphabetical list of launchable activities, with a search field that filters as you type. Tap launches, back closes.
3. **Slot assignment.** Long-press a home slot to open a picker (installed apps + built-in actions). This is the only settings UI in the MVP, config screens can wait until someone asks for one.
4. **Gestures.** Swipe up = drawer, swipe down = notifications, double tap = lock. The last two need an AccessibilityService, so when it is not granted the gesture sends the user to the settings screen that asks for it rather than doing nothing. The launcher stays fully usable without the grant.
5. **Persistence.** Slot config survives reboot and app update. Inherited `data/Prefs.kt` on SharedPreferences, which already does this, so there is nothing to build here.

**Non-goals, explicitly.** Widgets, icon packs, folders, themes, wallpaper picker, work profile, multi-user, notification badges, backup/sync, per-app hiding. Each of these is a real feature and each one doubles the surface area. They come after someone actually uses the thing.

Note: "minimalist" here means minimal code, not just minimal UI. A launcher that is visually spare but carries 40 dependencies has missed the point.

---

## 2. What Android actually lets us do

This section exists because the original ambition was "change the layout of the system", and that ambition is mostly not grantable. Better to write the wall down now than discover it in week three.

We own the home surface. That is the entire sandbox:

| Want | Reachable? | How |
|---|---|---|
| Home screen, drawer, icons, gestures on our own surface | yes | ordinary app code |
| Launch any installed app | yes | `LauncherApps.startMainActivity` |
| List all launchable apps | yes | `LauncherApps.getActivityList`, exempt from `QUERY_ALL_PACKAGES` |
| Lock the screen on double tap | yes | AccessibilityService `GLOBAL_ACTION_LOCK_SCREEN`, or DeviceAdmin `lockNow()` below API 28 |
| **Wake** the screen on double tap | **no** | see below |
| Pull the notification shade | yes | AccessibilityService `GLOBAL_ACTION_NOTIFICATIONS` |
| Read notifications | yes | NotificationListenerService, user grants it |
| Widgets | yes but out of scope | `AppWidgetHost`, heavy |
| Status bar or quick settings **layout** | no | SystemUI process, we can only publish one QS tile |
| Nav bar, recents, lock screen UI | no | SystemUI |
| Restyle or relayout other apps | no | different sandbox entirely |

Everything in the "no" rows requires root plus LSPosed, or a custom ROM. That is a different project with a different order of magnitude of effort, and it is not this one.

Note: on Android 12+ the old reflection trick for `StatusBarManager.expandNotificationsPanel` is blocked. AccessibilityService is the only supported path now, do not waste an afternoon on the reflection version.

Note: double tap to **wake** is not ours to build, and no permission unlocks it. When the screen is off the touch panel stops delivering events to Android at all, so there is no app, service or accessibility grant that can hear the tap. The feature exists on phones that have it because the touchscreen firmware itself watches for the gesture and wakes the CPU, and OEMs expose it as a system toggle (usually Settings > Display, "Double tap to wake", or under gestures). We can point the user at that screen, and that is the entire extent of what we can do. Lock is ours, wake is the hardware's.

---

## 3. Stack, inherited

Forking means taking their stack, not porting it. A rewrite to Compose would be a month of work to reach the feature parity we already have for free, so the stack below is a decision made by accepting it, not by choosing it:

- Kotlin, Android Views + ViewBinding, Fragments, Navigation component. Not Compose.
- minSdk 24, targetSdk 35, compileSdk 35, Java 17, Gradle 8.11.1.
- Dependencies: `core-ktx`, `appcompat`, `recyclerview`, `material`, lifecycle, `navigation-fragment-ktx`, `work-runtime-ktx`.
- Config lives in `data/Prefs.kt` on SharedPreferences. It works, so it stays.

Note: the earlier draft of this plan specified Compose, a single Activity, four dependencies and no `INTERNET` permission. None of that survived the fork. This is the actual cost of forking and it is still cheaper than the alternative.

The MVP work is therefore subtraction, not construction. Delete list, roughly in order:

1. The Olauncher Pro upsell, the rate-us prompt, and every string that asks the user for something.
2. `WallpaperWorker` and wallpaper downloading. This is what pulls in the `INTERNET` permission, and removing the permission entirely is a stronger privacy claim than any promise in a README.
3. `helper/usageStats/` and the `PACKAGE_USAGE_STATS` permission, four files that exist to power a feature the MVP does not have.
4. The 20 shipped translations, down to English. They will be stale the moment strings change, and right now they still say "Olauncher".
5. Whatever remains in `SettingsFragment` that is not slot assignment or the default-launcher prompt.

Nothing gets built until that list is done. The MVP is Olauncher minus the parts we do not want.

---

## 4. Risks, named

1. **Play Store and the AccessibilityService. Highest risk item.** Google requires a declaration for accessibility use, and "we use it to lock the screen" is a recurring rejection reason. Mitigation: ship F-Droid and direct APK first, and keep the accessibility features strictly optional so a Play build can be cut with them compiled out. Do not design anything that assumes the service is granted.
2. **OEM home-button hijacking.** MIUI, EMUI and some Samsung builds break third-party launchers in ways we cannot patch, the button goes back to the stock launcher or the gesture nav fights us. Mitigation: none available. Document it, test on whatever real device is at hand, do not burn days on it.
3. **Cold start.** A launcher that takes 400ms to draw is a launcher people uninstall. Icons are the usual culprit, so the MVP being text-only is partly a performance decision and not only an aesthetic one.
4. **Setting the default launcher.** There is no reliable API to prompt for it across versions. We open the home settings screen and tell the user what to tap, which is ugly and is what every launcher does.

---

## 5. Open questions

1. Six home slots, or unlimited with scroll? Six is a constraint and constraints are the product, but the first power user will complain within a day.
2. Do notifications on the home screen belong in the MVP? It is the single most requested minimalist-launcher feature, and it drags in NotificationListenerService plus a permission prompt.
3. Double tap to lock now works, but the fork carries both paths: AccessibilityService on API 28+, DeviceAdmin below it. minSdk 24 keeps roughly nobody on the DeviceAdmin path. Raise minSdk to 28 and delete the whole DeviceAdmin branch?
4. Is there a real second user for this, or is it a launcher for exactly one phone? The answer changes everything about packaging and release, and nothing about the code.

Question 4 is the one worth answering first. If the honest answer is "one phone", then there is no release to plan and this is a side-load away from done.
