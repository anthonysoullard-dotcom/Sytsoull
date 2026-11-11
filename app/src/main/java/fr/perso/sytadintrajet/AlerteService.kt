package fr.perso.sytadintrajet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

class AlerteService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        creerCanaux()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                ID_SURVEILLANCE,
                notificationSurveillance(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(ID_SURVEILLANCE, notificationSurveillance())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            while (isActive) {
                verifier()
                delay(INTERVALLE_MS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun verifier() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val gestionnaire = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        gestionnaire.notify(ID_SURVEILLANCE, notificationSurveillance())

        if (prefs.getBoolean(KEY_ALERTE_ACTIVEE, false)) {
            verifierSeuils(prefs, gestionnaire)
        }
        if (prefs.getBoolean(KEY_CHDG_ACTIF, false)) {
            verifierChienDeGarde(prefs, gestionnaire)
        }
    }

    private suspend fun verifierSeuils(
        prefs: android.content.SharedPreferences,
        gestionnaire: NotificationManager
    ) {
        val seuil = prefs.getInt(KEY_SEUIL, SEUIL_DEFAUT)

        for (direction in DIRECTIONS) {
            try {
                val resultat = SytadinClient.fetchTrajet(direction.motsDepart, direction.motsArrivee)
                val minutes = resultat.minutes ?: continue
                val depasse = minutes > seuil
                val cle = "alerte_${direction.cle}"
                val alertaitDeja = prefs.getBoolean(cle, false)
                if (depasse && !alertaitDeja) {
                    gestionnaire.notify(
                        direction.idNotification,
                        notificationAlerte(resultat, seuil)
                    )
                }
                prefs.edit().putBoolean(cle, depasse).apply()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun verifierChienDeGarde(
        prefs: android.content.SharedPreferences,
        gestionnaire: NotificationManager
    ) {
        val pointArrivee = prefs.getString(KEY_CHDG_ARRIVEE, CHDG_ARRIVEE_DEFAUT) ?: CHDG_ARRIVEE_DEFAUT
        val heureArrivee = prefs.getInt(KEY_CHDG_HEURE, CHDG_HEURE_DEFAUT)
        val marge = prefs.getInt(KEY_CHDG_MARGE, CHDG_MARGE_DEFAUT)

        val (motsDepart, motsArrivee, nomArrivee) = if (pointArrivee == "linas") {
            Triple(listOf("Vélizy"), listOf("Arpajon", "Linas"), "Linas (Arpajon)")
        } else {
            Triple(listOf("Arpajon", "Linas"), listOf("Vélizy"), "Vélizy")
        }

        try {
            val resultat = SytadinClient.fetchTrajet(motsDepart, motsArrivee)
            val minutes = resultat.minutes ?: return

            val maintenant = Calendar.getInstance()
            val minutesMaintenant = maintenant.get(Calendar.HOUR_OF_DAY) * 60 +
                maintenant.get(Calendar.MINUTE)
            val departAuPlusTard = heureArrivee - minutes
            val restant = departAuPlusTard - minutesMaintenant
            val doitAlerter = restant <= marge

            val alertaitDeja = prefs.getBoolean(KEY_CHDG_ALERTE_EN_COURS, false)
            if (doitAlerter && !alertaitDeja) {
                gestionnaire.notify(
                    ID_CHDG,
                    notificationChienDeGarde(nomArrivee, heureArrivee, resultat, departAuPlusTard)
                )
            }
            prefs.edit().putBoolean(KEY_CHDG_ALERTE_EN_COURS, doitAlerter).apply()
        } catch (_: Exception) {
        }
    }

    private fun creerCanaux() {
        val gestionnaire = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        gestionnaire.createNotificationChannel(
            NotificationChannel(
                CANAL_SURVEILLANCE,
                "Surveillance du trajet",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Notification permanente indiquant que la surveillance est active"
            }
        )
        gestionnaire.createNotificationChannel(
            NotificationChannel(
                CANAL_ALERTE,
                "Alertes temps de trajet",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifie quand le temps de trajet dépasse le seuil défini"
            }
        )
        gestionnaire.createNotificationChannel(
            NotificationChannel(
                CANAL_CHDG,
                "Chien de garde",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifie quand il est temps de partir pour arriver à l'heure voulue"
            }
        )
    }

    private fun texteSurveillance(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val elements = mutableListOf<String>()
        if (prefs.getBoolean(KEY_ALERTE_ACTIVEE, false)) {
            elements.add("seuil trajet : ${prefs.getInt(KEY_SEUIL, SEUIL_DEFAUT)} mn")
        }
        if (prefs.getBoolean(KEY_CHDG_ACTIF, false)) {
            val heure = prefs.getInt(KEY_CHDG_HEURE, CHDG_HEURE_DEFAUT)
            elements.add("arrivée : ${formaterHeure(heure)}")
        }
        if (elements.isEmpty()) {
            return getString(R.string.surveillance_generique)
        }
        return getString(R.string.surveillance_details, elements.joinToString(" • "))
    }

    private fun notificationSurveillance(): Notification =
        NotificationCompat.Builder(this, CANAL_SURVEILLANCE)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Surveillance du trajet active")
            .setContentText(texteSurveillance())
            .setOngoing(true)
            .setContentIntent(intentOuverture())
            .build()

    private fun notificationAlerte(resultat: TrajetResult, seuil: Int): Notification {
        val texte = getString(R.string.alerte_texte, resultat.temps, seuil) +
            "\nFiabilité : ${resultat.fiabilite ?: "?"} %"
        return NotificationCompat.Builder(this, CANAL_ALERTE)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Trafic dense : ${resultat.depart} → ${resultat.arrivee}")
            .setContentText(getString(R.string.alerte_texte, resultat.temps, seuil))
            .setStyle(NotificationCompat.BigTextStyle().bigText(texte))
            .setAutoCancel(true)
            .setContentIntent(intentOuverture())
            .build()
    }

    private fun notificationChienDeGarde(
        nomArrivee: String,
        heureArrivee: Int,
        resultat: TrajetResult,
        departAuPlusTard: Int
    ): Notification {
        val texte = getString(
            R.string.chdg_notif_texte,
            formaterHeure(heureArrivee),
            nomArrivee,
            resultat.temps,
            formaterHeure(moduloJour(departAuPlusTard))
        )
        return NotificationCompat.Builder(this, CANAL_CHDG)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.chdg_notif_titre))
            .setContentText(texte)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texte))
            .setAutoCancel(true)
            .setContentIntent(intentOuverture())
            .build()
    }

    private fun formaterHeure(minutesDuJour: Int): String {
        val m = moduloJour(minutesDuJour)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    private fun moduloJour(minutes: Int): Int = ((minutes % 1440) + 1440) % 1440

    private fun intentOuverture(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private data class Direction(
        val cle: String,
        val idNotification: Int,
        val motsDepart: List<String>,
        val motsArrivee: List<String>
    )

    companion object {
        private const val CANAL_SURVEILLANCE = "surveillance"
        private const val CANAL_ALERTE = "alerte"
        private const val CANAL_CHDG = "chien_de_garde"
        private const val ID_SURVEILLANCE = 1
        private const val ID_CHDG = 4

        private val DIRECTIONS = listOf(
            Direction(
                cle = "aller",
                idNotification = 2,
                motsDepart = listOf("Vélizy"),
                motsArrivee = listOf("Arpajon", "Linas")
            ),
            Direction(
                cle = "retour",
                idNotification = 3,
                motsDepart = listOf("Arpajon", "Linas"),
                motsArrivee = listOf("Vélizy")
            )
        )
    }
}
