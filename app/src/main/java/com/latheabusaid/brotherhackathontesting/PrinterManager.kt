package com.latheabusaid.brotherhackathontesting

import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import com.brother.ptouch.sdk.CustomPaperInfo
import com.brother.ptouch.sdk.LabelInfo
import com.brother.ptouch.sdk.Printer
import com.brother.ptouch.sdk.PrinterInfo
import com.brother.ptouch.sdk.Unit
import java.io.*
import java.util.*

object PrinterManager {
    val supportedModels = arrayOf(
        "QL-820NWB",
        "QL-1110NWB",
        "RJ-2150",
        "RJ-4250WB",
        "PJ-763",
        "PJ-763MFi",
        "PJ-773"
    )
    private val ROLLS = arrayOf(
        "DK-2251 (2.4\")",
        "DK-2205 (2.4\")",
        "RDQ03U1 (2\" x 4\")",
        "RD-M01E5 (4\")",
        "A4", "A4", "A4"
    )
    private val LABELS = arrayOf(
        "DK-1201 (1.14\" x 3.5\")",
        "DK-1247 (4.07\" x 6.4\")",
        "RDQ01U1 (2\" x 1\")",
        "RD-M03E1 (4\" x 6\")",  // "RD-M06E1"
        "LETTER", "LETTER", "LETTER"
    )
    private var model: PrinterInfo.Model? = null
    private var info: PrinterInfo? = null
    private var printer: Printer? = null
    private var printerModel: String? = null
    private var mode: String? = null
    private var connection: CONNECTION? = null
    private var ctx: Context? = null
    private var done = true
    private const val toast = true

    fun getModel(): String? {
        return printerModel
    }

    fun setModel(m: String?) {
        printerModel = m
        mode = null
    }

    val supportedConnections: Array<CONNECTION>
        get() = CONNECTION.values()

    val labelRoll: Array<String>
        get() {
            if (printerModel != null) {
                for (i in supportedModels.indices) if (supportedModels[i] == printerModel) return arrayOf(
                    LABELS[i],
                    ROLLS[i]
                )
            }
            return arrayOf()
        }

    private fun setRJ2150Paper(isRoll: Boolean) {
        info!!.customPaper =
            ctx!!.filesDir.absolutePath + "/rj_2150_"
        if (isRoll) {
            info!!.customPaper += "roll.bin"
        } else {
            info!!.customPaper += "label.bin"
        }
        info!!.paperSize = PrinterInfo.PaperSize.CUSTOM
        info!!.printMode = PrinterInfo.PrintMode.FIT_TO_PAGE
    }

    private fun setRJ4250Paper(isRoll: Boolean) {
        val width = 102.0f
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
            val height = 152f
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

    fun loadLabel() {
        mode = "label"
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
            "RJ-2150", "RJ_2150" -> setRJ2150Paper(false)
            "RJ-4250WB", "RJ_4250WB" -> setRJ4250Paper(false)
            "PJ-763", "PJ_763", "PJ-763MFi", "PJ_763MFi", "PJ-773", "PJ_773" -> {
                info!!.paperSize = PrinterInfo.PaperSize.LETTER
                info!!.printMode =
                    PrinterInfo.PrintMode.FIT_TO_PAGE
            }
        }
        toastIt("Load " + mode + " " + info!!.labelNameIndex + " " + info!!.paperSize + " " + info!!.customPaper)
        printer!!.printerInfo = info
    }

    fun loadRoll() {
        mode = "roll"
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
            "RJ-2150", "RJ_2150" -> setRJ2150Paper(true)
            "RJ-4250WB", "RJ_4250WB" -> setRJ4250Paper(true)
            "PJ-763", "PJ_763", "PJ-763MFi", "PJ_763MFi", "PJ-773", "PJ_773" -> {
                info!!.paperSize = PrinterInfo.PaperSize.A4
                info!!.printMode =
                    PrinterInfo.PrintMode.FIT_TO_PAGE
            }
        }
        toastIt("Load " + mode + " " + info!!.labelNameIndex + " " + info!!.paperSize + " " + info!!.customPaper)
        printer!!.printerInfo = info
    }

    fun setWorkingDirectory(context: Context?) {
        ctx = context
        raw2file(
            "rj_2150_roll.bin",
            R.raw.rj2150_58mm
        )
        raw2file(
            "rj_2150_label.bin",
            R.raw.rj2150_50x85mm
        )
        info!!.workPath = ctx!!.filesDir.absolutePath + "/"
    }

    fun dashToLower(`val`: String?): String {
        return `val`!!.replace("-", "_")
    }

    fun lowerToDash(`val`: String): String {
        return `val`.replace("_", "-")
    }

    fun findNetworkPrinterManually() {
        done = false
        printer = Printer()
        info = printer!!.printerInfo
        model = PrinterInfo.Model.valueOf(
            dashToLower(printerModel)
        )
    }

    fun connectNetworkPrinterManually(ip: String?) {
        info!!.printerModel = model
        info!!.port = PrinterInfo.Port.NET
        info!!.ipAddress = ip
        printer!!.printerInfo = info
        done = true
    }

    fun findPrinter(printer: String?, conn: CONNECTION?) {
        printerModel = printer
        connection = conn
        model =
            PrinterInfo.Model.valueOf(dashToLower(printer))
        findPrinter(conn)
    }

    private fun findPrinter(conn: CONNECTION?) {
        done = false
        printer = Printer()
        info = printer!!.printerInfo
        if (mode != null) {
            toastIt("Reloading $mode")
            when (mode) {
                "label" -> loadLabel()
                "roll" -> loadRoll()
            }
        }
        toastIt("Searching for printer")
        when (conn) {
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
                val pairedDevices =
                    getPairedBluetoothDevice(bluetoothAdapter)
                for (device in pairedDevices) {
                    if (device.name.contains(printerModel!!)) {
                        toastIt("Direct Bluetooth: " + printerModel + " " + device.name)
                        model = PrinterInfo.Model.valueOf(
                            dashToLower(printerModel)
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
                            dashToLower(printerModel)
                        )
                        info?.port = PrinterInfo.Port.BLE
                        info?.setLocalName(printer.localName) // Probably wrong.
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
                                dashToLower(supportedModels[i])
                            )
                            printer!!.setBluetooth(BluetoothAdapter.getDefaultAdapter())
                            printerModel =
                                lowerToDash(model.toString())
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
                                dashToLower(supportedModels[i])
                            )
                            printerModel =
                                lowerToDash(model.toString())
                            info?.port = PrinterInfo.Port.BLE
                            info?.setLocalName(printer.localName) // Probably wrong.
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
                        dashToLower(printer.modelName).split("Brother ")
                            .toTypedArray()[1]
                    )
                    printerModel =
                        lowerToDash(model.toString())
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
                            dashToLower(printer.modelName).split("Brother ")
                                .toTypedArray()[1]
                        )
                        printerModel =
                            lowerToDash(model.toString())
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

    private fun toastIt(s: String) {
        if (toast) {
            println(s)
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private fun getPairedBluetoothDevice(bluetoothAdapter: BluetoothAdapter?): List<BluetoothDevice> {
        val pairedDevices =
            bluetoothAdapter!!.bondedDevices
        if (pairedDevices == null || pairedDevices.size == 0) {
            return ArrayList()
        }
        val devices = ArrayList<BluetoothDevice>()
        for (device in pairedDevices) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                devices.add(device)
            } else {
                if (device.type != BluetoothDevice.DEVICE_TYPE_LE) {
                    devices.add(device)
                }
            }
        }
        return devices
    }

    /**
     * copy from raw in resource
     */
    private fun raw2file(fileName: String, fileID: Int) {
        val path = ctx!!.filesDir.absolutePath + "/"
        val newdir = File(path)
        if (!newdir.exists()) {
            newdir.mkdir()
        }
        val dstFile = File(path + fileName)
        if (!dstFile.exists()) {
            try {
                val output: OutputStream
                val input: InputStream = ctx!!.resources.openRawResource(fileID)
                output = FileOutputStream(dstFile)
                val defaultBufferSize = 1024 * 4
                val buffer = ByteArray(defaultBufferSize)
                var n: Int
                while (-1 != input.read(buffer).also { n = it }) {
                    output.write(buffer, 0, n)
                }
                input.close()
                output.close()
            } catch (ignored: IOException) {
            }
        }
    }

    enum class CONNECTION {
        BLUETOOTH, WIFI, USB
    }
}