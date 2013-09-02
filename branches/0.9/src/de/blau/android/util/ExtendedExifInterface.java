package de.blau.android.util;

import java.io.File;
import java.io.IOException;

import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.imaging.jpeg.JpegProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.GpsDirectory;

import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.ExifInterface;
import android.util.Log;

/**
 * Workaround android SDK brokeness
 * While it is possible to write the direction values, it is not possible to read them with the standard android library
 * @author simon
 *
 */
public class ExtendedExifInterface extends ExifInterface {
	Metadata metadata;
	
	public final static String TAG_GPS_IMG_DIRECTION 		= "GPSImgDirection";
	public final static String TAG_GPS_IMG_DIRECTION_REF	= "GPSImgDirectionRef";
	
	public ExtendedExifInterface(String filename) throws IOException {
		super(filename);
		
		File jpegFile = new File(filename);
		try {
			 metadata = JpegMetadataReader.readMetadata(jpegFile);
		} catch (JpegProcessingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}	
	
	@Override
	public String getAttribute(String tag) {
		if (!tag.equals(TAG_GPS_IMG_DIRECTION) && !tag.equals(TAG_GPS_IMG_DIRECTION_REF)) {
			return super.getAttribute(tag);
		} else {
			// obtain the Exif directory
			GpsDirectory directory = metadata.getDirectory(GpsDirectory.class);

			// query the tag's value
			if (tag.equals(TAG_GPS_IMG_DIRECTION) && directory.containsTag(GpsDirectory.TAG_GPS_IMG_DIRECTION)) {
				String r[] = directory.getString(GpsDirectory.TAG_GPS_IMG_DIRECTION).split("/");
				if (r.length != 2)
					return null;
				double d = Double.valueOf(r[0]) / Double.valueOf(r[1]);
				Log.d("ExtendedExifInterface",GpsDirectory.TAG_GPS_IMG_DIRECTION + " " + d);
				return (Double.valueOf(d).toString());
			}
			else if (directory.containsTag(GpsDirectory.TAG_GPS_IMG_DIRECTION_REF)) {
				Log.d("ExtendedExifInterface",GpsDirectory.TAG_GPS_IMG_DIRECTION_REF + " " + directory.getString(GpsDirectory.TAG_GPS_IMG_DIRECTION_REF));
				return directory.getString(GpsDirectory.TAG_GPS_IMG_DIRECTION_REF);
			}
			else {
				Log.d("ExtendedExifInterface", "No direction information");
				return null;
			}
		}
	}
}