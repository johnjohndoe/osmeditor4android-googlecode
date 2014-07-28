package de.blau.android.services;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.Resources;
import android.location.GpsStatus.NmeaListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import de.blau.android.Application;
import de.blau.android.DialogFactory;
import de.blau.android.Main;
import de.blau.android.R;
import de.blau.android.exception.OsmException;
import de.blau.android.exception.StorageException;
import de.blau.android.osm.OsmParser;
import de.blau.android.osm.Track;
import de.blau.android.osm.UndoStorage;
import de.blau.android.osm.Track.TrackPoint;
import de.blau.android.prefs.Preferences;
import de.blau.android.resources.Profile;
import de.blau.android.services.util.ServiceCompat;
import de.blau.android.util.SavingHelper;
import de.blau.android.util.SavingHelper.Exportable;

public class TrackerService extends Service implements LocationListener, NmeaListener, Exportable {

	private static final float TRACK_LOCATION_MIN_ACCURACY = 200f;

	private static final String TAG = "TrackerService";

	private final TrackerBinder mBinder = new TrackerBinder();
	
	private LocationManager locationManager = null;

	private boolean tracking = false;

	private Track track;

	private TrackerLocationListener externalListener = null;

	private boolean listenerNeedsGPS = false;

	private Location lastLocation = null;
	
	private boolean gpsEnabled = false;
	
	private ServiceCompat serviceCompat = null;

	@Override
	public void onCreate() {
		super.onCreate();
		serviceCompat = new ServiceCompat(this);
		Log.d(TAG, "onCreate");
		track = new Track(this);
		locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
	}

	@Override
	public void onDestroy() {
		stopTracking(false);
		track.close();
		serviceCompat.destroy();
		super.onDestroy();
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		startTrackingInternal();
		return START_STICKY;
	}
	
	/**
	 * Starts the tracker service (which invokes {@link #onStartCommand(Intent, int, int)},
	 * which invokes {@link #startTrackingInternal()}, which does the actual work.
	 * To start tracking, bind the service, then call this.
	 */
	public void startTracking() {
		startService(new Intent(this, TrackerService.class));
	}
	
	/**
	 * Actually starts tracking.
	 * Gets called by {@link #onStartCommand(Intent, int, int)} when the service is started.
	 * See {@link #startTracking()} for the public method to call when tracking should be started.
	 */
	private void startTrackingInternal() {
		if (tracking) return;
		NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this);
		Resources res = getResources();
		Intent appStartIntent = new Intent();
		appStartIntent
			.setAction(Intent.ACTION_MAIN)
			.addCategory(Intent.CATEGORY_LAUNCHER)
			.setComponent(new ComponentName(Main.class.getPackage().getName(), Main.class.getName()))
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent pendingAppIntent = PendingIntent.getActivity(this, 0, appStartIntent, 0);
		notificationBuilder
			.setContentTitle(res.getString(R.string.tracking_active_title))
			.setContentText(res.getString(R.string.tracking_active_text))
			.setSmallIcon(R.drawable.osm_logo)
			.setOngoing(true)
			.setUsesChronometer(true)
			.setContentIntent(pendingAppIntent);
		serviceCompat.startForeground(R.id.notification_tracker, notificationBuilder.build());
		tracking = true;
		track.markNewSegment();
		try {
			Application.mainActivity.triggerMenuInvalidation();
		} catch (Exception e) {} // ignore
		updateGPSState();
	}
	
	/**
	 * Stops tracking
	 * @param deleteTrack true if the track should be deleted, false if it should be kept
	 */
	public void stopTracking(boolean deleteTrack) {
		if (!tracking) {
			if (deleteTrack) track.reset();
			return;
		}
		if (deleteTrack) {
			track.reset();
		} else {
			track.save();
		}
		tracking = false;
		updateGPSState();
		serviceCompat.stopForeground(R.id.notification_tracker);
		stopSelf();
	}
	
	public boolean isTracking() {
		return tracking;
	}

	public List<TrackPoint> getTrackPoints() {
		return track.getTrackPoints();
	}
	
	/**
	 * Exports the GPX data
	 */
	@Override
	public void export(OutputStream outputStream) throws Exception {
		track.exportToGPX(outputStream);
	}
	
	@Override
	public String exportExtension() {
		return "gpx";
	}
	
	@Override
	public IBinder onBind(Intent arg0) {
		return mBinder;
	}
		
    public class TrackerBinder extends Binder {
        public TrackerService getService() {
            return TrackerService.this;
        }
    }

	@Override
	public void onLocationChanged(Location location) {
		//Log.v(TAG, "Location received");
		if (tracking && (!location.hasAccuracy() || location.getAccuracy() <= TRACK_LOCATION_MIN_ACCURACY)) {
			track.addTrackPoint(location);
		}
		if (externalListener != null) externalListener.onLocationChanged(location);
		lastLocation = location;
	}

	@Override
	public void onProviderDisabled(String provider) {
		Log.d(TAG, "Provider disabled: " + provider);
		gpsEnabled = false;
		locationManager.removeUpdates(this);
		// locationManager.removeNmeaListener(this);
	}

	@Override
	public void onProviderEnabled(String provider) {
	}

	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) {}
	
	public interface TrackerLocationListener {
		public void onLocationChanged(Location location);
	}

	private void updateGPSState() {
		boolean needed = listenerNeedsGPS || tracking;
		if (needed && !gpsEnabled) {
			Log.d(TAG, "Enabling GPS updates");
			Preferences prefs = new Preferences(this);
			try {
				Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
				if (last != null) onLocationChanged(last);
			} catch (Exception e) {} // Ignore
			try {
				locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, prefs.getGpsInterval(),
						prefs.getGpsDistance(), this);
				// locationManager.addNmeaListener(this);
			} catch (Exception e) {
				Log.e(TAG, "Failed to enable GPS", e);
				Toast.makeText(this, R.string.gps_failure, Toast.LENGTH_SHORT).show();				
			}
			gpsEnabled = true;
		} else if (!needed && gpsEnabled) {
			Log.d(TAG, "Disabling GPS updates");
			locationManager.removeUpdates(this);
			gpsEnabled = false;
		}
	}
	
	public void setListenerNeedsGPS(boolean listenerNeedsGPS) {
		this.listenerNeedsGPS = listenerNeedsGPS;
		updateGPSState();
		if (listenerNeedsGPS && externalListener != null && lastLocation != null) externalListener.onLocationChanged(lastLocation);
	}

	public void setListener(TrackerLocationListener listener) {
		if (listener == null) setListenerNeedsGPS(false);
		externalListener = listener;
	}

	/**
	 * Read a file in GPX format from device
	 * @param fileName
	 * @param add unused currently
	 * @throws FileNotFoundException 
	 */
	public void importGPXFile(final Uri uri) throws FileNotFoundException {
		
		final InputStream is;
		
		if (uri.getScheme().equals("file")) {
			is = new FileInputStream(new File(uri.getPath()));
		} else {
			ContentResolver cr = getContentResolver();
			is = cr.openInputStream(uri);
		}
		
		final int existingPoints = track.getTrackPoints().size();
	
		new AsyncTask<Void, Void, Integer>() {
			
			@Override
			protected void onPreExecute() {
				Application.mainActivity.showDialog(DialogFactory.PROGRESS_LOADING);
			}
			
			@Override
			protected Integer doInBackground(Void... arg) {
				int result = 0;
				InputStream in;
				in = new BufferedInputStream(is);
				try {
					track.importFromGPX(in);
				} finally {
					SavingHelper.close(in);
				}
				return result;
			}
			
			@Override
			protected void onPostExecute(Integer result) {
				try {
					Application.mainActivity.dismissDialog(DialogFactory.PROGRESS_LOADING);
					Toast.makeText(Application.mainActivity.getApplicationContext(), 
							Application.mainActivity.getApplicationContext().getResources().getString(R.string.toast_imported_track_points,track.getTrackPoints().size()-existingPoints), Toast.LENGTH_LONG).show();
					// the following is extremely ugly
					Main.triggerMenuInvalidationStatic();
				} catch (IllegalArgumentException e) {
					 // Avoid crash if dialog is already dismissed
					Log.d("TrackerService", "", e);
				}
			}
			
		}.execute();
	}

	public Track getTrack() {
		return track;
	}

	@Override
	public void onNmeaReceived(long timestamp, String nmea) {
		Log.d("TrackerService","NEMA " + nmea);
	}
}
