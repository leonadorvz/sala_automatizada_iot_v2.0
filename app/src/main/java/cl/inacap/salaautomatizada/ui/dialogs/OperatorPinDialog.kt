package cl.inacap.salaautomatizada.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import cl.inacap.salaautomatizada.databinding.DialogOperatorPinBinding
import cl.inacap.salaautomatizada.security.Iso27400SecurityManager

class OperatorPinDialog(
    private val context: Context,
    private val onAuthorized: () -> Unit,
    private val onDenied: () -> Unit
) {

    fun show() {
        val dialogBinding = DialogOperatorPinBinding.inflate(LayoutInflater.from(context))

        AlertDialog.Builder(context)
            .setTitle("Seguridad Industrial OT (ISO 27400)")
            .setView(dialogBinding.root)
            .setCancelable(false)
            .setPositiveButton("Autorizar") { _, _ ->
                val pin = dialogBinding.etOperatorPin.text.toString().trim()
                if (Iso27400SecurityManager.validateOperatorPin(pin)) {
                    onAuthorized()
                } else {
                    onDenied()
                }
            }
            .setNegativeButton("Cancelar") { _, _ ->
                onDenied()
            }
            .show()
    }
}
