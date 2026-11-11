package fr.perso.sytadintrajet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Pole(val id: String, val nom: String)

data class TrajetResult(
    val depart: String,
    val arrivee: String,
    val temps: String,
    val minutes: Int?,
    val fiabilite: Int?,
    val distanceKm: String?,
    val horodatage: String
)

class SytadinException(message: String, cause: Throwable? = null) : Exception(message, cause)

object SytadinClient {

    private const val FORM_URL = "https://www.sytadin.fr/gp/itineraire.action?stat=false"

    private val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val POLES_SECOURS = mapOf(
        "velizy" to Pole("26000212", "Velizy (D53)"),
        "arpajon" to Pole("14001512", "Linas (N20)"),
        "linas" to Pole("14001512", "Linas (N20)")
    )

    suspend fun fetchTrajet(
        motsClesDepart: List<String>,
        motsClesArrivee: List<String>
    ): TrajetResult = withContext(Dispatchers.IO) {
        val (doc, cookies) = getFormulaire()

        val form = doc.select("form").firstOrNull {
            it.attr("action").contains("itineraire", ignoreCase = true)
        } ?: throw SytadinException("Formulaire d'itinéraire introuvable sur Sytadin")

        val poles = listerPoles(form)
        val depart = choisirPole(poles, motsClesDepart)
            ?: POLES_SECOURS[motsClesDepart.first().normaliserCle()]
            ?: throw SytadinException("Pôle de départ introuvable sur Sytadin")
        val arrivee = choisirPole(poles, motsClesArrivee)
            ?: POLES_SECOURS[motsClesArrivee.first().normaliserCle()]
            ?: throw SytadinException("Pôle d'arrivée introuvable sur Sytadin")

        val resultDoc = soumettre(form, cookies, depart, arrivee)
        parserResultat(resultDoc, depart.nom, arrivee.nom)
    }

    private fun getFormulaire(): Pair<Document, Map<String, String>> {
        val response = try {
            Jsoup.connect(FORM_URL)
                .userAgent(USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-FR,fr;q=0.9")
                .timeout(20_000)
                .execute()
        } catch (e: Exception) {
            throw SytadinException("Impossible de joindre sytadin.fr : ${e.message}", e)
        }
        if (response.statusCode() != 200) {
            throw SytadinException("Sytadin a répondu HTTP ${response.statusCode()}")
        }
        return Pair(response.parse(), response.cookies())
    }

    private fun listerPoles(form: Element): List<Pole> {
        val select = form.select("select[name=iddepart]").firstOrNull()
            ?: form.select("select[name=depart]").firstOrNull()
            ?: return emptyList()
        return select.select("option").mapNotNull { option ->
            val id = option.attr("value").trim()
            val nom = option.text().trim()
            if (id.isEmpty() || nom.isEmpty()) null else Pole(id, nom)
        }
    }

    private fun choisirPole(poles: List<Pole>, motsCles: List<String>): Pole? {
        for (motCle in motsCles) {
            val cle = normaliser(motCle)
            poles.firstOrNull { normaliser(it.nom).contains(cle) }?.let { return it }
        }
        return null
    }

    private fun soumettre(
        form: Element,
        cookies: Map<String, String>,
        depart: Pole,
        arrivee: Pole
    ): Document {
        val action = form.absUrl("action").ifEmpty {
            "https://www.sytadin.fr" + form.attr("action")
        }

        val fields = LinkedHashMap<String, String>()
        for (el in form.select("input, select, textarea")) {
            val name = el.attr("name")
            if (name.isEmpty()) continue
            when (el.tagName()) {
                "select" -> fields[name] = el.select("option[selected]").firstOrNull()
                    ?.attr("value")
                    ?: el.select("option").firstOrNull()?.attr("value")
                    ?: ""
                "input" -> {
                    val type = el.attr("type").lowercase(Locale.ROOT)
                    when (type) {
                        "checkbox", "radio" ->
                            if (el.hasAttr("checked")) fields[name] = el.attr("value")
                        "submit", "button", "image", "file" -> Unit
                        else -> fields[name] = el.attr("value")
                    }
                }
                else -> fields[name] = el.text()
            }
        }

        fields["depart"] = depart.nom
        fields["iddepart"] = depart.id
        fields["arrivee"] = arrivee.nom
        fields["idarrivee"] = arrivee.id
        listOf("via1", "via2", "via3").forEach { via ->
            fields[via] = ""
            fields["id$via"] = ""
        }

        val criteres = form.select("input[name=critereRoutier]").map { it.attr("value") }
        if (criteres.isNotEmpty()) {
            fields["critereRoutier"] = criteres.firstOrNull { it.contains("TEMPS", true) }
                ?: fields["critereRoutier"]
                ?: criteres.first()
        }
        val peages = form.select("input[name=eviterPeage]").map { it.attr("value") }
        if (peages.isNotEmpty() && fields["eviterPeage"] == null) {
            fields["eviterPeage"] = peages.firstOrNull { it.equals("OUI", true) } ?: peages.first()
        }

        return try {
            Jsoup.connect(action)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "fr-FR,fr;q=0.9")
                .referrer(FORM_URL)
                .cookies(cookies)
                .data(fields)
                .timeout(20_000)
                .post()
        } catch (e: Exception) {
            throw SytadinException("Échec du calcul d'itinéraire : ${e.message}", e)
        }
    }

    private fun parserResultat(doc: Document, depart: String, arrivee: String): TrajetResult {
        val texte = doc.body().text().replace(Regex("\\s+"), " ")

        val tempsRegex = Regex(
            "Temps de parcours[^0-9h]{0,60}?(?:(\\d{1,2})\\s*h\\s*)?(\\d{1,2})\\s*mn",
            RegexOption.IGNORE_CASE
        )
        val matchTemps = tempsRegex.find(texte)
            ?: throw SytadinException("Temps de parcours introuvable dans la réponse de Sytadin")

        val heures = matchTemps.groupValues[1].toIntOrNull() ?: 0
        val minutes = matchTemps.groupValues[2].toIntOrNull() ?: 0
        val totalMinutes = heures * 60 + minutes
        val temps = if (heures > 0) "${heures}h%02d".format(Locale.FRENCH, minutes) else "$minutes mn"

        val fiabiliteRegex = Regex("Fiabilit[^%]{0,80}?(\\d{1,3})\\s*%", RegexOption.IGNORE_CASE)
        val fiabilite = fiabiliteRegex.find(texte)?.groupValues?.get(1)?.toIntOrNull()

        val distanceRegex = Regex(
            "Distance[^0-9]{0,40}(\\d+(?:[.,]\\d+)?)\\s*km",
            RegexOption.IGNORE_CASE
        )
        val distance = distanceRegex.find(texte)?.groupValues?.get(1)?.let { it.replace(',', '.') }

        val horodatage = SimpleDateFormat("dd/MM 'à' HH:mm", Locale.FRENCH).format(Date())

        return TrajetResult(depart, arrivee, temps, totalMinutes, fiabilite, distance, horodatage)
    }

    private fun normaliser(texte: String): String {
        val sansAccents = Normalizer.normalize(texte.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return sansAccents.replace(Regex("[^a-z0-9]+"), "")
    }

    private fun String.normaliserCle(): String =
        Normalizer.normalize(this.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), "")
}
