package moe.shizuku.api;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/** Shizuku Provider wire container: its class name and single Binder field are part of the ABI. */
public final class BinderContainer implements Parcelable {
    public final IBinder binder;
    public BinderContainer(IBinder binder) { this.binder = binder; }
    private BinderContainer(Parcel source) { binder = source.readStrongBinder(); }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel target, int flags) { target.writeStrongBinder(binder); }
    public static final Creator<BinderContainer> CREATOR = new Creator<BinderContainer>() {
        @Override public BinderContainer createFromParcel(Parcel source) { return new BinderContainer(source); }
        @Override public BinderContainer[] newArray(int size) { return new BinderContainer[size]; }
    };
}
