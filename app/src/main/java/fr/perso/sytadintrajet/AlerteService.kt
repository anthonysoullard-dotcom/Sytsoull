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
        val seuil = prefs.getInt(KEY_SEUIL, SEUIL_DEFAUT)
        val gestionnaire = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        gestionnaire.notify(ID_SURVEILLANCE, notificationSurveillance(seuil))

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
    }

    private fun notificationSurveillance(seuil: Int = prefsSeuil()): Notification =
        NotificationCompat.Builder(this, CANAL_SURVEILLANCE)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Surveillance du trajet active")
            .setContentText(getString(R.string.surveillance_texte, seuil))
            .setOngoing(true)
            .setContentIntent(intentOuverture())
            .build()

    private fun prefsSeuil(): Int =
        getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_SEUIL, SEUIL_DEFAUT)

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
        private const val ID_SURVEILLANCE = 1

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
