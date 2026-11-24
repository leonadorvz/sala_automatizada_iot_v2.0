package cl.inacap.salaautomatizada.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import cl.inacap.salaautomatizada.data.model.UserRole
import cl.inacap.salaautomatizada.data.repository.UserRepository
import cl.inacap.salaautomatizada.ui.admin.AdminActivity
import cl.inacap.salaautomatizada.ui.login.LoginActivity
import cl.inacap.salaautomatizada.ui.room.UserRoomActivity
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        if (user == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // Enrutar automáticamente según rol en Firebase
        UserRepository().getUserRole(user.uid, user.email) { role ->
            val targetClass = if (role == UserRole.ADMIN) AdminActivity::class.java else UserRoomActivity::class.java
            startActivity(Intent(this, targetClass))
            finish()
        }
    }
}
