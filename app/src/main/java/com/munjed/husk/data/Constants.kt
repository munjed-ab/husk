package com.munjed.husk.data

object Constants {

    object Key {
        const val FLAG = "flag"
        const val RENAME = "rename"
    }

    object Dialog {
        const val ABOUT = "ABOUT"
        const val HIDDEN = "HIDDEN"
        const val KEYBOARD = "KEYBOARD"
        const val DIGITAL_WELLBEING = "DIGITAL_WELLBEING"
    }

    object UserState {
        const val START = "START"
    }

    object DateTime {
        const val OFF = 0
        const val ON = 1
        const val DATE_ONLY = 2

        fun isTimeVisible(dateTimeVisibility: Int): Boolean {
            return dateTimeVisibility == ON
        }

        fun isDateVisible(dateTimeVisibility: Int): Boolean {
            return dateTimeVisibility == ON || dateTimeVisibility == DATE_ONLY
        }
    }

    const val RECENT_APPS_COUNT = 4
    const val LOW_BATTERY_PERCENT = 15

    // Substack category ids, from https://substack.com/api/v1/categories
    object Topic {
        const val TECHNOLOGY = 4
        const val PHILOSOPHY = 114
        const val SCIENCE = 134
        const val BUSINESS = 62
        const val CULTURE = 96
        const val HEALTH = 355
        const val LITERATURE = 339
        const val FAITH = 223
    }

    object Orientation {
        const val AUTO = 0
        const val PORTRAIT = 1
        const val LANDSCAPE = 2
    }

    object SwipeDownAction {
        const val SEARCH = 1
        const val NOTIFICATIONS = 2
        const val DIAL = 3
    }

    object HomeBackground {
        const val WALLPAPER = 0
        const val COLOR = 1
        const val GRADIENT = 2
        const val IMAGE = 3
    }

    object CharacterIndicator {
        const val SHOW = 102
        const val HIDE = 101
    }

    val CLOCK_APP_PACKAGES = arrayOf(
        "com.google.android.deskclock", //Google Clock
        "com.sec.android.app.clockpackage", //Samsung Clock
        "com.oneplus.deskclock", //OnePlus Clock
        "com.miui.clock", //Xiaomi Clock
    )


//    const val THEME_MODE_DARK = 0
//    const val THEME_MODE_LIGHT = 1
//    const val THEME_MODE_SYSTEM = 2

    const val FLAG_LAUNCH_APP = 100
    const val FLAG_HIDDEN_APPS = 101
    const val FLAG_BLOCKED_APPS = 102

    const val FLAG_SET_HOME_APP_1 = 1
    const val FLAG_SET_HOME_APP_2 = 2
    const val FLAG_SET_HOME_APP_3 = 3
    const val FLAG_SET_HOME_APP_4 = 4
    const val FLAG_SET_HOME_APP_5 = 5
    const val FLAG_SET_HOME_APP_6 = 6
    const val FLAG_SET_HOME_APP_7 = 7
    const val FLAG_SET_HOME_APP_8 = 8

    const val FLAG_SET_SWIPE_LEFT_APP = 11
    const val FLAG_SET_SWIPE_RIGHT_APP = 12
    const val FLAG_SET_CLOCK_APP = 13
    const val FLAG_SET_CALENDAR_APP = 14
    const val FLAG_SET_SCREEN_TIME_APP = 15

    const val REQUEST_CODE_ENABLE_ADMIN = 666
    const val REQUEST_CODE_LAUNCHER_SELECTOR = 678

    const val HINT_RATE_US = 15

    const val LONG_PRESS_DELAY_MS = 500L
    const val ONE_DAY_IN_MILLIS = 86400000L
    const val ONE_HOUR_IN_MILLIS = 3600000L
    const val ONE_MINUTE_IN_MILLIS = 60000L

    const val MIN_ANIM_REFRESH_RATE = 30f

    const val URL_ABOUT = "https://github.com/munjed-ab/husk#features"
    const val URL_PRIVACY = "https://github.com/munjed-ab/husk/blob/master/PRIVACY.md"
    const val URL_DOUBLE_TAP = "https://github.com/munjed-ab/husk#double-tap-to-lock-does-nothing"
    const val URL_GITHUB = "https://github.com/munjed-ab/husk"
    // upstream project Husk is forked from, kept for the GPL attribution in About
    const val URL_UPSTREAM = "https://github.com/tanujnotes/Olauncher"
    const val URL_DUCK_SEARCH = "https://duck.co/?q="

    const val DIGITAL_WELLBEING_PACKAGE_NAME = "com.google.android.apps.wellbeing"
    const val DIGITAL_WELLBEING_ACTIVITY = "com.google.android.apps.wellbeing.settings.TopLevelSettingsActivity"
    const val DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME = "com.samsung.android.forest"
    const val DIGITAL_WELLBEING_SAMSUNG_ACTIVITY = "com.samsung.android.forest.launcher.LauncherActivity"
    const val READING_WORKER_NAME = "READING_WORKER_NAME"
}