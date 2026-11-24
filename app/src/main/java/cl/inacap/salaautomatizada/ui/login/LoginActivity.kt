package cl.inacap.salaautomatizada.ui.login

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cl.inacap.salaautomatizada.data.model.UserRole
import cl.inacap.salaautomatizada.data.repository.UserRepository
import cl.inacap.salaautomatizada.databinding.ActivityLoginBinding
import cl.inacap.salaautomatizada.security.Iso27400SecurityManager
import cl.inacap.salaautomatizada.ui.admin.AdminActivity
import cl.inacap.salaautomatizada.ui.room.UserRoomActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private val userRepository = UserRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        // Verificar si el usuario ya tiene sesión activa
        val currentUser = auth.currentUser
        if (currentUser != null) {
            routeUserByRole(currentUser)
            return
        }

        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Por favor ingrese correo y contraseña", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        val user = auth.currentUser
                        Iso27400SecurityManager.auditLog("LOGIN_FIREBASE", "EXITOSO: $email")
                        if (user != null) {
                            routeUserByRole(user)
                        }
                    } else {
                        Iso27400SecurityManager.auditLog("LOGIN_FIREBASE", "FALLIDO: ${task.exception?.message}")
                        Toast.makeText(this, "Error de autenticación: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }

        binding.btnRegister.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Ingrese correo y contraseña para registrarse", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password.length < 6) {
                Toast.makeText(this, "La contraseña debe tener al menos 6 caracteres", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        val user = auth.currentUser
                        Iso27400SecurityManager.auditLog("REGISTRO_FIREBASE", "EXITOSO: $email")
                        Toast.makeText(this, "Registro exitoso en Firebase", Toast.LENGTH_SHORT).show()
                        if (user != null) {
                            routeUserByRole(user)
                        }
                    } else {
                        Iso27400SecurityManager.auditLog("REGISTRO_FIREBASE", "FALLIDO: ${task.exception?.message}")
                        Toast.makeText(this, "Error en registro: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    private fun routeUserByRole(user: FirebaseUser) {
        userRepository.getUserRole(user.uid, user.email) { role ->
            val targetIntent = if (role == UserRole.ADMIN) {
                Toast.makeText(this, "Acceso concedido como Administrador", Toast.LENGTH_SHORT).show()
                Intent(this, AdminActivity::class.java)
            } else {
                Toast.makeText(this, "Acceso concedido a Sala (Profesor)", Toast.LENGTH_SHORT).show()
                Intent(this, UserRoomActivity::class.java)
            }
            startActivity(targetIntent)
            finish()
        }
    }
}
