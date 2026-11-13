package com.example.digicar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView

@SuppressLint("MissingPermission") // Checked in startConnect before any Bluetooth call.
class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var readoutText: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var throttleSlider: ControlSlider
    private lateinit var steeringSlider: ControlSlider
    private lateinit var trimText: TextView

    private val link = CarLink(::showLinkState, ::showRoundTrip)
    private var linkState: LinkState = LinkState.Disconnected()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status)
        readoutText = findViewById(R.id.readout)
        connectButton = findViewById(R.id.connect)
        disconnectButton = findViewById(R.id.disconnect)
        throttleSlider = findViewById(R.id.throttle)
        steeringSlider = findViewById(R.id.steering)
        trimText = findViewById(R.id.trim)

        link.steeringTrimDegrees = getPreferences(MODE_PRIVATE).getInt(PREF_STEERING_TRIM, 0)
        findViewById<Button>(R.id.trim_left).setOnClickListener { changeTrim(-1) }
        findViewById<Button>(R.id.trim_right).setOnClickListener { changeTrim(1) }

        connectButton.setOnClickListener { startConnect() }
        disconnectButton.setOnClickListener { link.disconnect() }
        throttleSlider.onValueChanged = {
            link.throttle = it
            showReadout()
        }
        steeringSlider.onValueChanged = {
            link.steering = it
            showReadout()
        }

        applySystemBarInsets(findViewById(R.id.root))
        showLinkState(LinkState.Disconnected())
    }

    /** The car has no failsafe, so never leave it running behind another app. */
    override fun onStop() {
        super.onStop()
        link.disconnect()
    }

    private fun applySystemBarInsets(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val basePadding = root.paddingLeft
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(
                basePadding + bars.left,
                basePadding + bars.top,
                basePadding + bars.right,
                basePadding + bars.bottom,
            )
            insets
        }
    }

    private fun startConnect() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            statusText.setText(R.string.status_no_bluetooth)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_PERMISSION)
            return
        }
        if (!adapter.isEnabled) {
            startActivityForResult(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE)
            return
        }

        val cars = adapter.bondedDevices
            .filter { it.name?.startsWith(CarProtocol.DEVICE_NAME) == true }
            .sortedBy { it.address }
        when (cars.size) {
            0 -> showPairingHelp()
            1 -> link.connect(cars.single())
            else -> chooseCar(cars)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startConnect()
        } else {
            statusText.setText(R.string.status_permission_denied)
        }
    }

    @Deprecated("Framework callback; this app has no AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE && resultCode == RESULT_OK) startConnect()
    }

    private fun showPairingHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pair_title)
            .setMessage(getString(R.string.pair_message, CarProtocol.DEVICE_NAME))
            .setPositiveButton(R.string.pair_open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Every car advertises the same name, so only the address tells them apart. */
    private fun chooseCar(cars: List<BluetoothDevice>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_car_title)
            .setItems(cars.map { it.address }.toTypedArray()) { _, which -> link.connect(cars[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun changeTrim(delta: Int) {
        val trim = (link.steeringTrimDegrees + delta).coerceIn(
            -CarProtocol.MAX_STEERING_TRIM_DEGREES,
            CarProtocol.MAX_STEERING_TRIM_DEGREES,
        )
        link.steeringTrimDegrees = trim
        getPreferences(MODE_PRIVATE).edit().putInt(PREF_STEERING_TRIM, trim).apply()
        showReadout()
    }

    private fun showRoundTrip(summary: RoundTripSummary) {
        // A summary can arrive just after the link has gone down.
        if (linkState !is LinkState.Connected) return
        statusText.text = getString(R.string.status_connected_round_trip, summary.averageMs, summary.worstMs)
    }

    private fun showLinkState(state: LinkState) {
        linkState = state
        val connected = state is LinkState.Connected
        connectButton.isEnabled = state is LinkState.Disconnected
        disconnectButton.isEnabled = state !is LinkState.Disconnected
        throttleSlider.isEnabled = connected
        steeringSlider.isEnabled = connected
        statusText.setText(
            when (state) {
                LinkState.Connecting -> R.string.status_connecting
                LinkState.Connected -> R.string.status_connected
                is LinkState.Disconnected -> when (state.problem) {
                    null -> R.string.status_disconnected
                    LinkState.Problem.CONNECT_FAILED -> R.string.status_connect_failed
                    LinkState.Problem.LINK_LOST -> R.string.status_link_lost
                }
            },
        )
        showReadout()
    }

    private fun showReadout() {
        val drive = CarProtocol.drive(link.throttle, link.steering, link.steeringTrimDegrees)
        val motion = when {
            !drive.moving -> getString(R.string.readout_stopped)
            drive.forward -> getString(R.string.readout_forward, drive.speedPercent)
            else -> getString(R.string.readout_reverse, drive.speedPercent)
        }
        readoutText.text = getString(R.string.readout, motion, drive.steeringDegrees)
        trimText.text = getString(R.string.trim, link.steeringTrimDegrees)
    }

    private companion object {
        const val REQUEST_PERMISSION = 1
        const val REQUEST_ENABLE = 2
        const val PREF_STEERING_TRIM = "steering_trim"
    }
}
