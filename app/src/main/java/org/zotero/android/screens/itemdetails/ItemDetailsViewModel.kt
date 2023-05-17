package org.zotero.android.screens.itemdetails

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.net.toFile
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.realm.ObjectChangeSet
import io.realm.RealmObjectChangeListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.joda.time.DateTime
import org.zotero.android.architecture.BaseViewModel2
import org.zotero.android.architecture.Defaults
import org.zotero.android.architecture.EventBusConstants
import org.zotero.android.architecture.Result
import org.zotero.android.architecture.ScreenArguments
import org.zotero.android.architecture.ViewEffect
import org.zotero.android.architecture.ViewState
import org.zotero.android.architecture.ifFailure
import org.zotero.android.attachmentdownloader.AttachmentDownloader
import org.zotero.android.attachmentdownloader.AttachmentDownloaderEventStream
import org.zotero.android.database.DbWrapper
import org.zotero.android.database.objects.Attachment
import org.zotero.android.database.objects.FieldKeys
import org.zotero.android.database.objects.ItemTypes
import org.zotero.android.database.objects.RItem
import org.zotero.android.database.objects.UpdatableChangeType
import org.zotero.android.database.requests.CreateAttachmentsDbRequest
import org.zotero.android.database.requests.CreateItemFromDetailDbRequest
import org.zotero.android.database.requests.CreateNoteDbRequest
import org.zotero.android.database.requests.DeleteTagFromItemDbRequest
import org.zotero.android.database.requests.EditItemFromDetailDbRequest
import org.zotero.android.database.requests.EditNoteDbRequest
import org.zotero.android.database.requests.EditTagsForItemDbRequest
import org.zotero.android.database.requests.MarkItemsAsTrashedDbRequest
import org.zotero.android.database.requests.MarkObjectsAsDeletedDbRequest
import org.zotero.android.database.requests.ReadItemDbRequest
import org.zotero.android.database.requests.RemoveItemFromParentDbRequest
import org.zotero.android.files.FileStore
import org.zotero.android.helpers.GetMimeTypeUseCase
import org.zotero.android.helpers.MediaSelectionResult
import org.zotero.android.helpers.SelectMediaUseCase
import org.zotero.android.helpers.formatter.dateAndTimeFormat
import org.zotero.android.helpers.formatter.fullDateWithDashes
import org.zotero.android.helpers.formatter.iso8601DateFormatV2
import org.zotero.android.helpers.formatter.sqlFormat
import org.zotero.android.ktx.index
import org.zotero.android.screens.addnote.data.AddOrEditNoteArgs
import org.zotero.android.screens.addnote.data.SaveNoteAction
import org.zotero.android.screens.creatoredit.data.CreatorEditArgs
import org.zotero.android.screens.itemdetails.ItemDetailsViewEffect.OnBack
import org.zotero.android.screens.itemdetails.ItemDetailsViewEffect.OpenFile
import org.zotero.android.screens.itemdetails.ItemDetailsViewEffect.OpenWebpage
import org.zotero.android.screens.itemdetails.ItemDetailsViewEffect.ScreenRefresh
import org.zotero.android.screens.itemdetails.ItemDetailsViewEffect.ShowAddOrEditNoteEffect
import org.zotero.android.screens.itemdetails.ItemDetailsViewEffect.ShowCreatorEditEffect
import org.zotero.android.screens.itemdetails.ItemDetailsViewEffect.ShowImageViewer
import org.zotero.android.screens.itemdetails.ItemDetailsViewEffect.ShowItemTypePickerEffect
import org.zotero.android.screens.itemdetails.ItemDetailsViewEffect.ShowPdf
import org.zotero.android.screens.itemdetails.ItemDetailsViewEffect.ShowVideoPlayer
import org.zotero.android.screens.itemdetails.data.DetailType
import org.zotero.android.screens.itemdetails.data.ItemDetailAttachmentKind
import org.zotero.android.screens.itemdetails.data.ItemDetailCreator
import org.zotero.android.screens.itemdetails.data.ItemDetailData
import org.zotero.android.screens.itemdetails.data.ItemDetailError
import org.zotero.android.screens.itemdetails.data.ItemDetailField
import org.zotero.android.screens.itemdetails.data.ItemDetailsArgs
import org.zotero.android.screens.mediaviewer.image.ImageViewerArgs
import org.zotero.android.screens.mediaviewer.video.VideoPlayerArgs
import org.zotero.android.screens.tagpicker.data.TagPickerArgs
import org.zotero.android.screens.tagpicker.data.TagPickerResult
import org.zotero.android.sync.AttachmentFileCleanupController
import org.zotero.android.sync.AttachmentFileDeletedNotification
import org.zotero.android.sync.DateParser
import org.zotero.android.sync.ItemDetailCreateDataResult
import org.zotero.android.sync.ItemDetailDataCreator
import org.zotero.android.sync.KeyGenerator
import org.zotero.android.sync.Library
import org.zotero.android.sync.LibraryIdentifier
import org.zotero.android.sync.Note
import org.zotero.android.sync.SchemaController
import org.zotero.android.sync.Tag
import org.zotero.android.sync.UrlDetector
import org.zotero.android.sync.conflictresolution.AskUserToDeleteOrRestoreItem
import org.zotero.android.sync.conflictresolution.ConflictResolutionUseCase
import org.zotero.android.uicomponents.bottomsheet.LongPressOptionItem
import org.zotero.android.uicomponents.bottomsheet.LongPressOptionsHolder
import org.zotero.android.uicomponents.singlepicker.SinglePickerArgs
import org.zotero.android.uicomponents.singlepicker.SinglePickerResult
import org.zotero.android.uicomponents.singlepicker.SinglePickerStateCreator
import timber.log.Timber
import java.io.File
import java.util.Date
import java.util.UUID
import javax.inject.Inject


@HiltViewModel
class ItemDetailsViewModel @Inject constructor(
    dispatcher: CoroutineDispatcher,
    private val defaults: Defaults,
    private val dbWrapper: DbWrapper,
    private val fileStore: FileStore,
    private val urlDetector: UrlDetector,
    private val schemaController: SchemaController,
    private val selectMedia: SelectMediaUseCase,
    private val fileDownloader: AttachmentDownloader,
    private val attachmentDownloaderEventStream: AttachmentDownloaderEventStream,
    private val getMimeTypeUseCase: GetMimeTypeUseCase,
    private val fileCleanupController: AttachmentFileCleanupController,
    private val conflictResolutionUseCase: ConflictResolutionUseCase,
    private val dateParser: DateParser,
) : BaseViewModel2<ItemDetailsViewState, ItemDetailsViewEffect>(ItemDetailsViewState()) {

    private var coroutineScope = CoroutineScope(dispatcher)

    //required to keep item change listener alive
    private var currentItem: RItem? = null

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(askUserToDeleteOrRestoreItem: AskUserToDeleteOrRestoreItem) {
        updateState {
            copy(error = ItemDetailError.askUserToDeleteOrRestoreItem)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(itemDetailCreator: ItemDetailCreator) {
        onSaveCreator(itemDetailCreator)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(saveNoteAction: SaveNoteAction) {
        if (!saveNoteAction.isFromDashboard) {
            viewModelScope.launch {
                saveNote(saveNoteAction.text, saveNoteAction.tags, saveNoteAction.key)
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(singlePickerResult: SinglePickerResult) {
        if (singlePickerResult.callPoint == SinglePickerResult.CallPoint.ItemDetails) {
            viewModelScope.launch {
                changeType(singlePickerResult.id)
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(tagPickerResult: TagPickerResult) {
        viewModelScope.launch {
            setTags(tagPickerResult.tags)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(attachmentFileDeleted: EventBusConstants.AttachmentFileDeleted) {
        updateDeletedAttachmentFiles(attachmentFileDeleted.notification)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(event: EventBusConstants.FileWasSelected) {
        if (event.uri != null && event.callPoint == EventBusConstants.FileWasSelected.CallPoint.ItemDetails) {
            viewModelScope.launch {
                addAttachments(listOf(event.uri))
            }
        }
    }

    fun init() = initOnce {
        EventBus.getDefault().register(this)
        setupFileObservers()

        val args = ScreenArguments.itemDetailsArgs

        initViewState(args)
        loadInitialData()
    }

    private fun setupFileObservers() {
        attachmentDownloaderEventStream.flow()
            .onEach { update ->
                process(update)
                if (update.kind is AttachmentDownloader.Update.Kind.progress) {
                    return@onEach
                }
                if (viewState.attachmentToOpen != update.key) {
                    return@onEach
                }

                attachmentOpened(update.key)
                when (update.kind) {
                    AttachmentDownloader.Update.Kind.ready -> {
                        showAttachment(key = update.key, parentKey = update.parentKey, libraryId = update.libraryId)
                    }
                    is AttachmentDownloader.Update.Kind.failed -> {
                        //TODO implement when unzipping is supported
                    }
                    else -> {}
                }
            }
            .launchIn(coroutineScope)

    }

    fun attachmentOpened(key: String) {
        if (viewState.attachmentToOpen != key) {
            return
        }
        viewModelScope.launch {
            updateState {
                copy(attachmentToOpen = null)
            }
        }
    }

    override fun onCleared() {
        currentItem?.removeAllChangeListeners()
        EventBus.getDefault().unregister(this)
        conflictResolutionUseCase.currentlyDisplayedItemLibraryIdentifier = null
        conflictResolutionUseCase.currentlyDisplayedItemKey = null
        super.onCleared()
    }

    private fun initViewState(args: ItemDetailsArgs) {
        val type = args.type
        val library = args.library
        val preScrolledChildKey = args.childKey
        val userId = defaults.getUserId()

        when (type) {
            is DetailType.preview -> {
                updateState {
                    copy(
                        key = type.key,
                        isEditing = false
                    )
                }

            }
            is DetailType.creation, is DetailType.duplication -> {
                updateState {
                    copy(
                        key = KeyGenerator.newKey(),
                        isEditing = true,
                    )
                }

            }

        }
        updateState {
            copy(
                type = type,
                userId = userId,
                library = library,
                preScrolledChildKey = preScrolledChildKey
            )
        }
        conflictResolutionUseCase.currentlyDisplayedItemLibraryIdentifier = viewState.library?.identifier
        conflictResolutionUseCase.currentlyDisplayedItemKey = viewState.key

    }

    fun onSaveOrEditClicked() {
        if (viewState.isEditing) {
            saveChanges()
        } else {
            val updatedStateData = viewState.data.deepCopy()
            val updatedData = viewState.data.deepCopy(
                fieldIds = ItemDetailDataCreator.allFieldKeys(
                    viewState.data.type,
                    schemaController = this.schemaController
                )
            )
            updateState {
                copy(
                    snapshot = updatedStateData,
                    data = updatedData,
                    isEditing = true,
                )
            }
        }


    }

    private fun loadInitialData() {
        val key = viewState.key
        val libraryId = viewState.library!!.identifier
        var collectionKey: String? = null
        var data: ItemDetailCreateDataResult? = null

        try {
            val type = viewState.type
            when (type) {
                is DetailType.creation -> {
                    val itemType = type.type
                    val child = type.child
                    collectionKey = type.collectionKey
                    data = ItemDetailDataCreator.createData(
                        ItemDetailDataCreator.Kind.new(
                            itemType = itemType,
                            child = child
                        ),
                        schemaController = this.schemaController,
                        fileStorage = this.fileStore,
                        urlDetector = this.urlDetector,
                        dateParser = this.dateParser,
                        doiDetector = { doiValue -> FieldKeys.Item.isDoi(doiValue) })
                }
                is DetailType.preview -> {
                    reloadData(isEditing = viewState.isEditing)
                    return
                }
                is DetailType.duplication -> {
                    val itemKey = type.itemKey
                    val _collectionKey = type.collectionKey
                    collectionKey = _collectionKey
                    val item = dbWrapper.realmDbStorage.perform(
                        request = ReadItemDbRequest(
                            libraryId = viewState.library!!.identifier,
                            key = itemKey
                        )
                    )
                    data = ItemDetailDataCreator.createData(ItemDetailDataCreator.Kind.existing(
                        item = item,
                        ignoreChildren = true
                    ),
                        schemaController = this.schemaController,
                        fileStorage = this.fileStore,
                        urlDetector = this.urlDetector,
                        dateParser = this.dateParser,
                        doiDetector = { doiValue -> FieldKeys.Item.isDoi(doiValue) })
                }
                else -> {}
            }
        } catch (e: Exception) {
            Timber.e(e, "can't load initial data ")
            updateState {
                copy(error = ItemDetailError.cantCreateData)
            }
            return
        }
        data!!
        val request = CreateItemFromDetailDbRequest(
            key = key,
            libraryId = libraryId,
            collectionKey = collectionKey,
            data = data.itemData,
            attachments = data.attachments,
            notes = data.notes,
            tags = data.tags,
            dateParser = this.dateParser,
            schemaController = this.schemaController,
            fileStore = fileStore
        )
        viewModelScope.launch {
            val result = perform(dbWrapper, request = request, invalidateRealm = true)
            if (result is Result.Failure) {
                Timber.e(result.exception, "ItemDetailActionHandler: can't create initial item")
                updateState {
                    copy(error = ItemDetailError.cantCreateData)
                }
            } else {
                reloadData(isEditing = true)
            }
        }

    }

    private fun reloadData(isEditing: Boolean) = viewModelScope.launch {
        try {
            currentItem?.removeAllChangeListeners()
            val item = dbWrapper.realmDbStorage.perform(
                request = ReadItemDbRequest(
                    libraryId = viewState.library!!.identifier,
                    key = viewState.key
                ), refreshRealm = true
            )
            currentItem = item
            if (viewState.type is DetailType.preview) {
                item.addChangeListener(RealmObjectChangeListener<RItem> { items, changeSet ->
                    if (changeSet?.changedFields?.any {
                            RItem.observableKeypathsForItemDetail.contains(
                                it
                            )
                        } == true) {
                        itemChanged(items, changeSet)
                    }
                })
            }


            val (data, attachments, notes, tags) = ItemDetailDataCreator.createData(
                ItemDetailDataCreator.Kind.existing(item = item, ignoreChildren = false),
                schemaController = this@ItemDetailsViewModel.schemaController,
                dateParser = this@ItemDetailsViewModel.dateParser,
                fileStorage = this@ItemDetailsViewModel.fileStore,
                urlDetector = this@ItemDetailsViewModel.urlDetector,
                doiDetector = { doiValue -> FieldKeys.Item.isDoi(doiValue) })

            if (!isEditing) {
                data.fieldIds =
                    ItemDetailDataCreator.filteredFieldKeys(data.fieldIds, fields = data.fields)
            }

            saveReloaded(
                data = data,
                attachments = attachments,
                notes = notes,
                tags = tags,
                isEditing = isEditing
            )
        } catch (e: Exception) {
            Timber.e(e, "ItemDetailActionHandler: can't load data")
            updateState {
                copy(error = ItemDetailError.cantCreateData)
            }
        }
    }

    private fun saveReloaded(
        data: ItemDetailData,
        attachments: List<Attachment>,
        notes: List<Note>,
        tags: List<Tag>,
        isEditing: Boolean,
    ) {
        updateState {
            copy(data = data)
        }
        if (viewState.snapshot != null || isEditing) {
            val updatedSnapshot = data.deepCopy(
                fieldIds = ItemDetailDataCreator.filteredFieldKeys(
                    data.fieldIds,
                    fields = data.fields
                )
            )
            updateState {
                copy(
                    snapshot = updatedSnapshot,
                )
            }
        }
        updateState {
            copy(
                attachments = attachments,
                notes = notes,
                tags = tags,
                isLoadingData = false,
                isEditing = isEditing,
            )
        }
        //Update UI and hide progress.
    }

    private fun itemChanged(item: RItem, changeSet: ObjectChangeSet) {
        if (changeSet.isDeleted) {
            return
        }

        if (shouldReloadData(item, changeSet.changedFields)) {
            itemChanged(viewState)
        }
    }

    private fun shouldReloadData(item: RItem, changes: Array<String>): Boolean {
        if (changes.contains("version")) {
            //Use old value?
            if (changes.contains("changeType") && item.changeType != UpdatableChangeType.user.name) {
                return true
            }
            return false
        }

        if (changes.contains("children")) {
            return true
        }
        return false
    }

    private fun itemChanged(state: ItemDetailsViewState) {
        if (!state.isEditing) {
            reloadData(viewState.isEditing)
            return
        }
        updateState {
            copy(error = ItemDetailError.itemWasChangedRemotely)
        }
    }

    fun onAddCreator() {
        showCreatorCreation(itemType = viewState.data.type)
    }

    fun onAddNote() {
        openNoteEditor(null)
    }

    fun onAddTag() {
        val libraryId = viewState.library!!.identifier
        val selected = viewState.tags.map { it.id }.toSet()
        ScreenArguments.tagPickerArgs =
            TagPickerArgs(libraryId = libraryId, selectedTags = selected)
        triggerEffect(ItemDetailsViewEffect.ShowTagPickerEffect)
    }

    fun onAddAttachment() {
        triggerEffect(ItemDetailsViewEffect.AddAttachment)
    }

    fun showCreatorCreation(itemType: String) {
        val schema = schemaController.creators(itemType)?.firstOrNull { it.primary }
        if (schema == null) {
            return
        }
        val localized = schemaController.localizedCreator(schema.creatorType)
        if (localized == null) {
            return
        }
        val creator = ItemDetailCreator.init(
            type = schema.creatorType, primary = schema.primary,
            localizedType = localized, namePresentation = defaults.getCreatorNamePresentation()
        )
        _showCreatorEditor(creator, itemType = itemType)
    }

    private fun showCreatorEditor(creator: ItemDetailCreator, itemType: String) {
        _showCreatorEditor(creator, itemType = itemType)
    }

    private fun _showCreatorEditor(creator: ItemDetailCreator, itemType: String) {
        ScreenArguments.creatorEditArgs = CreatorEditArgs(creator = creator, itemType = itemType)
        triggerEffect(ShowCreatorEditEffect)
    }

    private fun onSaveCreator(creator: ItemDetailCreator) {
        if (!viewState.data.creatorIds.contains(creator.id)) {
            updateState {
                copy(
                    data = viewState.data.deepCopy(
                        creatorIds = viewState.data.creatorIds + creator.id,
                    )
                )
            }
        }
        val updatedCreators = viewState.data.creators.toMutableMap()
        updatedCreators[creator.id] = creator
        updateState {
            copy(
                data = viewState.data.deepCopy(
                    creators = updatedCreators
                )
            )
        }
    }

    fun setFieldValue(id: String, value: String) {
        val field = viewState.data.fields[id]
        if (field == null) {
            return
        }

        field.value = value
        field.isTappable = ItemDetailDataCreator.isTappable(
            key = field.key,
            value = field.value,
            urlDetector = this.urlDetector,
            doiDetector = { doiValue -> FieldKeys.Item.isDoi(doiValue) }
        )

        if (field.key == FieldKeys.Item.date || field.baseField == FieldKeys.Item.date) {
            val order = this.dateParser.parse(value)?.orderWithSpaces
            if (order != null) {
                val info = (field.additionalInfo ?: emptyMap()).toMutableMap()
                info[ItemDetailField.AdditionalInfoKey.dateOrder] = order
                field.additionalInfo = info
            } else if (field.additionalInfo != null) {
                field.additionalInfo = null
            }
        } else if (field.additionalInfo != null) {
            field.additionalInfo = null
        }

        val updatedData = viewState.data.deepCopy(fields = viewState.data.fields)
        updateState {
            copy(data = updatedData)
        }
        triggerEffect(ScreenRefresh)
    }

    fun onTitleEdit(newTitle: String) {
        val updatedData = viewState.data.deepCopy(title = newTitle)
        updateState {
            copy(data = updatedData)
        }
    }

    fun onAbstractEdit(newAbstract: String) {
        val updatedData = viewState.data.deepCopy(abstract = newAbstract)
        updateState {
            copy(data = updatedData)
        }
    }

    private fun parseDateSpecialValue(value: String): Date? {
        when (value.lowercase()) {
            "tomorrow" ->
                return DateTime.now().plusDays(1).toDate()
            "today" ->
                return Date()
            "yesterday" ->
                return DateTime.now().minusDays(1).toDate()
            else ->
                return null
        }
    }

    private fun updateDateFieldIfNeeded() {
        val field =
            viewState.data.fields.values.firstOrNull { it.baseField == FieldKeys.Item.date || it.key == FieldKeys.Item.date }
        if (field != null) {
            val date = parseDateSpecialValue(field.value)
            if (date != null) {
                field.value = fullDateWithDashes.format(date)
                val order = this.dateParser.parse(field.value)?.orderWithSpaces
                if (order != null) {
                    val mutableAdditionalInfo = (field.additionalInfo ?: emptyMap()).toMutableMap()
                    mutableAdditionalInfo[ItemDetailField.AdditionalInfoKey.dateOrder] = order
                    field.additionalInfo = mutableAdditionalInfo
                }

                val updatedFieldsMap = viewState.data.fields.toMutableMap()
                updatedFieldsMap[field.key] = field

                val updatedData = viewState.data.deepCopy(fields = updatedFieldsMap)
                updateState {
                    copy(data = updatedData)
                }
            }
        }
    }

    fun saveChanges() {
        if (viewState.snapshot == viewState.data) {
            return
        }
        try {
            coroutineScope.launch {
                save()
            }
        } catch (error: Exception) {
            Timber.e(error, "ItemDetailStore: can't store changes")
            updateState {
                copy(error = error as? ItemDetailError ?: ItemDetailError.cantStoreChanges)
            }
        }
    }

    private fun save() {
        updateDateFieldIfNeeded()
        updateAccessedFieldIfNeeded()

        viewModelScope.launch {
            updateState {
                copy(data = viewState.data.deepCopy(dateModified = Date()))
            }
        }

        val snapshot = viewState.snapshot
        if (snapshot != null) {
            val request = EditItemFromDetailDbRequest(
                libraryId = viewState.library!!.identifier,
                itemKey = viewState.key,
                data = viewState.data,
                snapshot = snapshot,
                schemaController = this.schemaController,
                dateParser = this.dateParser,
            )
            dbWrapper.realmDbStorage.perform(request = request)
        }
        val updatedData = viewState.data.deepCopy(
            fieldIds = ItemDetailDataCreator.filteredFieldKeys(
                viewState.data.fieldIds,
                viewState.data.fields
            )
        )
        viewModelScope.launch {
            updateState {
                copy(
                    snapshot = null,
                    isEditing = false,
                    type = DetailType.preview(viewState.key),
                    data = updatedData
                )
            }
        }

    }

    private fun updateAccessedFieldIfNeeded() {
        var field = viewState.data.fields[FieldKeys.Item.accessDate]
        if (field == null) {
            return
        }
        var date: Date? = null
        val dateSpecific = parseDateSpecialValue(field.value)
        if (dateSpecific != null) {
            date = dateSpecific
        } else {
            try {
                val _date = sqlFormat.parse(field.value)
                if (_date != null) {
                    date = _date
                }
            } catch (e: Exception) {
                //no-op
            }

        }

        if (date != null) {
            field.value = iso8601DateFormatV2.format(date)
            field.additionalInfo = mapOf(
                ItemDetailField.AdditionalInfoKey.formattedDate to dateAndTimeFormat.format(date),
                ItemDetailField.AdditionalInfoKey.formattedEditDate to sqlFormat.format(date)
            )
        } else {
            val snapshotField = viewState.snapshot?.fields?.get(FieldKeys.Item.accessDate)
            if (snapshotField != null) {
                field = snapshotField
            } else {
                field.value = ""
                field.additionalInfo = emptyMap()
            }
        }

        val updatedFieldsMap = viewState.data.fields.toMutableMap()
        updatedFieldsMap[field.key] = field

        val updatedData = viewState.data.deepCopy(fields = updatedFieldsMap)
        viewModelScope.launch {
            updateState {
                copy(data = updatedData)
            }
        }
    }

    private fun process(update: AttachmentDownloader.Update) {
        if (viewState.library!!.identifier != update.libraryId) {
            return
        }

        val index = viewState.attachments.indexOfFirst {  it.key == update.key }
        if (index == -1) {
            return
        }
        val attachment = viewState.attachments[index]
        viewModelScope.launch {
            when (update.kind) {
                AttachmentDownloader.Update.Kind.cancelled, is AttachmentDownloader.Update.Kind.failed, is AttachmentDownloader.Update.Kind.progress -> {
                    updateState {
                        copy(updateAttachmentKey = attachment.key)
                    }
                }
                AttachmentDownloader.Update.Kind.ready -> {
                    val new = attachment.changed(location = Attachment.FileLocation.local)
                    if (new == null) {
                        return@launch
                    }
                    val attachmentsMutable = viewState.attachments.toMutableList()
                    attachmentsMutable[index] = new
                    updateState {
                        copy(attachments = attachmentsMutable, updateAttachmentKey = new.key)
                    }
                }
            }
            triggerEffect(ScreenRefresh)
        }
    }

    fun onCancelOrBackClicked() {
        if (viewState.isEditing) {
            cancelChanges()
        } else {
            triggerEffect(OnBack)
        }
    }

    private fun cancelChanges() {
        viewModelScope.launch {
            val type = viewState.type
            if (type is DetailType.duplication) {
                val result = perform(
                    dbWrapper = dbWrapper,
                    request = MarkObjectsAsDeletedDbRequest(
                        clazz = RItem::class,
                        keys = listOf(viewState.key),
                        libraryId = viewState.library!!.identifier
                    )
                ).ifFailure {
                    Timber.e(it, "ItemDetailActionHandler: can't remove duplicated item")
                    updateState {
                        copy(error = ItemDetailError.cantRemoveDuplicatedItem)
                    }
                    return@launch
                }
                triggerEffect(OnBack)
                return@launch
            }
        }

        val snapshot = viewState.snapshot?.deepCopy()
        if (snapshot == null) {
            return
        }
        updateState {
            copy(
                data = snapshot,
                snapshot = null,
                isEditing = false
            )
        }
    }

    fun onCreatorClicked(creator: ItemDetailCreator) {
        showCreatorEditor(creator = creator, itemType = viewState.data.type)
    }

    fun onDeleteCreator(creatorId: UUID) {
        val index = viewState.data.creatorIds.indexOf(creatorId)
        if (index == -1) {
            return
        }
        val updatedCreators = viewState.data.creators.toMutableMap()
        updatedCreators.remove(creatorId)
        updateState {
            copy(
                data = viewState.data.deepCopy(
                    creatorIds = viewState.data.creatorIds - creatorId,
                    creators = updatedCreators
                )
            )
        }
    }

    fun openNoteEditor(note: Note?) {
        val library = viewState.library!!
        val key = note?.key ?: KeyGenerator.newKey()
        val title =
            AddOrEditNoteArgs.TitleData(type = viewState.data.type, title = viewState.data.title)

        ScreenArguments.addOrEditNoteArgs = AddOrEditNoteArgs(
            text = note?.text ?: "",
            tags = note?.tags ?: listOf(),
            title = title,
            key = key,
            libraryId = library.identifier,
            readOnly = !library.metadataEditable,
            isFromDashboard = false
        )
        triggerEffect(ShowAddOrEditNoteEffect)
    }

    private suspend fun saveNote(text: String, tags: List<Tag>, key: String) {
        val oldNote = viewState.notes.firstOrNull { it.key == key }
        val note = Note(key = key, text = text, tags = tags)

        val indexNote = viewState.notes.indexOfFirst { it.key == key }
        val updatedNotes = viewState.notes.toMutableList()
        if (indexNote != -1) {
            updatedNotes[indexNote] = note
        } else {
            updatedNotes.add(note)
        }
        updateState {
            copy(
                notes = updatedNotes,
                backgroundProcessedItems = backgroundProcessedItems + key
            )
        }

        fun finishSave(error: Throwable?) {
            updateState {
                copy(backgroundProcessedItems = backgroundProcessedItems - key)
            }
            if (error == null) {
                return
            }

            Timber.e(error, "Can't edit/save note $key")
            updateState {
                copy(error = ItemDetailError.cantSaveNote)
            }
            val index = viewState.notes.indexOfFirst { it.key == key }
            if (index == -1) {
                return
            }
            val updatedNotes = viewState.notes.toMutableList()
            if (oldNote != null) {
                updatedNotes[index] = oldNote
            } else {
                updatedNotes.removeAt(index)
            }
            updateState {
                copy(notes = updatedNotes)
            }
        }

        if (oldNote != null) {
            val request = EditNoteDbRequest (note = note, libraryId = viewState.library!!.identifier)
            perform(dbWrapper = dbWrapper, request = request).ifFailure {
                finishSave(it)
                return
            }
            return
        }

        val type = schemaController.localizedItemType(ItemTypes.note) ?: ItemTypes.note
        val request = CreateNoteDbRequest(
            note = note,
            localizedType = type,
            libraryId = viewState.library!!.identifier,
            collectionKey = null,
            parentKey = viewState.key
        )
        val result = perform(
            dbWrapper = dbWrapper,
            request = request,
            invalidateRealm = true
        ).ifFailure { error ->
            finishSave(error)
            return
        }
        finishSave(null)
    }

    fun openAttachment(attachment: Attachment) {
        val key = attachment.key
        val (progress, _) = this.fileDownloader.data(
            key = key,
            libraryId = viewState.library!!.identifier
        )

        if (progress != null) {
            if (viewState.attachmentToOpen == key) {
                updateState {
                    copy(attachmentToOpen = null)
                }
            }

            this.fileDownloader.cancel(key = key, libraryId = viewState.library!!.identifier)
            return
        }
        val attachment = viewState.attachments.firstOrNull { it.key == key }
        if (attachment == null) {
            return
        }

        updateState {
            copy(attachmentToOpen = key)
        }
        this.fileDownloader.downloadIfNeeded(attachment = attachment, parentKey = viewState.key)
    }

    fun onItemTypeClicked() {
        if (viewState.data.isAttachment) {
            return
        }
        val selected = viewState.data.type
        ScreenArguments.singlePickerArgs = SinglePickerArgs(
            singlePickerState = SinglePickerStateCreator.create(
                selected = selected,
                schemaController
            ),
            showSaveButton = false,
            callPoint = SinglePickerResult.CallPoint.ItemDetails
        )
        triggerEffect(ShowItemTypePickerEffect)
    }

    private fun changeType(newType: String) {
        val data: ItemDetailData
        try {
            data = data(newType, viewState.data)
        } catch (error: Throwable) {
            Timber.e(error)
            updateState {
                copy(
                    error = (error as? ItemDetailError) ?: ItemDetailError.typeNotSupported(newType)
                )
            }
            return
        }

        val droppedFields = droppedFields(viewState.data, data)
        updateState {
            if (droppedFields.isEmpty()) {
                copy(data = data)
            } else {
                copy(
                    promptSnapshot = data,
                    error = ItemDetailError.droppedFields(droppedFields)
                )
            }
        }
    }

    private fun droppedFields(fromData: ItemDetailData, toData: ItemDetailData): List<String> {
        val newFields = toData.fields.values.toMutableSet()
        val subtracted = fromData.fields.values.filter{ !it.value.isEmpty() }.toMutableSet()
        for (field in newFields) {
            val oldField = subtracted.firstOrNull {(it.baseField ?: it.name) == (field.baseField ?: field.name) }
            if (oldField == null) {
                continue
            }
            subtracted.remove(oldField)
        }
        return subtracted.map{ it.name }.sorted()
    }

    private fun creators(type: String, originalData: Map<UUID, ItemDetailCreator>): Map<UUID, ItemDetailCreator> {
        val schemas = schemaController.creators(type)
        if (schemas == null) {
            throw ItemDetailError.typeNotSupported(type)
        }
        val primary = schemas.firstOrNull { it.primary }
        if (primary == null) {
            throw ItemDetailError.typeNotSupported(type)
        }

        var creators = originalData.toMutableMap()
        for ((key, originalCreator) in originalData) {
            if(schemas.firstOrNull{ it.creatorType == originalCreator.type } != null) {
                continue
            }

            var creator = originalCreator.copy()

            if (originalCreator.primary) {
                creator.type = primary.creatorType
            } else {
                creator.type = "contributor"
            }
            creator.localizedType = schemaController.localizedCreator(creator.type) ?: ""
            creators[key] = creator
        }

        return creators
    }

    private fun data(type: String, originalData: ItemDetailData): ItemDetailData {
        val localizedType = schemaController.localizedItemType(itemType = type)
        if (localizedType == null) {
            throw ItemDetailError.typeNotSupported(type)
        }

        val (fieldIds, fields, hasAbstract) = ItemDetailDataCreator.fieldData(
            type,
            schemaController = this.schemaController,
            urlDetector = this.urlDetector,
            doiDetector = { FieldKeys.Item.isDoi(it) },
            getExistingData = { key, baseField ->
                val originalDataField = originalData.fields[key]
                if (originalDataField != null) {
                    return@fieldData originalDataField.name to originalDataField.value
                }
                val base = baseField
                if (base == null) {
                    return@fieldData null to null
                }

                val field = originalData.fields.values.firstOrNull { it.baseField == base }
                if (field != null) {
                    return@fieldData null to field.value
                }
                return@fieldData null to null
            },
            dateParser = this.dateParser
        )

        var data = originalData
            .deepCopy(
                type = type,
                isAttachment = type == ItemTypes.attachment,
                localizedType = localizedType,
                fields = fields,
                fieldIds = fieldIds,
                abstract = if (hasAbstract) (originalData.abstract ?: "") else null,
                creators = creators(type, originalData.creators),
                creatorIds = originalData.creatorIds,
            )
        return data
    }

    fun onDismissErrorDialog() {
        updateState {
            copy(
                error = null,
            )
        }
    }

    fun acceptPrompt() {
        val snapshot = viewState.promptSnapshot
        if (snapshot == null) {
            return
        }
        updateState {
            copy(
                data = snapshot.deepCopy(),
                promptSnapshot = null
            )
        }
    }

    fun acceptItemWasChangedRemotely() {
        reloadData(viewState.isEditing)
    }

    fun deleteOrRestoreItem(isDelete: Boolean) {
        conflictResolutionUseCase.deleteOrRestoreItem(isDelete = isDelete, key = viewState.key)
        if (isDelete) {
            triggerEffect(OnBack)
        }
    }

    fun cancelPrompt() {
        updateState {
            copy(
                promptSnapshot = null,
            )
        }
    }

    fun dismissBottomSheet() {
        updateState {
            copy(longPressOptionsHolder = null)
        }
    }

    fun onLongPressOptionsItemSelected(longPressOptionItem: LongPressOptionItem) {
        viewModelScope.launch {
            when (longPressOptionItem) {
                is LongPressOptionItem.TrashNote -> {
                    delete(longPressOptionItem.note)
                }
                is LongPressOptionItem.DeleteTag -> {
                    delete(longPressOptionItem.tag)
                }
                is LongPressOptionItem.DeleteCreator -> {
                    delete(longPressOptionItem.creator)
                }
                is LongPressOptionItem.MoveToTrashAttachment -> {
                    delete(longPressOptionItem.attachment)
                }
                is LongPressOptionItem.DeleteAttachmentFile -> {
                    deleteFile(longPressOptionItem.attachment)
                }
                is LongPressOptionItem.MoveToStandaloneAttachment -> {
                    moveToStandalone(longPressOptionItem.attachment)
                }
                else -> {}
            }
        }
    }

    fun onNoteLongClick(note: Note) {
        updateState {
            copy(
                longPressOptionsHolder = LongPressOptionsHolder(
                    title = note.title,
                    longPressOptionItems = listOf(LongPressOptionItem.TrashNote(note))
                )
            )
        }
    }
    fun onTagLongClick(tag: Tag) {
        updateState {
            copy(
                longPressOptionsHolder = LongPressOptionsHolder(
                    title = tag.name,
                    longPressOptionItems = listOf(LongPressOptionItem.DeleteTag(tag))
                )
            )
        }
    }

    fun onCreatorLongClick(creator: ItemDetailCreator) {
        updateState {
            copy(
                longPressOptionsHolder = LongPressOptionsHolder(
                    title = creator.name,
                    longPressOptionItems = listOf(LongPressOptionItem.DeleteCreator(creator))
                )
            )
        }
    }

    fun onAttachmentLongClick(attachment: Attachment) {
        val actions = mutableListOf<LongPressOptionItem>()
        val attachmentType = attachment.type
        if (attachmentType is Attachment.Kind.file && attachmentType.location == Attachment.FileLocation.local) {
            actions.add(LongPressOptionItem.DeleteAttachmentFile(attachment))
        }

        if (!viewState.data.isAttachment) {
            actions.add(LongPressOptionItem.MoveToStandaloneAttachment(attachment))
            actions.add(LongPressOptionItem.MoveToTrashAttachment(attachment))
        }
        if (actions.isNotEmpty()) {
            updateState {
                copy(
                    longPressOptionsHolder = LongPressOptionsHolder(
                        title = attachment.title,
                        longPressOptionItems = actions
                    )
                )
            }
        }
    }

    private suspend fun delete(tag: Tag) {
        updateState {
            copy(backgroundProcessedItems = backgroundProcessedItems + tag.name)
        }
        val request = DeleteTagFromItemDbRequest(
            key = viewState.key,
            libraryId = viewState.library!!.identifier,
            tagName = tag.name
        )
        perform(dbWrapper = dbWrapper, request = request).ifFailure { error ->
            Timber.e(error, "ItemDetailActionHandler: can't delete tag ${tag.name}")
            updateState {
                copy(
                    error = ItemDetailError.cantSaveTags,
                    backgroundProcessedItems = backgroundProcessedItems - tag.name
                )
            }
            return
        }
        val index = viewState.tags.indexOf(tag)
        if (index != -1) {
            val updatedTags = viewState.tags.toMutableList()
            updatedTags.removeAt(index)
            updateState {
                copy(
                    tags = updatedTags,
                    backgroundProcessedItems = backgroundProcessedItems - tag.name
                )
            }
        }
    }

    private suspend fun delete(note: Note) {
        if (!viewState.notes.contains(note)) {
            return
        }
        trashItem(key = note.key) {
            val index = viewState.notes.indexOf(note)
            if (index == -1) {
                return@trashItem
            }
            val updatedNotes = viewState.notes.toMutableList()
            updatedNotes.removeAt(index)
            updateState {
                copy(notes = updatedNotes)
            }
        }
    }

    private fun delete(creator: ItemDetailCreator) {
        val id = creator.id

        val index = viewState.data.creatorIds.indexOf(id)
        if (index == -1) {
            return
        }
        val updatedCreatorIds = viewState.data.creatorIds.toMutableList()
        updatedCreatorIds.removeAt(index)

        val updatedCreators = viewState.data.creators.toMutableMap()
        updatedCreators.remove(id)

        updateState {
            copy(data = data.deepCopy(creatorIds = updatedCreatorIds, creators = updatedCreators))
        }
    }

    private fun deleteFile(attachment: Attachment) {
        this.fileCleanupController.delete(
            AttachmentFileCleanupController.DeletionType.individual(
                attachment = attachment,
                parentKey = viewState.key
            ), completed = null
        )
    }

    private suspend fun delete(attachment: Attachment) {
        if (!viewState.attachments.contains(attachment)) {
            return
        }

        trashItem(key = attachment.key) {
            val index = viewState.attachments.indexOf(attachment)
            if (index == -1) {
                return@trashItem
            }
            val updatedAttachments = viewState.attachments.toMutableList()
            updatedAttachments.removeAt(index)
            updateState {
                copy(attachments = updatedAttachments)
            }
        }
    }

    private suspend fun trashItem(key: String, onSuccess: () -> Unit) {
        updateState {
            copy(backgroundProcessedItems = backgroundProcessedItems + key)
        }
        val request = MarkItemsAsTrashedDbRequest(
            keys = listOf(key),
            libraryId = viewState.library!!.identifier,
            trashed = true
        )
        perform(dbWrapper = dbWrapper, request = request).ifFailure { error ->
            Timber.e(error, "ItemDetailActionHandler: can't trash item $key")
            updateState {
                copy(
                    error = ItemDetailError.cantTrashItem,
                    backgroundProcessedItems = backgroundProcessedItems - key
                )
            }
            return
        }
        updateState {
            copy(backgroundProcessedItems = backgroundProcessedItems - key)
        }
        onSuccess()

    }

    private suspend fun showAttachment(key: String, parentKey: String?, libraryId: LibraryIdentifier) {
        val attachmentResult = attachment(key = key, parentKey = parentKey, libraryId = libraryId)
        if (attachmentResult == null) {
            return
        }
        val (attachment, library) = attachmentResult
        viewModelScope.launch {
            show(attachment = attachment, library = library)
        }
    }

    fun attachment(key: String, parentKey: String?, libraryId: LibraryIdentifier): Pair<Attachment, Library>? {
        val index = viewState.attachments.indexOfFirst { it.key == key && it.libraryId == libraryId }
        if (index == -1) {
            return null
        }
        val attachment = viewState.attachments[index]
        val library = viewState.library!!
        return attachment to library
    }

    private suspend fun show(attachment: Attachment, library: Library) {
        val attachmentType = attachment.type
        when (attachmentType) {
            is Attachment.Kind.url -> {
                showUrl(attachmentType.url)
            }
            is Attachment.Kind.file -> {
                val filename = attachmentType.filename
                val contentType = attachmentType.contentType
                val file = fileStore.attachmentFile(
                    libraryId = library.identifier,
                    key = attachment.key,
                    filename = filename,
                    contentType = contentType
                )
                when (contentType) {
                    "application/pdf" -> {
                        showPdf(file = file, attachment = attachment)
                    }
                    "text/html", "text/plain" -> {
                        openFile(file = file, mime = contentType)
                    }
                    else -> {
                        if (contentType.contains("image")) {
                            showImageFile(file)
                        } else if (contentType.contains("video")) {
                            showVideoFile(file)
                        }
                    }
                }
            }
        }
    }

    private suspend fun showUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != null && uri.scheme != "http" && uri.scheme != "https") {
            val mimeType = getMimeTypeUseCase.execute(url)!!
            triggerEffect(OpenFile(uri.toFile(), mimeType))
        } else {
            triggerEffect(OpenWebpage(uri))
        }
    }

    private fun showPdf(file: File, attachment: Attachment) {
        triggerEffect(ShowPdf(
            file = file,
            key = attachment.key,
            library = viewState.library!!
        ))
    }

    private fun openFile(file: File, mime: String) {
        triggerEffect(OpenFile(file, mime))
    }

    private fun showVideoFile(file: File) {
        ScreenArguments.videoPlayerArgs = VideoPlayerArgs(Uri.fromFile(file))
        triggerEffect(ShowVideoPlayer)
    }

    private fun showImageFile(file: File) {
        ScreenArguments.imageViewerArgs = ImageViewerArgs(Uri.fromFile(file), file.name)
        triggerEffect(ShowImageViewer)
    }

    fun calculateAttachmentKind(attachment: Attachment): ItemDetailAttachmentKind {
        val isProcessing = viewState.backgroundProcessedItems.contains(attachment.key)
        if (isProcessing) {
            return ItemDetailAttachmentKind.disabled
        }

        val (progress, error) = fileDownloader.data(key = attachment.key, libraryId = attachment.libraryId)

        if (error != null) {
            return ItemDetailAttachmentKind.failed(error)
        }

        if (progress != null) {
            return ItemDetailAttachmentKind.inProgress(progress)
        }

        return ItemDetailAttachmentKind.default
    }

    private fun updateDeletedAttachmentFiles(notification: AttachmentFileDeletedNotification) {
        when (notification) {
            AttachmentFileDeletedNotification.all -> {
                if(viewState.attachments.firstOrNull{ it.location == Attachment.FileLocation.local } == null) {
                    return
                }
                setAllAttachmentFilesAsDeleted()
            }
            is AttachmentFileDeletedNotification.library -> {
                val libraryId = notification.libraryId
                if (libraryId != viewState.library!!.identifier || viewState.attachments.firstOrNull { it.location == Attachment.FileLocation.local } == null) {
                    return
                }
                setAllAttachmentFilesAsDeleted()
            }
            is AttachmentFileDeletedNotification.allForItems -> {
                val libraryId = notification.libraryId
                val keys = notification.keys
                if (libraryId != viewState.library!!.identifier
                    || !keys.contains(viewState.key)
                    || viewState.attachments.firstOrNull { it.location == Attachment.FileLocation.local } == null) {
                    return
                }
                setAllAttachmentFilesAsDeleted()
            }
            is AttachmentFileDeletedNotification.individual -> {
                val libraryId = notification.libraryId
                val key = notification.key
                val index = viewState.attachments.indexOfFirst {  it.key == key && it.libraryId == libraryId }
                if (index == -1) {
                    return
                }
                val new = viewState.attachments[index].changed(location = Attachment.FileLocation.remote, condition = { it == Attachment.FileLocation.local })
                if (new == null) {
                    return
                }
                val updatedAttachments = viewState.attachments.toMutableList()
                updatedAttachments[index] = new
                updateState {
                    copy(
                        attachments = updatedAttachments,
                        updateAttachmentKey = new.key
                    )
                }
            }
        }
    }

    private fun setAllAttachmentFilesAsDeleted() {
        val updatedAttachments = viewState.attachments.toMutableList()
        for ((index, attachment) in viewState.attachments.withIndex()) {
            val new = attachment.changed(
                location = Attachment.FileLocation.remote,
                condition = { it == Attachment.FileLocation.local })
            if (new == null) {
                continue
            }
            updatedAttachments[index] = new
        }
        updateState {
            copy(attachments = updatedAttachments)
        }
    }

    private suspend fun setTags(tags: List<Tag>) {
        val oldTags = viewState.tags
        updateState {
            copy(
                tags = tags,
                backgroundProcessedItems = backgroundProcessedItems + tags.map { it.name }
            )
        }

        val request = EditTagsForItemDbRequest(key = viewState.key, libraryId = viewState.library!!.identifier, tags = tags)
        val result = perform(dbWrapper = dbWrapper, request = request)

        updateState {
            copy(backgroundProcessedItems = backgroundProcessedItems - tags.map { it.name })
        }
        if (result is Result.Failure) {
            Timber.e(result.exception, "ItemDetailActionHandler: can't set tags to item")
            updateState {
                copy(tags = oldTags)
            }
        }
    }

    private suspend fun addAttachments(urls: List<Uri>) {
        createAttachments(urls, libraryId = viewState.library!!.identifier) { attachments, failedCopyNames ->
            if (attachments.isEmpty()) {
                updateState {
                    copy(error = ItemDetailError.cantAddAttachments(ItemDetailError.AttachmentAddError.couldNotMoveFromSource(failedCopyNames)))
                }
                return@createAttachments
            }
            for (attachment in attachments) {
                val index = viewState.attachments.index(
                    attachment,
                    sortedBy = { first, second ->
                        first.title.compareTo(
                            second.title,
                            ignoreCase = true
                        ) == 1
                    })
                val updatedAttachments = viewState.attachments.toMutableList()
                updatedAttachments.add(index, attachment)
                updateState {
                    copy(
                        attachments = updatedAttachments,
                        backgroundProcessedItems = backgroundProcessedItems + attachment.key
                    )
                }
            }

            if (!failedCopyNames.isEmpty()) {
                updateState {
                    copy(
                        error = ItemDetailError.cantAddAttachments(
                            ItemDetailError.AttachmentAddError.couldNotMoveFromSource(
                                failedCopyNames
                            )
                        )
                    )
                }
            }

            val type = this.schemaController.localizedItemType(itemType = ItemTypes.attachment)
                ?: ItemTypes.attachment
            val request = CreateAttachmentsDbRequest(
                attachments = attachments,
                parentKey = viewState.key,
                localizedType = type,
                collections = emptySet(),
                fileStore = fileStore
            )

            viewModelScope.launch {
                val result =
                    perform(dbWrapper, invalidateRealm = true, request = request)
                for (attachment in attachments) {
                    updateState {
                        copy(backgroundProcessedItems = backgroundProcessedItems - attachment.key)
                    }
                }

                if (result is Result.Failure) {
                    Timber.e(
                        result.exception,
                        "ItemDetailActionHandler: could not create attachments"
                    )

                    val updatedAttachments = viewState.attachments.toMutableList()
                    updatedAttachments.removeAll { stateAttachment ->
                        attachments.map { it.key }.contains(stateAttachment.key)
                    }

                    updateState {
                        copy(
                            attachments = updatedAttachments,
                            error = ItemDetailError.cantAddAttachments(ItemDetailError.AttachmentAddError.allFailedCreation)
                        )
                    }
                } else if (result is Result.Success) {
                    val failed = result.value
                    if (failed.isEmpty()) {
                        return@launch
                    }
                    val updatedAttachments = viewState.attachments.toMutableList()
                    updatedAttachments.removeAll { stateAttachment ->
                        failed.map { it.first }.contains(stateAttachment.key)
                    }

                    updateState {
                        copy(
                            attachments = updatedAttachments,
                            error = ItemDetailError.cantAddAttachments(
                                ItemDetailError.AttachmentAddError.someFailedCreation(
                                    failed.map { it.second })
                            )
                        )
                    }
                }
            }
        }

    }

    private suspend fun createAttachments(urls: List<Uri>, libraryId: LibraryIdentifier, completion:  (List<Attachment>, List<String>) -> Unit) {
        val attachments = mutableListOf<Attachment>()
        val failedNames = mutableListOf<String>()
        for (url in urls) {
            val key = KeyGenerator.newKey()

            val selectionResult = selectMedia.execute(
                uri = url.toString(),
                isValidMimeType = { true }
            )

            val isSuccess = selectionResult is MediaSelectionResult.AttachMediaSuccess
            if (!isSuccess) {
                //TODO parse errors
                continue
            }
            selectionResult as MediaSelectionResult.AttachMediaSuccess

            val original = selectionResult.file.file
            val nameWithExtension = original.nameWithoutExtension + "." + original.extension
            val mimeType =
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(original.extension)
                    ?: "application/octet-stream"
            val file = fileStore.attachmentFile(
                libraryId = libraryId,
                key = key,
                filename = nameWithExtension,
                contentType = mimeType
            )
            if (!original.renameTo(file)) {
                Timber.e("ItemDetailActionHandler: can't move attachment from source url $url")
                failedNames.add(nameWithExtension)
            } else {
                attachments.add(
                    Attachment(
                        type = Attachment.Kind.file(
                            filename = nameWithExtension,
                            contentType = mimeType,
                            location = Attachment.FileLocation.local,
                            linkType = Attachment.FileLinkType.importedFile
                        ),
                        title = nameWithExtension,
                        key = key,
                        libraryId = libraryId
                    )
                )
            }
        }
        completion(attachments, failedNames)
    }

    private suspend fun moveToStandalone(attachment: Attachment) {
        updateState {
            copy(backgroundProcessedItems = backgroundProcessedItems + attachment.key)
        }

        val result = perform(
            dbWrapper,
            request = RemoveItemFromParentDbRequest(
                key = attachment.key,
                libraryId = attachment.libraryId
            )
        )

        updateState {
            copy(backgroundProcessedItems = backgroundProcessedItems - attachment.key)
        }
        if (result is Result.Failure) {
            Timber.e(
                result.exception,
                "ItemDetailActionHandler: can't move attachment to standalone"
            )
            updateState {
                copy(error = ItemDetailError.cantRemoveParent)
            }
        } else {
            updateState {
                copy(attachments = attachments - attachment)
            }

        }
    }

}

data class ItemDetailsViewState(
    val key: String = "",
    val library: Library? = null,
    val userId: Long = 0,
    val type: DetailType? = null,
    val preScrolledChildKey: String? = null,
    val isEditing: Boolean = false,
    var error: ItemDetailError? = null,
    val data: ItemDetailData = ItemDetailData.empty,
    var snapshot: ItemDetailData? = null,
    var promptSnapshot: ItemDetailData? = null,
    var notes: List<Note> = emptyList(),
    var attachments: List<Attachment> = emptyList(),
    var tags: List<Tag> = emptyList(),
    var updateAttachmentKey: String? = null,
    var attachmentToOpen: String? = null,
    var isLoadingData: Boolean = false,
    var backgroundProcessedItems: Set<String> = emptySet(),
    val longPressOptionsHolder: LongPressOptionsHolder? = null,
) : ViewState

sealed class ItemDetailsViewEffect : ViewEffect {
    object ShowCreatorEditEffect : ItemDetailsViewEffect()
    object ShowTagPickerEffect : ItemDetailsViewEffect()
    object ShowItemTypePickerEffect : ItemDetailsViewEffect()
    object ScreenRefresh : ItemDetailsViewEffect()
    object OnBack : ItemDetailsViewEffect()
    object ShowAddOrEditNoteEffect : ItemDetailsViewEffect()
    object ShowVideoPlayer : ItemDetailsViewEffect()
    object ShowImageViewer : ItemDetailsViewEffect()
    data class OpenFile(val file: File, val mimeType: String) : ItemDetailsViewEffect()
    data class ShowPdf(val file: File, val key: String, val library: Library) : ItemDetailsViewEffect()
    data class OpenWebpage(val uri: Uri) : ItemDetailsViewEffect()
    object AddAttachment : ItemDetailsViewEffect()
}
