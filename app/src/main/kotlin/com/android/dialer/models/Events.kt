package com.android.dialer.models

sealed class Events {
    data object RefreshCallLog : Events()
    data object RefreshDialpadSettings : Events()
	
    class StateChanged(val isEnabled: Boolean)

    class CameraUnavailable

    class StopStroboscope

    class StopSOS
}
