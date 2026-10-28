package android.content
import java.io.File
interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getLong(key: String, defValue: Long): Long
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putLong(key: String, value: Long): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun apply()
    }
}
open class Context {
    open val applicationContext: Context get() = this
    open val filesDir: File get() = File(System.getProperty("java.io.tmpdir"))
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = throw UnsupportedOperationException()
}
