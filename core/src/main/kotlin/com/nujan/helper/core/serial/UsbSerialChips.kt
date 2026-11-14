package com.nujan.helper.core.serial

/**
 * What a USB device is, by its vendor and product ids: the USB-serial bridge, or the chip's own USB, that a board shows
 * the phone. The bridge says nothing certain about the chip behind it; `esptool chip-id` names an Espressif chip.
 */
object UsbSerialChips {

    fun describe(vendorId: Int, productId: Int): String? = when (vendorId) {
        0x10C4 -> when (productId) {
            0xEA60 -> "Silicon Labs CP210x USB-serial bridge"
            0xEA70 -> "Silicon Labs CP2105 dual USB-serial bridge"
            0xEA71 -> "Silicon Labs CP2108 quad USB-serial bridge"
            else -> null
        }
        0x1A86 -> when (productId) {
            0x7523 -> "WCH CH340 USB-serial bridge"
            0x5523 -> "WCH CH341 USB-serial bridge"
            0x7522 -> "WCH CH34x USB-serial bridge"
            0x55D4 -> "WCH CH9102 USB-serial bridge"
            0x55D3 -> "WCH CH343 USB-serial bridge"
            else -> null
        }
        0x0403 -> when (productId) {
            0x6001 -> "FTDI FT232R USB-serial bridge"
            0x6010 -> "FTDI FT2232H dual USB-serial bridge"
            0x6011 -> "FTDI FT4232H quad USB-serial bridge"
            0x6014 -> "FTDI FT232H USB-serial bridge"
            0x6015 -> "FTDI FT-X USB-serial bridge"
            else -> null
        }
        0x067B -> "Prolific PL2303 USB-serial bridge"
        0x303A -> when (productId) {
            0x1001 -> "Espressif USB-Serial/JTAG, the chip's own USB (ESP32-C3, -S3, -C6, -H2 and others)"
            else -> "Espressif native USB (an ESP32-S2 or -S3 running its own USB stack)"
        }
        else -> null
    }

    /** Whether the device is likely a microcontroller board: one of the bridges or Espressif USB above. */
    fun isBoard(vendorId: Int, productId: Int): Boolean = describe(vendorId, productId) != null
}
