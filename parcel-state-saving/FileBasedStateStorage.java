package com.spatialnetworks.fulcrum.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.UUID;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

public class FileBasedStateStorage implements Parcelable {

    // ------------------------------------------------------------------------
    // Instance Variables
    // ------------------------------------------------------------------------

    public static final String CACHE_FILE_DIRECTORY = "fileBasedStateStorage";

    // ------------------------------------------------------------------------
    // Instance Variables
    // ------------------------------------------------------------------------

    private Context mContext;

    private HashMap<String, Parcelable> mKeysValues = new HashMap<>();

    // ------------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------------

    public FileBasedStateStorage(Context context) {
        mContext = context;
    }

    public FileBasedStateStorage(Parcel in) {
        int success = in.readInt();
        if ( success != 1 ) {
            FulcrumLogger.log("success is: " + success + ". Probably unable to write to file");
            return;
        }

        int bytesLength = in.readInt();
        byte[] dataAsBytes = new byte[bytesLength];

        URI savedStateFileURI = (URI) in.readSerializable();
        File savedStateFile = new File(savedStateFileURI);

        Parcel parcel = Parcel.obtain();

        try {
            // read bytes from the file
            FileInputStream fis = new FileInputStream(savedStateFile);
            fis.read(dataAsBytes, 0, bytesLength);
            fis.close();

            // delete the cache file
            savedStateFile.delete();

            // unmarshall the bytes to the parcel, where we can read a HashMap out
            parcel.unmarshall(dataAsBytes, 0, bytesLength);

            parcel.setDataPosition(0);
            // noinspection unchecked -- this will always be a hashmap because it's the only thing written
            mKeysValues = parcel.readHashMap(getClass().getClassLoader());
        }
        catch ( IOException e ) {
            FulcrumLogger.log("Wasn't able to read bytes from file.");
        }
        /*
         * we're catching RuntimeException here now to help debug an issue where line 70 throws a
         * RuntimeException trying to do what it's doing
         */
        catch ( RuntimeException e ) {
            FulcrumLogger.log("caught runtime exception");
            HashMap<String, String> meta = new HashMap<>();
            meta.put("success", String.valueOf(success));
            meta.put("bytesLength", String.valueOf(bytesLength));
            meta.put("savedStateFileUri", savedStateFileURI.getPath());
            meta.put("dataAsBytes", new String(dataAsBytes));

            FulcrumLogger.log(e, meta);
        }

        parcel.recycle();
    }

    // ------------------------------------------------------------------------
    // Parcelable Interface
    // ------------------------------------------------------------------------

    public static final Parcelable.Creator<FileBasedStateStorage> CREATOR = new Parcelable.Creator<FileBasedStateStorage>() {
        @Override
        public FileBasedStateStorage createFromParcel(Parcel source) {
            return new FileBasedStateStorage(source);
        }

        @Override
        public FileBasedStateStorage[] newArray(int size) {
            return new FileBasedStateStorage[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // must write map to new parcel instead of dest, because dest is written to the bundle
        // which will cause TransactionTooLargeException on large objects
        Parcel parcel = Parcel.obtain();
        parcel.writeMap(mKeysValues);

        byte[] dataAsBytes = parcel.marshall();

        File cacheDirectory = mContext.getDir(CACHE_FILE_DIRECTORY, Context.MODE_PRIVATE);
        File cacheFile = new File(cacheDirectory, UUID.randomUUID() + ".cache");

        try {
            // write map bytes to file
            FileOutputStream fos = new FileOutputStream(cacheFile);
            fos.write(dataAsBytes);
            fos.flush();
            fos.close();

            URI savedStateFileURI = cacheFile.toURI();

            // save data we need to retrieve bytes to the parcel that will be saved to the bundle
            dest.writeInt(1); // write a 1 for success
            dest.writeInt(dataAsBytes.length);
            dest.writeSerializable(savedStateFileURI);
        }
        catch ( IOException e ) {
            dest.writeInt(0); // write a 0 for failure
        }

        parcel.recycle();
    }

    // ------------------------------------------------------------------------
    // Public Methods
    // ------------------------------------------------------------------------

    public void store(String key, Parcelable value) {
        mKeysValues.put(key, value);
    }

    public Parcelable get(String key) {
        return mKeysValues.get(key);
    }
}
