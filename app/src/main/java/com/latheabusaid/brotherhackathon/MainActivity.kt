package com.latheabusaid.brotherhackathon

import android.annotation.SuppressLint
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
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

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
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "BMP_ToBeScanned", /* prefix */
            ".bmp", /* suffix */
            storageDir /* directory */
        ).apply {
            // Save a file: path for use with ACTION_VIEW intents
            currentPhotoPath = absolutePath
            println("File saved to ${absolutePath}")
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
                    println("File saved to URI: $photoURI")
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

            println("Bitmap loaded: $imageBitmap")
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
            println("attempting to scan barcode")
            val result = detector.detectInImage(image)
                .addOnSuccessListener { barcodes ->
                    println("${barcodes.size} barcodes successfully scanned")
                    for (barcode in barcodes) {
                        // Task completed successfully
                        println("barcode value: ${barcode.rawValue}")
                        lookupVINAsync(barcode.rawValue)
                    }
                }
                .addOnFailureListener {
                    // Task failed with an exception
                    println("could not read barcode")
                }
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
        val vehicleModel = JSONObject(jObject.getJSONArray("Results").get(8).toString()).get("Value")
        val vehicleYear = JSONObject(jObject.getJSONArray("Results").get(9).toString()).get("Value")
        val vehicleType = JSONObject(jObject.getJSONArray("Results").get(23).toString()).get("Value")
        println("Make: $vehicleMake\nModel: $vehicleModel\nYear: $vehicleYear")

        // Generate and print ticket
        val lines = listOf<String>(vehicleYear.toString(), vehicleMake.toString(), vehicleModel.toString())
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

    // Prints bitmap with hard coded settings
    private fun printBitmap(bitmapToPrint: Bitmap) {
        Thread(Runnable {
            // Configure connection
            findPrinter("RJ-4250WB", CONNECTION.BLUETOOTH)
//            findPrinter("QL-1110NWB", CONNECTION.WIFI)
            PrinterManager.setWorkingDirectory(this)
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

    // Android functions

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // UI Setup
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        //        //<editor-fold desc="webView Stuff">
//        // WebView for ticket generation
//        val webView: WebView = findViewById(R.id.wv)
//        webView.webViewClient = WebViewClient()
//        webView.settings.javaScriptEnabled = true
//        webView.settings.loadWithOverviewMode = true
//        webView.settings.useWideViewPort = true
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//            WebView.enableSlowWholeDocumentDraw();
//        }
//        val ticketHTML = "file:android_asset/valetTicket.html"
//        // Render the HTML file on WebView
//        webView.loadUrl(ticketHTML)
//
//        webView.webViewClient = object : WebViewClient() {
//            override fun onPageFinished(view: WebView, url: String) {
//                // do your stuff here
//                webView.measure(
//                    View.MeasureSpec.makeMeasureSpec(
//                        View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED
//                    ),
//                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
//                )
//                webView.layout(
//                    0, 0, webView.measuredWidth,
//                    webView.measuredHeight
//                )
//                webView.isDrawingCacheEnabled = true
//                webView.buildDrawingCache()
//                val bm = Bitmap.createBitmap(
//                    webView.measuredWidth,
//                    webView.measuredHeight, Bitmap.Config.ARGB_8888
//                )
//                val bigcanvas = Canvas(bm!!)
//                val paint = Paint()
//                val iHeight = bm.height
//                bigcanvas.drawBitmap(bm, 0f, iHeight.toFloat(), paint)
//                webView.draw(bigcanvas)
//                println(
//                    "1111111111111111111111="
//                            + bigcanvas.width
//                )
//                println(
//                    "22222222222222222222222="
//                            + bigcanvas.height
//                )
//                try {
//                    val path =
//                        Environment.getExternalStorageDirectory()
//                            .toString()
//                    var fOut: OutputStream? = null
//                    val file = File(path, "/aaaa.png")
//                    fOut = FileOutputStream(file)
//                    bm.compress(Bitmap.CompressFormat.PNG, 50, fOut)
//                    fOut.flush()
//                    fOut.close()
//                    bm.recycle()
//                } catch (e: Exception) {
//                    e.printStackTrace()
//                }
//            }
//        }
//
//        println("Generating Ticket")
//        //</editor-fold>

        // Printer setup
        loadPrinterPreferences()

        // Setup listeners
        fab.setOnClickListener {
        }

        imageView.setOnClickListener {
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
