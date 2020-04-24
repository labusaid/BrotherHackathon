package com.latheabusaid.brotherhackathontesting

import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.brother.ptouch.sdk.PrinterInfo
import com.brother.ptouch.sdk.PrinterInfo.ErrorCode
import kotlinx.android.synthetic.main.activity_main.*


class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    fun yourGreatFeature() {
        Log.d(TAG, "yourGreatFeature() invoked")
        val printerModel: PrinterInfo.Model? = PrinterManager.model

        Thread(Runnable {
            if (printer.startCommunication()) {
                // Put any code to use printer

                val result = printer.printPdfFile("label4x6.pdf", 0)
                if (result.errorCode != ErrorCode.ERROR_NONE) {
                    Log.d(TAG, "ERROR - " + result.errorCode)
                }
                Log.d(TAG, "Communication Established")
                printer.endCommunication()
            }
        }).start()
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
