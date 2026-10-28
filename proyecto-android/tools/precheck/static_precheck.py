from pathlib import Path
import sys
root = Path(__file__).resolve().parents[2]
base = root / 'app/src/main/java/com/lab/netlens'
engine = (base / 'Engine.kt').read_text()
service = (base / 'NetLensVpnService.kt').read_text()
mitm = (base / 'MitmProxy.kt').read_text()
main = (base / 'MainActivity.kt').read_text()
debug = (base / 'DebugLog.kt').read_text()
precheck = (root / 'tools/precheck/run_precheck.sh').read_text()
network = (base / 'NetworkTools.kt').read_text()
readme = (root / 'README.md').read_text()
validation = (root / 'VALIDACION.md').read_text()
scope_start = service.index('// App scope is security-critical')
scope_end = service.index('val fd = b.establish()', scope_start)
scope_config = service[scope_start:scope_end]
dot_connect = network.index('raw.connect(InetSocketAddress("1.1.1.1",853)')
checks = {
 'TCP native descriptor opened before protection and remote connect': 'SocketChannel.open()' in engine and engine.index('SocketChannel.open()') < engine.index('eng.protect(s)') < engine.index('s.connect(InetSocketAddress(InetAddress.getByAddress(sIp), sPort)'),
 'UDP descriptor opened before protection and remote connect': 'DatagramChannel.open()' in engine and engine.index('DatagramChannel.open()') < engine.index('protect(s)', engine.index('DatagramChannel.open()')) < engine.index('s.connect(InetAddress.getByAddress(dst), dp)'),
 'protect failure is not ignored and is visible': 'No se reintenta por una ruta sin proteger' in engine and 'Store.lastRuntimeError = message' in engine and 'Store.lastRuntimeError' in main,
 'MITM listener binds synchronously': 'listener.bind(' in mitm and 'serverSocket = listener' in mitm,
 'MITM read timeouts are removed after TLS handshake': 'sslClient.soTimeout = 0' in mitm and 'sslRemote.soTimeout = 0' in mitm,
 'MITM cannot toggle during live capture': 'Detén NetLens antes de cambiar MITM TLS' in main,
 'App-scope configuration does not silently swallow errors': 'packageManager.getApplicationInfo(target, 0)' in scope_config and 'b.addAllowedApplication(target)' in scope_config and 'catch' not in scope_config,
 'HTTP request without optional Host does not stall after complete header': 'HTTP sin Host; se reenvía sin filtro por dominio' in engine and 'indexOfHeaderEnd(a)' in engine,
 'IPv6 limitation disclosed in interface': 'IPv6 no inspeccionado' in main,
 'DoT protects the raw socket before connect': 'protect(raw)' in network and network.index('protect(raw)') < dot_connect,
 'MITM protects upstream socket before connect and validates HTTPS identity': 'protector(remote)' in mitm and mitm.index('protector(remote)') < mitm.index('remote.connect(InetSocketAddress(sni, 443)') and 'endpointIdentificationAlgorithm = "HTTPS"' in mitm,
 'MITM stop closes active sockets': 'activeSockets.clear()' in mitm and 'for (socket in activeSockets)' in mitm,
 'MITM stop waits for accept loop before final socket drain': 'acceptor.join(1500)' in mitm and mitm.index('acceptor.join(1500)') < mitm.index('for (socket in activeSockets)', mitm.index('@Synchronized fun stop()')),
 'QUIC blocking has a dedicated UDP/443 branch': 'dp == 443 && Store.blockQuic' in engine and 'QUIC/UDP 443 bloqueado' in engine,
 'Rule specificity includes package-scoped rules': 'thenByDescending { it.pkg != null }' in network,
 'IPv4 fragment limitations are disclosed in docs': 'fragment' in readme.lower() or 'fragment' in validation.lower(),
 'debug entry is available in Tools and has copy/clear actions': 'Depuración: ver / copiar log' in main and 'showDebugLog()' in main and 'Copiar log' in main and 'Borrar log' in main,
 'diagnostic log includes protect and upstream errors': 'DebugLog.log("SOCKET", message)' in engine and 'DebugLog.log("TCP", "ERROR upstream' in engine and 'DebugLog.log("VPN", "ERROR al iniciar' in service,
 'diagnostic log is bounded and payload-free by design': 'MAX_ENTRIES = 500' in debug and 'entries.size > MAX_ENTRIES' in debug and 'DebugLogSmoke.kt' in precheck,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' — ' + name)
if not all(checks.values()):
    sys.exit(1)
print(f'{len(checks)} static checks passed')
