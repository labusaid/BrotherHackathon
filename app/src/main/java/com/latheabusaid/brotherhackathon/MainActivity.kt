package com.latheabusaid.brotherhackathon

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private var barcodeScanUri: Uri? = null

    // Method to get a bitmap from assets
    private fun assetsToBitmap(fileName: String): Bitmap? {
        return try {
            val stream = assets.open(fileName)
            BitmapFactory.decodeStream(stream)
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    lateinit var currentPhotoPath: String

    @Throws(IOException::class)
    private fun createImageFile(): File {
        // Create an image file name
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "BMP_${timeStamp}_", /* prefix */
            ".bmp", /* suffix */
            storageDir /* directory */
        ).apply {
            // Save a file: path for use with ACTION_VIEW intents
            currentPhotoPath = absolutePath
            Log.d(TAG, "File saved to ${absolutePath}")
        }
    }

    // Dispatches photo intent
    val REQUEST_TAKE_PHOTO = 1

    private fun dispatchTakePictureIntent() {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            // Ensure that there's a camera activity to handle the intent
            takePictureIntent.resolveActivity(packageManager)?.also {
                // Create the File where the photo should go
                val photoFile: File? = try {
                    createImageFile()
                } catch (ex: IOException) {
                    // Error occurred while creating the File
                    null
                }
                // Continue only if the File was successfully created
                photoFile?.also {
                    val photoURI: Uri = FileProvider.getUriForFile(
                        this, "com.latheabusaid.brotherhackathon.fileprovider", it
                    )
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                    startActivityForResult(takePictureIntent, REQUEST_TAKE_PHOTO)
                    Log.d(TAG, "File saved to URI: $photoURI")
                    barcodeScanUri = photoURI
                }
            }
        }
    }

    // Captures thumbnail from camera intent
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TAKE_PHOTO && resultCode == RESULT_OK) {

            val imageBitmap = MediaStore.Images.Media.getBitmap(
                this.contentResolver,
                barcodeScanUri
            )

            Log.d(TAG, "Bitmap loaded: $imageBitmap")
            imageView.setImageBitmap(imageBitmap)
            val image = FirebaseVisionImage.fromBitmap(imageBitmap!!)
            // ML Kit barcode options (CODE_39 for VINs)
            val options = FirebaseVisionBarcodeDetectorOptions.Builder()
                .setBarcodeFormats(
                    FirebaseVisionBarcode.FORMAT_CODE_39
                )
                .build()
            // Create barcode detector object
            val detector = FirebaseVision.getInstance().visionBarcodeDetector
            Log.d(TAG, "attempting to scan barcode")
            val result = detector.detectInImage(image)
                .addOnSuccessListener { barcodes ->
                    Log.d(TAG, "${barcodes.size} barcodes successfully scanned")
                    for (barcode in barcodes) {
                        // Task completed successfully
                        Log.d(TAG, "barcode value: ${barcode.rawValue}")
                        printTicket(barcode.rawValue)
                    }
                }
                .addOnFailureListener {
                    // Task failed with an exception
                    Log.d(TAG, "could not read barcode")
                }
        }
    }

    private suspend fun lookupVin() = withContext(Dispatchers.IO) {
        val client = OkHttpClient()

        val request: Request = Request.Builder()
            .url("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVin/2T1BR30E46C595221?format=json")
            .build()

        val response: Response = client.newCall(request).execute()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")

            for ((name, value) in response.headers) {
                println("$name: $value")
            }

            println(response.body!!.string())
        }
    }

    // Copies preferences from globally shared prefs
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

    // Prints ticket with given information
    private fun printTicket(barcode: String?) {
        Log.d(TAG, "printText() invoked")
        Thread(Runnable {
            // Configure connection
//            PrinterManager.findPrinter("RJ-4250WB", CONNECTION.BLUETOOTH)
            PrinterManager.findPrinter("QL-1110NWB", CONNECTION.WIFI)
            PrinterManager.setWorkingDirectory(context = this)
            PrinterManager.loadRoll()
            val printer = PrinterManager.printer

            val templateBmp = assetsToBitmap("blankTicket.bmp")
            val mutableBitmap: Bitmap = templateBmp?.copy(Bitmap.Config.ARGB_8888, true)!!

            val canvas = Canvas(mutableBitmap)
            val textPaint = Paint()
            textPaint.setARGB(255, 0, 0, 0)
            textPaint.textAlign = Paint.Align.CENTER
            //textPaint.setTypeface()
            textPaint.textSize = 40F
            val xPos: Int = canvas.width / 2
            val yPos = (canvas.height / 2 - (textPaint.descent() + textPaint.ascent()) / 2)
            canvas.drawText(barcode!!, xPos.toFloat(), yPos, textPaint);

            // Establish connection
            if (printer != null) {
                if (printer.startCommunication()) {
                    Log.d(TAG, "Printer communication established")
                    // Put any code to use printer
                    val result = printer.printImage(mutableBitmap)
                    if (result.errorCode != ErrorCode.ERROR_NONE) {
                        Log.d(TAG, "ERROR - " + result.errorCode)
                    }
                    printer.endCommunication()
                    Log.d(TAG, "Printer communication ended")
                }
            }
        }).start()
    }

    // Android functions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // UI Setup
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        // Printer setup
        loadPrinterPreferences()

        // Async tasks
        GlobalScope.async {
            lookupVin()
        }

        // Setup listeners

        fab.setOnClickListener { view ->

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
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }
}
