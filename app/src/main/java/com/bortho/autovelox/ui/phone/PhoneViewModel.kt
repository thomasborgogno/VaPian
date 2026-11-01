package com.bortho.autovelox.ui.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bortho.autovelox.data.RegionDownloader
import com.bortho.autovelox.data.SettingsStore
import com.bortho.autovelox.data.local.DatasetMetaEntity
import com.bortho.autovelox.data.local.VaPianDatabase
import com.bortho.autovelox.guidance.GuidanceController
import com.bortho.autovelox.link.LinkStatus
import com.bortho.autovelox.link.PhoneLinkServer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PhoneViewModel @Inject constructor(
    db: VaPianDatabase,
    private val downloader: RegionDownloader,
    private val settings: SettingsStore,
    guidance: GuidanceController,
    phoneLink: PhoneLinkServer,
) : ViewModel() {
    val regions: StateFlow<List<DatasetMetaEntity>> =
        db.datasetMeta().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _lastOsm = MutableStateFlow(settings.lastOsmDownloadAt)
    val lastOsmDownloadAt: StateFlow<Long> = _lastOsm.asStateFlow()

    val guidanceRunning: StateFlow<Boolean> = guidance.running
    val linkStatus: StateFlow<LinkStatus> = phoneLink.status

    fun download(region: String) {
        if (region in _downloading.value) return
        _downloading.update { it + region }
        viewModelScope.launch {
            when (val result = downloader.download(region)) {
                is RegionDownloader.Result.Ok -> _message.value = "$region: ${result.count} autovelox scaricati"
                is RegionDownloader.Result.Failed -> _message.value = result.message
            }
            _lastOsm.value = settings.lastOsmDownloadAt
            _downloading.update { it - region }
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}
