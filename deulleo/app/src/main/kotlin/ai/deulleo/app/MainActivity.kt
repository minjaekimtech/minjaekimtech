package ai.deulleo.app

import ai.deulleo.app.ui.DeulleoApp
import ai.deulleo.app.ui.UiActions
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    /** 위치는 dangerous 권한이라 런타임에 요청합니다. 나머지 Pleos 권한은 Pleos App Market 승인 범위에서 부여됩니다. */
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionRequest.launch(arrayOf("pleos.car.permission.FUSED_LOCATION"))

        val actions = UiActions(
            brief = vm::brief,
            startTrip = vm::startTrip,
            listen = vm::listen,
            submitText = vm::submitText,
            simulateTire = vm::simulateTire,
            simulateArrival = vm::simulateArrival,
        )
        setContent {
            val state by vm.ui.collectAsStateWithLifecycle()
            DeulleoApp(state, actions)
        }
    }
}
