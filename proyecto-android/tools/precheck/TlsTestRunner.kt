package com.lab.netlens
fun main() {
    val t = DpiTlsTest()
    t.parsesSniAlpnAndTls13()
    t.incompleteTcpPayloadWaitsForMoreBytes()
    t.handlesClientHelloSplitAcrossTlsRecords()
    t.detectsEchExtensionWithoutClaimingInnerHostnameIsVisible()
    t.malformedInputsNeverCrashParser()
    println("PASS — 5 DpiTlsTest cases (SNI/ALPN/TLS 1.3, partial ClientHello, split records, ECH, fuzz)")
}
