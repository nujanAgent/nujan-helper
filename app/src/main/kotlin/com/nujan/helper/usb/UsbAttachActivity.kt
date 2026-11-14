package com.nujan.helper.usb

import android.app.Activity
import android.os.Bundle

/**
 * What Android opens when a board from `res/xml/usb_device_filter.xml` is plugged in. Answering Android's question in
 * NuJan Helper's favour grants the Helper that device, and "Always open" keeps the grant for every later attach, including
 * after the board comes back under a new device path; there is nothing to show, so the activity ends at once.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
