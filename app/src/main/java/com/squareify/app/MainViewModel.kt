package com.squareify.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Holds the app's [AppModel] (the grid and everything done to it), so it survives rotation and theme changes. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    val platform = AndroidPlatform(application)
    val model = AppModel(platform, viewModelScope)

    init {
        // Results saved under the app's earlier names move into the kkanvas folders.
        viewModelScope.launch(Dispatchers.IO) { GallerySaver.moveOldFolders(application) }
    }
}
