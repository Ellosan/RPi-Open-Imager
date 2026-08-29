package dev.openimager.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.openimager.appGraph
import dev.openimager.core.customization.CustomizationSettings
import dev.openimager.core.customization.InitFormat
import dev.openimager.core.image.ImageSource
import dev.openimager.core.image.WriteOptions
import dev.openimager.core.oslist.HardwareDevice
import dev.openimager.core.oslist.OsListItem
import dev.openimager.core.oslist.filterForDevice
import dev.openimager.image.HttpImageSource
import dev.openimager.image.LocalImageSource
import dev.openimager.storage.StorageTarget
import dev.openimager.write.WriteCoordinator
import dev.openimager.write.WriteRequest
import dev.openimager.write.WriteService
import dev.openimager.write.WriteState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the user picked as the thing to write. */
sealed interface OsSelection {
    val label: String
    val subtitle: String

    data class Catalogue(val item: OsListItem) : OsSelection {
        override val label: String get() = item.name
        override val subtitle: String get() = item.description.orEmpty()
    }

    data class Custom(val uri: Uri, override val label: String, val sizeBytes: Long) : OsSelection {
        override val subtitle: String get() = "Image from this device"
    }
}

sealed interface CatalogueState {
    data object Loading : CatalogueState
    data object Ready : CatalogueState
    data class Error(val message: String) : CatalogueState
}

/** One screen of the OS picker: the root list, or a category the user drilled into. */
data class BrowseLevel(
    val title: String,
    val items: List<OsListItem>,
    val loading: Boolean = false,
    val error: String? = null,
)

data class ImagerUiState(
    val catalogue: CatalogueState = CatalogueState.Loading,
    val hardware: List<HardwareDevice> = emptyList(),
    val selectedHardware: HardwareDevice? = null,
    val browseStack: List<BrowseLevel> = emptyList(),
    val selection: OsSelection? = null,
    val storage: List<StorageTarget> = emptyList(),
    val selectedStorage: StorageTarget? = null,
    val storageLoading: Boolean = false,
    val customization: CustomizationSettings = CustomizationSettings(),
    val verifyAfterWrite: Boolean = true,
    val rootAccessEnabled: Boolean = false,
    val write: WriteState = WriteState.Idle,
    val message: String? = null,
) {
    val canWrite: Boolean get() = selection != null && selectedStorage != null && write !is WriteState.Running

    /** Images that declare `init_format: none` cannot take first boot settings. */
    val customizationSupported: Boolean
        get() = when (val current = selection) {
            is OsSelection.Catalogue -> InitFormat.fromCatalogue(current.item.initFormat) != InitFormat.NONE
            is OsSelection.Custom -> true
            null -> false
        }
}

class ImagerViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = application.appGraph
    private val _state = MutableStateFlow(
        ImagerUiState(
            customization = graph.settings.customization,
            verifyAfterWrite = graph.settings.verifyAfterWrite,
            rootAccessEnabled = graph.settings.rootAccessEnabled,
        ),
    )
    val state: StateFlow<ImagerUiState> = _state.asStateFlow()

    private var document: dev.openimager.core.oslist.OsListDocument? = null

    init {
        loadCatalogue(force = false)
        viewModelScope.launch {
            WriteCoordinator.state.collect { write -> _state.update { it.copy(write = write) } }
        }
        viewModelScope.launch {
            graph.storageRepository.deviceChanges().collect { refreshStorage() }
        }
    }

    // region catalogue

    fun loadCatalogue(force: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(catalogue = CatalogueState.Loading) }
            try {
                val loaded = graph.catalogueRepository.load(force)
                document = loaded
                val remembered = graph.settings.selectedHardware
                val hardware = loaded.imager?.devices.orEmpty()
                val selected = hardware.firstOrNull { it.name == remembered }
                _state.update { current ->
                    current.copy(
                        catalogue = CatalogueState.Ready,
                        hardware = hardware,
                        selectedHardware = selected,
                        browseStack = listOf(rootLevel(selected)),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(catalogue = CatalogueState.Error(e.message ?: "the OS list could not be downloaded"))
                }
            }
        }
    }

    private fun rootLevel(hardware: HardwareDevice?): BrowseLevel {
        val items = document?.osList.orEmpty().filterForDevice(hardware)
        return BrowseLevel(title = "Operating System", items = items)
    }

    fun selectHardware(device: HardwareDevice?) {
        graph.settings.selectedHardware = device?.name
        _state.update { current ->
            current.copy(
                selectedHardware = device,
                browseStack = listOf(rootLevel(device)),
                // A pinned OS may not exist for the newly chosen board.
                selection = current.selection.takeIf { it !is OsSelection.Catalogue },
            )
        }
    }

    fun openCategory(item: OsListItem) {
        if (item.subitems.isNotEmpty()) {
            val level = BrowseLevel(item.name, item.subitems.filterForDevice(_state.value.selectedHardware))
            _state.update { it.copy(browseStack = it.browseStack + level) }
            return
        }
        val url = item.subitemsUrl ?: return
        _state.update { it.copy(browseStack = it.browseStack + BrowseLevel(item.name, emptyList(), loading = true)) }
        viewModelScope.launch {
            try {
                val children = graph.catalogueRepository.loadSubList(url)
                    .filterForDevice(_state.value.selectedHardware)
                replaceTopLevel(BrowseLevel(item.name, children))
            } catch (e: Exception) {
                replaceTopLevel(BrowseLevel(item.name, emptyList(), error = e.message ?: "download failed"))
            }
        }
    }

    private fun replaceTopLevel(level: BrowseLevel) {
        _state.update { current ->
            current.copy(browseStack = current.browseStack.dropLast(1) + level)
        }
    }

    fun closeCategory() {
        _state.update { current ->
            if (current.browseStack.size <= 1) current else current.copy(browseStack = current.browseStack.dropLast(1))
        }
    }

    fun resetBrowsing() {
        _state.update { it.copy(browseStack = listOf(rootLevel(it.selectedHardware))) }
    }

    fun selectOs(item: OsListItem) {
        _state.update { it.copy(selection = OsSelection.Catalogue(item)) }
    }

    fun selectCustomImage(uri: Uri) {
        viewModelScope.launch {
            val source = withContext(Dispatchers.IO) {
                LocalImageSource.from(getApplication<Application>().contentResolver, uri)
            }
            _state.update {
                it.copy(selection = OsSelection.Custom(uri, source.displayName, source.compressedSize))
            }
        }
    }

    // endregion

    // region storage

    fun refreshStorage() {
        // Probing a reader means claiming its interface, which would fight with a running write.
        if (WriteCoordinator.isRunning) return
        viewModelScope.launch {
            _state.update { it.copy(storageLoading = true) }
            val devices = graph.storageRepository.list(_state.value.rootAccessEnabled)
            _state.update { current ->
                current.copy(
                    storage = devices,
                    storageLoading = false,
                    // Keep the selection only while that device is still attached.
                    selectedStorage = devices.firstOrNull { it.id == current.selectedStorage?.id },
                )
            }
        }
    }

    fun selectStorage(target: StorageTarget) {
        if (target is StorageTarget.Usb && !target.hasPermission) {
            viewModelScope.launch {
                val granted = graph.storageRepository.requestPermission(target.device)
                if (granted) {
                    refreshStorage()
                    val refreshed = graph.storageRepository.resolve(target.id, _state.value.rootAccessEnabled)
                    _state.update { it.copy(selectedStorage = refreshed) }
                } else {
                    _state.update { it.copy(message = "Access to the card reader was denied") }
                }
            }
            return
        }
        _state.update { it.copy(selectedStorage = target) }
    }

    fun setRootAccessEnabled(enabled: Boolean) {
        graph.settings.rootAccessEnabled = enabled
        _state.update { it.copy(rootAccessEnabled = enabled) }
        refreshStorage()
    }

    // endregion

    // region settings and writing

    fun updateCustomization(settings: CustomizationSettings) {
        graph.settings.customization = settings
        _state.update { it.copy(customization = settings) }
    }

    fun setVerifyAfterWrite(verify: Boolean) {
        graph.settings.verifyAfterWrite = verify
        _state.update { it.copy(verifyAfterWrite = verify) }
    }

    fun startWrite() {
        val current = _state.value
        val selection = current.selection ?: return
        val target = current.selectedStorage ?: return
        if (target is StorageTarget.Usb && !graph.storageRepository.hasPermission(target.device)) {
            selectStorage(target)
            return
        }

        val customization = current.customization.takeIf { it.enabled && current.customizationSupported }
        val initFormat = when (selection) {
            is OsSelection.Catalogue -> InitFormat.fromCatalogue(selection.item.initFormat)
            is OsSelection.Custom -> null // detected from the boot partition once it is written
        }

        viewModelScope.launch {
            // Resolving a picked file reads its metadata, so keep it off the main thread.
            val source = withContext(Dispatchers.IO) { buildSource(selection) }
            if (source == null) {
                _state.update { it.copy(message = "This entry has no image to download") }
                return@launch
            }
            WriteCoordinator.submit(
                WriteRequest(
                    source = source,
                    target = target,
                    options = WriteOptions(
                        verify = current.verifyAfterWrite,
                        customization = customization,
                        initFormat = initFormat,
                    ),
                    imageLabel = selection.label,
                    targetLabel = target.label,
                ),
            )
            WriteService.start(getApplication())
        }
    }

    private fun buildSource(selection: OsSelection): ImageSource? = when (selection) {
        is OsSelection.Catalogue -> selection.item.url?.let { url ->
            HttpImageSource(
                url = url,
                displayName = selection.item.name,
                compressedSize = selection.item.downloadSize,
                uncompressedSize = selection.item.extractSize,
                expectedSha256 = selection.item.extractSha256,
            )
        }
        is OsSelection.Custom -> LocalImageSource.from(
            getApplication<Application>().contentResolver,
            selection.uri,
        )
    }

    fun cancelWrite() = WriteService.cancel(getApplication())

    fun dismissWriteResult() {
        WriteCoordinator.reset()
        refreshStorage()
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    // endregion
}
