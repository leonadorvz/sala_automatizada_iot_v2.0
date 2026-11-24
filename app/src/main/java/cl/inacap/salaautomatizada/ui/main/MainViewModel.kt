package cl.inacap.salaautomatizada.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import cl.inacap.salaautomatizada.data.model.TelemetryData
import cl.inacap.salaautomatizada.data.model.UserRole
import cl.inacap.salaautomatizada.data.repository.TelemetryRepository
import cl.inacap.salaautomatizada.data.repository.UserRepository
import cl.inacap.salaautomatizada.security.Iso27400SecurityManager

class MainViewModel(
    private val userRepository: UserRepository = UserRepository(),
    private val telemetryRepository: TelemetryRepository = TelemetryRepository()
) : ViewModel() {

    private val _telemetryData = MutableLiveData<TelemetryData>()
    val telemetryData: LiveData<TelemetryData> = _telemetryData

    private val _userRole = MutableLiveData(UserRole.PROFESOR)
    val userRole: LiveData<UserRole> = _userRole

    private val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> = _toastMessage

    private val _errorMessage = MutableLiveData<String>()
    val errorMessage: LiveData<String> = _errorMessage

    val temperatureThreshold = 30.0

    fun loadUserRole(uid: String, email: String?) {
        userRepository.getUserRole(uid, email) { role ->
            _userRole.value = role
            Iso27400SecurityManager.auditLog(
                "CONTROL_ACCESO_ROL",
                "Usuario: $email | Rol: ${role.roleName} | Control habilitado: ${role.isControlAllowed}"
            )
        }
    }

    fun startTelemetryListening() {
        telemetryRepository.startTelemetryListener(
            onDataChanged = { data ->
                _telemetryData.value = data
            },
            onError = { err ->
                _errorMessage.value = err
            }
        )
    }

    fun requestFanStateChange(onPinRequired: () -> Unit) {
        val currentRole = _userRole.value ?: UserRole.PROFESOR
        if (!currentRole.isControlAllowed) {
            _toastMessage.value = "Acceso denegado: El rol '${currentRole.roleName}' solo tiene permisos de visualización."
            Iso27400SecurityManager.auditLog("CONTROL_DENEGADO", "Usuario con rol '${currentRole.roleName}' intentó controlar ventilador")
            return
        }

        onPinRequired()
    }

    fun confirmFanStateChange(newState: Boolean) {
        telemetryRepository.updateFanState(
            newState = newState,
            onSuccess = {
                _toastMessage.value = "Ventilador actualizado en Arduino/Cloud"
            },
            onFailure = { err ->
                _toastMessage.value = "Error actualizando actuador: $err"
            }
        )
    }

    override fun onCleared() {
        super.onCleared()
        telemetryRepository.stopTelemetryListener()
    }
}
