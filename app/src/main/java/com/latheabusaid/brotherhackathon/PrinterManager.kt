// Credit to Lazaro Herrera for making the initial java version of this and validating most printers
// Ported to Kotlin, cleaned up, and slightly modified by Lathe Abusaid

package com.latheabusaid.brotherhackathon

import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.widget.Toast
import com.brother.ptouch.sdk.CustomPaperInfo
import com.brother.ptouch.sdk.LabelInfo
import com.brother.ptouch.sdk.Printer
import com.brother.ptouch.sdk.PrinterInfo
import com.brother.ptouch.sdk.Unit
import java.io.*
import java.util.*

@Suppress("MemberVisibilityCanBePrivate")
object PrinterManager {
    /** Usage:
     * Establish connection to printer (findPrinter() is simplest method)
     * Pass context in with setWorkingDirectory()
     * Set label type
     * Print with SDK functions
     */

    // TODO: rework rolls/labels system to allow input of any supported rolls

    // Define supported printers
    val supportedModels = arrayOf(
        "QL-820NWB",
        "QL-1110NWB",
        "RJ-4250WB"
    )

    // Define 'roll' for each printer
    private val ROLLS = arrayOf(
        "DK-2251 (2.4\")",
        "DK-2205 (2.4\")",
        "RD-M01E5 (4\")"
    )

    // Define 'label' for each printer
    private val LABELS = arrayOf(
        "DK-1201 (1.14\" x 3.5\")",
        "DK-1247 (4.07\" x 6.4\")",
        "RD-M03E1 (4\" x 6\")" // "RD-M06E1"
    )

    // Printer definition
    var printer: Printer? = null
        private set

    // Printer info definition
    private var info: PrinterInfo? = null

    // Printer model definition
    private var model: PrinterInfo.Model? = null

    // Printer model as string, resets label type when model is changed
    var printerModel: String? = null
        set(value) {
            field = value
            labelType = null
        }

    // Type of label (roll or label)
    private var labelType: String? = null

    // Defines valid connection types
    enum class CONNECTION {
        BLUETOOTH, WIFI, USB
    }
    var connection: CONNECTION? = null

    private var ctx: Context? = null
    private var done = true

    // Returns corresponding label and roll for model
    fun getLabelRoll(): Array<String> {
        if (supportedModels.indexOf(printerModel) != -1) {
            return arrayOf(
                LABELS[supportedModels.indexOf(printerModel)],
                ROLLS[supportedModels.indexOf(printerModel)]
            )
        }
        // Returns empty array if no supported model was found
        return arrayOf()
    }

    // Sets labelType to corresponding label type
    fun loadLabel() {
        labelType = "label"
        when (printerModel) {
            "QL-820NWB", "QL_820NWB" -> {
                info!!.labelNameIndex = LabelInfo.QL700.W29H90.ordinal
                info!!.printMode =
                    PrinterInfo.PrintMode.FIT_TO_PAGE
                info!!.isAutoCut = true
            }
            "QL-1110NWB", "QL_1110NWB" -> {
                info!!.labelNameIndex = LabelInfo.QL1100.W103H164.ordinal
                info!!.printMode =
                    PrinterInfo.PrintMode.FIT_TO_PAGE
                info!!.isAutoCut = true
            }
            "RJ-4250WB", "RJ_4250WB" -> setRJ4250Paper(false)
        }
        toastIt("Load " + labelType + " " + info!!.labelNameIndex + " " + info!!.paperSize + " " + info!!.customPaper)
        printer!!.printerInfo = info
    }

    // Sets labelType to corresponding continuous roll type
    fun loadRoll() {
        labelType = "roll"
        when (printerModel) {
            "QL-820NWB", "QL_820NWB" -> {
                info!!.labelNameIndex = LabelInfo.QL700.W62RB.ordinal
                info!!.printMode =
                    PrinterInfo.PrintMode.FIT_TO_PAGE
                info!!.isAutoCut = true
            }
            "QL-1110NWB", "QL_1110NWB" -> {
                info!!.labelNameIndex = LabelInfo.QL1100.W62.ordinal
                info!!.printMode =
                    PrinterInfo.PrintMode.FIT_TO_PAGE
                info!!.isAutoCut = true
            }
            "RJ-4250WB", "RJ_4250WB" -> setRJ4250Paper(true)
        }
        toastIt("Load " + labelType + " " + info!!.labelNameIndex + " " + info!!.paperSize + " " + info!!.customPaper)
        printer!!.printerInfo = info
    }

    // Custom settings for RJ4250
    private fun setRJ4250Paper(isRoll: Boolean) {
        val width = 102.0f // width in mm
        val margins = 0.0f
        val customPaperInfo: CustomPaperInfo
        customPaperInfo = if (isRoll) {
            CustomPaperInfo.newCustomRollPaper(
                info!!.printerModel,
                Unit.Mm,
                width,
                margins,
                margins,
                margins
            )
        } else {
            val height = 49.92f // height in mm
            CustomPaperInfo.newCustomDiaCutPaper(
                info!!.printerModel,
                Unit.Mm,
                width,
                height,
                margins,
                margins,
                margins,
                margins,
                2.5f
            )
        }
        val errors =
            info!!.setCustomPaperInfo(customPaperInfo)
        if (errors.isNotEmpty()) {
            println(errors.toString())
            return
        }
        info!!.paperSize = PrinterInfo.PaperSize.CUSTOM
        info!!.printMode = PrinterInfo.PrintMode.FIT_TO_PAGE
    }

    // Sets context and working directory for printer SDK to use
    fun setWorkingDirectory(context: Context?) {
        ctx = context
        info!!.workPath = ctx!!.filesDir.absolutePath + "/"
    }

    // ???
    fun findNetworkPrinterManually() {
        done = false
        printer = Printer()
        info = printer!!.printerInfo
        model = PrinterInfo.Model.valueOf(printerModel!!.replace("-", "_"))
    }

    // Manually set IP for network connection
    fun connectNetworkPrinterManually(ip: String?) {
        info!!.printerModel = model
        info!!.port = PrinterInfo.Port.NET
        info!!.ipAddress = ip
        printer!!.printerInfo = info
        done = true
    }

    // Auto connects to printer when given model and desired connection type
    fun findPrinter(newPrinterModel: String?, newConnection: CONNECTION?) {
        printerModel = newPrinterModel
        connection = newConnection
        model = PrinterInfo.Model.valueOf(newPrinterModel!!.replace("-", "_"))
        findPrinter()
    }

    // Sets up printer with configured settings
    private fun findPrinter() {
        done = false
        printer = Printer()
        info = printer!!.printerInfo
        if (labelType != null) {
            toastIt("Reloading $labelType")
            when (labelType) {
                "label" -> loadLabel()
                "roll" -> loadRoll()
            }
        }
        toastIt("Searching for printer")
        when (connection) {
            CONNECTION.BLUETOOTH -> {
                val bluetoothAdapter = BluetoothAdapter
                    .getDefaultAdapter()
                if (bluetoothAdapter != null) {
                    if (!bluetoothAdapter.isEnabled) {
                        printer = null // Not enabled.
                        done = true
                        return
                    }
                }
                val pairedDevices = getPairedBluetoothDevices(bluetoothAdapter)
                for (device in pairedDevices) {
                    if (device.name.contains(printerModel!!)) {
                        toastIt("Direct Bluetooth: " + printerModel + " " + device.name)
                        model = PrinterInfo.Model.valueOf(
                            printerModel!!.replace("-", "_")
                        )
                        printer!!.setBluetooth(BluetoothAdapter.getDefaultAdapter())
                        info?.printerModel = model
                        info?.port = PrinterInfo.Port.BLUETOOTH
                        info?.macAddress = device.address
                        done = true
                        return
                    }
                }
                val bleList =
                    printer!!.getBLEPrinters(BluetoothAdapter.getDefaultAdapter(), 30)
                for (printer in bleList) {
                    if (printer.localName.contains(printerModel!!)) {
                        toastIt("Direct BLE: " + printerModel + " " + printer.localName)
                        model = PrinterInfo.Model.valueOf(
                            printerModel!!.replace("-", "_")
                        )
                        info?.port = PrinterInfo.Port.BLE
                        info?.localName = printer.localName // Probably wrong.
                        done = true
                        return
                    }
                }
                for (device in pairedDevices) {
                    var i = 0
                    while (i < supportedModels.size) {
                        if (device.name.contains(supportedModels[i])) {
                            toastIt(
                                "Fallback Bluetooth: " + supportedModels[i] + " " + device.name
                            )
                            model = PrinterInfo.Model.valueOf(
                                supportedModels[i]!!.replace("-", "_")
                            )
                            printer!!.setBluetooth(BluetoothAdapter.getDefaultAdapter())
                            printerModel =
                                model.toString().replace("_", "-")
                            info?.printerModel = model
                            info?.port =
                                PrinterInfo.Port.BLUETOOTH
                            info?.macAddress = device.address
                            done = true
                            return
                        }
                        i++
                    }
                }
                for (printer in bleList) {
                    var i = 0
                    while (i < supportedModels.size) {
                        if (printer.localName.contains(supportedModels[i])) {
                            toastIt("Fallback BLE: " + supportedModels[i] + " " + printer.localName)
                            model = PrinterInfo.Model.valueOf(
                                supportedModels[i]!!.replace("-", "_")
                            )
                            printerModel =
                                model.toString().replace("_", "-")
                            info?.port = PrinterInfo.Port.BLE
                            info?.localName = printer.localName // Probably wrong.
                            done = true
                            return
                        }
                        i++
                    }
                }
                printer = null // No BL-based printers.
                done = true
            }
            CONNECTION.WIFI -> {
                var name = "Brother $printerModel"
                var printerList =
                    printer!!.getNetPrinters(name)
                for (printer in printerList) {
                    toastIt("Direct WiFi: $name")
                    model = PrinterInfo.Model.valueOf(
                        printer.modelName!!.replace("-", "_").split("Brother ")
                            .toTypedArray()[1]
                    )
                    printerModel =
                        model.toString().replace("_", "-")
                    info?.printerModel = model
                    info?.port = PrinterInfo.Port.NET
                    info?.ipAddress = printer.ipAddress
                    done = true
                    break
                }
                for (s in supportedModels) {
                    name = "Brother $s"
                    printerList = printer!!.getNetPrinters(name)
                    for (printer in printerList) {
                        toastIt("Fallback WiFi: $name")
                        model = PrinterInfo.Model.valueOf(
                            printer.modelName!!.replace("-", "_").split("Brother ")
                                .toTypedArray()[1]
                        )
                        printerModel = model.toString().replace("_", "-")
                        info?.printerModel = model
                        info?.port = PrinterInfo.Port.NET
                        info?.ipAddress = printer.ipAddress
                        done = true
                        break
                    }
                }
                printer = null // No Net-based printers.
                done = true
            }
            CONNECTION.USB -> {
                toastIt("USB: YOLO")
                info?.port =
                    PrinterInfo.Port.USB // YOLO. USB-printers?
                done = true
            }
            else -> {
                toastIt("Default Case")
                printer = null // Error, add nothing.
                done = true
            }
        }
    }

    // Returns list of paired bluetooth devices
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private fun getPairedBluetoothDevices(bluetoothAdapter: BluetoothAdapter?): List<BluetoothDevice> {
        val pairedDevices = bluetoothAdapter!!.bondedDevices
        if (pairedDevices == null || pairedDevices.size == 0) {
            return ArrayList()
        }
        val devices = ArrayList<BluetoothDevice>()
        for (device in pairedDevices) {
            if (device.type != BluetoothDevice.DEVICE_TYPE_LE) {
                devices.add(device)
            }
        }
        return devices
    }

    // ------------------- Helper Functions -------------------

    // Creates a toast for information displaying
    private fun toastIt(text: String) {
        val duration = Toast.LENGTH_SHORT

//        val toast = Toast.makeText(ctx, text, duration)
//        toast.show()
    }
}

