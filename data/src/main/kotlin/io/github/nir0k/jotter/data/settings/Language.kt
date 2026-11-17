package io.github.nir0k.jotter.data.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList

// The interface language is the platform's per-application language: the application's own
// setting and the system's settings of the application change the same value, which the platform
// keeps and applies to the activities.

/** Returns the chosen interface language as its language code, or null when it follows the system. */
fun Context.interfaceLanguage(): String? =
    getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }?.get(0)?.language

/**
 * Chooses the interface language as the per-application language.
 *
 * @param tag BCP 47 language tag, or null to follow the system.
 */
fun Context.setInterfaceLanguage(tag: String?) {
    val locales = if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    val manager = getSystemService(LocaleManager::class.java)
    if (manager.applicationLocales != locales) manager.applicationLocales = locales
}

/**
 * Returns a context whose resources speak the language chosen in the application, for text made
 * outside an activity: notifications and the titles of conflict copies.
 */
fun Context.inAppLanguage(): Context {
    val locales = getSystemService(LocaleManager::class.java).applicationLocales
    if (locales.isEmpty) return this
    val configuration = Configuration(resources.configuration).apply { setLocales(locales) }
    return createConfigurationContext(configuration)
}
