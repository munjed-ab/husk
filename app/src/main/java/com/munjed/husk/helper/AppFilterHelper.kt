package com.munjed.husk.helper

import com.munjed.husk.data.AppModel

interface AppFilterHelper {
    fun onAppFiltered(items:List<AppModel>)
}