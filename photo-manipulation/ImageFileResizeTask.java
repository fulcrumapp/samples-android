package com.spatialnetworks.fulcrum.util;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

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
import android.net.Uri;
import android.os.AsyncTask;

import com.spatialnetworks.fulcrum.settings.UserSettings;

import com.squareup.picasso.Picasso;

public class ImageFileResizeTask extends AsyncTask<String, Void, Void> {

    // ------------------------------------------------------------------------
    // Instance Variables
    // ------------------------------------------------------------------------

    private final Context mContext;

    private final Uri mUri;

    private final String mFilePath;

    private final Location mLocation;

    private final int mOriginalHeight;

    private final int mOriginalWidth;

    private final int mNewHeight;

    private final int mNewWidth;

    private final CompressFormat mCompressFormat;

    // ------------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------------

    public ImageFileResizeTask(Context context, Uri uri, Location location) {
        mContext = context;
        mUri = uri;
        mFilePath = uri.getPath();
        mLocation = location;

        // determine the image's original height and width
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(mFilePath, options);

        mOriginalWidth = options.outWidth;
        mOriginalHeight = options.outHeight;

        // determine what the new largest dimension should be based on user's setting
        float largestDimension;
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

        mNewHeight = Math.round(mOriginalHeight * scaleFactor);
        mNewWidth = Math.round(mOriginalWidth * scaleFactor);

        mCompressFormat = BitmapUtils.getCompressFormat(mFilePath, options);
    }

    // ------------------------------------------------------------------------
    // Protected Methods
    // ------------------------------------------------------------------------

    @Override
    protected Void doInBackground(final String... args) {
        try {
            File imageFile = new File(mFilePath);

            // get the exif out of the image
            TiffOutputSet exif = getSanselanOutputSet(imageFile, TiffConstants.DEFAULT_TIFF_BYTE_ORDER);

            // resize the photo if necessary
            if ( mNewHeight != mOriginalHeight || mNewWidth != mOriginalWidth ) {
                // resize the file
                Bitmap bitmap = Picasso.with(mContext).load(mUri).resize(mNewWidth, mNewHeight).get();

                /*
                 *  I could not find a way to save a bitmap object to disk correctly (so that it was still an image)
                 *  byte by byte, so I had to use bitmap.compress, which loses the exif data.
                 *
                 *  Which is why I'm using sanselan android. Android changed the tag identifiers of some exif tags enough
                 *  so that the pure sanselan library didnt pull tags correctly. i couldnt find another library that would
                 *  read and write tags and worked in android. the ExifInterface class has been reported to have problems,
                 *  and I couldnt get it to work
                 */

                FileOutputStream fos = new FileOutputStream(mFilePath);
                bitmap.compress(mCompressFormat, 100, fos);
                fos.close();

                exif.removeField(TiffConstants.EXIF_TAG_ORIENTATION);
                exif.removeField(TiffConstants.TIFF_TAG_ORIENTATION);
            }

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
            }

            writeExifLocation(exif, mLocation);

            // save the exif back into the image
            saveExifToFile(imageFile, exif);
        }
        catch ( IOException | ImageWriteException | ImageReadException e ) {
            FCMLog.log(e);
        }

        return null;
    }

    // ------------------------------------------------------------------------
    // Private Methods
    // ------------------------------------------------------------------------

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

    private TiffOutputSet getSanselanOutputSet(File jpegImageFile, int defaultByteOrder)
        throws IOException, ImageReadException, ImageWriteException {
        TiffImageMetadata metadata = EXIFUtils.getImageMetadata(jpegImageFile);

        TiffOutputSet outputSet = metadata == null ? null : metadata.getOutputSet();

        // If JPEG file contains no EXIF metadata, create an empty set
        // of EXIF metadata. Otherwise, use existing EXIF metadata to
        // keep all other existing tags
        if ( outputSet == null ) {
            outputSet = new TiffOutputSet(metadata == null ? defaultByteOrder : metadata.contents.header.byteOrder);
        }

        return outputSet;
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
            FCMLog.log(e);
        }
    }
}
