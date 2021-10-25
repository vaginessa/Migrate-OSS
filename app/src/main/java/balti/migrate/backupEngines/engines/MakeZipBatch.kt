package balti.migrate.backupEngines.engines

import android.util.Log
import balti.filex.FileX
import balti.migrate.AppInstance
import balti.migrate.AppInstance.Companion.CACHE_DIR
import balti.migrate.AppInstance.Companion.MAX_WORKING_SIZE
import balti.migrate.AppInstance.Companion.RESERVED_SPACE
import balti.migrate.R
import balti.migrate.backupEngines.BackupServiceKotlin
import balti.migrate.backupEngines.BackupServiceKotlin.Companion.flasherOnly
import balti.migrate.backupEngines.ParentBackupClass
import balti.migrate.backupEngines.containers.*
import balti.migrate.extraBackupsActivity.apps.containers.AppPacket
import balti.migrate.utilities.CommonToolsKotlin
import balti.migrate.utilities.CommonToolsKotlin.Companion.BACKUP_NAME_SETTINGS
import balti.migrate.utilities.CommonToolsKotlin.Companion.DEBUG_TAG
import balti.migrate.utilities.CommonToolsKotlin.Companion.ERR_MAKING_APP_ZIP_PACKET
import balti.migrate.utilities.CommonToolsKotlin.Companion.ERR_MAKING_EXTRA_ZIP_PACKET
import balti.migrate.utilities.CommonToolsKotlin.Companion.ERR_MOVE_SCRIPT
import balti.migrate.utilities.CommonToolsKotlin.Companion.ERR_MOVE_TRY_CATCH
import balti.migrate.utilities.CommonToolsKotlin.Companion.ERR_MOVING_ROOT
import balti.migrate.utilities.CommonToolsKotlin.Companion.ERR_ZIP_BATCHING
import balti.migrate.utilities.CommonToolsKotlin.Companion.EXTRA_PROGRESS_TYPE_MAKING_ZIP_BATCH
import balti.migrate.utilities.CommonToolsKotlin.Companion.PREF_NEW_ICON_METHOD
import balti.migrate.utilities.CommonToolsKotlin.Companion.PREF_SEPARATE_EXTRAS_FOR_FLASHER_ONLY
import balti.migrate.utilities.CommonToolsKotlin.Companion.PREF_SEPARATE_EXTRAS_FOR_SMALL_BACKUP
import balti.migrate.utilities.CommonToolsKotlin.Companion.SU_INIT
import balti.migrate.utilities.CommonToolsKotlin.Companion.WARNING_ZIP_BATCH
import balti.module.baltitoolbox.functions.GetResources.getStringFromRes
import balti.module.baltitoolbox.functions.Misc.iterateBufferedReader
import balti.module.baltitoolbox.functions.SharedPrefs.getPrefBoolean
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class MakeZipBatch(private val jobcode: Int, bd: BackupIntentData,
                   private val appList: ArrayList<AppPacket>, private val extras: ArrayList<FileX>) : ParentBackupClass(bd, EXTRA_PROGRESS_TYPE_MAKING_ZIP_BATCH) {

    private val zipBatches by lazy { ArrayList<ZipBatch>(0) }
    private val errors by lazy { ArrayList<String>(0) }
    private val warnings by lazy { ArrayList<String>(0) }
    private var totalAppSize = 0L
    private var totalExtraSize = 0L

    private val allFiles by lazy { rootLocation.run { exists(); listEverything() } }

    private val allZipAppPackets by lazy { ArrayList<ZipAppPacket>(0) }
    private val allZipExtraPackets by lazy { ArrayList<ZipExtraPacket>(0) }

    private fun shareLogProgress(string: String){
        broadcastProgress("", string, false)
    }

    private fun makeZipAppPackets(){
        val title = getTitle(R.string.making_packets)
        resetBroadcast(true, title)

        val subTask: String = getStringFromRes(R.string.reading_app_related_files)

        var c = 0
        appList.forEach {

            try {

                if (BackupServiceKotlin.cancelAll) return@forEach

                val associatedFileNames = ArrayList<String>(0)
                val associatedFileSizes = ArrayList<Long>(0)

                broadcastProgress(subTask, "${it.appName}(${++c}/${appList.size})", false)

                val appDirName = "${it.packageName}.app"
                val dataName = "${it.packageName}.tar.gz"
                val permName = "${it.packageName}.perm"
                val jsonName = "${it.packageName}.json"
                val systemShName = "${it.packageName}.sh"
                val iconName = if (getPrefBoolean(PREF_NEW_ICON_METHOD, true)) "${it.packageName}.png"
                else "${it.packageName}.icon"

                val apkInfoList = AppInstance.appApkFiles[it.packageName] ?: listOf()

                fun addToAssociatedFiles(name: String, isDirectory: Boolean = false){
                    val index = allFiles?.first?.indexOf(name) ?: -1
                    if (index != -1) {
                        associatedFileNames.add(name)
                        associatedFileSizes.add(
                            if (isDirectory) 0
                            else allFiles?.third?.getOrNull(index) ?: 0
                        )
                    }
                }

                if (it.APP) addToAssociatedFiles(appDirName, true)
                if (it.DATA) addToAssociatedFiles(dataName)
                if (it.PERMISSION) addToAssociatedFiles(permName)
                addToAssociatedFiles(jsonName)
                addToAssociatedFiles(systemShName)
                addToAssociatedFiles(iconName)

                val zipPacket = ZipAppPacket(it, associatedFileNames, associatedFileSizes, ArrayList(apkInfoList))
                allZipAppPackets.add(zipPacket)
                totalAppSize += zipPacket.zipPacketSize
            }
            catch (e: Exception){
                e.printStackTrace()
                errors.add("$ERR_MAKING_APP_ZIP_PACKET: ${e.message}")
            }
        }
    }

    private fun makeZipExtraPackets(){
        val title = getTitle(R.string.making_packets)
        resetBroadcast(true, title)

        val subTask: String = getStringFromRes(R.string.reading_extra_backups_files)

        try {

            fun addToAllExtraPackets(fileExtension: String, isSystemFile: Boolean = false) {
                extras.filter { it.name.endsWith(fileExtension) }.forEach { file ->
                    broadcastProgress(subTask, "${file.name})", false)
                    val relativePath = file.canonicalPath.substring(rootLocation.canonicalPath.length)
                    ZipExtraPacket(file.name, relativePath, file.length(), isSystemFile).let {
                        allZipExtraPackets.add(it)
                        totalExtraSize += it.zipPacketSize
                    }
                }
            }

            addToAllExtraPackets(".vcf")
            addToAllExtraPackets(".calls.db")
            addToAllExtraPackets(".sms.db")
            addToAllExtraPackets(BACKUP_NAME_SETTINGS)

        }
        catch (e: Exception){
            e.printStackTrace()
            errors.add("$ERR_MAKING_EXTRA_ZIP_PACKET: ${e.message}")
        }
    }

    private fun makeBatches(){

        Thread.sleep(100)

        val title = getTitle(R.string.making_batches)
        resetBroadcast(true, title)

        try {

            getStringFromRes(R.string.grouping_into_batches).let { broadcastProgress(it, it, true) }

            val MAX_TWRP_ZIP_SIZE = MAX_WORKING_SIZE - RESERVED_SPACE
            /**
             * This variable is true if a TWRP backup can be accommodated in a single zip.
             * i.e. if the total size of all app and extra packets
             * is less than the max size of a TWRP flashable zip.
             */
            val singleZipTwrpBackup = (totalAppSize + totalExtraSize) <= MAX_TWRP_ZIP_SIZE

            val doSeparateExtras: Boolean =
                when {
                    /**
                     * If no extras, then no separate backup.
                     */
                    totalExtraSize <= 0 -> false

                    /**
                     * For flasher only backup, separate extras only if user wants
                     */
                    flasherOnly -> getPrefBoolean(PREF_SEPARATE_EXTRAS_FOR_FLASHER_ONLY, false)

                    /**
                     * For normal TWRP backup, if only 1 zip to be created,
                     * then separate extras only if user wants.
                     *
                     * For more than 1 zips, create separate extras.
                     */
                    singleZipTwrpBackup -> getPrefBoolean(PREF_SEPARATE_EXTRAS_FOR_SMALL_BACKUP, false)

                    /**
                     * By default, create separate extras.
                     */
                    else -> true
                }

            if (flasherOnly || singleZipTwrpBackup) {

                /**
                 * Add all packets in one batch or 2 batches for different separate extras.
                 */

                var zipBatch = ZipBatch()
                allZipAppPackets.forEach {
                    zipBatch.addZipAppPacket(it)
                }

                /**
                 * If `doSeparateExtras` is true, this block will execute
                 * and create a new batch before going to extra packets.
                 * Else, no new batch is created and extra packets are added to same batch.
                 */
                if (doSeparateExtras){
                    zipBatches.add(zipBatch)
                    zipBatch = ZipBatch()
                }

                allZipExtraPackets.forEach {
                    zipBatch.addZipExtraPacket(it)
                }

                /** Finally add the batch to [zipBatches] */
                zipBatches.add(zipBatch)
            }
            else {

                /**
                 * Case for normal TWRP multi-zip backup.
                 * Different zips for apps and extras.
                 */

                val totalParts = ((totalAppSize + totalExtraSize) / MAX_TWRP_ZIP_SIZE) + 1
                /* Examples: assume
                 * max size = 4 GB, total size = 10 kb
                 *    parts = (0 by decimal division) + 1 = 1
                 * max size = 4 GB, total size = 4 GB
                 *    parts = (1 by decimal division) + 1 = 2  => Backup will be split into two parts of 2 GB
                 * max size = 4 GB, total size = 4.1 GB
                 *    parts = (1 by decimal division) + 1 = 2  => Backup will be split into two parts of greater than 2 GB
                 */

                /**
                 * Function to group into batches: applicable on app and extra packets both.
                 * @param zipNameHeader Will be "Extras" or "App" based on the type of [inputList].
                 *                      Accordingly zip names will be set as:
                 *                      Extras_Zip_1_of_7, Extras_Zip_2_of_7, Extras_Zip_3_of_7,
                 *                      App_Zip_4_of_7, App_Zip_5_of_7, App_Zip_6_of_7, App_Zip_7_of_7
                 *                      so on..
                 * @param inputList Pass [allZipAppPackets] for grouping app packets.
                 * Pass [allZipExtraPackets] for grouping extra packets.
                 */
                fun <T:ParentZipPacket> groupIntoBatches(zipNameHeader: String, inputList: ArrayList<T>): ArrayList<ZipBatch> {
                    val tempZipBatches = ArrayList<ZipBatch>(0)

                    var maxOuterLoop = 1000   // break if outer loop has scanned over this number.
                    while (inputList.isNotEmpty() && maxOuterLoop > 0){
                        val tempZipBatch = ZipBatch()
                        var c = 0

                        // initially add small apps within 80% max size
                        while (c < inputList.size && tempZipBatch.zipFullSize <= MAX_TWRP_ZIP_SIZE) {
                            val p = inputList[c]

                            if ((tempZipBatch.zipFullSize + p.zipPacketSize) <= MAX_TWRP_ZIP_SIZE) {

                                if (p is ZipAppPacket) {
                                    tempZipBatch.addZipAppPacket(p)
                                    Log.d(DEBUG_TAG, "adding app packet: ${p.displayName}, size: ${p.zipPacketSize}, packet no: $c")
                                }

                                if (p is ZipExtraPacket) {
                                    tempZipBatch.addZipExtraPacket(p)
                                    Log.d(DEBUG_TAG, "adding extra packet: ${p.displayName}, size: ${p.zipPacketSize}, packet no: $c")
                                }

                                shareLogProgress(
                                        "${engineContext.getString(R.string.adding)}: ${p.displayName} | " +
                                                "${engineContext.getString(R.string.packet)}: ${zipBatches.size + 1}"
                                        )

                                inputList.remove(p)

                            } else c++
                        }

                        // Set name of this batch and add this batch to list to be returned.
                        tempZipBatch.apply {
                            val thisCount = tempZipBatches.size + zipBatches.size
                            zipName =
                                "${zipNameHeader}_${getStringFromRes(R.string.zip)}_${thisCount + 1}" +
                                        "_${getStringFromRes(R.string.of)}_${totalParts}"
                            tempZipBatches.add(this)
                        }
                        --maxOuterLoop
                    }

                    return tempZipBatches
                }

                /**
                 * Remove too big packets.
                 * This is an error case. This should not normally occur.
                 * This should be handled previously in Extra backups section.
                 * But this is still present to filter out any rogue app/extra packet
                 * which might have slipped through.
                 */
                fun <T:ParentZipPacket> removeTooBigPackets(inputList: ArrayList<T>) {
                    var c = 0
                    while (c < inputList.size) {
                        val p = inputList[c]
                        if (p.zipPacketSize > MAX_TWRP_ZIP_SIZE) {
                            errors.add("$WARNING_ZIP_BATCH: Removing ${p.displayName}. Cannot backup. Too big!")
                            inputList.remove(p)
                            --c
                        }
                        ++c
                    }
                }

                removeTooBigPackets(allZipAppPackets)
                removeTooBigPackets(allZipExtraPackets)

                zipBatches.addAll(groupIntoBatches(getStringFromRes(R.string.app), allZipAppPackets))
                zipBatches.addAll(groupIntoBatches(getStringFromRes(R.string.extras), allZipExtraPackets))

            }

        }
        catch (e: Exception){
            e.printStackTrace()
            errors.add("$ERR_ZIP_BATCHING: ${e.message}")
        }

    }

    private fun createMoveScript(): FileX? {

        val title = getTitle(R.string.moving_files)
        resetBroadcast(true, title)

        val scriptFile = FileX.new(CACHE_DIR, "${CommonToolsKotlin.FILE_PREFIX_MOVE_TO_CONTAINER_SCRIPT}.sh", true)
        scriptFile.parentFile?.mkdirs()

        try {
            scriptFile.startWriting(object : FileX.Writer() {
                override fun writeLines() {

                    getStringFromRes(R.string.creating_script_to_move).let { broadcastProgress(it, it, false) }

                    var c = 0
                    zipBatches.forEach { batch ->
                        if (BackupServiceKotlin.cancelAll) return

                        if (batch.zipName != "") {

                            val zipDestination = "${rootLocation.canonicalPath}/${batch.zipName}"
                            val fileListPath = "${zipDestination}/${CommonToolsKotlin.FILE_FILE_LIST}"

                            writeLine("\n# Part ${c++}, part name: ${batch.zipName}\n")
                            writeLine("mkdir -p $zipDestination")

                            /**
                             * Filter out the directories and create them first.
                             * Then move the files.
                             */
                            batch.zipFiles.filter { it.third }.apply {
                                writeLine("\n# Creating directories. Items: ${this.size}.\n")
                            }.forEach {
                                val directoryLocation = "${zipDestination}/${it.first}"
                                writeLine("mkdir -p $directoryLocation")
                            }

                            /**
                             * Now move all the files.
                             */
                            batch.zipFiles.filter { !it.third }.apply {
                                writeLine("\n# Moving files. Items: ${this.size}.\n")
                            }.forEach {
                                val oldFileLocation = "${rootLocation.canonicalPath}/${it.first}"
                                val newFileLocation = "${zipDestination}/${it.first}"
                                writeLine("mv $oldFileLocation $newFileLocation")
                            }

                            /**
                             * Find the top directory/file for each item.
                             * Example:
                             * Item: Calls.calls.db -> Header name: Calls.calls.db
                             * Item: com.whatsapp.app -> Header name: com.whatsapp.app
                             * Item: com.whatsapp.app/com.whatsapp.apk -> Header name: com.whatsapp.app
                             * Item: some_directory/another_directory/another_file -> Header: some_directory
                             */
                            val headers = mutableListOf<String>()
                            batch.zipFiles.map { it.first }.forEach {
                                if (!it.contains('/')) headers.add(it)
                                else headers.add(it.substring(0, it.indexOf('/')))
                            }

                            /**
                             * Finally write to file list.
                             */
                            headers.run {
                                if (isNotEmpty()) {
                                    writeLine("\n# Writing file list. Items: ${this.size}.\n")
                                    forEach {
                                        writeLine("echo \"${it}\" >> $fileListPath")
                                    }
                                }
                            }
                        }
                    }
                }
            })

            return scriptFile

        }
        catch (e: Exception){
            e.printStackTrace()
            errors.add("$ERR_MOVE_SCRIPT: ${e.message}")
        }

        return null
    }

    private fun executeMoveScript(scriptFile: FileX) {
        try {
            getStringFromRes(R.string.running_script_to_move).let { broadcastProgress(it, it, false) }

            val suProcess = Runtime.getRuntime().exec(SU_INIT)
            suProcess?.let {
                val suInputStream = BufferedWriter(OutputStreamWriter(it.outputStream))
                val errorStream = BufferedReader(InputStreamReader(it.errorStream))

                suInputStream.write("cp ${scriptFile.canonicalPath} $CACHE_DIR/\n")
                suInputStream.write("sh ${scriptFile.canonicalPath}\n")
                suInputStream.write("exit\n")
                suInputStream.flush()

                iterateBufferedReader(errorStream, { errorLine ->
                    errors.add("$ERR_MOVING_ROOT: $errorLine")
                    return@iterateBufferedReader false
                })
            }
        }
        catch (e: Exception){
            e.printStackTrace()
            errors.add("$ERR_MOVE_TRY_CATCH: ${e.message}")
        }
    }

    override suspend fun doInBackground(arg: Any?): Any {

        if (!BackupServiceKotlin.cancelAll) makeZipAppPackets()
        if (!BackupServiceKotlin.cancelAll) makeZipExtraPackets()
        if (!BackupServiceKotlin.cancelAll) makeBatches()
        val scriptFile = if (errors.isEmpty()) { createMoveScript() } else null
        if (!BackupServiceKotlin.cancelAll) scriptFile?.let { executeMoveScript(it) }

        return 0
    }

    override fun postExecuteFunction() {
        if (errors.isEmpty()) {
            onEngineTaskComplete.onComplete(jobcode, errors, warnings, zipBatches)
        }
    }

}