package app.olauncher.helper

/**
 * Name matching for the dialer. Pure functions with no Android types, so they can be unit tested
 * on the JVM, which is where the fiddly bit (Arabic folding) actually lives.
 */

private val HARAKAT = Regex("[ً-ٰٟ]")

/** Lowercased, harakat stripped, alef and ya variants folded, so spelling variants still match. */
fun String.normalizeName(): String = lowercase()
    .replace(HARAKAT, "")
    .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
    .replace('ة', 'ه')
    .replace('ى', 'ي')
    .replace('ؤ', 'و')
    .replace('ئ', 'ي')

/**
 * True when [query] matches the contact by name or by phone number. [name] and [query] are
 * normalized here; [number] may carry any formatting.
 *
 * ponytail: no T9 spelling. Digits match numbers only, because "626" silently matching the middle
 * of "Abdulrahman" reads as a bug, not a feature.
 */
fun contactMatches(name: String, number: String, query: String): Boolean {
    val q = query.trim().normalizeName()
    if (q.isEmpty()) return true
    if (name.normalizeName().contains(q)) return true
    val qDigits = q.filter { it.isDigit() }
    return qDigits.isNotEmpty() && number.filter { it.isDigit() }.contains(qDigits)
}
