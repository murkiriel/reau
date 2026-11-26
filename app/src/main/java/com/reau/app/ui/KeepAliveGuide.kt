package com.reau.app.ui

import androidx.annotation.StringRes
import com.reau.app.R
import com.reau.app.data.KeepAliveChecklist.Item

/**
 * What to do on the phone so the system does not close REau in the middle of a ride, by the phone's maker: the steps are the makers' own settings (a padlock in
 * the recent apps, the app's battery line, the automatic start), reached from Android's standard page of the app's details, so no component of a maker is
 * called. The steps lower the chance and do not guarantee it, and the page says so. No Android in it: tested on the JVM.
 *
 * Since 26/11/2026 (A3 of the Xiaomi and realme plan) the page also has what the rider says he did ([items]) and, on Xiaomi, a button to the Autostart screen
 * ([KeepAliveOpen]: the components of the maker are there, kept out of this file).
 */
object KeepAliveGuide {
    /** The families of makers that have their own steps; any other maker gets the general ones. */
    enum class Brand { XIAOMI, OPPO, OTHER }

    private val XIAOMI = setOf("xiaomi", "redmi", "poco")
    private val OPPO = setOf("oppo", "realme", "oneplus")

    /**
     * The family of the phone from `Build.MANUFACTURER` and `Build.BRAND` (either one naming it is enough: a POCO phone has the maker Xiaomi and the brand
     * POCO). Case and spaces around do not matter; null or empty is "other".
     */
    fun brandOf(manufacturer: String?, brand: String?): Brand {
        val names = listOf(manufacturer, brand).map { it.orEmpty().trim().lowercase() }
        return when {
            names.any { it in XIAOMI } -> Brand.XIAOMI
            names.any { it in OPPO } -> Brand.OPPO
            else -> Brand.OTHER
        }
    }

    /** Whether the maker's family has a notice of its own before the first ride (the two with steps of their own; any other phone is left as it was). */
    fun hasNotice(brand: Brand): Boolean = brand != Brand.OTHER

    /** What the rider may tick for the family, in the order of the steps; empty for another maker (it has no list). */
    fun items(brand: Brand): List<Item> = when (brand) {
        Brand.XIAOMI -> listOf(Item.AUTOSTART, Item.BATTERY, Item.LOCK)
        Brand.OPPO -> listOf(Item.AUTOSTART, Item.BACKGROUND, Item.LOCK)
        Brand.OTHER -> emptyList()
    }

    /** The words of one tick. */
    @StringRes
    fun label(item: Item): Int = when (item) {
        Item.AUTOSTART -> R.string.keepalive_item_autostart
        Item.BACKGROUND -> R.string.keepalive_item_background
        Item.LOCK -> R.string.keepalive_item_lock
        Item.BATTERY -> R.string.keepalive_item_battery
    }

    /**
     * What the page says of the battery optimization, by whether REau is exempt (null: Android did not say, which reads as not exempt): the exemption is the one
     * thing the app asks for itself, so a rider who has it is told that the maker's steps are still his, and one who does not is told to do that first.
     */
    @StringRes
    fun batteryAdvice(exempt: Boolean?): Int =
        if (exempt == true) R.string.keepalive_battery_exempt else R.string.keepalive_battery_not_exempt

    /**
     * The family to use: the faked maker when there is one ([faked], from the debug build's `BrandFake`; always null in a release), else the phone's own. The one place
     * the fake enters, so a release can only read the real brand (26/11/2026: the owner's phone is a Motorola, and he wanted to see the card and the page of a Xiaomi).
     */
    fun brandFor(manufacturer: String?, brand: String?, faked: String?): Brand =
        if (faked != null) brandOf(faked, faked) else brandOf(manufacturer, brand)

    /** The family of this phone (or the faked one, in a debug build). */
    fun current(): Brand = brandFor(android.os.Build.MANUFACTURER, android.os.Build.BRAND, com.reau.app.devtools.BrandFake.maker())

    /** The name the page gives the family above its steps. */
    @StringRes
    fun title(brand: Brand): Int = when (brand) {
        Brand.XIAOMI -> R.string.keepalive_brand_xiaomi
        Brand.OPPO -> R.string.keepalive_brand_oppo
        Brand.OTHER -> R.string.keepalive_brand_other
    }

    /** The texts of the family's steps, in the order to do them; the last of Xiaomi's is optional and says so. */
    fun steps(brand: Brand): List<Int> = when (brand) {
        Brand.XIAOMI -> listOf(
            R.string.keepalive_step_lock, R.string.keepalive_step_xiaomi_battery, R.string.keepalive_step_autostart, R.string.keepalive_step_xiaomi_performance,
        )
        Brand.OPPO -> listOf(R.string.keepalive_step_lock, R.string.keepalive_step_oppo_battery, R.string.keepalive_step_oppo_autostart)
        Brand.OTHER -> listOf(R.string.keepalive_step_other_battery, R.string.keepalive_step_other_lock)
    }
}
