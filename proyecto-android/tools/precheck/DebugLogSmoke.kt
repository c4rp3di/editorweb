package com.lab.netlens

fun main() {
    DebugLog.clear()
    DebugLog.log("TEST", "primera linea\nsegunda linea")
    check(DebugLog.count() == 1)
    check(DebugLog.snapshot().contains("[TEST] primera linea segunda linea"))
    repeat(600) { DebugLog.log("TEST", "evento-$it") }
    check(DebugLog.count() == 500) { "El buffer debe conservar como máximo 500 eventos" }
    check(!DebugLog.snapshot().contains("evento-0"))
    check(DebugLog.snapshot().contains("evento-599"))
    DebugLog.clear()
    check(DebugLog.count() == 0)
    check(DebugLog.snapshot().contains("aún no hay eventos"))
    println("PASS — registro de depuración acotado a 500 eventos, sanitiza saltos de línea y permite borrar")
}
