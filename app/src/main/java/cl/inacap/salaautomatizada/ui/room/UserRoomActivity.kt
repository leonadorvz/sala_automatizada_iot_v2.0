package cl.inacap.salaautomatizada.ui.room

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cl.inacap.salaautomatizada.R
import cl.inacap.salaautomatizada.data.model.UserRole
import cl.inacap.salaautomatizada.data.repository.TelemetryRepository
import cl.inacap.salaautomatizada.data.repository.UserRepository
import cl.inacap.salaautomatizada.databinding.ActivityUserRoomBinding
import cl.inacap.salaautomatizada.security.Iso27400SecurityManager
import cl.inacap.salaautomatizada.ui.admin.AdminActivity
import cl.inacap.salaautomatizada.ui.login.LoginActivity
import com.google.firebase.auth.FirebaseAuth
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Interfaz de Usuario / Pantalla de Monitoreo de Sala.
 * Sincroniza en tiempo real los sensores del Arduino UNO R4 WiFi y Firebase Firestore.
 */
class UserRoomActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserRoomBinding
    private lateinit var auth: FirebaseAuth
    private val telemetryRepository = TelemetryRepository()
    private val userRepository = UserRepository()
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var isArduinoDirectActive = false

    private val arduinoPollRunnable = object : Runnable {
        override fun run() {
            pollArduinoSensorData()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityUserRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollUserRoom) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        if (user == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val email = user.email.orEmpty()
        binding.tvUserAccount.text = "$email (Rol: Docente / Vista de Sala)"

        // Cerrar sesión
        binding.btnLogoutUser.setOnClickListener {
            auth.signOut()
            Iso27400SecurityManager.auditLog("LOGOUT_USUARIO", "Sesión de sala cerrada por $email")
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        // Acceso al Panel Administrador
        binding.btnGoToAdmin.setOnClickListener {
            userRepository.getUserRole(user.uid, user.email) { role ->
                if (role == UserRole.ADMIN) {
                    startActivity(Intent(this, AdminActivity::class.java))
                } else {
                    Toast.makeText(this, "Esta cuenta solo tiene permisos de visualización de sala", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Escuchar telemetría desde Firebase Firestore y sincronizar con Arduino
        startListeningTelemetry()
        handler.post(arduinoPollRunnable)
    }

    private fun startListeningTelemetry() {
        telemetryRepository.startTelemetryListener(
            onDataChanged = { data ->
                if (!isArduinoDirectActive) {
                    runOnUiThread {
                        updateRoomUI(data.temp, data.hum, data.people, data.light, data.fanState)
                    }
                }
            },
            onError = { err ->
                if (!isArduinoDirectActive) {
                    runOnUiThread {
                        binding.tvUserSyncStatus.text = "Aviso Firebase: $err"
                    }
                }
            }
        )
    }

    private fun pollArduinoSensorData() {
        val prefs = getSharedPreferences("sala_settings", MODE_PRIVATE)
        val ip = prefs.getString("arduino_ip", "192.168.4.1")
        io.execute {
            val result = telemetryRepository.fetchFromArduinoEndpoint(ip)
            if (result != null) {
                val (data, endpoint) = result
                isArduinoDirectActive = true
                telemetryRepository.syncTelemetryFromArduino(data)
                runOnUiThread {
                    binding.tvUserSyncStatus.text = "Sensores Arduino UNO R4 en Vivo ($endpoint) · Sincronizado con Firebase"
                    updateRoomUI(data.temp, data.hum, data.people, data.light, data.fanState)
                }
            } else {
                isArduinoDirectActive = false
            }
        }
    }

    private fun updateRoomUI(temp: Double, hum: Double, people: Int, light: String, fan: Boolean) {
        binding.tvUserTemperature.text = String.format(Locale.getDefault(), "%.1f °C", temp)
        binding.tvUserHumidity.text = String.format(Locale.getDefault(), "%.0f %%", hum)
        binding.tvUserLight.text = light
        binding.tvUserPeople.text = people.toString()

        // Estado del ventilador (solo lectura)
        if (fan) {
            binding.tvUserFanBadge.text = "Activo (Encendido)"
            binding.tvUserFanBadge.setTextColor(Color.parseColor("#2E7D32"))
            binding.tvUserFanBadge.setBackgroundResource(R.drawable.bg_badge_green)
        } else {
            binding.tvUserFanBadge.text = "Inactivo (Apagado)"
            binding.tvUserFanBadge.setTextColor(Color.parseColor("#1E40AF"))
            binding.tvUserFanBadge.setBackgroundResource(R.drawable.bg_badge_blue)
        }

        // Semáforo de confort de la sala (Sincronizado con el semáforo LED físico > 25°C)
        val isCritical = temp > 25.0
        val isWarm = temp in 24.0..25.0

        when {
            isCritical -> {
                binding.cardRoomComfort.setCardBackgroundColor(Color.parseColor("#FEE2E2"))
                binding.cardRoomComfort.strokeColor = Color.parseColor("#DC2626")
                binding.tvComfortTitle.text = "🔴 ¡ALERTA TÉRMICA EN SALA!"
                binding.tvComfortTitle.setTextColor(Color.parseColor("#DC2626"))
                binding.tvComfortDescription.text = "La temperatura supera los 25.0 °C (Semáforo en ROJO). Ventilación automática encendida."
                binding.tvComfortDescription.setTextColor(Color.parseColor("#991B1B"))

                binding.tvTempStatusBadge.text = "🔴 Crítico (> 25°C)"
                binding.tvTempStatusBadge.setTextColor(Color.parseColor("#DC2626"))
                binding.tvTempStatusBadge.setBackgroundResource(R.drawable.bg_badge_red)
            }
            isWarm -> {
                binding.cardRoomComfort.setCardBackgroundColor(Color.parseColor("#FFF3E0"))
                binding.cardRoomComfort.strokeColor = Color.parseColor("#EF6C00")
                binding.tvComfortTitle.text = "🟡 TEMPERATURA ELEVADA (PRECAUCIÓN)"
                binding.tvComfortTitle.setTextColor(Color.parseColor("#EF6C00"))
                binding.tvComfortDescription.text = "La sala presenta condiciones templadas entre 24°C y 25°C (Semáforo en AMARILLO)."
                binding.tvComfortDescription.setTextColor(Color.parseColor("#E65100"))

                binding.tvTempStatusBadge.text = "🟡 Templado (24-25°C)"
                binding.tvTempStatusBadge.setTextColor(Color.parseColor("#EF6C00"))
            }
            else -> {
                binding.cardRoomComfort.setCardBackgroundColor(Color.parseColor("#E8F5E9"))
                binding.cardRoomComfort.strokeColor = Color.parseColor("#2E7D32")
                binding.tvComfortTitle.text = "🟢 SALA EN CONDICIONES ÓPTIMAS"
                binding.tvComfortTitle.setTextColor(Color.parseColor("#2E7D32"))
                binding.tvComfortDescription.text = "Temperatura, humedad y luz adecuadas para docentes y alumnos (Semáforo en VERDE)."
                binding.tvComfortDescription.setTextColor(Color.parseColor("#1B5E20"))

                binding.tvTempStatusBadge.text = "🟢 Confortable (< 24°C)"
                binding.tvTempStatusBadge.setTextColor(Color.parseColor("#2E7D32"))
                binding.tvTempStatusBadge.setBackgroundResource(R.drawable.bg_badge_green)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(arduinoPollRunnable)
        handler.post(arduinoPollRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(arduinoPollRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(arduinoPollRunnable)
        telemetryRepository.stopTelemetryListener()
    }
}
