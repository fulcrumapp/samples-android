package com.spatialnetworks.fulcrum.util;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import org.apache.sanselan.ImageReadException;
import org.apache.sanselan.ImageWriteException;
import org.apache.sanselan.formats.jpeg.exifRewrite.ExifRewriter;
import org.apache.sanselan.formats.tiff.TiffImageMetadata;
import org.apache.sanselan.formats.tiff.constants.ExifTagConstants;
import org.apache.sanselan.formats.tiff.constants.TagInfo;
import org.apache.sanselan.formats.tiff.constants.TiffConstants;
import org.apache.sanselan.formats.tiff.constants.TiffDirectoryConstants;
import org.apache.sanselan.formats.tiff.constants.TiffFieldTypeConstants;
import org.apache.sanselan.formats.tiff.write.TiffOutputDirectory;
import org.apache.sanselan.formats.tiff.write.TiffOutputField;
import org.apache.sanselan.formats.tiff.write.TiffOutputSet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;

import com.spatialnetworks.fulcrum.model.Account;
import com.spatialnetworks.fulcrum.settings.UserSettings;

import com.squareup.picasso.Picasso;

public class ImageFileResizeTask extends AsyncTask<Void, Void, Void> {

    // ------------------------------------------------------------------------
    // Class Variables
    // ------------------------------------------------------------------------

    private static final String TAG = ImageFileResizeTask.class.getSimpleName();

    // ------------------------------------------------------------------------
    // Instance Variables
    // ------------------------------------------------------------------------

    private final Context mContext;

    private final Uri mFileUri;

    private final String mFilePath;

    private final String mPhotoID;

    private final Location mLocation;

    private final int mOriginalHeight;

    private final int mOriginalWidth;

    private int mNewHeight;

    private int mNewWidth;

    private final CompressFormat mCompressFormat;

    // ------------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------------

    public ImageFileResizeTask(Context context, Uri sourceUri, Location location,
                               String photoID, String overrideQuality) {
        mContext = context;
        mFileUri = sourceUri;
        mFilePath = sourceUri.getPath();
        mLocation = location;
        mPhotoID = photoID;

        // is the app's quality setting overriden by a data event?
        Integer overrideDimension = null;
        if ( overrideQuality != null ) {
            try {
                if ( overrideQuality.equals("native") ) {
                    overrideDimension = 0;
                }
                else {
                    overrideDimension = Integer.parseInt(overrideQuality);
                }
            }
            catch ( NumberFormatException ignored ) {
            }
        }

        // determine the image's original height and width
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(mFilePath, options);
        mOriginalWidth = options.outWidth;
        mOriginalHeight = options.outHeight;

        // determine what the new largest dimension should be based on user's setting
        float largestDimension;
        if ( overrideDimension != null ) {
            largestDimension = overrideDimension;
        }
        else {
            switch ( UserSettings.getPhotoCaptureQuality(mContext) ) {
                case UserSettings.PHOTO_QUALITY_HIGH:
                    largestDimension = 1080;
                    break;
                case UserSettings.PHOTO_QUALITY_MEDIUM:
                    largestDimension = 720;
                    break;
                case UserSettings.PHOTO_QUALITY_LOW:
                    largestDimension = 480;
                    break;
                case UserSettings.PHOTO_QUALITY_NATIVE:
                default:
                    largestDimension = 0;
            }
        }

        // determine the scaling factor for the dimensions
        float scaleFactor;
        if ( largestDimension == 0 ) {
            scaleFactor = 1;
        }
        else if ( mOriginalHeight >= mOriginalWidth ) {
            scaleFactor = largestDimension / mOriginalHeight;
        }
        else {
            scaleFactor = largestDimension / mOriginalWidth;
        }

        // determine the new dimensions based on the calculated scale factor
        mNewHeight = Math.round(mOriginalHeight * scaleFactor);
        mNewWidth = Math.round(mOriginalWidth * scaleFactor);

        // get the compression format used for saving the resized file
        mCompressFormat = BitmapUtils.getCompressFormat(mFilePath, options);
    }

    // ------------------------------------------------------------------------
    // Protected Methods
    // ------------------------------------------------------------------------

    @Override
    protected Void doInBackground(Void... args) {
        File sourceFile = new File(mFilePath);

        // pull the exif from the image before resizing
        TiffOutputSet exif = getSanselanOutputSet(sourceFile, TiffConstants.DEFAULT_TIFF_BYTE_ORDER);

        // determine the orientation if there is one (if it's a jpeg, mainly from a samsung device)
        int orientation = EXIFUtils.getOrientation(mFilePath);

        // if a resize is required, or a rotate
        if ( mNewHeight != mOriginalHeight || mNewWidth != mOriginalWidth || orientation != 0 ) {
            try {
                /*
                 * we had to move to Picasso 2.5.3-SNAPSHOT as some devices were getting IOException:
                 * Cannot Reset when doing the resize a few lines down. The SNAPSHOT versions cause
                 * photos to be stretched on devices with an orientation value (like Samsung S7), we believe
                 * it's because code was added that swaps the width and height when resizing, which wasn't
                 * needed and is handled elsewhere in Picasso. To counter that, we have to swap the
                 * width and height before resizing if there's an orientation of 90 or 270. This Picasso
                 * github issue has more info: https://github.com/square/picasso/issues/1555
                 */
                if ( orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_270 ) {
                    int tmpNewHeight = mNewHeight;
                    mNewHeight = mNewWidth;
                    mNewWidth = tmpNewHeight;
                }

                // size and get the image
                Bitmap bitmap = Picasso.with(mContext).load(mFileUri).resize(mNewWidth, mNewHeight).onlyScaleDown().get();

                /*
                 * Some camera apps (some Samsungs) will return the jpeg with an orientation value
                 * instead of rotating the pixels before giving it back to us. Picasso handles this
                 * gracefully and 'fixes' the image when we resize. It's then necessary for us to
                 * get the image's actual height and width so the EXIF is correct.
                 */
                mNewHeight = bitmap.getHeight();
                mNewWidth = bitmap.getWidth();

                /*
                 * I could not find a way to save a bitmap object to disk correctly (so that it
                 * was still an image) byte by byte, so I had to use bitmap.compress, which loses
                 * the exif data.
                 *
                 * Which is why I'm using sanselan android. Android changed the tag identifiers
                 * of some exif tags enough so that the pure sanselan library didnt pull tags
                 * correctly. i couldnt find another library that would read and write tags and
                 * worked in android. the ExifInterface class has been reported to have problems,
                 * and I couldnt get it to work
                 */
                FileOutputStream fos = new FileOutputStream(mFilePath);
                bitmap.compress(mCompressFormat, 100, fos);
                fos.close();

                if ( exif != null ) {
                    exif.removeField(TiffConstants.EXIF_TAG_ORIENTATION);
                    exif.removeField(TiffConstants.TIFF_TAG_ORIENTATION);
                }
            }
            catch ( IOException e ) {
                FulcrumLogger.log(TAG, "Picasso threw IOException trying to resize photo: " + mFilePath);
            }
        }

        if ( exif != null ) {
            try {
                writeExifInformation(exif);

                writeExifLocation(exif, mLocation);

                // save the exif back into the image
                saveExifToFile(sourceFile, exif);
            }
            catch ( IOException | ImageWriteException | ImageReadException e ) {
                FulcrumLogger.log(e);
            }
        }

        return null;
    }

    // ------------------------------------------------------------------------
    // Private Methods
    // ------------------------------------------------------------------------

    private TiffOutputSet getSanselanOutputSet(File jpegImageFile, int defaultByteOrder) {
        try {
            TiffImageMetadata metadata = EXIFUtils.getImageMetadata(jpegImageFile);
            TiffOutputSet outputSet = metadata == null ? null : metadata.getOutputSet();

            // If JPEG file contains no EXIF metadata, create an empty set
            // of EXIF metadata. Otherwise, use existing EXIF metadata to
            // keep all other existing tags
            return outputSet == null ?
                new TiffOutputSet(metadata == null ? defaultByteOrder : metadata.contents.header.byteOrder) :
                outputSet;
        }
        catch ( IOException | ImageWriteException | ImageReadException e ) {
            FulcrumLogger.log(e);
            return null;
        }
    }

    private void writeExifInformation(TiffOutputSet exif) throws ImageWriteException {
        // store the EXIF width/height
        TiffOutputDirectory exifDirectory = exif.getOrCreateExifDirectory();

        if ( exifDirectory != null ) {
            TiffOutputField field;

            field = TiffOutputField.create(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_WIDTH, exif.byteOrder, mNewWidth);
            exifDirectory.removeField(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_WIDTH);
            exifDirectory.add(field);

            field = TiffOutputField.create(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_LENGTH, exif.byteOrder, mNewHeight);
            exifDirectory.removeField(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_LENGTH);
            exifDirectory.add(field);

            if ( exifDirectory.findField(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL) == null ) {
                SimpleDateFormat formatter = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
                formatter.setTimeZone(TimeZone.getDefault());

                field = TiffOutputField.create(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, exif.byteOrder, formatter.format(new Date()));
                exifDirectory.removeField(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL);
                exifDirectory.add(field);
            }

            if ( exifDirectory.findField(ExifTagConstants.EXIF_TAG_CREATE_DATE) == null ) {
                SimpleDateFormat formatter = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
                formatter.setTimeZone(TimeZone.getDefault());

                field = TiffOutputField.create(ExifTagConstants.EXIF_TAG_CREATE_DATE, exif.byteOrder, formatter.format(new Date()));
                exifDirectory.removeField(ExifTagConstants.EXIF_TAG_CREATE_DATE);
                exifDirectory.add(field);
            }
        }

        // store the regular width/height (the width+height is stored twice in the EXIF under 2 different tags)
        TiffOutputDirectory rootDirectory = exif.getOrCreateRootDirectory();

        if ( rootDirectory != null ) {
            TiffOutputField field;

            field = TiffOutputField.create(ExifTagConstants.EXIF_TAG_IMAGE_WIDTH_IFD0, exif.byteOrder, mNewWidth);
            rootDirectory.removeField(ExifTagConstants.EXIF_TAG_IMAGE_WIDTH_IFD0);
            rootDirectory.add(field);

            field = TiffOutputField.create(ExifTagConstants.EXIF_TAG_IMAGE_HEIGHT_IFD0, exif.byteOrder, mNewHeight);
            rootDirectory.removeField(ExifTagConstants.EXIF_TAG_IMAGE_HEIGHT_IFD0);
            rootDirectory.add(field);

            field = TiffOutputField.create(ExifTagConstants.EXIF_TAG_ARTIST, exif.byteOrder, Account.getActiveAccount().getUserID());
            rootDirectory.removeField(ExifTagConstants.EXIF_TAG_ARTIST);
            rootDirectory.add(field);

            field = TiffOutputField.create(ExifTagConstants.EXIF_TAG_SOFTWARE, exif.byteOrder, ApplicationUtils.getUserAgentString(mContext));
            rootDirectory.removeField(ExifTagConstants.EXIF_TAG_SOFTWARE);
            rootDirectory.add(field);

            field = TiffOutputField.create(ExifTagConstants.EXIF_TAG_IMAGE_DESCRIPTION, exif.byteOrder, mPhotoID);
            rootDirectory.removeField(ExifTagConstants.EXIF_TAG_IMAGE_DESCRIPTION);
            rootDirectory.add(field);

            field = TiffOutputField.create(ExifTagConstants.EXIF_TAG_IMAGE_UNIQUE_ID, exif.byteOrder, mPhotoID);
            rootDirectory.removeField(ExifTagConstants.EXIF_TAG_IMAGE_UNIQUE_ID);
            rootDirectory.add(field);

            if ( rootDirectory.findField(ExifTagConstants.EXIF_TAG_MAKE) == null ) {
                field = TiffOutputField.create(ExifTagConstants.EXIF_TAG_MAKE, exif.byteOrder, Build.MANUFACTURER);
                rootDirectory.removeField(ExifTagConstants.EXIF_TAG_MAKE);
                rootDirectory.add(field);
            }

            if ( rootDirectory.findField(ExifTagConstants.EXIF_TAG_MODEL) == null ) {
                field = TiffOutputField.create(ExifTagConstants.EXIF_TAG_MODEL, exif.byteOrder, Build.MODEL);
                rootDirectory.removeField(ExifTagConstants.EXIF_TAG_MODEL);
                rootDirectory.add(field);
            }

            if ( rootDirectory.findField(ExifTagConstants.EXIF_TAG_DOCUMENT_NAME) == null ) {
                field = TiffOutputField.create(ExifTagConstants.EXIF_TAG_DOCUMENT_NAME, exif.byteOrder, DateUtils.getTimestampFormatter().format(new Date()));
                rootDirectory.removeField(ExifTagConstants.EXIF_TAG_DOCUMENT_NAME);
                rootDirectory.add(field);
            }
        }
    }

    private void writeExifLocation(TiffOutputSet exif, Location location) {
        // if the exif already has location or we have no location to set, exit
        if ( location == null ) {
            return;
        }

        try {
            TiffOutputDirectory gps = exif.getOrCreateGPSDirectory();

            boolean hasLocation = gps.findField(TiffConstants.GPS_TAG_GPS_LATITUDE_REF) != null &&
                gps.findField(TiffConstants.GPS_TAG_GPS_LATITUDE) != null &&
                gps.findField(TiffConstants.GPS_TAG_GPS_LONGITUDE_REF) != null &&
                gps.findField(TiffConstants.GPS_TAG_GPS_LONGITUDE) != null;

            TiffOutputField field;

            if ( !hasLocation ) {
                double longitude = location.getLongitude();
                double latitude = location.getLatitude();

                String longitudeRef = longitude < 0.0 ? "W" : "E";
                longitude = Math.abs(longitude);

                String latitudeRef = latitude < 0.0 ? "S" : "N";
                latitude = Math.abs(latitude);

                // add longitude ref
                field = TiffOutputField.create(TiffConstants.GPS_TAG_GPS_LONGITUDE_REF, exif.byteOrder, longitudeRef);
                gps.removeField(TiffConstants.GPS_TAG_GPS_LONGITUDE_REF);
                gps.add(field);

                // add latitude ref
                field = TiffOutputField.create(TiffConstants.GPS_TAG_GPS_LATITUDE_REF, exif.byteOrder, latitudeRef);
                gps.removeField(TiffConstants.GPS_TAG_GPS_LATITUDE_REF);
                gps.add(field);

                field = TiffOutputField.create(TiffConstants.GPS_TAG_GPS_LONGITUDE, exif.byteOrder, toDMS(longitude));
                gps.removeField(TiffConstants.GPS_TAG_GPS_LONGITUDE);
                gps.add(field);

                field = TiffOutputField.create(TiffConstants.GPS_TAG_GPS_LATITUDE, exif.byteOrder, toDMS(latitude));
                gps.removeField(TiffConstants.GPS_TAG_GPS_LATITUDE);
                gps.add(field);
            }

            if ( location.hasAltitude() ) {
                double altitude = location.getAltitude();

                int altitudeRef = altitude < 0.0 ?
                    TiffConstants.GPS_TAG_GPS_ALTITUDE_REF_VALUE_BELOW_SEA_LEVEL :
                    TiffConstants.GPS_TAG_GPS_ALTITUDE_REF_VALUE_ABOVE_SEA_LEVEL;

                altitude = Math.abs(altitude);

                TagInfo altitudeRefTag = new TagInfo("GPS Altitude Ref", 5,
                                                     TiffFieldTypeConstants.FIELD_TYPE_DESCRIPTION_BYTE, 1,
                                                     TiffDirectoryConstants.EXIF_DIRECTORY_GPS);

                // add altitude ref
                field = TiffOutputField.create(altitudeRefTag, exif.byteOrder, (byte) altitudeRef);
                gps.removeField(altitudeRefTag);
                gps.add(field);

                // add altitude
                // the altitude tag is defined incorrectly in sanselan, it should have length 1, not -1
                TagInfo altitudeTag = new TagInfo("GPS Altitude", 6,
                                                  TiffFieldTypeConstants.FIELD_TYPE_DESCRIPTION_RATIONAL, 1,
                                                  TiffDirectoryConstants.EXIF_DIRECTORY_GPS);

                field = TiffOutputField.create(altitudeTag, exif.byteOrder, new Double[] {
                    altitude
                });
                gps.removeField(altitudeTag);
                gps.add(field);
            }

            if ( location.hasAccuracy() ) {
                double accuracy = location.getAccuracy();

                // sanselan doesn't define the H Positioning Error tag, so we manually define it at offset 31
                // http://www.sno.phy.queensu.ca/~phil/exiftool/TagNames/GPS.html
                // iOS writes to this field and it has some level of standardization
                TagInfo accuracyTag = new TagInfo("GPS H Positioning Error", 31,
                                                  TiffFieldTypeConstants.FIELD_TYPE_DESCRIPTION_RATIONAL, 1,
                                                  TiffDirectoryConstants.EXIF_DIRECTORY_GPS);

                // add accuracy
                field = TiffOutputField.create(accuracyTag, exif.byteOrder, new Double[] {
                    accuracy
                });
                gps.removeField(accuracyTag);
                gps.add(field);

                // add accuracy to the DOP field too
                // the GPS DOP tag is defined incorrectly in sanselan, it should have length 1, not -1
                TagInfo dopTag = new TagInfo("GPS DOP", 11,
                                             TiffFieldTypeConstants.FIELD_TYPE_DESCRIPTION_RATIONAL, 1,
                                             TiffDirectoryConstants.EXIF_DIRECTORY_GPS);

                field = TiffOutputField.create(dopTag, exif.byteOrder, new Double[] { accuracy });
                gps.removeField(dopTag);
                gps.add(field);
            }
        }
        catch ( ImageWriteException e ) {
            FulcrumLogger.log(e);
        }
    }

    private Double[] toDMS(double input) {
        double degrees, minutes, seconds, remainder;

        degrees = (double) ((long) input);

        remainder = input % 1.0;
        remainder *= 60.0;

        minutes = (double) ((long) remainder);

        remainder %= 1.0;

        seconds = remainder * 60.0;

        return new Double[] {
            degrees, minutes, seconds
        };
    }

    private void saveExifToFile(File imageFile, TiffOutputSet exif)
        throws IOException, ImageWriteException, ImageReadException {
        String tempFileName = imageFile.getAbsolutePath() + ".tmp";
        File tempFile = new File(tempFileName);

        BufferedOutputStream tempStream = new BufferedOutputStream(new FileOutputStream(tempFile));
        new ExifRewriter().updateExifMetadataLossless(imageFile, tempStream, exif);
        tempStream.close();

        if ( imageFile.delete() ) {
            tempFile.renameTo(imageFile);
        }
    }
}
