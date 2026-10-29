package org.nododeverdad.app

/** Contenido educativo: glosario en dos registros, preguntas y retos. */
object Learn {

    class Term(val name: String, val t15: String, val t70: String)

    val glossary: List<Term> = listOf(
        Term("Rastreador",
            "Un trocito de código dentro de una app que apunta lo que haces y lo manda a otra empresa.",
            "Un programa escondido en una aplicación que anota lo que usted hace y lo envía a una empresa."),
        Term("DNS",
            "La agenda de internet: tu móvil pregunta «¿dónde vive esta web?» antes de conectarse.",
            "El servicio que traduce el nombre de una página en la dirección donde está. El móvil lo consulta antes de cada conexión."),
        Term("ID de publicidad",
            "Un número de tu móvil que los anunciantes usan para reconocerte entre apps. Puedes borrarlo.",
            "Un código de su teléfono que sirve a los anunciantes para identificarle entre distintas aplicaciones. Puede eliminarlo en Ajustes."),
        Term("Beneficiario final",
            "La persona real que, al final de una cadena de empresas, se queda con los beneficios o manda.",
            "La persona que de verdad se beneficia o decide, aunque su nombre no aparezca en la empresa."),
        Term("Matriz y filial",
            "La empresa «madre» y sus «hijas».",
            "La empresa que controla a otras (matriz) y las controladas (filiales)."),
        Term("EEE",
            "El Espacio Económico Europeo: la UE más Noruega, Islandia y Liechtenstein. Allí rige el RGPD.",
            "Los países de la Unión Europea más Noruega, Islandia y Liechtenstein. En ellos se aplica la ley europea de protección de datos."),
        Term("RGPD",
            "La ley europea que dice que tus datos son tuyos y puedes pedir verlos o borrarlos.",
            "La ley europea de protección de datos personales."),
        Term("LEI",
            "El DNI de una empresa grande, único en el mundo.",
            "Un código único que identifica a una empresa en todo el mundo."),
        Term("Paraíso fiscal",
            "Un lugar donde pagar impuestos es muy barato y se pregunta poco. No hay una lista oficial única.",
            "Un lugar donde las empresas pagan muy pocos impuestos y hay poca información pública. No existe una definición legal única."),
        Term("Sociedad pantalla",
            "Una empresa creada solo para esconder quién hay detrás de otra (aunque a veces tiene usos legítimos).",
            "Una empresa que existe sobre todo para que no se vea quién es el verdadero dueño. Algunas se usan por motivos legítimos."),
        Term("Metadatos",
            "Datos sobre los datos: a quién llamas y cuándo, aunque no se sepa qué dices.",
            "Información sobre una comunicación (con quién, cuándo, cuánto), no sobre su contenido."),
        Term("VPN local",
            "Un truco del móvil para que una app vea por dónde salen las conexiones, sin mandarlas fuera.",
            "Una función del teléfono que permite a una aplicación revisar las conexiones sin sacarlas del aparato."),
        Term("Falso positivo",
            "Una alarma que suena sin que haya incendio.",
            "Una señal de alerta que resulta no ser un problema real."),
        Term("Derecho de réplica",
            "Si te nombran, puedes contestar y que se vea tu respuesta.",
            "Derecho de quien es nombrado a responder públicamente.")
    )

    class Question(val q: String, val options: List<String>, val correct: Int, val why: String)

    val quiz: List<Question> = listOf(
        Question(
            "¿Qué ve exactamente este radar de lo que haces en tu móvil?",
            listOf(
                "Todo: mensajes, fotos y contraseñas",
                "Solo los nombres de los servidores a los que se conectan tus apps",
                "Tu ubicación en tiempo real"
            ), 1,
            "El radar mira solo las consultas DNS: el nombre del servidor, nunca el contenido de lo que envías o recibes."
        ),
        Question(
            "Una empresa aparece en una investigación pública. ¿Qué significa?",
            listOf(
                "Que ha cometido un delito",
                "Que existe un documento que la menciona; por sí solo no prueba una irregularidad",
                "Que hay que desinstalar su app"
            ), 1,
            "Aparecer en un registro o investigación no equivale a culpabilidad. Por eso cada dato lleva su fuente y su contexto."
        ),
        Question(
            "¿Para qué sirve el «ID de publicidad» de Android?",
            listOf(
                "Para que los anunciantes te reconozcan entre distintas apps",
                "Para desbloquear el móvil",
                "Para identificar tu tarjeta SIM"
            ), 0,
            "Es un identificador que puedes restablecer o eliminar en Ajustes → Privacidad → Anuncios."
        ),
        Question(
            "¿Qué es el EEE?",
            listOf(
                "Una empresa de publicidad",
                "El Espacio Económico Europeo: la UE más Noruega, Islandia y Liechtenstein",
                "Un tipo de cifrado"
            ), 1,
            "Dentro del EEE se aplica el RGPD. Si una empresa tiene la sede fuera, sus datos pueden regirse por otras leyes."
        ),
        Question(
            "¿Qué hace un rastreador de «atribución»?",
            listOf(
                "Mide de qué anuncio vino una instalación",
                "Cifra tus mensajes",
                "Bloquea los virus"
            ), 0,
            "Sirve a los anunciantes para saber qué campaña funcionó. Es habitual y no siempre implica seguirte a ti."
        ),
        Question(
            "Una app tiene muchos rastreadores. ¿Cuál es la mejor primera medida?",
            listOf(
                "Revisar sus permisos y valorar si la necesitas o hay una alternativa",
                "Apagar el móvil",
                "Nada: es imposible hacer algo"
            ), 0,
            "Revisar permisos, restablecer el ID de publicidad y elegir alternativas más respetuosas son medidas al alcance de cualquiera."
        )
    )

    class Challenge(val id: String, val title: String, val detail: String)

    val challenges: List<Challenge> = listOf(
        Challenge("ad_id", "Restablece tu ID de publicidad", "Ajustes → Privacidad → Anuncios. Cambia el número con el que te reconocen los anunciantes."),
        Challenge("perms", "Revisa los permisos de tu app más «habladora»", "Abre su ficha desde el Radar y quita lo que no necesite."),
        Challenge("parent", "Descubre quién es la matriz de tu empresa nº 1", "Entra en «Quién» y abre su ficha. Mira dónde tiene la sede."),
        Challenge("share", "Comparte tu radar con alguien", "Cuéntale a una persona cercana qué has descubrido."),
        Challenge("unused", "Desinstala una app que ya no uses", "Menos apps, menos empresas que reciben datos.")
    )
}
