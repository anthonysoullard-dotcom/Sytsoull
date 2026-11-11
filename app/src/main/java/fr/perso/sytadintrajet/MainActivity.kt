package fr.perso.sytadintrajet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private var boucleJob: Job? = null
    private var actualisationJob: Job? = null

    private lateinit var tempsAller: TextView
    private lateinit var detailsAller: TextView
    private lateinit var tempsRetour: TextView
    private lateinit var detailsRetour: TextView
    private lateinit var majView: TextView
    private lateinit var erreurView: TextView
    private lateinit var progression: ProgressBar
    private lateinit var boutonActualiser: Button
    private lateinit var boutonMoins: Button
    private lateinit var boutonPlus: Button
    private lateinit var seuilValeur: TextView
    private lateinit var switchAlerte: MaterialSwitch

    private val demandePermissionNotification =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { accordee ->
            if (accordee) {
                activerSurveillance()
            } else {
                switchAlerte.isChecked = false
                prefs.edit().putBoolean(KEY_ALERTE_ACTIVEE, false).apply()
                Toast.makeText(
                    this,
                    "Notifications refusées : la surveillance ne peut pas être activée",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tempsAller = findViewById(R.id.temps_aller)
        detailsAller = findViewById(R.id.details_aller)
        tempsRetour = findViewById(R.id.temps_retour)
        detailsRetour = findViewById(R.id.details_retour)
        majView = findViewById(R.id.maj)
        erreurView = findViewById(R.id.erreur)
        progression = findViewById(R.id.progression)
        boutonActualiser = findViewById(R.id.actualiser)
        boutonMoins = findViewById(R.id.seuil_moins)
        boutonPlus = findViewById(R.id.seuil_plus)
        seuilValeur = findViewById(R.id.seuil_valeur)
        switchAlerte = findViewById(R.id.switch_alerte)

        boutonActualiser.setOnClickListener { actualiser() }
        boutonMoins.setOnClickListener { ajusterSeuil(-QUANTUM_SEUIL) }
        boutonPlus.setOnClickListener { ajusterSeuil(QUANTUM_SEUIL) }

        afficherSeuil()
        switchAlerte.isChecked = prefs.getBoolean(KEY_ALERTE_ACTIVEE, false)
        switchAlerte.setOnCheckedChangeListener { _, cochee ->
            if (cochee) {
                if (peutNotifier()) {
                    activerSurveillance()
                } else {
                    demandePermissionNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                desactiverSurveillance()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        demarrerBoucle()
    }

    override fun onPause() {
        boucleJob?.cancel()
        boucleJob = null
        super.onPause()
    }

    private fun peutNotifier(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun activerSurveillance() {
        prefs.edit().putBoolean(KEY_ALERTE_ACTIVEE, true).apply()
        switchAlerte.isChecked = true
        ContextCompat.startForegroundService(this, Intent(this, AlerteService::class.java))
        Toast.makeText(this, "Surveillance activée (tâche de fond)", Toast.LENGTH_SHORT).show()
    }

    private fun desactiverSurveillance() {
        prefs.edit().putBoolean(KEY_ALERTE_ACTIVEE, false).apply()
        stopService(Intent(this, AlerteService::class.java))
    }

    private fun ajusterSeuil(delta: Int) {
        val nouveau = (prefs.getInt(KEY_SEUIL, SEUIL_DEFAUT) + delta)
            .coerceIn(SEUIL_MIN, SEUIL_MAX)
        prefs.edit().putInt(KEY_SEUIL, nouveau).apply()
        afficherSeuil()
    }

    private fun afficherSeuil() {
        seuilValeur.text = getString(R.string.seuil_format, prefs.getInt(KEY_SEUIL, SEUIL_DEFAUT))
    }

    private fun demarrerBoucle() {
        boucleJob?.cancel()
        boucleJob = lifecycleScope.launch {
            while (isActive) {
                actualiser()
                delay(INTERVALLE_MS)
            }
        }
    }

    private fun actualiser() {
        actualisationJob?.cancel()
        actualisationJob = lifecycleScope.launch {
            afficherChargement()
            try {
                val aller = async {
                    SytadinClient.fetchTrajet(listOf("Vélizy"), listOf("Arpajon", "Linas"))
                }
                val retour = async {
                    SytadinClient.fetchTrajet(listOf("Arpajon", "Linas"), listOf("Vélizy"))
                }
                afficherCarte(tempsAller, detailsAller, aller.await())
                afficherCarte(tempsRetour, detailsRetour, retour.await())
                terminerChargement()
                erreurView.visibility = View.GONE
            } catch (e: Exception) {
                progression.visibility = View.GONE
                boutonActualiser.isEnabled = true
                erreurView.text = e.message ?: "Erreur inconnue"
                erreurView.visibility = View.VISIBLE
            }
        }
    }

    private fun afficherChargement() {
        progression.visibility = View.VISIBLE
        majView.visibility = View.GONE
        erreurView.visibility = View.GONE
        boutonActualiser.isEnabled = false
    }

    private fun afficherCarte(tempsView: TextView, detailsView: TextView, resultat: TrajetResult) {
        tempsView.text = resultat.temps
        val elements = buildList {
            resultat.fiabilite?.let { add("Fiabilité : $it %") }
            resultat.distanceKm?.let { add("$it km") }
        }
        detailsView.text = elements.joinToString("  •  ")
        detailsView.visibility = if (elements.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun terminerChargement() {
        progression.visibility = View.GONE
        boutonActualiser.isEnabled = true
        majView.text = getString(
            R.string.derniere_maj,
            SimpleDateFormat("dd/MM 'à' HH:mm", Locale.FRENCH).format(Date())
        )
        majView.visibility = View.VISIBLE
    }
}
