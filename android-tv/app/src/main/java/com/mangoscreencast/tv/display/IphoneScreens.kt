package com.mangoscreencast.tv.display

/** The screens of iPhones, by the model identifier they send when they connect ("iPhone17,2"). */
object IphoneScreens {
    /** Native pixels held upright: (width, height). From the iPhone 8 and X on. */
    private val SIZES: Map<String, Pair<Int, Int>> = buildMap {
        fun add(size: Pair<Int, Int>, vararg models: String) = models.forEach { put(it, size) }
        add(750 to 1334, "iPhone10,1", "iPhone10,4", "iPhone12,8", "iPhone14,6") // 8, SE (2nd and 3rd)
        add(1080 to 1920, "iPhone10,2", "iPhone10,5") // 8 Plus
        add(1125 to 2436, "iPhone10,3", "iPhone10,6", "iPhone11,2", "iPhone12,3") // X, XS, 11 Pro
        add(1242 to 2688, "iPhone11,4", "iPhone11,6", "iPhone12,5") // XS Max, 11 Pro Max
        add(828 to 1792, "iPhone11,8", "iPhone12,1") // XR, 11
        add(1080 to 2340, "iPhone13,1", "iPhone14,4") // 12 mini, 13 mini
        // 12, 12 Pro, 13 Pro, 13, 14, 16e
        add(1170 to 2532, "iPhone13,2", "iPhone13,3", "iPhone14,2", "iPhone14,5", "iPhone14,7", "iPhone17,5")
        add(1284 to 2778, "iPhone13,4", "iPhone14,3", "iPhone14,8") // 12 Pro Max, 13 Pro Max, 14 Plus
        add(1179 to 2556, "iPhone15,2", "iPhone15,4", "iPhone16,1", "iPhone17,3") // 14 Pro, 15, 15 Pro, 16
        add(1290 to 2796, "iPhone15,3", "iPhone15,5", "iPhone16,2", "iPhone17,4") // 14 Pro Max, 15 Plus, 15 Pro Max, 16 Plus
        add(1206 to 2622, "iPhone17,1", "iPhone18,1", "iPhone18,3") // 16 Pro, 17 Pro, 17
        add(1320 to 2868, "iPhone17,2", "iPhone18,2") // 16 Pro Max, 17 Pro Max
        add(1260 to 2736, "iPhone18,4") // Air
    }

    /** The largest screen in the list, for iPhones it doesn't know yet. */
    private val LARGEST = SIZES.values.maxBy { it.second }

    fun isIphone(model: String): Boolean = model.startsWith("iPhone")

    /**
     * `model`'s screen held upright, (width, height). An iPhone the list
     * doesn't know (one newer than it) gets the largest screen in it, so it
     * is never offered less than its own; one older than the iPhone 8 gets null.
     */
    fun nativeSize(model: String): Pair<Int, Int>? {
        SIZES[model]?.let { return it }
        val family = family(model) ?: return null
        return if (family >= 10) LARGEST else null
    }

    /** 17 for "iPhone17,2". */
    private fun family(model: String): Int? =
        if (isIphone(model)) model.removePrefix("iPhone").substringBefore(',').toIntOrNull() else null
}
