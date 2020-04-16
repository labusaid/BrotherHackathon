package com.example.brotherhackathontesting

import android.bluetooth.BluetoothAdapter
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.brother.ptouch.sdk.LabelInfo
import com.brother.ptouch.sdk.Printer
import com.brother.ptouch.sdk.PrinterInfo
import com.brother.ptouch.sdk.PrinterInfo.ErrorCode
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File


class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    fun yourGreatFeature() {
        Log.d(TAG, "yourGreatFeature() invoked")

        val printer = Printer()
        val settings: PrinterInfo = printer.getPrinterInfo()
        settings.printerModel = PrinterInfo.Model.QL_1110NWB

        // For Bluetooth:
        printer.setBluetooth(BluetoothAdapter.getDefaultAdapter())
        settings.port = PrinterInfo.Port.BLUETOOTH
        settings.macAddress = "18:04:ED:68:2A:88"

        // Print Settings
        settings.labelNameIndex = LabelInfo.QL1100.W102H51.ordinal
        settings.printMode = PrinterInfo.PrintMode.FIT_TO_PAGE
        settings.isAutoCut = true
        printer.printerInfo = settings

        Thread(Runnable {
            if (printer.startCommunication()) {
                // Put any code to use printer

                val bitmap1 = R.drawable.david
                Log.d(TAG, bitmap1.toString())

                val fileName = "@drawable/david.bmp"
                val file = File(fileName)
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)

                val result = printer.printImage(bitmap)
                if (result.errorCode != ErrorCode.ERROR_NONE) {
                    Log.d(TAG, "ERROR - " + result.errorCode)
                }
                Log.d(TAG, "Communication Established")
                printer.endCommunication()
            }
        }).start()


        // For Network:
//        settings.port = PrinterInfo.Port.NET
//        settings.ipAddress = "your-printer-ip-address"
        // For USB:
//        settings.port = PrinterInfo.Port.USB
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        fab.setOnClickListener { view ->
            yourGreatFeature()
//            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
//                    .setAction("Action", null).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when(item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }
}
