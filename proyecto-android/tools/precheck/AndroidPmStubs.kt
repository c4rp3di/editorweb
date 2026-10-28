package android.content.pm
open class PackageManager {
    open fun getPackagesForUid(uid: Int): Array<String>? = null
    open fun getApplicationInfo(pkg: String, flags: Int): ApplicationInfo = ApplicationInfo()
}
class ApplicationInfo { fun loadLabel(pm: PackageManager): CharSequence = "test" }
