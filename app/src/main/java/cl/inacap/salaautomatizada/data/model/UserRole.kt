package cl.inacap.salaautomatizada.data.model

enum class UserRole(val roleName: String, val isControlAllowed: Boolean) {
    ADMIN("admin", true),
    PROFESOR("profesor", false);

    companion object {
        fun fromString(role: String?, email: String? = null): UserRole {
            val cleanEmail = email?.trim()?.lowercase().orEmpty()
            val cleanRole = role?.trim()?.lowercase().orEmpty()

            // Si el correo contiene "admin" o es admin@sala.cl, se fuerza ADMIN
            if (cleanEmail.startsWith("admin@") || cleanEmail.contains("admin")) {
                return ADMIN
            }

            // Variantes comunes de roles administradores
            return when (cleanRole) {
                "admin", "administrador", "admin@sala.cl", "administrator", "superadmin" -> ADMIN
                else -> PROFESOR
            }
        }
    }
}
