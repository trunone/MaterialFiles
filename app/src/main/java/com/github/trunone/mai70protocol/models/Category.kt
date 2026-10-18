package com.github.trunone.mai70protocol.models

import java.util.Locale

/**
 * 70mai camera file storage categories.
 *
 * Protocol category types:
 * - 0: [NORMAL]
 * - 1: [EVENT]
 * - 2: [PARKING]
 * - 3: [PHOTO]
 * - 4: [UNKNOWN]
 * - 5: [TIME_LAPSE]
 */
enum class Category(val type: Int, val categoryName: String) {
    NORMAL(0, "Normal"),
    EVENT(1, "Event"),
    PARKING(2, "Parking"),
    PHOTO(3, "Photo"),
    UNKNOWN(4, "Unknown"),
    TIME_LAPSE(5, "TimeLapse");

    companion object {
        private val TYPE_MAP = entries.associateBy { it.type }
        private val NAME_MAP = entries.associateBy { it.categoryName.lowercase(Locale.ROOT) }

        /**
         * Resolves a [Category] by its protocol integer type.
         */
        fun fromType(type: Int): Category = TYPE_MAP[type] ?: UNKNOWN

        /**
         * Resolves a [Category] by its name (case-insensitive).
         */
        fun fromName(name: String): Category = NAME_MAP[name.lowercase(Locale.ROOT)] ?: UNKNOWN
    }
}
