package com.spatialnetworks.fulcrum.util;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sanselan.ImageReadException;
import org.apache.sanselan.Sanselan;
import org.apache.sanselan.common.IImageMetadata;
import org.apache.sanselan.common.RationalNumber;
import org.apache.sanselan.formats.jpeg.JpegImageMetadata;
import org.apache.sanselan.formats.tiff.TiffDirectory;
import org.apache.sanselan.formats.tiff.TiffField;
import org.apache.sanselan.formats.tiff.TiffImageMetadata;
import org.apache.sanselan.formats.tiff.TiffImageMetadata.GPSInfo;
import org.apache.sanselan.formats.tiff.constants.ExifTagConstants;
import org.apache.sanselan.formats.tiff.constants.GPSTagConstants;

import android.text.TextUtils;

public class EXIFUtils {

    // ------------------------------------------------------------------------
    // Class Methods
    // ------------------------------------------------------------------------

    public static TiffImageMetadata getImageMetadata(File jpegImageFile)
        throws IOException, ImageReadException {
        TiffImageMetadata exif = null;

        IImageMetadata metadata = Sanselan.getMetadata(jpegImageFile);
        JpegImageMetadata jpegMetadata = (JpegImageMetadata) metadata;
        if ( jpegMetadata != null ) {
            exif = jpegMetadata.getExif();
        }

        return exif;
    }

    public static HashMap<String, Object> getRawEXIF(File image) {
        HashMap<String, Object> exif = new HashMap<>();

        try {
            TiffImageMetadata sourceExif = getImageMetadata(image);

            if ( sourceExif == null ) {
                return exif;
            }

            List fields = sourceExif.getAllFields();

            for ( int i = 0; i < fields.size(); ++i ) {
                TiffField field = (TiffField) fields.get(i);

                String tagName = field.getTagName();

                Object value = convertExifField(field.getValue());

                if ( value != null ) {
                    exif.put(tagName, value);
                }
            }

            TiffDirectory gpsDirectory = sourceExif.findDirectory(-3);

            if ( gpsDirectory != null ) {
                GPSInfo gps = sourceExif.getGPS();

                if ( gps != null ) {
                    exif.put("GPS Latitude Value", gps.getLatitudeAsDegreesNorth());
                    exif.put("GPS Longitude Value", gps.getLongitudeAsDegreesEast());
                }

                for ( int i = 0; i < gpsDirectory.entries.size(); ++i ) {
                    TiffField field = (TiffField) gpsDirectory.entries.get(i);

                    if ( field.tag < GPSTagConstants.ALL_GPS_TAGS.length ) {
                        String tagName = GPSTagConstants.ALL_GPS_TAGS[field.tag].name;

                        Object value = convertExifField(field.getValue());

                        if ( value != null ) {
                            exif.put(tagName, value);
                        }
                    }
                    else {
                        if ( field.tag == 31 ) {
                            // this is the GPS accuracy field
                            Object value = convertExifField(field.getValue());

                            if ( value != null ) {
                                exif.put("GPS H Positioning Error", value);
                            }
                        }
                    }
                }
            }
        }
        catch ( IOException | ImageReadException e ) {
            FCMLog.log(e);
        }

        return exif;
    }

    public static HashMap<String, Object> getEXIF(File image) {
        HashMap<String, Object> raw = getRawEXIF(image);

        HashMap<String, Object> exif = new HashMap<>();

        if ( raw != null ) {
            if ( raw.containsKey("GPS Latitude Value") ) {
                exif.put("latitude", raw.get("GPS Latitude Value"));
            }
            if ( raw.containsKey("GPS Longitude Value") ) {
                exif.put("longitude", raw.get("GPS Longitude Value"));
            }
            if ( raw.containsKey("GPS H Positioning Error") ) {
                exif.put("accuracy", raw.get("GPS H Positioning Error"));
            }
            if ( raw.containsKey(GPSTagConstants.GPS_TAG_GPS_ALTITUDE.name) ) {
                Integer ref = (Integer) raw.get(GPSTagConstants.GPS_TAG_GPS_ALTITUDE_REF.name);
                Double altitude = (Double) raw.get(GPSTagConstants.GPS_TAG_GPS_ALTITUDE.name);

                if ( ref == GPSTagConstants.GPS_TAG_GPS_ALTITUDE_REF_VALUE_BELOW_SEA_LEVEL ) {
                    altitude = -altitude;
                }

                exif.put("altitude", altitude);
            }
            if ( raw.containsKey(GPSTagConstants.GPS_TAG_GPS_IMG_DIRECTION.name) ) {
                exif.put("direction", raw.get(GPSTagConstants.GPS_TAG_GPS_IMG_DIRECTION.name));
            }
            if ( raw.containsKey(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL.name) ) {
                // Reformat the timestampp to be sane, YYYY-MM-DD HH:MM:SS
                String stamp = (String) raw.get(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL.name);

                String[] parts = TextUtils.split(stamp, " ");

                if ( parts.length > 1 ) {
                    exif.put("timestamp", parts[0].replace(":", "-") + " " + parts[1]);
                }
            }
            if ( raw.containsKey(ExifTagConstants.EXIF_TAG_ORIENTATION.name) ) {
                exif.put("orientation", raw.get(ExifTagConstants.EXIF_TAG_ORIENTATION.name));
            }
            if ( raw.containsKey(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_WIDTH.name) ) {
                exif.put("width", raw.get(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_WIDTH.name));
            }
            if ( raw.containsKey(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_LENGTH.name) ) {
                exif.put("height", raw.get(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_LENGTH.name));
            }
        }

        return exif;
    }

    // ------------------------------------------------------------------------
    // Private Class Methods
    // ------------------------------------------------------------------------

    private static Object convertExifField(Object value) {
        if ( value instanceof String ) {
            return value;
        }
        else if ( value instanceof RationalNumber ) {
            return ((RationalNumber) value).doubleValue();
        }
        else if ( value instanceof Integer ) {
            return value;
        }
        else if ( value instanceof Byte ) {
            return ((Byte) value).intValue();
        }
        else if ( value instanceof byte[] ) {
            ArrayList<Integer> bytes = new ArrayList<>();

            for ( byte b : (byte[]) value ) {
                bytes.add((int) b);
            }

            return bytes;
        }
        else if ( value instanceof int[] ) {
            ArrayList<Integer> numbers = new ArrayList<>();

            for ( int b : (int[]) value ) {
                numbers.add(b);
            }

            return numbers;
        }
        else if ( value instanceof RationalNumber[] ) {
            ArrayList<Double> numbers = new ArrayList<>();

            for ( RationalNumber n : (RationalNumber[]) value ) {
                numbers.add(n.doubleValue());
            }

            return numbers;
        }

        return null;
    }
}
