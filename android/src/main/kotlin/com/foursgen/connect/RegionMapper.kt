package com.foursgen.connect

/**
 * Map region code (e.g. "VN") → area code (e.g. "sg") for GWIoT SDK.
 * Area code depends on which Gwell server region is used.
 * Ported from iOS RegionMapper.swift.
 */
object RegionMapper {

    private val regionToArea = mapOf(
        // Southeast Asia → Singapore server
        "VN" to "sg", "TH" to "sg", "ID" to "sg", "MY" to "sg",
        "PH" to "sg", "SG" to "sg", "KH" to "sg", "LA" to "sg", "MM" to "sg",
        // East Asia
        "CN" to "cn", "HK" to "sg", "TW" to "sg", "JP" to "sg", "KR" to "sg",
        // South Asia
        "IN" to "sg",
        // Europe → EU server
        "DE" to "eu", "FR" to "eu", "GB" to "eu", "IT" to "eu",
        "ES" to "eu", "NL" to "eu", "PL" to "eu", "PT" to "eu", "RU" to "eu",
        // Americas → US server
        "US" to "us", "CA" to "us", "BR" to "us", "MX" to "us",
    )

    /** Get area code from region. Returns "sg" if not found. */
    fun getAreaCode(region: String): String {
        return regionToArea[region.uppercase()] ?: "sg"
    }
}
