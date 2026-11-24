package cl.inacap.salaautomatizada.ui.admin

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cl.inacap.salaautomatizada.data.repository.TelemetryRepository
import cl.inacap.salaautomatizada.databinding.ActivityAdminBinding
import cl.inacap.salaautomatizada.security.Iso27400SecurityManager
import cl.inacap.salaautomatizada.ui.dialogs.OperatorPinDialog
import cl.inacap.salaautomatizada.ui.login.LoginActivity
import cl.inacap.salaautomatizada.ui.room.UserRoomActivity
import com.google.firebase.auth.FirebaseAuth
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Panel de Administración Industrial OT.
 * Sincroniza automáticamente los sensores reales del Arduino UNO R4 WiFi con la interfaz
 * y con Firebase Cloud Firestore en tiempo real.
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private lateinit var auth: FirebaseAuth
    private val telemetryRepository = TelemetryRepository()
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var isUpdatingSwitchProgrammatically = false
    private var currentFanState = false
    private var arduinoIp = "192.168.4.1"
    private var isArduinoConnected = false
    private var activeEndpoint = ""

    private val arduinoSyncRunnable = object : Runnable {
        override fun run() {
            pollArduinoAndSyncToFirebase()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollAdmin) { v, insets ->
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

        val prefs = getSharedPreferences("sala_settings", MODE_PRIVATE)
        arduinoIp = prefs.getString("arduino_ip", "192.168.4.1") ?: "192.168.4.1"

        val email = user.email.orEmpty()
        binding.tvAdminEmail.text = "$email · Rol: Administrador General OT"
        binding.tvAdminConnectionMsg.text = "Buscando enlace con Arduino UNO R4 WiFi y Firebase..."

        Iso27400SecurityManager.auditLog("ADMIN_LOGIN", "Panel de administración iniciado por $email")

        // Escuchar auditoría de seguridad ISO 27400
        Iso27400SecurityManager.setLogListener { logs ->
            runOnUiThread {
                binding.tvAdminAuditLogs.text = logs.joinToString("\n")
            }
        }

        // Salir
        binding.btnLogoutAdmin.setOnClickListener {
            auth.signOut()
            Iso27400SecurityManager.auditLog("ADMIN_LOGOUT", "Sesión de admin cerrada por $email")
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        // Previsualizar la interfaz de usuario / sala
        binding.btnViewAsRoomUser.setOnClickListener {
            startActivity(Intent(this, UserRoomActivity::class.java))
        }

        // Control Manual del Ventilador (Switch con PIN ISO 27400)
        binding.switchAdminFan.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener

            setSwitchSilently(!isChecked)

            OperatorPinDialog(
                context = this,
                onAuthorized = {
                    Iso27400SecurityManager.auditLog(
                        "CONTROL_VENTILADOR",
                        if (isChecked) "ENCENDIDO (PIN Autorizado)" else "APAGADO (PIN Autorizado)"
                    )
                    applyFanState(isChecked)
                },
                onDenied = {
                    Iso27400SecurityManager.auditLog("CONTROL_VENTILADOR", "DENEGADO (PIN inválido o cancelado)")
                    Toast.makeText(this, "PIN incorrecto o cancelado. Acción bloqueada por ISO 27400.", Toast.LENGTH_LONG).show()
                }
            ).show()
        }

        // Parada de Emergencia Inmediata
        binding.btnEmergencyStop.setOnClickListener {
            Iso27400SecurityManager.auditLog("PARADA_EMERGENCIA", "¡Parada de emergencia ejecutada por operador!")
            applyFanState(false)
            Toast.makeText(this, "¡PARADA DE EMERGENCIA EJECUTADA! Actuador apagado.", Toast.LENGTH_LONG).show()
        }

        // Botón Conectar / Forzar lectura con Arduino
        binding.btnAdminConnectWifi.setOnClickListener {
            Toast.makeText(this, "Sincronizando con Arduino UNO R4...", Toast.LENGTH_SHORT).show()
            pollArduinoAndSyncToFirebase(showToastOnConnect = true)
        }

        // Botón Cambiar IP del Arduino
        binding.btnAdminSetIp.setOnClickListener {
            showChangeIpDialog()
        }

        // Limpiar visor de auditoría
        binding.btnClearAuditLogs.setOnClickListener {
            binding.tvAdminAuditLogs.text = "[CONSOLA REINICIADA - ESPERANDO NUEVOS EVENTOS]"
            Iso27400SecurityManager.auditLog("AUDITORIA", "Visor de consola limpiado por admin")
        }

        // Escuchar Firebase como respaldo y sincronizar automáticamente con Arduino
        startListeningTelemetry()
        handler.post(arduinoSyncRunnable)
    }

    private fun startListeningTelemetry() {
        telemetryRepository.startTelemetryListener(
            onDataChanged = { data ->
                if (!isArduinoConnected) {
                    runOnUiThread {
                        updateAdminUI(data.temp, data.hum, data.people, data.light, data.fanState)
                    }
                }
            },
            onError = { err ->
                if (!isArduinoConnected) {
                    runOnUiThread {
                        Toast.makeText(this, "Aviso Firebase: $err", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun pollArduinoAndSyncToFirebase(showToastOnConnect: Boolean = false) {
        io.execute {
            val result = telemetryRepository.fetchFromArduinoEndpoint(arduinoIp)
            if (result != null) {
                val (data, endpoint) = result
                val wasConnected = isArduinoConnected
                isArduinoConnected = true
                activeEndpoint = endpoint

                // Sincronizar lectura real del Arduino hacia Firebase Firestore
                telemetryRepository.syncTelemetryFromArduino(data)

                runOnUiThread {
                    binding.btnAdminConnectWifi.text = "Arduino Conectado (Sincronizando)"
                    binding.tvAdminConnectionMsg.text = "Enlace Activo con Arduino UNO R4 ($endpoint) + Firebase Cloud"
                    updateAdminUI(data.temp, data.hum, data.people, data.light, data.fanState)
                    if (!wasConnected || showToastOnConnect) {
                        Iso27400SecurityManager.auditLog("ARDUINO_SYNC", "Datos en vivo recibidos desde Arduino ($endpoint)")
                        if (showToastOnConnect) {
                            Toast.makeText(this, "Arduino conectado: ${String.format(Locale.getDefault(), "%.1f °C", data.temp)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                isArduinoConnected = false
                if (showToastOnConnect) {
                    runOnUiThread {
                        binding.tvAdminConnectionMsg.text = "Sin respuesta directa del Arduino. Leyendo desde Firebase Cloud."
                        Toast.makeText(this, "No se detectó el Arduino en $arduinoIp. Usando Firebase Cloud.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showChangeIpDialog() {
        val input = EditText(this).apply {
            setText(arduinoIp)
            hint = "Ej.: 192.168.4.1 o 10.0.2.2:8085"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Configurar IP de Arduino")
            .setMessage("Ingrese la dirección IP del Arduino UNO R4 WiFi:")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val newIp = input.text.toString().trim().removePrefix("http://").trimEnd('/')
                if (newIp.isNotBlank()) {
                    arduinoIp = newIp
                    getSharedPreferences("sala_settings", MODE_PRIVATE).edit().putString("arduino_ip", arduinoIp).apply()
                    Iso27400SecurityManager.auditLog("CONFIG_IP", "IP Arduino configurada: $arduinoIp")
                    pollArduinoAndSyncToFirebase(showToastOnConnect = true)
                }
            }.show()
    }

    private fun updateAdminUI(temp: Double, hum: Double, people: Int, light: String, fan: Boolean) {
        binding.tvAdminTemp.text = String.format(Locale.getDefault(), "%.1f °C", temp)
        binding.tvAdminHumidity.text = String.format(Locale.getDefault(), "%.0f %%", hum)
        binding.tvAdminLight.text = light
        binding.tvAdminPeople.text = people.toString()

        currentFanState = fan
        setSwitchSilently(fan)
        binding.tvAdminFanStateDesc.text = if (fan) "Estado: Encendido (Manual/Auto)" else "Estado: Apagado"

        val critical = temp > 25.0
        val warm = temp in 24.0..25.0
        binding.cardAdminAlert.visibility = if (critical) View.VISIBLE else View.GONE
        when {
            critical -> {
                binding.cardAdminTemp.setCardBackgroundColor(Color.parseColor("#FEE2E2"))
                binding.tvAdminTempBadge.text = "🔴 ¡Alerta Crítica! (> 25°C)"
                binding.tvAdminTempBadge.setTextColor(Color.parseColor("#DC2626"))
            }
            warm -> {
                binding.cardAdminTemp.setCardBackgroundColor(Color.parseColor("#FFF3E0"))
                binding.tvAdminTempBadge.text = "🟡 Precaución: Cálido (24-25°C)"
                binding.tvAdminTempBadge.setTextColor(Color.parseColor("#EF6C00"))
            }
            else -> {
                binding.cardAdminTemp.setCardBackgroundColor(Color.parseColor("#F7F5F0"))
                binding.tvAdminTempBadge.text = "🟢 Óptimo (< 24°C)"
                binding.tvAdminTempBadge.setTextColor(Color.parseColor("#2E7D32"))
            }
        }
    }

    private fun applyFanState(newState: Boolean) {
        currentFanState = newState
        setSwitchSilently(newState)
        binding.tvAdminFanStateDesc.text = if (newState) "Estado: Encendido" else "Estado: Apagado"

        // 1. Enviar al Arduino físico
        io.execute {
            val sent = telemetryRepository.sendFanCommandToArduino(newState, arduinoIp)
            if (sent) {
                Iso27400SecurityManager.auditLog("ARDUINO_ACTUADOR", "Relé ventilador actualizado a: $newState")
            }
        }

        // 2. Actualizar en Firebase Cloud
        telemetryRepository.updateFanState(
            newState = newState,
            onSuccess = {
                Toast.makeText(this, "Ventilador actualizado en Arduino y Firebase", Toast.LENGTH_SHORT).show()
            },
            onFailure = { err ->
                Toast.makeText(this, "Aviso Firebase: $err", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun setSwitchSilently(value: Boolean) {
        isUpdatingSwitchProgrammatically = true
        binding.switchAdminFan.isChecked = value
        isUpdatingSwitchProgrammatically = false
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(arduinoSyncRunnable)
        handler.post(arduinoSyncRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(arduinoSyncRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(arduinoSyncRunnable)
        Iso27400SecurityManager.setLogListener(null)
        telemetryRepository.stopTelemetryListener()
    }
}
