package cl.inacap.salaautomatizada.data.repository

import cl.inacap.salaautomatizada.data.model.TelemetryData
import cl.inacap.salaautomatizada.security.Iso27400SecurityManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TelemetryRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    private var listenerRegistration: ListenerRegistration? = null

    fun startTelemetryListener(
        onDataChanged: (TelemetryData) -> Unit,
        onError: (String) -> Unit
    ) {
        listenerRegistration?.remove()

        listenerRegistration = db.collection("sala_automatizada")
            .document("telemeter")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Iso27400SecurityManager.auditLog("FIRESTORE_SYNC", "Error telemetría: ${error.message}")
                    onError(error.message ?: "Error desconocido")
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val temp = snapshot.getDouble("temperatura") ?: 24.0
                    val hum = snapshot.getDouble("humedad") ?: 50.0
                    val people = snapshot.getLong("personas")?.toInt() ?: 0
                    val light = snapshot.getString("luz") ?: "Óptimo"
                    val fanState = snapshot.getBoolean("ventilador") ?: false

                    val data = TelemetryData(temp, hum, people, light, fanState)
                    onDataChanged(data)
                } else {
                    val initialData = mapOf(
                        "temperatura" to 24.5,
                        "humedad" to 55.0,
                        "personas" to 2,
                        "luz" to "Óptimo",
                        "ventilador" to false
                    )
                    db.collection("sala_automatizada").document("telemeter").set(initialData)
                }
            }
    }

    fun stopTelemetryListener() {
        listenerRegistration?.remove()
        listenerRegistration = null
    }

    fun syncTelemetryFromArduino(data: TelemetryData) {
        val payload = mapOf(
            "temperatura" to data.temp,
            "humedad" to data.hum,
            "personas" to data.people,
            "luz" to data.light,
            "ventilador" to data.fanState
        )
        db.collection("sala_automatizada")
            .document("telemeter")
            .set(payload)
    }

    fun fetchFromArduinoEndpoint(customIp: String? = null): Pair<TelemetryData, String>? {
        val candidates = linkedSetOf<String>()
        if (!customIp.isNullOrBlank()) {
            candidates.add(customIp.trim().removePrefix("http://").trimEnd('/'))
        }
        candidates.add("10.0.2.2:8085")
        candidates.add("127.0.0.1:8085")
        candidates.add("192.168.4.1")

        for (endpoint in candidates) {
            try {
                val connection = (URL("http://$endpoint/estado").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1500
                    readTimeout = 1500
                }
                val code = connection.responseCode
                if (code in 200..299) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()
                    val json = JSONObject(response)
                    val temp = json.optDouble("temperatura", Double.NaN)
                    val hum = json.optDouble("humedad", Double.NaN)
                    if (!temp.isNaN() && !hum.isNaN()) {
                        val people = json.optInt("personas", 2)
                        val light = json.optString("luz", "Óptimo")
                        val fan = json.optBoolean("ventilador", false)
                        return Pair(TelemetryData(temp, hum, people, light, fan), endpoint)
                    }
                } else {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                // Probar siguiente candidato
            }
        }
        return null
    }

    fun sendFanCommandToArduino(newState: Boolean, customIp: String? = null): Boolean {
        val candidates = linkedSetOf<String>()
        if (!customIp.isNullOrBlank()) {
            candidates.add(customIp.trim().removePrefix("http://").trimEnd('/'))
        }
        candidates.add("10.0.2.2:8085")
        candidates.add("127.0.0.1:8085")
        candidates.add("192.168.4.1")

        val body = JSONObject().put("ventilador", newState).toString()
        for (endpoint in candidates) {
            try {
                val conn = (URL("http://$endpoint/ventilador").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 1500
                    readTimeout = 1500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) {
                    return true
                }
            } catch (_: Exception) {
                // Probar siguiente
            }
        }
        return false
    }

    fun updateFanState(newState: Boolean, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        db.collection("sala_automatizada").document("telemeter")
            .update("ventilador", newState)
            .addOnSuccessListener {
                Iso27400SecurityManager.auditLog("CONTROL_VENTILADOR", "Estado cambiado a: $newState")
                onSuccess()
            }
            .addOnFailureListener { exception ->
                Iso27400SecurityManager.auditLog("CONTROL_VENTILADOR_ERROR", "Fallo al actualizar: ${exception.message}")
                onFailure(exception.message ?: "Error al actualizar ventilador")
            }
    }
}
