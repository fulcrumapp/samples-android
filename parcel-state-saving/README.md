From the [Android 7.0 changes notes](https://developer.android.com/about/versions/nougat/android-7.0-changes.html#other):

> Many platform APIs have now started checking for large payloads being sent across Binder transactions, and the system now rethrows TransactionTooLargeExceptions as RuntimeExceptions, instead of silently logging or suppressing them.

Fulcrum uses parceling to save the state of a lot of data when the editor view goes into the background. We do this so if a user backgrounds the app, then comes back later, the data they've entered isnt lost. When we decided to support Android 7 and started running into `TransactionTooLargeException`, we looked at alternates to parceling. None were what we wanted, and many could potentially create more bugs.

So we created `FileBasedStateStorage`. This class will store any objects you want to store in a map. When it's time to parcel, it will transform them into bytes using `parcel.marshall()` and write those bytes to a file. When it's time to restore, it reads the bytes from the file and recreates the map. Below is an example of how to use it:

```
@Override
public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);

    // you can save values to the bundle like normal
    outState.putString("key1", "value1");
    outState.putInt("key2", value2);

    // create your FileBasedStateStorage
    FileBasedStateStorage stateStorage = new FileBasedStateStorage(getActivity());
    // store the big objects that case the TransactionTooLargeException
    stateStorage.store("unique_key_1", bigObject1);
    stateStorage.store("unique_key_2", bigObject2);
    // cause the stateStorage's parcel code to run.
    outState.putParcelable("STATE_FILE_SAVED_STORAGE", stateStorage);
}

@Override
public void onCreate(Bundle savedState) {
    super.onCreate(savedState);

    if ( savedState != null ) {
        // read stuff from bundle in the same order
        String value1 = savedState.getString("key1");
        int value2 = savedState.getInt("key2");

        FileBasedStateStorage stateStorage = savedState.getParcelable("STATE_FILE_SAVED_STORAGE");
        if ( stateStorage != null ) {
            bigObject1 = (ObjectType) stateStorage.get("unique_key_1");
            bigObject2 = (ObjectType2) stateStorage.get("unique_key_2");
        }
        else {
            FulcrumLogger.log("FileBasedStateStorage was null");
        }
    }
}
```