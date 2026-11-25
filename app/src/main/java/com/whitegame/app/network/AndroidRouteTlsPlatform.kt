package com.whitegame.app.network

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.N)
internal fun routeTlsSupportsEndpointIdentification(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
