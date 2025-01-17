package com.latheabusaid.brotherhackathon

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Size
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.brother.ptouch.sdk.PrinterInfo.ErrorCode
import com.google.common.util.concurrent.ListenableFuture
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetector
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetectorOptions
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.latheabusaid.brotherhackathon.GlobalState.Companion.detectedVin
import com.latheabusaid.brotherhackathon.GlobalState.Companion.onUpdateVin
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
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    // CameraX analyzer for VIN tags using ML Kit Vision
    class VinAnalyzer : ImageAnalysis.Analyzer {
        private val vinDetector: FirebaseVisionBarcodeDetector by lazy {
            println("vinAnalyzer instantiated")
            // ML Kit barcode options (CODE_39 for VINs)
            val options = FirebaseVisionBarcodeDetectorOptions.Builder()
                .setBarcodeFormats(
                    FirebaseVisionBarcode.FORMAT_CODE_39
                )
                .build()
            FirebaseVision.getInstance().getVisionBarcodeDetector(options)
        }

        private fun degreesToFirebaseRotation(degrees: Int): Int = when (degrees) {
            0 -> FirebaseVisionImageMetadata.ROTATION_0
            90 -> FirebaseVisionImageMetadata.ROTATION_90
            180 -> FirebaseVisionImageMetadata.ROTATION_180
            270 -> FirebaseVisionImageMetadata.ROTATION_270
            else -> throw Exception("Rotation must be 0, 90, 180, or 270.")
        }

        @SuppressLint("UnsafeExperimentalUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            val degrees = imageProxy.imageInfo.rotationDegrees
            val mediaImage = imageProxy.image
            val imageRotation = degreesToFirebaseRotation(degrees)
            if (mediaImage != null) {
                val image = FirebaseVisionImage.fromMediaImage(mediaImage, imageRotation)
                // Pass image to an ML Kit Vision API
                vinDetector.detectInImage(image)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            // Task completed successfully
                            onUpdateVin(barcode.rawValue!!)
                        }
                    }
                    .addOnFailureListener {
                        // Task failed with an exception
                        println("could not read barcode")
                    }
            }
            // Manually close to prevent stalling
            imageProxy.close()
        }
    }

    // Gets camera from camera provider and runs bindCamera()
    private fun startCamera() {
        println("starting camera")
        // Camera setup
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        // Check cameraProvider availability
        cameraProviderFuture.addListener(Runnable {
            val cameraProvider = cameraProviderFuture.get()
            bindCamera(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    // Simple Executor for running on a new Thread
    internal class ThreadPerTaskExecutor : Executor {
        override fun execute(r: Runnable?) {
            Thread(r).start()
        }
    }

    // Binds CameraX analyzer and preview
    private fun bindCamera(cameraProvider: ProcessCameraProvider) {
        println("binding camera")
        val preview: Preview = Preview.Builder()
            .build()

        // Image analysis
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1920, 1080))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val cameraSelector: CameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        imageAnalysis.setAnalyzer(ThreadPerTaskExecutor(), VinAnalyzer())

        val camera = cameraProvider.bindToLifecycle(
            this as LifecycleOwner,
            cameraSelector,
            imageAnalysis,
            preview
        )

        preview.setSurfaceProvider(previewView.createSurfaceProvider(camera.cameraInfo))
    }

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

    // Vehicle data class
    @IgnoreExtraProperties
    data class Vehicle(
        var Vin: String? = "None",
        var Make: String? = "None",
        var Model: String? = "None",
        var Year: String? = "None",
        var Type: String? = "None",
        var LicencePlate: String? = "None"
    )

    // Calls NTHSA API with given vin and returns JSON object with info
    private suspend fun lookupVIN(vinToLookup: String?) = withContext(Dispatchers.IO) {

        // 'I' seems to be improperly identified on VIN tag barcodes,
        // simple fix to remove since no region codes (and thus valid VINs) start with 'I'
        val vinToLookupSanitized = vinToLookup!!.removePrefix("I")

        val client = OkHttpClient()

        val request: Request = Request.Builder()
            .url("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVin/$vinToLookupSanitized?format=json")
            .build()

        var jObject = JSONObject()

        client.newCall(request).execute().use { apiResponse ->
            if (!apiResponse.isSuccessful) throw IOException("Unexpected code $apiResponse")

            for ((name, value) in apiResponse.headers) {
                println("$name: $value")
            }

            // Create JSONObject from response body
            jObject = JSONObject(apiResponse.body!!.string())
        }

        onLookupVIN(jObject)
    }

    // lookupVIN callback (parses data and handles ticket creation
    private fun onLookupVIN(jObject: JSONObject) {
        // Extract data
        val newVehicle = Vehicle(
            jObject.get("SearchCriteria").toString().removePrefix("VIN:"),
            JSONObject(jObject.getJSONArray("Results").get(6).toString()).get("Value") as String,
            JSONObject(jObject.getJSONArray("Results").get(8).toString()).get("Value") as String,
            JSONObject(jObject.getJSONArray("Results").get(9).toString()).get("Value") as String,
            JSONObject(jObject.getJSONArray("Results").get(23).toString()).get("Value") as String,
            "None"
        )

        println("New Vehicle added: $newVehicle")

        // Generate and print ticket
        printBitmap(createTicket(newVehicle))
    }

    // Function to call lookupVIN asynchronously
    private fun lookupVINAsync(vinToLookup: String?) = GlobalScope.async {
        lookupVIN(vinToLookup)
    }

    // Raises Intent for voice recognition dialogue
    private fun startVoiceRecognitionActivity() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        intent.putExtra(
            RecognizerIntent.EXTRA_PROMPT,
            "Read Out Licence Plate"
        )
        startActivityForResult(intent, REQUEST_VOICE_RECOGNITION)
    }

    // Firebase reference
    private val database: DatabaseReference = Firebase.database.reference

    // Generates ticket with given info and returns bitmap
    private fun createTicket(newVehicle: Vehicle): Bitmap {
        println("Creating new ticket")
        // generate QR code
        val multiFormatWriter = MultiFormatWriter()
        val bitMatrix: BitMatrix =
            multiFormatWriter.encode("valet.latheabusaid.com/retrieve/" + newVehicle.Vin, BarcodeFormat.QR_CODE, 200, 200)
        val barcodeEncoder = BarcodeEncoder()
        val qrCodeBitmap: Bitmap = barcodeEncoder.createBitmap(bitMatrix)

        database.child("vehicle_inventory").child(newVehicle.Vin!!).setValue(newVehicle)

        val templateBmp = assetsToBitmap("valetTicket.bmp")
        val mutableBitmap: Bitmap = templateBmp?.copy(Bitmap.Config.ARGB_8888, true)!!

        // Canvas and Paint setup
        val canvas = Canvas(mutableBitmap)
        val textPaint = Paint()
        textPaint.setARGB(255, 0, 0, 0) // pure black
        textPaint.textAlign = Paint.Align.LEFT
        //textPaint.setTypeface()
        textPaint.textSize = 128F

        // Write text from given list of lines
        val xPos = 200f
        var yPos = 450f
        // writes first 3 lines of text
        canvas.drawText(newVehicle.Year!!, xPos, yPos, textPaint)
        yPos -= ((textPaint.descent() + textPaint.ascent()) * 1.5).toInt()
        canvas.drawText(newVehicle.Make!!, xPos, yPos, textPaint)
        yPos -= ((textPaint.descent() + textPaint.ascent()) * 1.5).toInt()
        canvas.drawText(newVehicle.Model!!, xPos, yPos, textPaint)

        // Draw QR code on ticket
        canvas.drawBitmap(qrCodeBitmap, Rect(25, 25, 175, 175), Rect(1200, 300, 1800, 900), null)

        return mutableBitmap
    }

    var selectedPrinterModel: String? = null
    var selectedConnectionType: CONNECTION = CONNECTION.BLUETOOTH

    // Copies preferences from globally shared prefs
    private fun loadPrinterPreferences() {
        val prefs = applicationContext
            .getSharedPreferences("printer_settings", Context.MODE_PRIVATE)
        val printer = prefs.getString("printer", null)
        val rawConnection = prefs.getString("connection", null)
        var connection: CONNECTION? = null
        if (rawConnection != null && rawConnection != "null") {
            connection = CONNECTION.valueOf(rawConnection)
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

    // Prints bitmap with hard coded settings
    private fun printBitmap(bitmapToPrint: Bitmap) {
        Thread(Runnable {
            // Configure connection
            findPrinter(selectedPrinterModel, selectedConnectionType)
            PrinterManager.setWorkingDirectory(this)
            //loadLabel()
            loadRoll()
            val printer = PrinterManager.printer // copies printer reference for easier calls

            // Establish connection
            if (printer != null) {
                if (printer.startCommunication()) {
                    println("Printer communication established")
                    // Put any code to use printer
                    val result = printer.printImage(bitmapToPrint)
                    if (result.errorCode != ErrorCode.ERROR_NONE) {
                        println("ERROR - " + result.errorCode)
                    }
                    printer.endCommunication()
                    println("Printer communication ended")
                }
            }
        }).start()
    }

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private val PERMISSIONS_REQUEST_CAMERA = 10
    private val REQUEST_VOICE_RECOGNITION = 11

    // Activity onCreate
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check permissions
        if (ContextCompat.checkSelfPermission(
                this.applicationContext,
                Manifest.permission.CAMERA
            )
            != PackageManager.PERMISSION_GRANTED
        ) {

            println("Requesting camera permission")
            // No explanation needed, we can request the permission.
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA), PERMISSIONS_REQUEST_CAMERA
            )
        } else {
            // Permission has already been granted
            startCamera()
        }

        // UI Setup
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        // Printer configuration spinners
        val validPrinters = resources.getStringArray(R.array.printers_array)
        val validConnectionTypes = resources.getStringArray(R.array.connections_array)

        val printerSpinner: Spinner = findViewById(R.id.printer_spinner)
        val connectionSpinner: Spinner = findViewById(R.id.connection_spinner)

        val printerSpinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item, validPrinters
        )
        printerSpinner.adapter = printerSpinnerAdapter
        printerSpinner.onItemSelectedListener = object :
            AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View,
                position: Int,
                id: Long
            ) {
                selectedPrinterModel = validPrinters[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // write code to perform some action
            }
        }

        val connectionSpinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item, validConnectionTypes
        )
        connectionSpinner.adapter = connectionSpinnerAdapter
        connectionSpinner.onItemSelectedListener = object :
            AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View,
                position: Int,
                id: Long
            ) {
                when (validConnectionTypes[position]) {
                    "Bluetooth" -> selectedConnectionType = CONNECTION.BLUETOOTH
                    "Wifi" -> selectedConnectionType = CONNECTION.WIFI
                    "USB" -> selectedConnectionType = CONNECTION.USB
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // write code to perform some action
            }
        }

        // Printer setup
        loadPrinterPreferences()

        // Buttons
        val printButton = findViewById<Button>(R.id.print_button)
        printButton.setOnClickListener {
            println("Current VIN is: $detectedVin")
            lookupVINAsync(detectedVin)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            PERMISSIONS_REQUEST_CAMERA -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    // permission was granted
                    startCamera()
                } else {
                    // permission denied
                }
                return
            }

            // Add other 'when' lines to check for other
            // permissions this app might request.
            else -> {
                // Ignore all other requests.
            }
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

    // Handles results from raised Intents
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Voice recognition dialogue handling
        if (requestCode == REQUEST_VOICE_RECOGNITION && resultCode == Activity.RESULT_OK) {
            val results: List<String> =
                data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)!!
            val mAnswer = results[0]
            println("Speech Result: $mAnswer")
        }
    }
}


// Class for keeping and accessing global variables within the app
class GlobalState : Application() {
    companion object {
        var detectedVin: String? = null
        fun onUpdateVin(newVin: String) {
            detectedVin = newVin
        }
    }
}
