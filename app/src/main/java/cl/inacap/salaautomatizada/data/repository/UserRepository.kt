package cl.inacap.salaautomatizada.data.repository

import cl.inacap.salaautomatizada.data.model.UserRole
import cl.inacap.salaautomatizada.security.Iso27400SecurityManager
import com.google.firebase.firestore.FirebaseFirestore

class UserRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    fun getUserRole(uid: String, email: String?, onResult: (UserRole) -> Unit) {
        val cleanEmail = email?.trim()?.lowercase().orEmpty()
        val isAdminEmail = cleanEmail.startsWith("admin@") || cleanEmail.contains("admin")
        val defaultRole = if (isAdminEmail) UserRole.ADMIN else UserRole.PROFESOR

        db.collection("usuarios").document(uid)
            .get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val roleStr = document.getString("rol")
                    var role = UserRole.fromString(roleStr, cleanEmail)

                    // Si por correo es administrador pero en Firebase Firestore quedó como profesor, lo corregimos automáticamente en la BD
                    if (isAdminEmail && role != UserRole.ADMIN) {
                        db.collection("usuarios").document(uid).update("rol", UserRole.ADMIN.roleName)
                        role = UserRole.ADMIN
                    }
                    Iso27400SecurityManager.auditLog("USER_ROLE", "Rol obtenido para $cleanEmail: ${role.roleName}")
                    onResult(role)
                } else {
                    // Si el documento aún no existe en Firebase, lo crea con el rol correcto
                    val defaultData = mapOf(
                        "email" to cleanEmail,
                        "rol" to defaultRole.roleName
                    )
                    db.collection("usuarios").document(uid).set(defaultData)
                    Iso27400SecurityManager.auditLog("USER_CREATED_FIREBASE", "Usuario registrado en Firestore con rol: ${defaultRole.roleName}")
                    onResult(defaultRole)
                }
            }
            .addOnFailureListener { e ->
                Iso27400SecurityManager.auditLog("USER_ROLE_ERROR", "Fallo obteniendo rol en Firestore: ${e.message}")
                // En caso de error de red o permisos en Firestore, aseguramos que admin@sala.cl conserve rol ADMIN
                onResult(defaultRole)
            }
    }
}
