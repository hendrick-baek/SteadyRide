package ai.steadyride.app

import ai.steadyride.app.ui.MainScreen
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var controller: SteadyRideController

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Restart so the Car API subscriptions pick up the new grants.
            controller.stop()
            controller.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = SteadyRideController(applicationContext)

        val missing = RUNTIME_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())

        setContent {
            val ui by controller.ui.collectAsStateWithLifecycle()
            val log by controller.actuator.log.collectAsStateWithLifecycle()
            val ambientLook by controller.ambient.look.collectAsStateWithLifecycle()
            MainScreen(
                s = ui,
                actionLog = log,
                ambientLook = ambientLook,
                onAmbient = controller::setAmbientMotion,
                onMode = controller::setMode,
                onComfort = controller::setManualComfort,
                onRouteSearch = { controller.searchComfortRoute() },
                onNamyangScenario = controller::startNamyangScenario,
                onNavigationScreen = controller::showNavigation,
                onRouteResearch = controller::researchRoute,
                onRouteTolerance = controller::setRouteTolerance,
                onCueSound = controller::setCueSound,
                onTone = controller::toggleTone,
                onPreviewSound = controller::previewCueSound,
            )
        }
        handleVoiceAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleVoiceAction(intent)
    }

    // Guide: stop sensor work and screen updates in the background, restore on return.
    override fun onStart() {
        super.onStart()
        controller.start()
    }

    override fun onStop() {
        controller.stop()
        super.onStop()
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }

    /** Gleo AI voice actions declared in assets/schema.json. */
    private fun handleVoiceAction(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_START -> {
                controller.setManualComfort(true)
                replyToGleo(action, "success", "Motion sickness relief mode is on: fresh air and passenger seat ventilation.")
            }
            ACTION_STOP -> {
                controller.setManualComfort(null)
                replyToGleo(action, "success", "Motion sickness relief mode is back to automatic.")
            }
            ACTION_ROUTE -> {
                val ok = controller.searchComfortRoute()
                val min = controller.ui.value.route.toleranceMin
                replyToGleo(
                    action,
                    if (ok) "success" else "fail",
                    if (ok) "Looking for the gentlest route that arrives at most $min minutes later." else "A route search is already running.",
                )
            }
        }
    }

    private fun replyToGleo(functionName: String, status: String, message: String) {
        runCatching {
            val json = JSONObject()
                .put("functionName", functionName)
                .put("status", status)
                .put("message", message)
            sendBroadcast(
                Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE")
                    .putExtra("packageName", packageName)
                    .putExtra("response", json.toString()),
            )
        }.onFailure { Log.w("SteadyRide", "Gleo reply failed", it) }
    }

    private companion object {
        const val ACTION_START = "START_MOTION_SICKNESS_RELIEF"
        const val ACTION_STOP = "STOP_MOTION_SICKNESS_RELIEF"
        const val ACTION_ROUTE = "REQUEST_COMFORTABLE_ROUTE"

        val RUNTIME_PERMISSIONS = listOf(
            "android.car.permission.CAR_SPEED",
        )
    }
}
