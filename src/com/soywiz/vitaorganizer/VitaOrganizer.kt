package com.soywiz.vitaorganizer

import com.soywiz.util.open2
import com.soywiz.util.stream
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.*
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

object VitaOrganizer : JPanel(BorderLayout()) {
    @JvmStatic fun main(args: Array<String>) {
        //PsvitaDevice.discoverIp()
        //SwingUtilities.invokeLater {
        //Create and set up the window.
        val frame = JFrame("VitaOrganizer 0.1")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.iconImage = ImageIO.read(ClassLoader.getSystemResource("vitafrontblk.jpg"))

        //Create and set up the content pane.
        val newContentPane = VitaOrganizer
        newContentPane.isOpaque = true //content panes must be opaque
        frame.contentPane = newContentPane

        //Display the window.
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        //}
    }

    class GameEntry(val gameId: String) {
        val entry = VitaOrganizerCache.entry(gameId)
        val psf by lazy {
            try {
                PSF.read(entry.paramSfoFile.readBytes().stream)
            } catch (e: Throwable) {
                mapOf<String, Any>()
            }
        }
        val id by lazy { psf["TITLE_ID"].toString() }
        val title by lazy { psf["TITLE"].toString() }
        var inVita = false
        var inPC = false
        val vpkFile: String? get() = entry.pathFile.readText(Charsets.UTF_8)
        val size: Long by lazy {
            try {
                entry.sizeFile.readText().toLong()
            } catch (e: Throwable) {
                0L
            }
        }

        override fun toString(): String = id
    }

    val VPK_GAME_IDS = hashSetOf<String>()
    val VITA_GAME_IDS = hashSetOf<String>()

    val statusLabel = JLabel("Started")

    fun updateEntries() {
        val ALL_GAME_IDS = LinkedHashMap<String, GameEntry>()

        fun getGameEntryById(gameId: String) = ALL_GAME_IDS.getOrPut(gameId) { GameEntry(gameId) }

        synchronized(VPK_GAME_IDS) {
            for (gameId in VPK_GAME_IDS) getGameEntryById(gameId).inPC = true
        }
        synchronized(VITA_GAME_IDS) {
            for (gameId in VITA_GAME_IDS) getGameEntryById(gameId).inVita = true
        }

        val newRows = arrayListOf<Array<Any>>()

        for (entry in ALL_GAME_IDS.values.sortedBy { it.title }) {
            try {
                val gameId = entry.gameId
                val entry2 = VitaOrganizerCache.entry(gameId)
                val icon = entry2.icon0File
                val image = ImageIO.read(ByteArrayInputStream(icon.readBytes()))
                val psf = PSF.read(entry2.paramSfoFile.readBytes().stream)

                //println(psf)
                if (image != null) {
                    newRows.add(arrayOf(
                            ImageIcon(getScaledImage(image, 64, 64)),
                            entry,
                            if (entry.inVita && entry.inPC) {
                                "BOTH"
                            } else if (entry.inVita) {
                                "VITA"
                            } else if (entry.inPC) {
                                "PC"
                            } else {
                                "NONE"
                            },
                            FileSize.toString(entry.size),
                            entry.title
                    ))
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        while (model.rowCount > 0) model.removeRow(model.rowCount - 1)
        for (row in newRows) model.addRow(row)

        model.fireTableDataChanged()
    }

    private val DEBUG = true

    val model = object : DefaultTableModel() {
        override fun isCellEditable(row: Int, column: Int): Boolean {
            return false
        }
    }

    init {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())

        //val columnNames = arrayOf("Icon", "ID", "Title")

        //val data = arrayOf(arrayOf(JLabel("Kathy"), "Smith", "Snowboarding", 5, false), arrayOf("John", "Doe", "Rowing", 3, true), arrayOf("Sue", "Black", "Knitting", 2, false), arrayOf("Jane", "White", "Speed reading", 20, true), arrayOf("Joe", "Brown", "Pool", 10, false))

        val table = object : JTable(model) {
            val dialog = this@VitaOrganizer
            val gameTitlePopup = JMenuItem("").apply {
                this.isEnabled = false
            }

            val popupMenu = object : JPopupMenu() {
                var entry: GameEntry? = null

                val deleteFromVita = JMenuItem("Delete from PSVita").apply {
                    addActionListener {
                        val entry = entry
                        if (entry != null) {
                            JOptionPane.showConfirmDialog(dialog, "Are you sure to delete '${entry.title}'?", "Confirmation", JOptionPane.OK_CANCEL_OPTION, JOptionPane.OK_OPTION)
                        }
                    }
                    this.isEnabled = false
                }

                fun createSmallVpk(zip: ZipFile): ByteArray {
                    // out put file
                    val outBytes = ByteArrayOutputStream()
                    val out = ZipOutputStream(outBytes)
                    out.setLevel(Deflater.DEFAULT_COMPRESSION)

                    // name the file inside the zip  file
                    for (e in zip.entries()) {
                        if (e.name == "eboot.bin" || e.name.startsWith("sce_sys/")) {
                            out.putNextEntry(ZipEntry(e.name))
                            out.write(zip.getInputStream(e).readBytes())
                        }
                    }

                    out.close()

                    return outBytes.toByteArray()
                }

                val sendVpkToVita = JMenuItem("Send promoting VPK to PSVita").apply {
                    addActionListener {
                        //JOptionPane.showMessageDialog(frame, "Right-click performed on table and choose DELETE")
                        val entry = entry
                        if (entry != null) {
                            Thread {
                                SwingUtilities.invokeLater {
                                    statusLabel.text = "Generating small VPK for promoting..."
                                }

                                val vpkPath = "ux0:/organizer/${entry.id}.VPK"

                                //val zip = ZipFile(entry.vpkFile)
                                try {
                                    val originalZip = ZipFile(entry.vpkFile)
                                    val vpkData = createSmallVpk(originalZip)

                                    SwingUtilities.invokeLater {
                                        statusLabel.text = "Uploading VPK for promoting (${FileSize.toString(vpkData.size.toLong())})..."
                                    }

                                    PsvitaDevice.uploadFile("/$vpkPath", vpkData)
                                    originalZip.close()

                                    //statusLabel.text = "Processing game ${vitaGameCount + 1}/${vitaGameIds.size} ($gameId)..."
                                } catch (e: Throwable) {
                                    JOptionPane.showMessageDialog(VitaOrganizer, "${e.toString()}", "${e.message}", JOptionPane.ERROR_MESSAGE);
                                }
                                SwingUtilities.invokeLater {
                                    statusLabel.text = "Sent game vpk ${entry.id}"
                                    JOptionPane.showMessageDialog(VitaOrganizer, "Now use VitaShell to install\n$vpkPath\n\nAfer that active ftp again and use this program to Send Data to PSVita", "Actions", JOptionPane.INFORMATION_MESSAGE);
                                }
                            }.start()
                        }
                    }
                }

                val sendToVita = JMenuItem("Send Data to PSVita").apply {
                    addActionListener {
                        //JOptionPane.showMessageDialog(frame, "Right-click performed on table and choose DELETE")
                        val entry = entry
                        if (entry != null) {
                            Thread {
                                SwingUtilities.invokeLater {
                                    statusLabel.text = "Sending game ${entry.id}..."
                                }
                                //val zip = ZipFile(entry.vpkFile)
                                try {
                                    PsvitaDevice.uploadGame(entry.id, ZipFile(entry.vpkFile)) { status ->
                                        //println("$status")
                                        val currentSizeStr = FileSize.toString(status.currentSize)
                                        val totalSizeStr = FileSize.toString(status.totalSize)
                                        SwingUtilities.invokeLater {
                                            statusLabel.text = "Uploading ${entry.id} :: ${status.currentFile}/${status.totalFiles} :: $currentSizeStr/$totalSizeStr"
                                        }
                                    }
                                    //statusLabel.text = "Processing game ${vitaGameCount + 1}/${vitaGameIds.size} ($gameId)..."
                                } catch (e: Throwable) {
                                    JOptionPane.showMessageDialog(VitaOrganizer, "${e.toString()}", "${e.message}", JOptionPane.ERROR_MESSAGE);
                                }
                                SwingUtilities.invokeLater {
                                    statusLabel.text = "Sent game data ${entry.id}"
                                    JOptionPane.showMessageDialog(VitaOrganizer, "Game sent successfully", "Actions", JOptionPane.INFORMATION_MESSAGE);
                                }
                            }.start()
                        }
                    }
                }

                init {
                    add(gameTitlePopup)
                    add(JSeparator())
                    add(deleteFromVita)
                    add(sendVpkToVita)
                    add(sendToVita)
                }

                override fun show(invoker: Component?, x: Int, y: Int) {
                    val entry = entry
                    gameTitlePopup.text = "UNKNOWN"
                    deleteFromVita.isEnabled = false
                    sendToVita.isEnabled = false

                    if (entry != null) {
                        gameTitlePopup.text = "${entry.id} : ${entry.title}"
                        deleteFromVita.isEnabled = entry.inVita
                        //sendToVita.isEnabled = !entry.inVita
                        sendToVita.isEnabled = true
                    }

                    super.show(invoker, x, y)
                }
            }

            init {
                this.componentPopupMenu = popupMenu
            }

            fun showMenuForRow(row: Int) {
                val rect = getCellRect(row, 1, true)
                val entry = dataModel.getValueAt(row, 1) as GameEntry
                popupMenu.entry = entry

                popupMenu.show(this, rect.x, rect.y + rect.height)
            }

            fun showMenu() {
                showMenuForRow(selectedRow)
            }

            override fun getColumnClass(column: Int): Class<*> {
                return getValueAt(0, column).javaClass
            }

            override fun processKeyEvent(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> showMenu()
                    else -> super.processKeyEvent(e)
                }
            }
        }
        table.preferredScrollableViewportSize = Dimension(640, 480)

        if (DEBUG) {
        }

        //Create the scroll pane and add the table to it.
        val scrollPane = JScrollPane(table)

        //Add the scroll pane to this panel.
        //val const = SpringLayout.Constraints()
        //const.setConstraint(SpringLayout.NORTH, Spring.constant(32, 32, 32))
        //const.height = Spring.constant(32, 32, 32)

        val footer = JPanel().apply {
            add(statusLabel)
        }

        val header = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("Select folder...").apply {
                this.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        super.mouseClicked(e)
                        val chooser = JFileChooser()
                        chooser.currentDirectory = java.io.File(".")
                        chooser.dialogTitle = "Select PsVita VPK folder"
                        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        chooser.isAcceptAllFileFilterUsed = false
                        chooser.selectedFile = File(VitaOrganizerSettings.vpkFolder)
                        val result = chooser.showOpenDialog(this@VitaOrganizer)
                        if (result == JFileChooser.APPROVE_OPTION) {
                            VitaOrganizerSettings.vpkFolder = chooser.selectedFile.absolutePath
                            updateFileList()
                        }
                    }
                })
            })

            val connectText = "Connect to PsVita..."
            val disconnectText = "Disconnect from %s"
            var connected = false

            val connectButton = JButton(connectText).apply {
                val button = this

                fun disconnect() {
                    connected = false
                    button.text = connectText
                    synchronized (VITA_GAME_IDS) {
                        VITA_GAME_IDS.clear()
                    }
                    updateEntries()
                    statusLabel.text = "Disconnected"
                }

                fun connect(ip: String) {
                    connected = true
                    VitaOrganizerSettings.lastDeviceIp = ip
                    PsvitaDevice.setIp(ip, 1337)
                    button.text = disconnectText.format(ip)
                    synchronized (VITA_GAME_IDS) {
                        VITA_GAME_IDS.clear()
                    }
                    var done = false
                    var updated = false
                    Thread {
                        try {
                            var vitaGameCount = 0
                            val vitaGameIds = PsvitaDevice.getGameIds()
                            for (gameId in vitaGameIds) {
                                SwingUtilities.invokeLater {
                                    statusLabel.text = "Processing game ${vitaGameCount + 1}/${vitaGameIds.size} ($gameId)..."
                                }
                                //println(gameId)
                                try {
                                    PsvitaDevice.getParamSfoCached(gameId)
                                    PsvitaDevice.getGameIconCached(gameId)
                                    val sizeFile = VitaOrganizerCache.entry(gameId).sizeFile
                                    if (!sizeFile.exists()) {
                                        sizeFile.writeText("" + PsvitaDevice.getGameSize(gameId))
                                    }
                                    synchronized (VITA_GAME_IDS) {
                                        VITA_GAME_IDS += gameId
                                    }
                                    updated = true
                                    //val entry = getGameEntryById(gameId)
                                    //entry.inVita = true
                                } catch (e: Throwable) {
                                    e.printStackTrace()
                                }
                                vitaGameCount++
                            }
                        } finally {
                            done = true
                            updated = true
                        }

                        SwingUtilities.invokeLater {
                            statusLabel.text = "Connected"
                        }
                    }.start()

                    Thread {
                        do {
                            //println("a")
                            while (!updated) {
                                //println("b")
                                Thread.sleep(100L)
                            }
                            updated = false
                            updateEntries()
                        } while (!done)


                    }.start()
                }

                this.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        if (connected) {
                            statusLabel.text = "Disconnecting..."
                            disconnect()
                        } else {
                            statusLabel.text = "Connecting..."
                            button.isEnabled = false
                            if (PsvitaDevice.checkAddress(VitaOrganizerSettings.lastDeviceIp)) {
                                connect(VitaOrganizerSettings.lastDeviceIp)
                                button.isEnabled = true
                                x
                            } else {
                                Thread {
                                    val ips = PsvitaDevice.discoverIp()
                                    println("Discovered ips: $ips")
                                    if (ips.size >= 1) {
                                        connect(ips.first())
                                    }
                                    button.isEnabled = true
                                }.start()
                            }
                        }
                    }
                })
            }
            add(connectButton)
        }

        add(header, SpringLayout.NORTH)
        add(scrollPane)
        add(footer, SpringLayout.SOUTH)

        table.rowHeight = 64

        //table.getColumnModel().getColumn(0).cellRenderer = JTable.IconRenderer()
        //(table.model as DefaultTableModel).addRow(arrayOf("John", "Doe", "Rowing", 3, true))

        table.fillsViewportHeight = true

        model.addColumn("Icon")
        model.addColumn("ID")
        model.addColumn("Where")
        model.addColumn("Size")
        model.addColumn("Title")

        //table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        table.getColumn("Icon").apply {
            width = 64
            minWidth = 64
            maxWidth = 64
            preferredWidth = 64
            resizable = false
        }
        table.getColumn("ID").apply {
            width = 96
            minWidth = 96
            maxWidth = 96
            preferredWidth = 96
            resizable = false
            cellRenderer = DefaultTableCellRenderer().apply {
                setHorizontalAlignment(JLabel.CENTER);
            }
        }
        table.getColumn("Size").apply {
            width = 96
            minWidth = 96
            maxWidth = 96
            preferredWidth = 96
            resizable = false
            cellRenderer = DefaultTableCellRenderer().apply {
                setHorizontalAlignment(JLabel.CENTER);
            }
        }
        table.getColumn("Where").apply {
            width = 64
            minWidth = 64
            maxWidth = 64
            preferredWidth = 64
            resizable = false
            cellRenderer = DefaultTableCellRenderer().apply {
                setHorizontalAlignment(JLabel.CENTER);
            }
        }
        table.getColumn("Title").apply {
            width = 512
            preferredWidth = 512
            //resizable = false
        }

        table.font = Font(Font.MONOSPACED, Font.PLAIN, 14)

        table.selectionModel.addListSelectionListener(object : ListSelectionListener {
            override fun valueChanged(e: ListSelectionEvent?) {
                println(e)
            }
        });

        table.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    table.showMenu()
                } else {
                    super.keyPressed(e)
                }
            }
        })

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                super.mouseClicked(e)
                val row = table.rowAtPoint(Point(e.x, e.y))
                if (row >= 0) table.showMenu()
            }
        })

        updateFileList()
    }

    fun updateFileList() {
        synchronized(VPK_GAME_IDS) {
            VPK_GAME_IDS.clear()
        }
        for (vpkFile in File(VitaOrganizerSettings.vpkFolder).listFiles().filter { it.extension.toLowerCase() == "vpk" }) {
            try {
                val zip = ZipFile(vpkFile)
                val paramSfoData = zip.getInputStream(zip.getEntry("sce_sys/param.sfo")).readBytes()

                val psf = PSF.read(paramSfoData.open2("r"))
                val gameId = psf["TITLE_ID"].toString()

                val entry = VitaOrganizerCache.entry(gameId)

                if (!entry.icon0File.exists()) {
                    entry.icon0File.writeBytes(zip.getInputStream(zip.getEntry("sce_sys/icon0.png")).readBytes())
                }
                if (!entry.paramSfoFile.exists()) {
                    entry.paramSfoFile.writeBytes(paramSfoData)
                }
                if (!entry.sizeFile.exists()) {
                    val uncompressedSize = ZipFile(vpkFile).entries().toList().map { it.size }.sum()
                    entry.sizeFile.writeText("" + uncompressedSize)
                }
                entry.pathFile.writeBytes(vpkFile.absolutePath.toByteArray(Charsets.UTF_8))
                synchronized(VPK_GAME_IDS) {
                    VPK_GAME_IDS += gameId
                }
                //getGameEntryById(gameId).inPC = true
            } catch (e: Throwable) {

            }
        }
        updateEntries()
    }

    private fun getScaledImage(srcImg: Image, w: Int, h: Int): Image {
        val resizedImg = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g2 = resizedImg.createGraphics()

        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2.drawImage(srcImg, 0, 0, w, h, null)
        g2.dispose()

        return resizedImg
    }

    private fun printDebugData(table: JTable) {
        val numRows = table.rowCount
        val numCols = table.columnCount
        val model = table.model

        println("Value of data: ")
        for (i in 0..numRows - 1) {
            print("    row $i:")
            for (j in 0..numCols - 1) {
                print("  " + model.getValueAt(i, j))
            }
            println()
        }
        println("--------------------------")
    }
}
