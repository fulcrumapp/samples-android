If Fulcrum Android users select a lower photo quality setting in the app, then `ImageFileResizeTask.java` is used to resize those photos down.

`EXIFUtils.java` helps to ensure EXIF data is not lost when resizing. Also, some Android devices don't record location information when taking photos via the camera intent (Nexus devices among these). `EXIFUtils.java` adds that data in.

Both classes make use of the sanselan-android library. You can get the [latest version here](https://github.com/fulcrumapp/sanselan-android/releases). As of posting, the version we currently use is [v1.0.0](https://github.com/fulcrumapp/sanselan-android/releases/tag/v1.0.0)