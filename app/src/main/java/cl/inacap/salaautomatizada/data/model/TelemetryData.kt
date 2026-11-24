package cl.inacap.salaautomatizada.data.model

data class TelemetryData(
    val temp: Double = 24.0,
    val hum: Double = 50.0,
    val people: Int = 0,
    val light: String = "Óptimo",
    val fanState: Boolean = false
)
