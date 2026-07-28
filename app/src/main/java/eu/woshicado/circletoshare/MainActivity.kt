package eu.woshicado.circletoshare

import android.app.StatusBarManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import eu.woshicado.circletoshare.assist.AssistInteractionService
import eu.woshicado.circletoshare.capture.ScreenshotAccessibilityService
import eu.woshicado.circletoshare.tile.CaptureTileService

class MainActivity : AppCompatActivity() {

    // True while updateStatus() writes the switches, so the listeners can tell
    // programmatic sync from real input. (Checking view.isPressed instead would
    // also drop toggles made via TalkBack or a keyboard.)
    private var syncingSwitches = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_assistant_settings).setOnClickListener {
            openFirstAvailable(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            )
        }
        findViewById<Button>(R.id.btn_accessibility_settings).setOnClickListener {
            openFirstAvailable(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_test).setOnClickListener {
            val service = AssistInteractionService.instance
            if (service == null) {
                Toast.makeText(this, R.string.error_not_assistant, Toast.LENGTH_LONG).show()
            } else {
                service.launchCaptureSession()
            }
        }

        val addTile = findViewById<Button>(R.id.btn_add_tile)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addTile.setOnClickListener {
                // Refuse at the source like the bubble/snap switches, rather
                // than letting the user place a tile that can only ever bounce
                // them back here.
                if (ScreenshotAccessibilityService.instance == null) {
                    Toast.makeText(
                        this, R.string.error_tile_needs_accessibility, Toast.LENGTH_LONG
                    ).show()
                } else {
                    requestAddTile()
                }
            }
        } else {
            // Pre-33 the tile can only be placed by hand from the shade's edit
            // screen, which triggers_body already explains.
            addTile.visibility = View.GONE
        }

        findViewById<MaterialSwitch>(R.id.switch_bubble).setOnCheckedChangeListener { view, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            if (checked && ScreenshotAccessibilityService.instance == null) {
                view.isChecked = false
                Toast.makeText(this, R.string.error_bubble_needs_accessibility, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            ScreenshotAccessibilityService.setBubblePref(this, checked)
        }

        findViewById<MaterialSwitch>(R.id.switch_freeform).setOnCheckedChangeListener { _, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            Prefs.setFreeformCrop(this, checked)
        }

        findViewById<MaterialSwitch>(R.id.switch_snap).setOnCheckedChangeListener { view, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            if (checked && ScreenshotAccessibilityService.instance == null) {
                view.isChecked = false
                Toast.makeText(this, R.string.error_snap_needs_accessibility, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            Prefs.setSnapEnabled(this, checked)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val roleManager = getSystemService(RoleManager::class.java)
        val isAssistant = roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true ||
            Settings.Secure.getString(contentResolver, "voice_interaction_service")
                ?.contains(packageName) == true

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val fallbackEnabled = enabledServices.contains(packageName)

        findViewById<TextView>(R.id.status_assistant).text = getString(
            if (isAssistant) R.string.status_assistant_on else R.string.status_assistant_off
        )
        findViewById<TextView>(R.id.status_accessibility).text = getString(
            if (fallbackEnabled) R.string.status_fallback_on else R.string.status_fallback_off
        )

        syncingSwitches = true
        findViewById<MaterialSwitch>(R.id.switch_bubble).isChecked =
            ScreenshotAccessibilityService.isBubbleEnabled(this)

        findViewById<MaterialSwitch>(R.id.switch_freeform).isChecked =
            Prefs.isFreeformCrop(this)

        findViewById<MaterialSwitch>(R.id.switch_snap).isChecked =
            Prefs.isSnapEnabled(this)
        syncingSwitches = false
    }

    /**
     * Ask the system to show its "add this tile?" prompt. The system silently
     * returns TILE_ALREADY_ADDED without a dialog when the tile is in place,
     * so that case needs its own feedback or the button looks broken.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestAddTile() {
        // Nullable like the RoleManager lookup above — absent on form factors
        // and stripped AOSP images with no status bar service.
        val statusBar = getSystemService(StatusBarManager::class.java)
        if (statusBar == null) {
            Toast.makeText(this, R.string.tile_add_failed, Toast.LENGTH_LONG).show()
            return
        }
        statusBar.requestAddTileService(
            ComponentName(this, CaptureTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_app_glyph),
            mainExecutor
        ) { result ->
            when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                    Toast.makeText(this, R.string.tile_already_added, Toast.LENGTH_SHORT).show()
                // The user saw the dialog and answered (added, or declined /
                // dismissed) — the outcome is on screen already, so stay quiet.
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> Unit
                // Everything else is a TILE_ADD_REQUEST_ERROR_* (1000+), e.g.
                // a request already in progress from a double-tap. No dialog is
                // shown for those, so without this the button looks dead.
                else -> Toast.makeText(this, R.string.tile_add_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openFirstAvailable(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        Toast.makeText(this, R.string.error_no_settings, Toast.LENGTH_SHORT).show()
    }
}
