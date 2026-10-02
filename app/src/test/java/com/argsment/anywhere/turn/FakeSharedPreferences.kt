package com.argsment.anywhere.turn

import android.content.SharedPreferences

/** In-memory SharedPreferences for JVM tests; `apply()` and `commit()` write through. */
class FakeSharedPreferences : SharedPreferences {
    val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?): String? =
        if (values.containsKey(key)) values[key] as String? else defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        if (values.containsKey(key)) values[key] as MutableSet<String>? else defValues
    override fun getInt(key: String, defValue: Int): Int = (values[key] as Int?) ?: defValue
    override fun getLong(key: String, defValue: Long): Long = (values[key] as Long?) ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = (values[key] as Float?) ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = (values[key] as Boolean?) ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class Editor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = this.also { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = this.also { pending[key] = values }
        override fun putInt(key: String, value: Int) = this.also { pending[key] = value }
        override fun putLong(key: String, value: Long) = this.also { pending[key] = value }
        override fun putFloat(key: String, value: Float) = this.also { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = this.also { pending[key] = value }
        override fun remove(key: String) = this.also { removed += key }
        override fun clear() = this.also { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) values.clear()
            removed.forEach { values.remove(it) }
            values.putAll(pending)
        }
    }
}
