package com.david.gitly.core

import kotlinx.serialization.Serializable

/** Lo que el feed necesita de un README: sus imágenes "de verdad" y su texto plano. */
@Serializable
data class ReadmePreview(val images: List<String>, val text: String)

@Serializable
internal class ReadmeFile(val path: String = "README.md", val content: String = "", val encoding: String = "base64")

object ReadmeParser {
    /** Fragmentos de URL que delatan insignias, contadores o banners de donación: no son "fotos" del proyecto. */
    private val NOISE = listOf(
        "shields.io", "badgen", "badge", "travis-ci", "circleci", "codecov", "coveralls", "/workflows/",
        "buymeacoffee", "ko-fi", "liberapay", "paypal", "patreon", "opencollective", "discord", "gitter",
        "star-history", "contrib.rocks", "repobeats", "komarev", "visitor", "hits.", "snyk", "sonarcloud",
        "codacy", "codeclimate", "deepsource", "fossa", "weblate", "crowdin", "github-readme-stats",
        "readme-typing", "skillicons", "forthebadge", "/sponsors/", "awesome.re", "get-it-on",
        "play.google.com/intl", "trendshift", "producthunt", "api.netlify", "vercel.com/button",
        "deploy-button", "deploytoheroku", "gstatic.com", "emoji", "/icons/", "favicon",
        "/widgets/", "weblate", "/repo/index.png", "qrcode", "qr-code", "/qr.", "_qr.", "-qr.",
    )

    private val MD_IMG = Regex("""!\[[^\]]*]\(\s*<?([^)\s>]+)>?(?:\s+["'][^"']*["'])?\s*\)""")
    private val MD_REF_IMG = Regex("""!\[[^\]]*]\[([^\]]*)]""")
    private val REF_DEF = Regex("""(?m)^\s{0,3}\[([^\]]+)]:\s*<?(\S+?)>?(?:\s+["'(].*)?$""")
    private val HTML_IMG = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val SRC = Regex("""\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val WIDTH = Regex("""\bwidth\s*=\s*["']?(\d+)(%?)""", RegexOption.IGNORE_CASE)
    private val HEIGHT = Regex("""\bheight\s*=\s*["']?(\d+)(%?)""", RegexOption.IGNORE_CASE)

    fun parse(markdown: String, fullName: String, readmePath: String = "README.md"): ReadmePreview {
        val dir = readmePath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        return ReadmePreview(images(markdown, fullName, dir), text(markdown, fullName.substringAfter('/')))
    }

    fun images(md: String, fullName: String, dir: String = ""): List<String> {
        val refs = REF_DEF.findAll(md).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val found = ArrayList<Pair<Int, String>>()
        MD_IMG.findAll(md).forEach { found += it.range.first to it.groupValues[1] }
        MD_REF_IMG.findAll(md).forEach { m -> refs[m.groupValues[1].lowercase()]?.let { found += m.range.first to it } }
        HTML_IMG.findAll(md).forEach { m ->
            val tag = m.value
            val small = listOf(WIDTH, HEIGHT).any { r ->
                r.find(tag)?.let { it.groupValues[2] != "%" && it.groupValues[1].toInt() < 90 } == true
            }
            if (!small) SRC.find(tag)?.let { found += m.range.first to it.groupValues[1] }
        }
        return found.sortedBy { it.first }
            .mapNotNull { resolve(it.second, fullName, dir) }
            .filter { url -> NOISE.none { url.lowercase().contains(it) } }
            .distinct()
            .take(10)
    }

    private val GH_FILE = Regex("^https://github\\.com/([^/]+)/([^/]+)/(?:raw|blob)/(.+)$")

    /**
     * github.com/o/r/raw|blob/… responde con una redirección a raw.githubusercontent.com: otra conexión
     * TLS y otra ida y vuelta por imagen. Se va directo.
     */
    fun direct(url: String): String {
        val m = GH_FILE.find(url) ?: return url
        val (o, r, rest) = m.destructured
        return "https://raw.githubusercontent.com/$o/$r/${rest.substringBefore('?').substringBefore('#')}"
    }

    fun resolve(raw: String, fullName: String, dir: String = ""): String? = resolveRaw(raw, fullName, dir)?.let(::direct)

    private fun resolveRaw(raw: String, fullName: String, dir: String = ""): String? {
        val u = raw.trim().replace(" ", "%20")
        if (u.isEmpty() || u.startsWith("data:") || u.startsWith("#")) return null
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://") || u.startsWith("https://") -> {
                val https = u.replaceFirst("http://", "https://")
                if (https.startsWith("https://github.com/") && "/blob/" in https) https.replaceFirst("/blob/", "/raw/") else https
            }
            else -> {
                val rel = if (u.startsWith("/")) u.removePrefix("/") else dir + u.removePrefix("./")
                val parts = ArrayDeque<String>()
                for (p in rel.split('/')) when (p) {
                    "", "." -> {}
                    ".." -> parts.removeLastOrNull()
                    else -> parts.addLast(p)
                }
                "https://github.com/$fullName/raw/HEAD/${parts.joinToString("/")}"
            }
        }
    }

    private val ENTITIES = mapOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'")

    fun text(md: String, repoName: String, maxChars: Int = 3000): String {
        val dot = setOf(RegexOption.DOT_MATCHES_ALL)
        var s = md.replace("\r\n", "\n")
        s = s.replace(Regex("<!--.*?-->", dot), "")
        s = s.replace(Regex("```.*?```", dot), "\n").replace(Regex("~~~.*?~~~", dot), "\n")
        s = s.replace(Regex("<(script|style)[^>]*>.*?</\\1>", dot + RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("</?(p|div|h[1-6]|li|ul|ol|tr|table|details|summary|picture)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "").replace(Regex("!\\[[^\\]]*]\\[[^\\]]*]"), "")
        s = s.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1").replace(Regex("\\[([^\\]]*)]\\[[^\\]]*]"), "$1")
        ENTITIES.forEach { (k, v) -> s = s.replace(k, v) }

        val lines = s.lines().mapNotNull { line ->
            var l = line
            if (REF_DEF.matches(l)) return@mapNotNull null
            val t = l.trim()
            if (t.startsWith("|") || t.matches(Regex("[-=*_:| ]{3,}"))) return@mapNotNull null
            l = l.replace(Regex("^\\s*>?\\s*\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^\\s*#{1,6}\\s*"), "")
                .replace(Regex("^\\s*>\\s?"), "")
                .replace(Regex("^\\s*[-*+]\\s+(\\[[ xX]]\\s*)?"), "• ")
                .replace("**", "").replace("__", "").replace("`", "").replace("~~", "")
                .replace(Regex("\\s+"), " ")
                .trim()
            // Restos de insignias: líneas que solo eran enlaces/imágenes quedan vacías o de 1 carácter.
            // Barras de navegación ("Features · Install · FAQ", selectores de idioma): fuera.
            val seps = Regex("\\s[|·•/]\\s").findAll(l).count()
            if (l.length <= 1 || seps >= 2 || (seps >= 1 && l.length < 40) || l.trim('•', ' ').length <= 2 || (l.startsWith("•") && l.endsWith("•")) || l.matches(Regex("(•\\s*)?https?://\\S+"))) "" else l
        }
        var out = lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        // El primer renglón suele repetir el nombre del repo.
        val first = out.substringBefore('\n')
        fun norm(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
        if (norm(first) == norm(repoName)) out = out.substringAfter('\n', "").trim()
        return if (out.length > maxChars) out.take(maxChars).substringBeforeLast(' ') + "…" else out
    }
}
