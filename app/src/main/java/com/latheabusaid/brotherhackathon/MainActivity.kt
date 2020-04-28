package com.latheabusaid.brotherhackathon

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    // Dispatches photo intent
    val REQUEST_TAKE_PHOTO = 1
    @SuppressLint("LogConditional")
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
                        this, "com.latheabusaid.brotherhackathon.fileprovider", it)
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                    startActivityForResult(takePictureIntent, REQUEST_TAKE_PHOTO)
                    Log.d(TAG, "File saved to URI: $photoURI")

                    val imageBitmap = assetsToBitmap("vinbarcode.bmp")
                    Log.d(TAG, "Bitmap loaded: $imageBitmap")
                    imageView.setImageBitmap(imageBitmap)
                    val image = imageBitmap?.let { it1 -> FirebaseVisionImage.fromBitmap(it1) }
                    val detector = FirebaseVision.getInstance().visionBarcodeDetector
                    val result = image?.let { it1 ->
                        detector.detectInImage(it1)
                            .addOnSuccessListener { barcodes ->
                                // Task completed successfully
                                for (barcode in barcodes) {
                                    Log.d(TAG, "barcode value: ${barcode.rawValue}")
                                }
                            }
                            .addOnFailureListener {
                                // Task failed with an exception
                                Log.d(TAG, "could not read barcode")
                            }
                    }
                }
            }
        }
    }

    // Method to get a bitmap from assets
    private fun assetsToBitmap(fileName:String):Bitmap?{
        return try{
            val stream = assets.open(fileName)
            BitmapFactory.decodeStream(stream)
        }catch (e:IOException){
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
            "JPEG_${timeStamp}_", /* prefix */
            ".jpg", /* suffix */
            storageDir /* directory */
        ).apply {
            // Save a file: path for use with ACTION_VIEW intents
            currentPhotoPath = absolutePath
            Log.d(TAG, "File saved to ${absolutePath}")
        }
    }

    // Captures thumbnail from camera intent
//    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
//        super.onActivityResult(requestCode, resultCode, data)
//        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
//            val imageBitmap = data?.extras?.get("data") as Bitmap
//            imageView.setImageBitmap(imageBitmap)
//            val image = FirebaseVisionImage.fromBitmap(imageBitmap)
//            val detector = FirebaseVision.getInstance().visionBarcodeDetector
//            val result = detector.detectInImage(image)
//                .addOnSuccessListener { barcodes ->
//                    // Task completed successfully
//                    for (barcode in barcodes) {
//                        Log.d(TAG, "barcode value: ${barcode.rawValue}")
//                    }
//                }
//                .addOnFailureListener {
//                    // Task failed with an exception
//                    Log.d(TAG, "could not read barcode")
//                }
//        }
//    }


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
            }
            .addOnFailureListener {
                // Task failed with an exception
                // ...
            }
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
                    val result = printer.printImage(assetsToBitmap("vinbarcode.bmp"))
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
