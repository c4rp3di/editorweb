package android.net
import android.content.pm.PackageManager
import java.net.DatagramSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.InetSocketAddress
open class VpnService {
    open fun protect(socket: Socket): Boolean = true
    open fun protect(socket: DatagramSocket): Boolean = true
    companion object {
        @JvmField var simulatePrepareIntent: Boolean = false
        @JvmStatic fun prepare(context: Any): Any? = if (simulatePrepareIntent) Any() else null
    }
}
open class ConnectivityManager {
    open fun getConnectionOwnerUid(protocol: Int, local: InetSocketAddress, remote: InetSocketAddress): Int = -1
}
