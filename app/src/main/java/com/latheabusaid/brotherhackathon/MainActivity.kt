package com.latheabusaid.brotherhackathon

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.brother.ptouch.sdk.PrinterInfo.ErrorCode
import com.latheabusaid.brotherhackathon.PrinterManager.CONNECTION
import com.latheabusaid.brotherhackathon.PrinterManager.findPrinter
import com.latheabusaid.brotherhackathon.PrinterManager.loadLabel
import com.latheabusaid.brotherhackathon.PrinterManager.loadRoll
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import java.io.IOException


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
        val templateBmp = assetsToBitmap("blankLabelTemplate.bmp")
        val mutableBitmap: Bitmap = templateBmp?.copy(Bitmap.Config.ARGB_8888, true)!!

        // Canvas and Paint setup
        val canvas = Canvas(mutableBitmap)
        val textPaint = Paint()
        textPaint.setARGB(255, 0, 0, 0) // pure black
        textPaint.textAlign = Paint.Align.CENTER
        //textPaint.setTypeface()
        textPaint.textSize = 72F

        // Centering logic
        val textHeight = textPaint.descent() - textPaint.ascent()
        val textOffset = textHeight / 2 - textPaint.descent()

        val bounds = RectF(0F, 0F, canvas.width.toFloat(), canvas.height.toFloat())

        // Write text from first line in given list of lines
        canvas.drawText(linesToWrite[0], bounds.centerX(), bounds.centerY() + textOffset, textPaint)

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

    private var mySensorManager: SensorManager? = null
    private var myProximitySensor: Sensor? = null
    var proximitySensorEventListener: SensorEventListener = object : SensorEventListener {
        var timeStamp = System.currentTimeMillis()

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
                if (event.values[0] == 0F) {
                    // On proximity state changed to close
                    timeStamp = System.currentTimeMillis()
                } else {
                    // On proximity state changed to far
                    val elapsedTime: Long = System.currentTimeMillis() - timeStamp
                    // Time between events in milliseconds
                    if (elapsedTime <= 500) {
                        startVoiceRecognitionActivity()
                    }
                }
            }
        }
    }

    private val VOICE_RECOGNITION_REQUEST_CODE = 1234

    // Activity onCreate
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI Setup
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        // Printer configuration stuff
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


        // Proximity sensor stuff
        mySensorManager = getSystemService(
            Context.SENSOR_SERVICE
        ) as SensorManager
        myProximitySensor = mySensorManager!!.getDefaultSensor(
            Sensor.TYPE_PROXIMITY
        )
        if (myProximitySensor == null) {
            println("No proximity sensor found!")
        } else {
            mySensorManager!!.registerListener(
                proximitySensorEventListener,
                myProximitySensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }

        // Printer setup
        loadPrinterPreferences()

        // Button listeners
        btn_speak.setOnClickListener {
            startVoiceRecognitionActivity()
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

    fun startVoiceRecognitionActivity() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        intent.putExtra(
            RecognizerIntent.EXTRA_PROMPT,
            "Speech recognition demo"
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
            printBitmap(createTicket(listOf(mAnswer)))
        }

    }
}
