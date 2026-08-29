package dev.openimager.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import dev.openimager.ui.ImagerUiState
import dev.openimager.ui.ImagerViewModel

internal enum class Sheet { HOME, HARDWARE, OPERATING_SYSTEM, STORAGE, CUSTOMISE }

@Composable
fun ImagerApp(state: ImagerUiState, viewModel: ImagerViewModel) {
    var sheet by rememberSaveable { mutableStateOf(Sheet.HOME) }

    BackHandler(enabled = sheet != Sheet.HOME) {
        // Inside the OS picker, back walks out of the category before leaving the screen.
        if (sheet == Sheet.OPERATING_SYSTEM && state.browseStack.size > 1) {
            viewModel.closeCategory()
        } else {
            sheet = Sheet.HOME
        }
    }

    when (sheet) {
        Sheet.HOME -> HomeScreen(
            state = state,
            onChooseHardware = { sheet = Sheet.HARDWARE },
            onChooseOs = {
                viewModel.resetBrowsing()
                sheet = Sheet.OPERATING_SYSTEM
            },
            onChooseStorage = {
                viewModel.refreshStorage()
                sheet = Sheet.STORAGE
            },
            onCustomise = { sheet = Sheet.CUSTOMISE },
            onWrite = viewModel::startWrite,
            onCancel = viewModel::cancelWrite,
            onDismissResult = viewModel::dismissWriteResult,
            onDismissMessage = viewModel::dismissMessage,
            onRetryCatalogue = { viewModel.loadCatalogue(force = true) },
            onVerifyChanged = viewModel::setVerifyAfterWrite,
            onCustomImagePicked = viewModel::selectCustomImage,
        )

        Sheet.HARDWARE -> HardwarePickerScreen(
            hardware = state.hardware,
            selected = state.selectedHardware,
            onSelect = {
                viewModel.selectHardware(it)
                sheet = Sheet.HOME
            },
            onClose = { sheet = Sheet.HOME },
        )

        Sheet.OPERATING_SYSTEM -> OsPickerScreen(
            state = state,
            onOpenCategory = viewModel::openCategory,
            onSelect = {
                viewModel.selectOs(it)
                sheet = Sheet.HOME
            },
            onCustomImagePicked = {
                viewModel.selectCustomImage(it)
                sheet = Sheet.HOME
            },
            onBack = {
                if (state.browseStack.size > 1) viewModel.closeCategory() else sheet = Sheet.HOME
            },
            onClose = { sheet = Sheet.HOME },
        )

        Sheet.STORAGE -> StoragePickerScreen(
            state = state,
            onSelect = {
                viewModel.selectStorage(it)
                sheet = Sheet.HOME
            },
            onRefresh = viewModel::refreshStorage,
            onRootAccessChanged = viewModel::setRootAccessEnabled,
            onClose = { sheet = Sheet.HOME },
        )

        Sheet.CUSTOMISE -> CustomizationScreen(
            settings = state.customization,
            onSave = {
                viewModel.updateCustomization(it)
                sheet = Sheet.HOME
            },
            onClose = { sheet = Sheet.HOME },
        )
    }
}
