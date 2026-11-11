// Remote API of "OpenVPN for Android" (de.blinkt.openvpn).
// IMPORTANT: the ORDER of these methods defines the binder transaction codes.
// It must match upstream. New upstream methods are only ever appended at the end, so this prefix stays valid.
package de.blinkt.openvpn.api;

import de.blinkt.openvpn.api.APIVpnProfile;
import de.blinkt.openvpn.api.IOpenVPNStatusCallback;
import android.content.Intent;

interface IOpenVPNAPIService {
    List<APIVpnProfile> getProfiles();

    void startProfile(in String profileUUID);

    boolean addVPNProfile(in String name, in String config);

    /** Start a profile using a config given as inline string (all certificates must be inlined). */
    void startVPN(in String inlineconfig);

    /** Returns null if this app is already allowed to use the API, otherwise an Intent that asks the user. */
    Intent prepare(in String packagename);

    /** Returns null if the Android VPN permission is already granted to OpenVPN for Android. */
    Intent prepareVPNService();

    void disconnect();

    void pause();

    void resume();

    void registerStatusCallback(in IOpenVPNStatusCallback cb);

    void unregisterStatusCallback(in IOpenVPNStatusCallback cb);
}
