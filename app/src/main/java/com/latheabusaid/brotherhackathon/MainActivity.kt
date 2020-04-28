package com.latheabusaid.brotherhackathon

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.brother.ptouch.sdk.PrinterInfo.ErrorCode
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetectorOptions
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.latheabusaid.brotherhackathon.PrinterManager.CONNECTION
import com.latheabusaid.brotherhackathon.PrinterManager.findPrinter
import com.latheabusaid.brotherhackathon.PrinterManager.loadLabel
import com.latheabusaid.brotherhackathon.PrinterManager.loadRoll
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import java.io.IOException
import java.io.InputStream


class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    // Dispatches camera intent
    val REQUEST_IMAGE_CAPTURE = 1
    private fun dispatchTakePictureIntent() {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            takePictureIntent.resolveActivity(packageManager)?.also {
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
            }
        }
    }

    // Captures thumbnail from camera intent
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            val imageBitmap = data?.extras?.get("data") as Bitmap
            imageView.setImageBitmap(imageBitmap)
        }
    }


    private fun scanBarcodes(image: FirebaseVisionImage) {
        // ML Kit barcode options (CODE_39 for VINs)
        val options = FirebaseVisionBarcodeDetectorOptions.Builder()
            .setBarcodeFormats(
                FirebaseVisionBarcode.FORMAT_CODE_39)
            .build()

        val detector = FirebaseVision.getInstance().visionBarcodeDetector

        val result = detector.detectInImage(image)
            .addOnSuccessListener { barcodes ->
                // Task completed successfully
                // [START_EXCLUDE]
                // [START get_barcodes]
                for (barcode in barcodes) {
                    val bounds = barcode.boundingBox
                    val corners = barcode.cornerPoints

                    val rawValue = barcode.rawValue

                    val valueType = barcode.valueType
                    // See API reference for complete list of supported types
                    when (valueType) {
                        FirebaseVisionBarcode.TYPE_WIFI -> {
                            val ssid = barcode.wifi!!.ssid
                            val password = barcode.wifi!!.password
                            val type = barcode.wifi!!.encryptionType
                        }
                        FirebaseVisionBarcode.TYPE_URL -> {
                            val title = barcode.url!!.title
                            val url = barcode.url!!.url
                        }
                    }
                }
                // [END get_barcodes]
                // [END_EXCLUDE]
            }
            .addOnFailureListener {
                // Task failed with an exception
                // ...
            }
        // [END run_detector]
    }

    private fun loadPrinterPreferences() {
        val prefs = applicationContext
            .getSharedPreferences("printer_settings", Context.MODE_PRIVATE)
        val printer = prefs.getString("printer", null)
        val rawconnection = prefs.getString("connection", null)
        var connection: CONNECTION? = null
        if (rawconnection != null && rawconnection != "null") {
            connection = CONNECTION.valueOf(rawconnection)
        }
        val mode = prefs.getString("mode", null)
        if (printer != null) {
            PrinterManager.printerModel = printer
        }
        if (connection != null) {
            PrinterManager.connection = connection
        }
        if (printer != null && connection != null) {
            findPrinter(printer, connection)
        }
        if (mode != null) {
            when (mode) {
                "label" -> loadLabel()
                "roll" -> loadRoll()
            }
        }
    }

    // Returns bitmap from .bmp in asset folder
    fun getBitmapFromAsset(context: Context, filePath: String?): Bitmap? {
        val assetManager = context.assets
        val istr: InputStream
        var bitmap: Bitmap? = null
        try {
            istr = assetManager.open(filePath!!)
            bitmap = BitmapFactory.decodeStream(istr)
        } catch (e: IOException) {
            // handle exception
        }
        return bitmap
    }

    private fun printBMP() {
        Log.d(TAG, "printBMP() invoked")
        Thread(Runnable {
            // Configure connection
            PrinterManager.findPrinter("QL-1110NWB", CONNECTION.BLUETOOTH)
            PrinterManager.loadRoll()
            val printer = PrinterManager.printer

            // Establish connection
            if (printer != null) {
                if (printer.startCommunication()) {
                    Log.d(TAG, "Printer communication established")
                    // Put any code to use printer
                    val result = printer.printImage(getBitmapFromAsset(applicationContext,"testbumpmap.bmp"))
                    if (result.errorCode != ErrorCode.ERROR_NONE) {
                        Log.d(TAG, "ERROR - " + result.errorCode)
                    }
                    printer.endCommunication()
                    Log.d(TAG, "Printer communication ended")
                }
            }
        }).start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
        loadPrinterPreferences()

        fab.setOnClickListener { view ->
            printBMP()
        }

        imageView.setOnClickListener { view ->
            dispatchTakePictureIntent()
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
