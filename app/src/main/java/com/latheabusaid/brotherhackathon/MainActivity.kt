package com.latheabusaid.brotherhackathon

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
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
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetector
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetectorOptions
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
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
import com.latheabusaid.brotherhackathon.globalState.Companion.detectedVin
class MainActivity : AppCompatActivity() {

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
        val vehicleMake = JSONObject(jObject.getJSONArray("Results").get(6).toString()).get("Value")
        val vehicleModel =
            JSONObject(jObject.getJSONArray("Results").get(8).toString()).get("Value")
        val vehicleYear = JSONObject(jObject.getJSONArray("Results").get(9).toString()).get("Value")
        val vehicleType =
            JSONObject(jObject.getJSONArray("Results").get(23).toString()).get("Value")
        println("Make: $vehicleMake\nModel: $vehicleModel\nYear: $vehicleYear\n $vehicleType")

        // Generate and print ticket
        val lines =
            listOf<String>(vehicleYear.toString(), vehicleMake.toString(), vehicleModel.toString())
        printBitmap(createTicket(lines))
    }

    // Function to call lookupVIN asynchronously
    private fun lookupVINAsync(vinToLookup: String?) = GlobalScope.async {
        lookupVIN(vinToLookup)
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

    // Generates ticket with given info and returns bitmap
    private fun createTicket(linesToWrite: List<String>): Bitmap {
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
        var xPos = 200
        var yPos = 450
        for (textToWrite in linesToWrite) {
            canvas.drawText(textToWrite, xPos.toFloat(), yPos.toFloat(), textPaint)
            yPos -= ((textPaint.descent() + textPaint.ascent()) * 1.5).toInt()
        }
        return mutableBitmap
    }

    var selectedPrinterModel: String? = null
    var selectedConnectionType: CONNECTION = CONNECTION.BLUETOOTH
    // Prints bitmap with hard coded settings
    private fun printBitmap(bitmapToPrint: Bitmap) {
        Thread(Runnable {
            // Configure connection
            findPrinter(selectedPrinterModel, selectedConnectionType)
            PrinterManager.setWorkingDirectory(this)
            loadLabel()
            //loadRoll()
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

    private val VOICE_RECOGNITION_REQUEST_CODE = 1234

    // Activity onCreate
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
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
                arrayOf(Manifest.permission.CAMERA), MY_PERMISSIONS_REQUEST_CAMERA
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
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
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
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
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
        }


    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            MY_PERMISSIONS_REQUEST_CAMERA -> {
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

    // Simple Executor for running on a new Thread
    internal class ThreadPerTaskExecutor : Executor {
        override fun execute(r: Runnable?) {
            Thread(r).start()
        }
    }

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

        val camera = cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector, imageAnalysis, preview)

        preview.setSurfaceProvider(previewView.createSurfaceProvider(camera.cameraInfo))
    }

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

        private fun degreesToFirebaseRotation(degrees: Int): Int = when(degrees) {
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
                            detectedVin = barcode.rawValue
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
        startActivityForResult(intent, VOICE_RECOGNITION_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_RECOGNITION_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val results: List<String> =
                data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)!!
            val mAnswer = results[0]
            println("Speech Result: $mAnswer")
//            printBitmap(createTicket(listOf(mAnswer)))
        }

    }

    companion object {
        const val MY_PERMISSIONS_REQUEST_CAMERA: Int = 10

    }
}

class globalState : Application() {
    companion object {
        var detectedVin: String? = null
    }
}
