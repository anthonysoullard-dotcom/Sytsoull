package fr.perso.sytadintrajet

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var velizyVersArpajon: Boolean = true
    private var refreshJob: Job? = null

    private lateinit var titreView: TextView
    private lateinit var tempsView: TextView
    private lateinit var detailsView: TextView
    private lateinit var majView: TextView
    private lateinit var erreurView: TextView
    private lateinit var progression: ProgressBar
    private lateinit var boutonActualiser: Button
    private lateinit var boutonInverser: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        titreView = findViewById(R.id.titre)
        tempsView = findViewById(R.id.temps)
        detailsView = findViewById(R.id.details)
        majView = findViewById(R.id.maj)
        erreurView = findViewById(R.id.erreur)
        progression = findViewById(R.id.progression)
        boutonActualiser = findViewById(R.id.actualiser)
        boutonInverser = findViewById(R.id.inverser)

        boutonActualiser.setOnClickListener { actualiser() }
        boutonInverser.setOnClickListener {
            velizyVersArpajon = !velizyVersArpajon
            actualiser()
        }

        actualiser()
    }

    private fun actualiser() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            afficherChargement()
            try {
                val (depart, arrivee) = if (velizyVersArpajon) {
                    listOf("Vélizy") to listOf("Arpajon", "Linas")
                } else {
                    listOf("Arpajon", "Linas") to listOf("Vélizy")
                }
                val resultat = SytadinClient.fetchTrajet(depart, arrivee)
                afficherResultat(resultat)
            } catch (e: Exception) {
                afficherErreur(e.message ?: "Erreur inconnue")
            }
        }
    }

    private fun afficherChargement() {
        progression.visibility = View.VISIBLE
        tempsView.visibility = View.INVISIBLE
        detailsView.visibility = View.GONE
        majView.visibility = View.GONE
        erreurView.visibility = View.GONE
        boutonActualiser.isEnabled = false
        boutonInverser.isEnabled = false
    }

    private fun afficherResultat(resultat: TrajetResult) {
        progression.visibility = View.GONE
        tempsView.visibility = View.VISIBLE
        titreView.text = "${resultat.depart} → ${resultat.arrivee}"
        tempsView.text = resultat.temps

        val elements = buildList {
            resultat.fiabilite?.let { add("Fiabilité : $it %") }
            resultat.distanceKm?.let { add("Distance : $it km") }
        }
        detailsView.text = elements.joinToString("  •  ")
        detailsView.visibility = if (elements.isEmpty()) View.GONE else View.VISIBLE

        majView.text = "Actualisé le ${resultat.horodatage} • Source : sytadin.fr"
        majView.visibility = View.VISIBLE
        erreurView.visibility = View.GONE
        boutonActualiser.isEnabled = true
        boutonInverser.isEnabled = true
    }

    private fun afficherErreur(message: String) {
        progression.visibility = View.GONE
        tempsView.visibility = View.INVISIBLE
        detailsView.visibility = View.GONE
        majView.visibility = View.GONE
        erreurView.text = message
        erreurView.visibility = View.VISIBLE
        boutonActualiser.isEnabled = true
        boutonInverser.isEnabled = true
    }
}
