package org.nododeverdad.app

/**
 * Base de conocimiento incluida en la app (lista curada v1).
 *
 * - Relaciona dominios de rastreo con la empresa que los opera.
 * - Solo recoge hechos públicos y ampliamente documentados (sede, matriz, operaciones
 *   corporativas y resoluciones de autoridades), con año y fuente.
 * - NO afirma delitos. Aparecer aquí no implica ilegalidad.
 * - Puede contener errores u omisiones: verifica siempre en la fuente (GLEIF, registros
 *   mercantiles, resoluciones de las autoridades de protección de datos).
 */

enum class Cat(
    val key: String,
    val label: String,
    val plain: String,
    val attr: Int,
    val analogy15: String,
    val plain70: String,
    val export: String
) {
    PUB(
        "PUB", "Publicidad", "Te perfilan para enseñarte anuncios", R.attr.nvPub,
        "Es como un vendedor que apunta lo que miras en cada tienda para luego ofrecerte cosas.",
        "Su negocio es mostrar anuncios. Para ello anota sus intereses a partir de las aplicaciones que usted utiliza.",
        "advertising"
    ),
    ANA(
        "ANA", "Analítica", "Miden cómo usas la app", R.attr.nvAna,
        "Es como alguien que cuenta cuántas veces entras a una tienda y por qué pasillos andas.",
        "Mide cómo se usa la aplicación: qué pantallas se abren y con qué frecuencia.",
        "analytics"
    ),
    ATR(
        "ATR", "Atribución", "Miden de qué anuncio vino una instalación", R.attr.nvAtr,
        "Es como apuntar de qué folleto vino cada cliente: sirve para saber qué anuncio funcionó.",
        "Sirve para saber qué anuncio le llevó a instalar la aplicación.",
        "attribution"
    ),
    SOC(
        "SOC", "Redes sociales", "Conectan la app con una red social", R.attr.nvSoc,
        "Es como un amigo al que la app le cuenta cosas que haces, para conectarte con tu red social.",
        "Conecta la aplicación con una red social y puede informarle de su actividad.",
        "social"
    ),
    CRA(
        "CRA", "Fallos", "Avisan cuando la app se rompe", R.attr.nvCra,
        "Es como el mecánico que recibe un aviso cuando el coche se avería.",
        "Avisa a los programadores cuando la aplicación falla. Suele ser inofensivo, aunque también envía datos del aparato.",
        "crash_reporting"
    ),
    PUSH(
        "PUSH", "Mensajería", "Gestionan notificaciones y mensajes", R.attr.nvPush,
        "Es como el cartero que te toca el timbre cuando hay algo nuevo.",
        "Gestiona los avisos que le llegan a la pantalla del teléfono.",
        "functional"
    );

    companion object {
        fun of(key: String): Cat {
            for (c in values()) if (c.key == key) return c
            return ANA
        }
    }
}

class Company(
    val id: String,
    val name: String,
    val parent: String?,
    val country: String,
    val eea: Boolean,
    val cat: Cat,
    val what: String,
    val domains: List<String>
)

class Rule(val company: Company, val cat: Cat)

/** kind: 0 = operación corporativa, 1 = resolución de una autoridad */
class Event(val year: Int, val kind: Int, val title: String, val detail: String, val companyIds: List<String>)

object Knowledge {

    private fun c(
        id: String, name: String, parent: String?, country: String, eea: Boolean,
        cat: Cat, what: String, doms: String
    ): Company = Company(id, name, parent, country, eea, cat, what, doms.trim().split(Regex("\\s+")))

    val all: List<Company> = listOf(
        c("google", "Google", "Alphabet", "Estados Unidos", false, Cat.PUB,
            "Publicidad (AdMob, DoubleClick) y analítica (Google Analytics, Firebase) usadas por una enorme cantidad de apps.",
            "doubleclick.net googlesyndication.com googleadservices.com googletagservices.com 2mdn.net admob.com adservice.google.com imasdk.googleapis.com google-analytics.com:ANA googletagmanager.com:ANA app-measurement.com:ANA firebaselogging-pa.googleapis.com:ANA crashlytics.com:CRA"),
        c("meta", "Meta", "Meta Platforms", "Estados Unidos", false, Cat.SOC,
            "Dueña de Facebook, Instagram y WhatsApp. Su SDK conecta apps con sus redes y mide anuncios.",
            "graph.facebook.com connect.facebook.net an.facebook.com:PUB pixel.facebook.com:ANA"),
        c("amazon", "Amazon Advertising", "Amazon", "Estados Unidos", false, Cat.PUB,
            "Red publicitaria de Amazon, presente en apps y webs de terceros.",
            "amazon-adsystem.com mads.amazon.com"),
        c("microsoft", "Microsoft (Bing Ads, Clarity, App Center)", "Microsoft", "Estados Unidos", false, Cat.PUB,
            "Publicidad de Bing, mapas de calor de uso (Clarity) y analítica de apps (App Center).",
            "bat.bing.com clarity.ms:ANA appcenter.ms:CRA events.data.microsoft.com:ANA"),
        c("linkedin", "LinkedIn Ads", "Microsoft", "Estados Unidos", false, Cat.PUB,
            "Publicidad y medición de conversiones de LinkedIn.",
            "px.ads.linkedin.com snap.licdn.com"),
        c("adjust", "Adjust", "AppLovin", "Alemania", true, Cat.ATR,
            "Mide de qué campaña viene cada instalación. Fundada en Berlín; hoy pertenece a AppLovin.",
            "adjust.com adjust.io adjust.net.in"),
        c("appsflyer", "AppsFlyer", null, "Israel", false, Cat.ATR,
            "Plataforma de atribución y medición de campañas publicitarias móviles.",
            "appsflyer.com appsflyersdk.com"),
        c("branch", "Branch", null, "Estados Unidos", false, Cat.ATR,
            "Enlaces profundos y atribución de instalaciones.",
            "branch.io app.link"),
        c("singular", "Singular", null, "Estados Unidos", false, Cat.ATR,
            "Atribución y analítica de marketing móvil.",
            "singular.net"),
        c("kochava", "Kochava", null, "Estados Unidos", false, Cat.ATR,
            "Atribución, analítica y datos de audiencias para publicidad móvil.",
            "kochava.com"),
        c("mixpanel", "Mixpanel", null, "Estados Unidos", false, Cat.ANA,
            "Analítica de producto: qué botones pulsas y cómo avanzas por la app.",
            "mixpanel.com"),
        c("amplitude", "Amplitude", null, "Estados Unidos", false, Cat.ANA,
            "Analítica de producto y comportamiento dentro de la app.",
            "amplitude.com"),
        c("segment", "Segment", "Twilio", "Estados Unidos", false, Cat.ANA,
            "Recoge eventos de uso y los reparte a otras herramientas de terceros.",
            "segment.io segment.com"),
        c("braze", "Braze", null, "Estados Unidos", false, Cat.PUSH,
            "Notificaciones y mensajes personalizados según tu comportamiento.",
            "braze.com appboy.com"),
        c("onesignal", "OneSignal", null, "Estados Unidos", false, Cat.PUSH,
            "Servicio de notificaciones push para apps.",
            "onesignal.com"),
        c("sentry", "Sentry", null, "Estados Unidos", false, Cat.CRA,
            "Informes de errores y rendimiento para programadores.",
            "sentry.io"),
        c("bugsnag", "Bugsnag", "SmartBear", "Estados Unidos", false, Cat.CRA,
            "Informes de fallos de aplicaciones.",
            "bugsnag.com"),
        c("datadog", "Datadog", null, "Estados Unidos", false, Cat.CRA,
            "Monitorización del rendimiento y errores de apps.",
            "datadoghq.com datadoghq.eu"),
        c("newrelic", "New Relic", null, "Estados Unidos", false, Cat.CRA,
            "Monitorización del rendimiento de aplicaciones.",
            "newrelic.com nr-data.net"),
        c("flurry", "Flurry", "Yahoo", "Estados Unidos", false, Cat.ANA,
            "Analítica móvil con décadas de presencia en apps Android.",
            "flurry.com"),
        c("applovin", "AppLovin (incluye MoPub)", null, "Estados Unidos", false, Cat.PUB,
            "Red de anuncios y mediación para apps y juegos. Absorbió MoPub en 2022.",
            "applovin.com applvn.com mopub.com"),
        c("unity", "Unity Ads", "Unity", "Estados Unidos", false, Cat.PUB,
            "Publicidad dentro de juegos hechos con Unity.",
            "unityads.unity3d.com ads.unity3d.com config.unity3d.com cdp.cloud.unity3d.com:ANA uca.cloud.unity3d.com:ANA"),
        c("ironsource", "ironSource", "Unity", "Israel", false, Cat.PUB,
            "Anuncios y mediación en juegos y apps. Se fusionó con Unity en 2022.",
            "ironsrc.com supersonicads.com ironsource.mobi"),
        c("vungle", "Vungle", null, "Estados Unidos", false, Cat.PUB,
            "Anuncios en vídeo dentro de apps.",
            "vungle.com"),
        c("chartboost", "Chartboost", null, "Estados Unidos", false, Cat.PUB,
            "Publicidad y monetización de juegos móviles.",
            "chartboost.com"),
        c("inmobi", "InMobi", null, "India / Singapur", false, Cat.PUB,
            "Red de publicidad móvil con operaciones en Asia y otros mercados.",
            "inmobi.com inmobicdn.net"),
        c("mintegral", "Mintegral", "Mobvista", "China", false, Cat.PUB,
            "Red publicitaria móvil de origen chino muy presente en juegos.",
            "mintegral.com mintegral.net"),
        c("pangle", "Pangle", "ByteDance", "China", false, Cat.PUB,
            "Red publicitaria del grupo ByteDance (el de TikTok) para apps de terceros.",
            "pangleglobal.com"),
        c("tapjoy", "Tapjoy", null, "Estados Unidos", false, Cat.PUB,
            "Publicidad con recompensas dentro de juegos.",
            "tapjoy.com"),
        c("adcolony", "AdColony", "Digital Turbine", "Estados Unidos", false, Cat.PUB,
            "Anuncios en vídeo para apps y juegos. Pertenece a Digital Turbine desde 2021.",
            "adcolony.com"),
        c("startapp", "StartApp (Start.io)", null, "Israel", false, Cat.PUB,
            "Red de anuncios móviles muy presente en apps gratuitas.",
            "startappservice.com startapp.com"),
        c("criteo", "Criteo", null, "Francia", true, Cat.PUB,
            "Publicidad de «retargeting»: te muestra de nuevo lo que ya miraste en una tienda.",
            "criteo.com criteo.net"),
        c("taboola", "Taboola", null, "Estados Unidos", false, Cat.PUB,
            "Recomendaciones de contenido patrocinado en medios y apps.",
            "taboola.com"),
        c("outbrain", "Outbrain", null, "Estados Unidos", false, Cat.PUB,
            "Recomendaciones de contenido patrocinado en medios y apps.",
            "outbrain.com"),
        c("teads", "Teads", "Outbrain", "Luxemburgo", true, Cat.PUB,
            "Publicidad en vídeo dentro de medios de comunicación. Pertenece a Outbrain desde 2025.",
            "teads.tv"),
        c("ttd", "The Trade Desk", null, "Estados Unidos", false, Cat.PUB,
            "Plataforma que compra anuncios en tiempo real para anunciantes.",
            "adsrvr.org"),
        c("xandr", "Xandr (Microsoft)", "Microsoft", "Estados Unidos", false, Cat.PUB,
            "Bolsa de anuncios en tiempo real (antes AppNexus).",
            "adnxs.com"),
        c("pubmatic", "PubMatic", null, "Estados Unidos", false, Cat.PUB,
            "Intermediario que vende el espacio publicitario de apps y webs.",
            "pubmatic.com"),
        c("magnite", "Magnite (Rubicon)", null, "Estados Unidos", false, Cat.PUB,
            "Intermediario que vende el espacio publicitario de apps y webs.",
            "rubiconproject.com"),
        c("openx", "OpenX", null, "Estados Unidos", false, Cat.PUB,
            "Intermediario que vende el espacio publicitario de apps y webs.",
            "openx.net"),
        c("indexexchange", "Index Exchange", null, "Canadá", false, Cat.PUB,
            "Bolsa de anuncios en tiempo real.",
            "casalemedia.com indexww.com"),
        c("adform", "Adform", null, "Dinamarca", true, Cat.PUB,
            "Plataforma publicitaria europea de compra y venta de anuncios.",
            "adform.net"),
        c("equativ", "Equativ (Smart AdServer)", null, "Francia", true, Cat.PUB,
            "Plataforma europea de venta de espacio publicitario.",
            "smartadserver.com"),
        c("snap", "Snap", null, "Estados Unidos", false, Cat.SOC,
            "Medición de anuncios y conexión con Snapchat.",
            "tr.snapchat.com sc-static.net"),
        c("x", "X (antes Twitter)", "X Corp.", "Estados Unidos", false, Cat.PUB,
            "Publicidad y analítica de la red social X.",
            "ads-twitter.com analytics.twitter.com ads-api.twitter.com"),
        c("tiktok", "TikTok", "ByteDance", "China", false, Cat.ANA,
            "Analítica y medición de anuncios de TikTok dentro de otras apps.",
            "analytics.tiktok.com business-api.tiktok.com:ATR byteoversea.com"),
        c("pinterest", "Pinterest", null, "Estados Unidos", false, Cat.ATR,
            "Medición de conversiones de anuncios de Pinterest.",
            "ct.pinterest.com analytics.pinterest.com"),
        c("reddit", "Reddit Ads", null, "Estados Unidos", false, Cat.ATR,
            "Medición de anuncios y eventos de Reddit.",
            "events.redditmedia.com alb.reddit.com"),
        c("baidu", "Baidu", null, "China", false, Cat.PUB,
            "Publicidad y estadísticas del buscador chino Baidu.",
            "mobads.baidu.com hm.baidu.com:ANA"),
        c("umeng", "Umeng", "Alibaba", "China", false, Cat.ANA,
            "Analítica móvil muy extendida en apps de origen chino.",
            "umeng.com umengcloud.com"),
        c("tencent", "Tencent (MTA, Bugly)", "Tencent", "China", false, Cat.ANA,
            "Estadísticas y registro de fallos del grupo Tencent.",
            "mta.qq.com bugly.qq.com:CRA"),
        c("xiaomi", "Xiaomi", null, "China", false, Cat.ANA,
            "Telemetría y publicidad del sistema y las apps de Xiaomi.",
            "mistat.xiaomi.com ad.xiaomi.com:PUB"),
        c("samsung", "Samsung Ads", "Samsung", "Corea del Sur", false, Cat.PUB,
            "Publicidad y reconocimiento de contenido en dispositivos Samsung.",
            "samsungads.com samsungacr.com"),
        c("yandex", "Yandex", null, "Rusia", false, Cat.ANA,
            "Analítica (AppMetrica) y publicidad del grupo ruso Yandex.",
            "mc.yandex.ru appmetrica.yandex.net appmetrica.yandex.com yandexadexchange.net:PUB"),
        c("hotjar", "Hotjar", "Contentsquare", "Malta", true, Cat.ANA,
            "Mapas de calor y grabaciones de uso. Pertenece a Contentsquare desde 2021.",
            "hotjar.com hotjar.io"),
        c("contentsquare", "Contentsquare", null, "Francia", true, Cat.ANA,
            "Analítica de experiencia de usuario: cómo navegas y dónde pulsas.",
            "contentsquare.net contentsquare.com"),
        c("fullstory", "FullStory", null, "Estados Unidos", false, Cat.ANA,
            "Grabación y reproducción de sesiones de uso.",
            "fullstory.com"),
        c("optimizely", "Optimizely", null, "Estados Unidos", false, Cat.ANA,
            "Pruebas A/B y experimentación con usuarios.",
            "optimizely.com"),
        c("adobe", "Adobe (Analytics, Audience Manager)", "Adobe", "Estados Unidos", false, Cat.ANA,
            "Analítica y gestión de audiencias para empresas.",
            "omtrdc.net adobedtm.com everesttech.net demdex.net:PUB"),
        c("liveramp", "LiveRamp", null, "Estados Unidos", false, Cat.PUB,
            "Conecta identidades de usuarios entre distintas empresas para publicidad.",
            "rlcdn.com"),
        c("lotame", "Lotame", null, "Estados Unidos", false, Cat.PUB,
            "Plataforma de datos de audiencias para publicidad.",
            "crwdcntrl.net"),
        c("quantcast", "Quantcast", null, "Estados Unidos", false, Cat.ANA,
            "Medición de audiencias y publicidad.",
            "quantserve.com quantcount.com"),
        c("comscore", "comScore", null, "Estados Unidos", false, Cat.ANA,
            "Medición de audiencias de webs y apps.",
            "scorecardresearch.com"),
        c("nielsen", "Nielsen", null, "Estados Unidos", false, Cat.ANA,
            "Medición de audiencias.",
            "imrworldwide.com"),
        c("tealium", "Tealium", null, "Estados Unidos", false, Cat.ANA,
            "Gestor de etiquetas: reparte tus datos de uso entre otras herramientas.",
            "tiqcdn.com tealiumiq.com"),
        c("doubleverify", "DoubleVerify", null, "Estados Unidos", false, Cat.ANA,
            "Verificación de anuncios: comprueba dónde y a quién se muestran.",
            "doubleverify.com dvtps.com"),
        c("ias", "Integral Ad Science", null, "Estados Unidos", false, Cat.ANA,
            "Verificación y medición de anuncios.",
            "adsafeprotected.com"),
        c("cloudflare", "Cloudflare Web Analytics", "Cloudflare", "Estados Unidos", false, Cat.ANA,
            "Estadísticas de visitas de webs que usan Cloudflare.",
            "cloudflareinsights.com")
    )

    val events: List<Event> = listOf(
        Event(2014, 0, "Google compra Firebase", "Firebase, hoy base de buena parte de la analítica de apps, pasa a ser de Google.", listOf("google")),
        Event(2017, 0, "Google se queda con Crashlytics", "El sistema de informes de fallos Crashlytics pasa de Twitter a Google.", listOf("google")),
        Event(2018, 0, "AT&T compra AppNexus", "La bolsa de anuncios AppNexus pasa a AT&T y más tarde se llamará Xandr.", listOf("xandr")),
        Event(2020, 0, "Twilio compra Segment", "El distribuidor de datos de uso Segment pasa a formar parte de Twilio.", listOf("segment")),
        Event(2021, 0, "AppLovin compra Adjust", "La herramienta de atribución Adjust, nacida en Berlín, pasa a AppLovin.", listOf("adjust", "applovin")),
        Event(2021, 0, "Contentsquare compra Hotjar", "El analizador de mapas de calor Hotjar se integra en Contentsquare.", listOf("hotjar", "contentsquare")),
        Event(2021, 0, "Digital Turbine compra AdColony", "La red de anuncios en vídeo AdColony cambia de dueño.", listOf("adcolony")),
        Event(2022, 0, "AppLovin completa la compra de MoPub", "La red de anuncios móviles que era de Twitter pasa a AppLovin.", listOf("applovin")),
        Event(2022, 0, "Microsoft completa la compra de Xandr", "La bolsa de anuncios Xandr pasa de AT&T a Microsoft.", listOf("xandr", "microsoft")),
        Event(2022, 0, "Unity e ironSource se fusionan", "Dos de las mayores redes de anuncios para juegos se unen en una sola empresa.", listOf("unity", "ironsource")),
        Event(2025, 0, "Outbrain compra Teads", "Dos grandes empresas de publicidad en medios de comunicación se unen.", listOf("outbrain", "teads")),
        Event(2019, 1, "Multa a Google de 50 M€ (CNIL, Francia)", "La autoridad francesa de protección de datos sancionó la falta de transparencia y de base legal en la publicidad personalizada. Resolución de enero de 2019.", listOf("google")),
        Event(2021, 1, "Multa a Amazon de 746 M€ (CNPD, Luxemburgo)", "La autoridad de Luxemburgo sancionó el tratamiento de datos para publicidad personalizada. La empresa recurrió la decisión.", listOf("amazon")),
        Event(2023, 1, "Multa a Meta de 1.200 M€ (DPC, Irlanda)", "La autoridad irlandesa sancionó las transferencias de datos de usuarios europeos a EE. UU. La empresa recurrió la decisión.", listOf("meta")),
        Event(2023, 1, "Multa a TikTok de 345 M€ (DPC, Irlanda)", "La autoridad irlandesa sancionó el tratamiento de datos de menores en 2020.", listOf("tiktok")),
        Event(2023, 1, "Multa a Criteo de 40 M€ (CNIL, Francia)", "La autoridad francesa sancionó incumplimientos del RGPD en su publicidad personalizada.", listOf("criteo")),
        Event(2024, 1, "Multa a LinkedIn de 310 M€ (DPC, Irlanda)", "La autoridad irlandesa sancionó el análisis de datos de usuarios para publicidad dirigida.", listOf("linkedin"))
    )

    private val rules = HashMap<String, Rule>()
    private val ids = HashMap<String, Company>()

    init {
        for (co in all) {
            ids[co.id] = co
            for (token in co.domains) {
                val d = domainOf(token)
                val k = catOf(token, co)
                rules[d] = Rule(co, k)
            }
        }
    }

    fun domainOf(token: String): String {
        val i = token.indexOf(':')
        return if (i < 0) token else token.substring(0, i)
    }

    fun catOf(token: String, co: Company): Cat {
        val i = token.indexOf(':')
        return if (i < 0) co.cat else Cat.of(token.substring(i + 1))
    }

    fun byId(id: String): Company? = ids[id]

    fun eventsFor(id: String): List<Event> = events.filter { it.companyIds.contains(id) }

    /** Busca por sufijos: «a.b.tracker.com» → «b.tracker.com» → «tracker.com». */
    fun match(host: String): Rule? {
        var h = host.lowercase().trim().trimEnd('.')
        while (true) {
            val r = rules[h]
            if (r != null) return r
            val i = h.indexOf('.')
            if (i < 0) return null
            h = h.substring(i + 1)
            if (h.indexOf('.') < 0) return rules[h]
        }
    }
}

object Score {
    fun of(companies: Int, adCompanies: Int): Int {
        if (companies <= 0) return 0
        val base = 10.0 * Math.sqrt(companies.toDouble())
        val extra = Math.min(15, adCompanies * 2)
        return Math.min(100, Math.round(base + extra).toInt())
    }

    fun label(score: Int): String = when {
        score < 25 -> "Tranquilo"
        score < 50 -> "Curioso"
        score < 75 -> "Muy observado"
        else -> "Radar al rojo"
    }
}
