package de.blinkt.openvpn.api;

import android.os.Parcel;
import android.os.Parcelable;

/** Parcelable returned by IOpenVPNAPIService.getProfiles(); must keep this exact wire format. */
public class APIVpnProfile implements Parcelable {
    public final String mUUID;
    public final String mName;
    public final boolean mUserEditable;

    public APIVpnProfile(Parcel in) {
        mUUID = in.readString();
        mName = in.readString();
        mUserEditable = in.readInt() != 0;
    }

    public APIVpnProfile(String uuidString, String name, boolean userEditable) {
        mUUID = uuidString;
        mName = name;
        mUserEditable = userEditable;
    }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mUUID);
        dest.writeString(mName);
        dest.writeInt(mUserEditable ? 1 : 0);
    }

    public static final Parcelable.Creator<APIVpnProfile> CREATOR = new Parcelable.Creator<APIVpnProfile>() {
        @Override public APIVpnProfile createFromParcel(Parcel in) { return new APIVpnProfile(in); }
        @Override public APIVpnProfile[] newArray(int size) { return new APIVpnProfile[size]; }
    };
}
