package org.zotero.android.attachmentdownloader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import org.zotero.android.api.SyncApi
import org.zotero.android.api.network.CustomResult
import org.zotero.android.architecture.exhaustive
import org.zotero.android.database.DbWrapper
import org.zotero.android.database.objects.Attachment
import org.zotero.android.database.requests.MarkFileAsDownloadedDbRequest
import org.zotero.android.files.FileStore
import org.zotero.android.sync.LibraryIdentifier
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentDownloader @Inject constructor(
    private val syncApi: SyncApi,
    private val fileStorage: FileStore,
    private val dbWrapper: DbWrapper,
    private val attachmentDownloaderEventStream: AttachmentDownloaderEventStream,
    private val dispatcher: CoroutineDispatcher,
) {
    sealed class Error : Exception() {
        object incompatibleAttachment : Error()
        object zipDidntContainRequestedFile : Error()
        object cantUnzipSnapshot: Error()
    }

    data class Download(
        val key: String,
        val libraryId: LibraryIdentifier,
    )

    data class Update(
        val key: String,
        val parentKey: String?,
        val libraryId: LibraryIdentifier,
        val kind: Kind,
    ) {
        sealed class Kind {
            data class progress(val progressInHundreds: Int) : Kind()
            object ready : Kind()
            data class failed(val exception: Throwable) : Kind()
            object cancelled : Kind()
        }


        companion object {
            fun init(
                key: String,
                parentKey: String?,
                libraryId: LibraryIdentifier,
                kind: Kind
            ): Update {
                return Update(
                    key = key,
                    parentKey = parentKey,
                    libraryId = libraryId,
                    kind = kind,
                )

            }

            fun init(download: Download, parentKey: String?, kind: Kind): Update {
                return Update(
                    key = download.key,
                    parentKey = parentKey,
                    libraryId = download.libraryId,
                    kind = kind,
                )
            }
        }
    }

    private var userId: Long = 0L
    private var coroutineScope = CoroutineScope(dispatcher)
    private var operations = mutableMapOf<Download, AttachmentDownloadOperation>()
    private var errors = mutableMapOf<Download, Throwable>()
    private var batchProgress: AttachmentBatchProgress = AttachmentBatchProgress()
    private var totalBatchCount: Int = 0

    val batchData: Triple<Int?, Int, Int>
        get() {
            val progress = this.batchProgress.currentProgress
            val totalBatchCount = this.totalBatchCount
            val remainingBatchCount = this.operations.size
            return Triple(progress, remainingBatchCount, totalBatchCount)
        }

    fun init(userId: Long) {
        this.userId = userId
        operations.clear()
        errors.clear()
        batchProgress = AttachmentBatchProgress()
    }

    fun downloadIfNeeded(attachment: Attachment, parentKey: String?) {
        val attachmentType = attachment.type
        when(attachmentType) {
            is Attachment.Kind.url -> {
                Timber.i("AttachmentDownloader: open url ${attachment.key}")
                attachmentDownloaderEventStream.emitAsync(
                    Update.init(key = attachment.key, parentKey = parentKey, libraryId = attachment.libraryId, kind = Update.Kind.ready)
                )
            }
            is Attachment.Kind.file -> {
                val filename = attachmentType.filename
                val contentType = attachmentType.contentType
                val location = attachmentType.location
                val linkType = attachmentType.linkType
                when (linkType) {
                    Attachment.FileLinkType.linkedFile, Attachment.FileLinkType.embeddedImage -> {
                        Timber.w("AttachmentDownloader: tried opening linkedFile or embeddedImage ${attachment.key}")

                        attachmentDownloaderEventStream.emitAsync(
                            Update.init(
                                key = attachment.key,
                                parentKey = parentKey,
                                libraryId = attachment.libraryId,
                                kind = Update.Kind.failed(
                                    Error.incompatibleAttachment
                                )
                            )
                        )
                    }
                    Attachment.FileLinkType.importedFile, Attachment.FileLinkType.importedUrl -> {
                        when(location) {
                            Attachment.FileLocation.local -> {
                                Timber.i("AttachmentDownloader: open local file ${attachment.key}")

                                attachmentDownloaderEventStream.emitAsync(
                                    Update.init(
                                        key = attachment.key,
                                        parentKey = parentKey,
                                        libraryId = attachment.libraryId,
                                        kind = Update.Kind.ready
                                    )
                                )
                            }
                            Attachment.FileLocation.remote, Attachment.FileLocation.remoteMissing -> {
                                Timber.i("AttachmentDownloader: download remote${if (location == Attachment.FileLocation.remoteMissing) "ly missing" else ""} file ${attachment.key}")

                                val file = fileStorage.attachmentFile(
                                    libraryId = attachment.libraryId,
                                    key = attachment.key,
                                    filename = filename,
                                    contentType = contentType
                                )
                                download(
                                    file = file,
                                    key = attachment.key,
                                    parentKey = parentKey,
                                    libraryId = attachment.libraryId,
                                    hasLocalCopy = false
                                )
                            }
                            Attachment.FileLocation.localAndChangedRemotely -> {
                                val file = fileStorage.attachmentFile(
                                    libraryId = attachment.libraryId,
                                    key = attachment.key,
                                    filename = filename,
                                    contentType = contentType
                                )

                                var hasLocalCopy = true

                                if (file.extension == "pdf" && file.exists() && !fileStorage.isPdf(
                                        file = file
                                    )
                                ) {
                                    file.deleteRecursively()
                                    hasLocalCopy = false
                                }

                                if (hasLocalCopy) {
                                    Timber.i("AttachmentDownloader: download local file with remote change ${attachment.key}")
                                } else {
                                    Timber.i("AttachmentDownloader: download remote file ${attachment.key}. Fixed local PDF.")
                                }

                                download(
                                    file = file,
                                    key = attachment.key,
                                    parentKey = parentKey,
                                    libraryId = attachment.libraryId,
                                    hasLocalCopy = hasLocalCopy
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun cancel(key: String, libraryId: LibraryIdentifier) {
        _cancel(key = key, libraryId = libraryId)
    }

    private fun _cancel(key: String, libraryId: LibraryIdentifier) {
        val download = Download(key = key, libraryId = libraryId)
        val operation = this.operations[download]
        if (operation == null) {
            return
        }
        this.operations.remove(download)
        this.batchProgress.finishDownload(key)
        resetBatchDataIfNeeded()

        Timber.i("AttachmentDownloader: cancelled ${download.key}")
        operation.cancel()
    }

    private fun download(file: File, key: String, parentKey: String?, libraryId: LibraryIdentifier, hasLocalCopy: Boolean) {
        val pairResult = createDownload(
            file = file,
            key = key,
            parentKey = parentKey,
            libraryId = libraryId,
            hasLocalCopy = hasLocalCopy
        )
        if (pairResult == null) {
            return
        }
        val (download, operation) = pairResult
        enqueue(operation = operation, download = download, parentKey = parentKey)
    }

    private fun enqueue(operation: AttachmentDownloadOperation, download: Download, parentKey: String?) {
        Timber.i("AttachmentDownloader: enqueue ${download.key}")

        attachmentDownloaderEventStream.emitAsync(
            Update.init(download = download, parentKey = parentKey, kind = Update.Kind.progress(0))
        )
        coroutineScope.async {
            operation.start(this)
        }
    }

    private fun createDownload(file: File, key: String, parentKey: String?, libraryId: LibraryIdentifier, hasLocalCopy: Boolean): Pair<Download, AttachmentDownloadOperation>? {
         val download = Download(key = key, libraryId = libraryId)

        if (operations[download] != null) {
            return null
        }

        val operation = AttachmentDownloadOperation(
            file = file,
            download = download,
            userId = this.userId,
            syncApi = syncApi,
            fileStorage = this.fileStorage,
        )
        operation.onDownloadProgressUpdated = object : OnDownloadProgressUpdated {
            override fun onProgressUpdated(progressInHundreds: Int) {
                batchProgress.updateProgress(key, progressInHundreds)
                attachmentDownloaderEventStream.emitAsync(
                    Update.init(
                        download = download,
                        parentKey = parentKey,
                        kind = Update.Kind.progress(
                            progressInHundreds
                        )
                    )
                )
            }
        }
        operation.finishedDownload = { result ->
            when (result) {
                is CustomResult.GeneralError -> {
                    finish(
                        download = download,
                        parentKey = parentKey,
                        result = result,
                        hasLocalCopy = hasLocalCopy
                    )
                }
                is CustomResult.GeneralSuccess -> {
                    dbWrapper.realmDbStorage.perform(
                        request = MarkFileAsDownloadedDbRequest(
                            key = download.key,
                            libraryId = download.libraryId,
                            downloaded = true
                        )
                    )
                    finish(
                        download = download,
                        parentKey = parentKey,
                        result = result,
                        hasLocalCopy = hasLocalCopy
                    )
                }
            }
        }

        this.errors.remove(download)
        this.operations[download] = operation
        this.totalBatchCount += 1

        return download to operation
    }

    private fun finish(
        download: Download,
        parentKey: String?,
        result: CustomResult<Unit>,
        hasLocalCopy: Boolean
    ) {
        _finish(
            download = download,
            parentKey = parentKey,
            result = result,
            hasLocalCopy = hasLocalCopy
        )
    }

    private fun _finish(download: Download, parentKey: String?, result: CustomResult<Unit>, hasLocalCopy: Boolean) {
        this.operations.remove(download)
        resetBatchDataIfNeeded()

        when(result) {
            is CustomResult.GeneralError.CodeError -> {
                val isCancelError = result.throwable is AttachmentDownloadOperation.Error.cancelled
                if (isCancelError || hasLocalCopy) {
                    this.errors.remove(download)
                } else {
                    this.errors[download] = result.throwable
                }
                if (isCancelError) {
                    attachmentDownloaderEventStream.emitAsync(Update.init(download = download, parentKey = parentKey, kind = Update.Kind.cancelled))
                } else if (hasLocalCopy) {
                    attachmentDownloaderEventStream.emitAsync(Update.init(download = download, parentKey = parentKey, kind = Update.Kind.ready))
                } else {
                    Timber.e(result.throwable, "AttachmentDownloader: failed to download attachment ${download.key}, ${download.libraryId}")
                    attachmentDownloaderEventStream.emitAsync(Update.init(download = download, parentKey = parentKey, kind = Update.Kind.failed(result.throwable)))
                }
            }
            is CustomResult.GeneralSuccess -> {
                Timber.i("AttachmentDownloader: finished downloading ${download.key}")
                this.errors.remove(download)
                attachmentDownloaderEventStream.emitAsync(Update.init(download = download, parentKey = parentKey, kind = Update.Kind.ready))
            }

            else -> {}
        }
    }

    fun stop() {
        operations.forEach {
            it.value.cancel()
        }
    }

    fun data(key: String, libraryId: LibraryIdentifier): Pair<Int?, Throwable?> {
        val download = Download(key = key, libraryId = libraryId)
        val progress = this.operations[download]?.progressInHundreds
        val error = this.errors[download]
        return progress to error
    }

    private fun resetBatchDataIfNeeded() {
        if (this.operations.isEmpty()) {
            this.batchProgress = AttachmentBatchProgress()
            this.totalBatchCount = 0
        }
    }

}