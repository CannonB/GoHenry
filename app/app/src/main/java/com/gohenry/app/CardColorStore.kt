package com.gohenry.app

import android.content.Context

/**
 * Local, per-Ford-slug carousel card color chosen by the user on the Settings
 * screen. Stored as a packed ARGB int in SharedPreferences (no backend, no SQL).
 * Defaults to [DEFAULT_CARD_COLOR] (a dark grey) for any slug never customized,
 * so cards start neutral until the user picks a color.
 */
class CardColorStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The stored color for [slug], or the dark-grey default when unset. */
    fun colorFor(slug: String): Int =
        prefs.getInt(key(slug), DEFAULT_CARD_COLOR)

    /** Persists [color] (packed ARGB) as the card color for [slug]. */
    fun setColor(slug: String, color: Int) {
        prefs.edit().putInt(key(slug), color).apply()
    }

    /** Snapshot of every customized slug → color (defaults are applied at read). */
    fun all(): Map<String, Int> =
        prefs.all.entries
            .filter { it.key.startsWith(KEY_PREFIX) && it.value is Int }
            .associate { it.key.removePrefix(KEY_PREFIX) to (it.value as Int) }

    private fun key(slug: String) = KEY_PREFIX + slug.trim().lowercase()

    companion object {
        private const val PREFS = "gohenry_card_colors"
        private const val KEY_PREFIX = "card_color_"
        /** Default card color: a dark grey (opaque). */
        const val DEFAULT_CARD_COLOR: Int = 0xFF3A3A3A.toInt()
    }
}
