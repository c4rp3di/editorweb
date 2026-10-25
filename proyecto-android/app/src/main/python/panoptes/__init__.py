# Paquete Python embebido via Chaquopy (F5). Cada módulo expone una
# función run(...) que devuelve un str JSON — es el único contrato con el
# lado Kotlin (PythonBridge.kt), para no acoplar tipos Python a Kotlin
# más allá de eso.
