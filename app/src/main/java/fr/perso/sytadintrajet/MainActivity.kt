package fr.perso.sytadintrajet

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioButton
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
    private lateinit var boutonQuitter: Button
    private lateinit var boutonMoins: Button
    private lateinit var boutonPlus: Button
    private lateinit var seuilValeur: TextView
    private lateinit var switchAlerte: MaterialSwitch

    private lateinit var radioVelizy: RadioButton
    private lateinit var radioLinas: RadioButton
    private lateinit var boutonHeure: Button
    private lateinit var boutonMargeMoins: Button
    private lateinit var boutonMargePlus: Button
    private lateinit var margeValeur: TextView
    private lateinit var switchChienDeGarde: MaterialSwitch

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
        boutonQuitter = findViewById(R.id.quitter)
        boutonMoins = findViewById(R.id.seuil_moins)
        boutonPlus = findViewById(R.id.seuil_plus)
        seuilValeur = findViewById(R.id.seuil_valeur)
        switchAlerte = findViewById(R.id.switch_alerte)
        radioVelizy = findViewById(R.id.arrivee_velizy)
        radioLinas = findViewById(R.id.arrivee_linas)
        boutonHeure = findViewById(R.id.heure_arrivee)
        boutonMargeMoins = findViewById(R.id.marge_moins)
        boutonMargePlus = findViewById(R.id.marge_plus)
        margeValeur = findViewById(R.id.marge_valeur)
        switchChienDeGarde = findViewById(R.id.switch_chien_de_garde)

        boutonActualiser.setOnClickListener { actualiser() }
        boutonQuitter.setOnClickListener { quitter() }
        boutonMoins.setOnClickListener { ajusterSeuil(-QUANTUM_SEUIL) }
        boutonPlus.setOnClickListener { ajusterSeuil(QUANTUM_SEUIL) }
        boutonMargeMoins.setOnClickListener { ajusterMarge(-QUANTUM_MARGE) }
        boutonMargePlus.setOnClickListener { ajusterMarge(QUANTUM_MARGE) }
        boutonHeure.setOnClickListener { choisirHeure() }

        afficherSeuil()
        afficherMarge()
        afficherHeure()
        when (prefs.getString(KEY_CHDG_ARRIVEE, CHDG_ARRIVEE_DEFAUT)) {
            "linas" -> radioLinas.isChecked = true
            else -> radioVelizy.isChecked = true
        }
        radioVelizy.setOnCheckedChangeListener { _, cochee ->
            if (cochee) prefs.edit().putString(KEY_CHDG_ARRIVEE, "velizy").apply()
        }
        radioLinas.setOnCheckedChangeListener { _, cochee ->
            if (cochee) prefs.edit().putString(KEY_CHDG_ARRIVEE, "linas").apply()
        }

        switchAlerte.isChecked = prefs.getBoolean(KEY_ALERTE_ACTIVEE, false)
        switchAlerte.setOnCheckedChangeListener { _, cochee ->
            if (cochee) {
                if (peutNotifier()) {
                    activerSurveillance()
                } else {
                    demandePermissionNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                prefs.edit().putBoolean(KEY_ALERTE_ACTIVEE, false).apply()
                gererService()
            }
        }

        switchChienDeGarde.isChecked = prefs.getBoolean(KEY_CHDG_ACTIF, false)
        switchChienDeGarde.setOnCheckedChangeListener { _, cochee ->
            if (cochee) {
                if (peutNotifier()) {
                    activerChienDeGarde()
                } else {
                    switchChienDeGarde.isChecked = false
                    Toast.makeText(
                        this,
                        "Notifications refusées : le chien de garde ne peut pas être activé",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                prefs.edit().putBoolean(KEY_CHDG_ACTIF, false).apply()
                prefs.edit().putBoolean(KEY_CHDG_ALERTE_EN_COURS, false).apply()
                gererService()
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
        gererService()
        Toast.makeText(this, "Surveillance activée (tâche de fond)", Toast.LENGTH_SHORT).show()
    }

    private fun activerChienDeGarde() {
        prefs.edit().putBoolean(KEY_CHDG_ACTIF, true).apply()
        prefs.edit().putBoolean(KEY_CHDG_ALERTE_EN_COURS, false).apply()
        switchChienDeGarde.isChecked = true
        gererService()
        Toast.makeText(this, "Chien de garde activé (tâche de fond)", Toast.LENGTH_SHORT).show()
    }

    private fun gererService() {
        val quelqueChoseActif =
            prefs.getBoolean(KEY_ALERTE_ACTIVEE, false) || prefs.getBoolean(KEY_CHDG_ACTIF, false)
        if (quelqueChoseActif) {
            ContextCompat.startForegroundService(this, Intent(this, AlerteService::class.java))
        } else {
            stopService(Intent(this, AlerteService::class.java))
        }
    }

    private fun quitter() {
        boucleJob?.cancel()
        actualisationJob?.cancel()
        prefs.edit().putBoolean(KEY_ALERTE_ACTIVEE, false).apply()
        prefs.edit().putBoolean(KEY_CHDG_ACTIF, false).apply()
        switchAlerte.isChecked = false
        switchChienDeGarde.isChecked = false
        stopService(Intent(this, AlerteService::class.java))
        finishAffinity()
    }

    private fun ajusterSeuil(delta: Int) {
        val nouveau = (prefs.getInt(KEY_SEUIL, SEUIL_DEFAUT) + delta)
            .coerceIn(SEUIL_MIN, SEUIL_MAX)
        prefs.edit().putInt(KEY_SEUIL, nouveau).apply()
        afficherSeuil()
    }

    private fun ajusterMarge(delta: Int) {
        val nouveau = (prefs.getInt(KEY_CHDG_MARGE, CHDG_MARGE_DEFAUT) + delta)
            .coerceIn(CHDG_MARGE_MIN, CHDG_MARGE_MAX)
        prefs.edit().putInt(KEY_CHDG_MARGE, nouveau).apply()
        afficherMarge()
    }

    private fun afficherSeuil() {
        seuilValeur.text = getString(R.string.seuil_format, prefs.getInt(KEY_SEUIL, SEUIL_DEFAUT))
    }

    private fun afficherMarge() {
        margeValeur.text =
            getString(R.string.seuil_format, prefs.getInt(KEY_CHDG_MARGE, CHDG_MARGE_DEFAUT))
    }

    private fun afficherHeure() {
        val minutes = prefs.getInt(KEY_CHDG_HEURE, CHDG_HEURE_DEFAUT)
        boutonHeure.text = "%02d:%02d".format(minutes / 60, minutes % 60)
    }

    private fun choisirHeure() {
        val actuel = prefs.getInt(KEY_CHDG_HEURE, CHDG_HEURE_DEFAUT)
        TimePickerDialog(
            this,
            { _, heure, minute ->
                var total = heure * 60 + Math.round(minute / 10.0).toInt() * QUANTUM_HEURE
                if (total >= 1440) total -= 1440
                prefs.edit().putInt(KEY_CHDG_HEURE, total).apply()
                afficherHeure()
            },
            actuel / 60,
            (actuel % 60 / 10) * 10,
            true
        ).show()
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
