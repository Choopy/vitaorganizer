package com.soywiz.vitaorganizer

import it.sauronsoftware.ftp4j.FTPClient
import it.sauronsoftware.ftp4j.FTPDataTransferListener
import it.sauronsoftware.ftp4j.FTPException
import it.sauronsoftware.ftp4j.FTPFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.*
import java.util.zip.ZipFile

object PsvitaDevice {
    fun checkAddress(ip: String, port: Int = 1337): Boolean {
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(ip, port), 200)
            sock.close()
            return true
        } catch (e: Throwable) {
            return false
        }
    }

    fun discoverIp(port: Int = 1337): List<String> {
        val ips = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
        val ips2 = ips.map { it.hostAddress }.filter { it.startsWith("192.") }
        val ipEnd = Regex("\\.(\\d+)$")
        val availableIps = arrayListOf<String>()
        var rest = 256
        for (baseIp in ips2) {
            for (n in 0 until 256) {
                Thread {
                    val ip = baseIp.replace(ipEnd, "\\.$n")
                    if (checkAddress(ip, port)) {
                        availableIps += ip
                    }
                    rest--
                }.start()
            }
        }
        while (rest > 0) {
            //println(rest)
            Thread.sleep(20L)
        }
        //println(availableIps)
        return availableIps
    }

    val ftp = FTPClient().apply {
        type = FTPClient.TYPE_BINARY
    }

    fun setFtpPromoteTimeouts() {
        //ftp.connector.setCloseTimeout(20)
        //ftp.connector.setReadTimeout(240) // PROM could take a lot of time!
        //ftp.connector.setConnectionTimeout(120)
    }

    fun resetFtpTimeouts() {
        ftp.connector.setCloseTimeout(20)
        ftp.connector.setReadTimeout(240) // PROM could take a lot of time!
        ftp.connector.setConnectionTimeout(120)

        //ftp.connector.setCloseTimeout(2)
        //ftp.connector.setReadTimeout(2)
        //ftp.connector.setConnectionTimeout(2)
    }

    init {
        resetFtpTimeouts()
    }

    //init {
        //ftp.sendCustomCommand()
    //}

    private fun connectedFtp(): FTPClient {
		val ip = VitaOrganizerSettings.lastDeviceIp
		val port = try { VitaOrganizerSettings.lastDevicePort.toInt() } catch (t: Throwable) { 1337 }



        retries@for (n in 0 until 5) {
			println("Connecting to ftp $ip:$port...")
            if (!ftp.isConnected) {
                ftp.connect(ip, port)
                ftp.login("", "")
				println("Connected")
            }
            try {
                ftp.noop()
                break@retries
            } catch (e: IOException) {
                ftp.disconnect(false)
            }
        }
        return ftp
    }

    fun getGameIds() = connectedFtp().list("/ux0:/app").filter { i -> i.type == it.sauronsoftware.ftp4j.FTPFile.TYPE_DIRECTORY }.map { File(it.name).name }

    fun getGameFolder(id: String) = "/ux0:/app/${File(id).name}"

    fun downloadSmallFile(path: String): ByteArray {
        try {
            if (connectedFtp().fileSize(path) == 0L) {
                return byteArrayOf()
            }
        } catch (e: Throwable) {
            return byteArrayOf()
        }

        val file = File.createTempFile("vita", "download")
        try {
            connectedFtp().download(path, file)
            return file.readBytes()
        } catch (e: FTPException) {
            e.printStackTrace()
        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            //e.printStackTrace()
            file.delete()
        }
        return byteArrayOf()
    }

    fun getParamSfo(id: String): ByteArray = downloadSmallFile("${getGameFolder(id)}/sce_sys/param.sfo")
    fun getGameIcon(id: String): ByteArray {
        val result = downloadSmallFile("${getGameFolder(id)}/sce_sys/icon0.png")
        return result
    }
    fun downloadEbootBin(id: String): ByteArray = downloadSmallFile("${getGameFolder(id)}/eboot.bin")

    fun getParamSfoCached(id: String): ByteArray {
        val file = VitaOrganizerCache.entry(id).paramSfoFile
        if (!file.exists()) file.writeBytes(getParamSfo(id))
        return file.readBytes()
    }

    fun getGameIconCached(id: String): ByteArray {
        val file = VitaOrganizerCache.entry(id).icon0File
        if (!file.exists()) file.writeBytes(getGameIcon(id))
        return file.readBytes()
    }

    fun getFolderSize(path: String, folderSizeCache: HashMap<String, Long> = hashMapOf<String, Long>()): Long {
        return folderSizeCache.getOrPut(path) {
            var out = 0L
            val ftp = connectedFtp()
            try {
                for (file in ftp.list(path)) {
                    //println("$path/${file.name}: ${file.size}")
                    if (file.type == FTPFile.TYPE_DIRECTORY) {
                        out += getFolderSize("$path/${file.name}", folderSizeCache)
                    } else {
                        out += file.size
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            out
        }
    }

    fun getGameSize(id: String, folderSizeCache: HashMap<String, Long> = hashMapOf<String, Long>()): Long {
        return getFolderSize(getGameFolder(id), folderSizeCache)
    }

    class Status() {
		var startTime: Long = 0L
        var currentFile: Int = 0
        var totalFiles: Int = 0
        var currentSize: Long = 0L
        var totalSize: Long = 0L
		val elapsedTime: Int get() = (System.currentTimeMillis() - startTime).toInt()
		val speed: Double get() {
			return if (elapsedTime == 0) 0.0 else currentSize.toDouble() / (elapsedTime.toDouble() / 1000.0)
		}

		val currentSizeString: String get() = FileSize.toString(currentSize)
		val totalSizeString: String get() = FileSize.toString(totalSize)

		val speedString: String get() = FileSize.toString(speed.toLong()) + "/s"

        val fileRange: String get() = "$currentFile/$totalFiles"
        val sizeRange: String get() = "$currentSizeString/$totalSizeString"
	}

    val createDirectoryCache = hashSetOf<String>()

    fun createDirectories(_path: String, createDirectoryCache: HashSet<String> = PsvitaDevice.createDirectoryCache) {
        val path = _path.replace('\\', '/')
        val parent = File(path).parent
        if (parent != "" && parent != null) {
            createDirectories(parent, createDirectoryCache)
        }
        if (path !in createDirectoryCache) {
            println("Creating directory $path...")
            createDirectoryCache.add(path)
            try {
                connectedFtp().createDirectory(path)
            } catch (e: IOException) {
                throw e
            } catch (e: FTPException) {
                e.printStackTrace()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    fun uploadGame(id: String, zip: ZipFile, filter: (path: String) -> Boolean = { true }, updateStatus: (Status) -> Unit = { }) {
        val base = getGameFolder(id)

        val status = Status()

        val unfilteredEntries = zip.entries().toList()

        val filteredEntries = unfilteredEntries.filter { filter(it.name) }

		status.startTime = System.currentTimeMillis()

        status.currentFile = 0
        status.totalFiles = filteredEntries.size

        status.currentSize = 0L
        status.totalSize = filteredEntries.map { it.size }.sum()

        for (entry in filteredEntries) {
            val normalizedName = entry.name.replace('\\', '/')
            val vname = "$base/$normalizedName"
            val directory = File(vname).parent.replace('\\', '/')
            val startSize = status.currentSize
            println("Writting $vname...")
            if (!entry.isDirectory) {
                createDirectories(directory)
                try {
                    connectedFtp().upload(vname, zip.getInputStream(entry), 0L, 0L, object : FTPDataTransferListener {
                        override fun started() {
                        }

                        override fun completed() {
                            updateStatus(status)
                        }

                        override fun aborted() {
                        }

                        override fun transferred(size: Int) {
                            status.currentSize += size
                            updateStatus(status)
                        }

                        override fun failed() {
                        }
                    })
                }catch (e: FTPException) {
                    e.printStackTrace()
                    throw FileNotFoundException("Can't upload file $vname")
                }
            }
            status.currentSize = startSize + entry.size
            status.currentFile++
            updateStatus(status)
        }

        println("DONE. Now package should be promoted!")
    }

    fun uploadFile(path: String, data: ByteArray, updateStatus: (Status) -> Unit = { }) {
        val status = Status()
        createDirectories(File(path).parent)
		status.startTime = System.currentTimeMillis()
        status.currentFile = 0
        status.totalFiles = 1
        status.totalSize = data.size.toLong()
        updateStatus(status)
        connectedFtp().upload(path, ByteArrayInputStream(data), 0L, 0L, object : FTPDataTransferListener {
            override fun started() {
            }

            override fun completed() {
            }

            override fun aborted() {
            }

            override fun transferred(size: Int) {
                status.currentSize += size
                updateStatus(status)
            }

            override fun failed() {
            }
        })
        status.currentFile++
        updateStatus(status)
    }

    fun removeFile(path: String) {
        try {
            connectedFtp().deleteFile(path)
        } catch (e: Throwable) {
            println("Can't delete $path")
            e.printStackTrace()
        }
    }

    fun promoteVpk(vpkPath: String) {
        setFtpPromoteTimeouts()
        println("Promoting: 'PROM $vpkPath'")
        connectedFtp().sendCustomCommand("PROM $vpkPath")
        resetFtpTimeouts()
    }
}