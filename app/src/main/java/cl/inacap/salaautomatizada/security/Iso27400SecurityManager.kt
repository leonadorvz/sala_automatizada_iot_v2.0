package cl.inacap.salaautomatizada.security

import android.util.Log
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gestor de Seguridad OT basado en la norma ISO 27400 para sistemas IoT industriales.
 * Proporciona cifrado/hashing SHA-256 para verificación de PIN de operadores,
 * validación de comandos manuales críticos y registros de auditoría de seguridad.
 */
object Iso27400SecurityManager {
    private const val TAG = "Iso27400SecurityManager"

    // PIN de operador predeterminado: "1234" (Hash SHA-256: 03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4)
    private const val DEFAULT_PIN_HASH = "03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4"

    private val auditLogs = mutableListOf<String>()
    private var logListener: ((List<String>) -> Unit)? = null

    init {
        auditLog("SISTEMA_INICIADO", "Módulo de Seguridad ISO 27400 Activo")
    }

    /**
     * Calcula el hash SHA-256 de una contraseña o PIN en texto plano.
     */
    fun hashPin(pin: String): String {
        return try {
            val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculando hash SHA-256", e)
            ""
        }
    }

    /**
     * Valida si el PIN ingresado por el operador es correcto (ISO 27400 Control de Acceso OT).
     */
    fun validateOperatorPin(enteredPin: String): Boolean {
        val hashedInput = hashPin(enteredPin)
        val isValid = hashedInput.equals(DEFAULT_PIN_HASH, ignoreCase = true)
        auditLog("VERIFICACION_PIN_OPERADOR", if (isValid) "AUTORIZADO (Hash SHA-256 verificado)" else "DENEGADO (PIN inválido)")
        return isValid
    }

    /**
     * Registra eventos de seguridad y comandos manuales según directrices ISO 27400.
     */
    fun auditLog(action: String, status: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $action -> $status"
        synchronized(auditLogs) {
            auditLogs.add(0, entry)
            if (auditLogs.size > 100) {
                auditLogs.removeAt(auditLogs.size - 1)
            }
        }
        Log.w(TAG, "[ISO-27400 AUDIT] $entry")
        logListener?.invoke(getRecentLogs())
    }

    fun getRecentLogs(): List<String> {
        return synchronized(auditLogs) { auditLogs.toList() }
    }

    fun setLogListener(listener: ((List<String>) -> Unit)?) {
        this.logListener = listener
        if (listener != null) {
            listener(getRecentLogs())
        }
    }
}
