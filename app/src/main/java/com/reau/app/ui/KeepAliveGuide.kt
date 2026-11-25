package com.reau.app.ui

import androidx.annotation.StringRes
import com.reau.app.R

/**
 * What to do on the phone so the system does not close REau in the middle of a ride, by the phone's maker: the steps are the makers' own settings (a padlock in
 * the recent apps, the app's battery line, the automatic start), reached from Android's standard page of the app's details, so no component of a maker is
 * called. The steps lower the chance and do not guarantee it, and the page says so. No Android in it: tested on the JVM.
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

    /** The family of this phone. */
    fun current(): Brand = brandOf(android.os.Build.MANUFACTURER, android.os.Build.BRAND)

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
