package de.blinkt.openvpn.api;

/**
 * Callback used by OpenVPN for Android to report status changes.
 * Wire-compatible with schwabe/ics-openvpn (remote API, not subject to its GPL license:
 * "The use of the AIDL API to control OpenVPN for Android from an external app is not subject to the license").
 */
oneway interface IOpenVPNStatusCallback {
    void newStatus(in String uuid, in String state, in String message, in String level);
}
