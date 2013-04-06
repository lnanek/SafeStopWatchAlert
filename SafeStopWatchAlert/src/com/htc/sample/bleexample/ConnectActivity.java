/************************************************************************************
 *
 *  Copyright (C) 2013 HTC (modified)
 *  Copyright (C) 2009-2011 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ************************************************************************************/
package com.htc.sample.bleexample;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.util.Log;
import android.widget.Toast;

/**
 * Allows user to choose connection settings and connect to a device.
 *
 */
public class ConnectActivity extends Activity {
		
	private static final int VIBRATION_TIME_MS = 300;

	// Consider NYC dangerous for now.
	public static final Location DANGER_AREA = new Location("");
	{
		DANGER_AREA.setLatitude(40.7142d);
		DANGER_AREA.setLongitude(-74.0064);
	}
	
	public static final int METERS_DISTANCE_TO_TRIGGER_ALERT = 100;
	
	public static final Profile PROFILE = Profile.FIND_ME;
	
	public static final int LOCATION_UPDATE_TIME_MS = 5000;
	
	private class BleActionsReceiver extends BroadcastReceiver {
		public void onReceive(final Context context, final Intent intent) {
			runOnUiThread(new Runnable() {
				public void run() {
					if ( intent.getAction().equals(BleActions.ACTION_INITIALIZED) ) {
						mUi.mStatusView.append("BLE Profile initialized\n");
					} else if ( intent.getAction().equals(BleActions.ACTION_INITIALIZE_FAILED) ) {
						mUi.mStatusView.append("BLE Profile failed to initialize.\n");
						mUi.mStatusView.append("Restart Bluetooth and try again.\n");
					} else if ( intent.getAction().equals(BleActions.ACTION_REGISTERED) ) {
						mUi.mStatusView.append("BLE Profile registered.\n");
						mUi.mStatusView.append("Choose a connection option...\n");
						mUi.setConnectionButtonsEnabled(true);
					} else if ( intent.getAction().equals(BleActions.ACTION_CONNECTED) ) {
						mUi.mStatusView.append("BLE Profile connected\n");
						mUi.mStatusView.append("Attempting to refresh...\n");
					} else if ( intent.getAction().equals(BleActions.ACTION_REFRESHED) ) {
						mUi.mStatusView.append("BLE Profile refreshed\n");
						mForegroundConnectionAttempter.removeMessages(ATTEMPT_CONNECTION_MESSAGE);
						mUi.mStatusView.append("Ready for GATT actions...\n");
						mUi.setProfileButtonsEnabled(true);
					} else if ( intent.getAction().equals(BleActions.ACTION_NOTIFICATION) ) {
						mUi.mStatusView.append("Characteristic changed.\n");
					} else if ( intent.getAction().equals(BleActions.ACTION_DISCONNECTED) ) {
						mUi.mStatusView.append("BLE Profile disconnected\n");
					}
				}
			});
		}
	};
	
	private static final String LOG_TAG = "BleFindMeClientActivity";

	private static final int REQUEST_ENABLE_BT = 1;

	private static final int REQUEST_SELECT_DEVICE = 2;
	
	private static final int ATTEMPT_CONNECTION_MESSAGE = 0;

	private DeviceSettings mSelection = new DeviceSettings();

	private ProfileClient mClient;

	private ConnectUi mUi;

	private BluetoothAdapter mAdapter;

	private BroadcastReceiver mReceiver;
	
	private LocationManager mLocations;
	
	private Handler mForegroundConnectionAttempter = new Handler() {
		@Override
		public void dispatchMessage(Message msg) {
			performForegroundConnectionAttempt(msg.arg1);			
		}
	};
	
	private LocationListener mLocationListener = new LocationListener() {
		@Override
		public void onLocationChanged(Location location) {
			setLocation(location);
		}
		@Override
		public void onProviderDisabled(String provider) {
		}
		@Override
		public void onProviderEnabled(String provider) {
		}
		@Override
		public void onStatusChanged(String provider, int status,
				Bundle extras) {
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		Log.i(LOG_TAG, "onCreate");
		
		super.onCreate(savedInstanceState);
		
		mLocations = (LocationManager) getSystemService(LOCATION_SERVICE);
		mLocations.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_UPDATE_TIME_MS, 0, mLocationListener);
		mLocations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, LOCATION_UPDATE_TIME_MS, 0, mLocationListener);
		
		mUi = new ConnectUi(this);

		mSelection = new Prefs(this).getLastDeviceSettings();
		if ( null != mSelection ) {
			mUi.mDeviceNameView.setText(mSelection.getDisplayName());
		}
	}
	
	public void forceLocation(Location location) {
		mLocations.removeUpdates(mLocationListener);
		setLocation(location);
	}
	
	private void setLocation(Location location) {
		
		mUi.mStatusView.append("Current lat, lon: " + location.getLatitude() + ", " + location.getLongitude() + "\n");
		float distance = location.distanceTo(DANGER_AREA);
		
		if ( distance > METERS_DISTANCE_TO_TRIGGER_ALERT ) {
			mUi.mStatusView.append("Safe " + (distance/1000) + " km from danger lat, lon: " + DANGER_AREA.getLatitude() + ", " + DANGER_AREA.getLongitude() + "\n");					
		} else {
			mUi.mStatusView.append("Dangerous " + (distance/1000) + " km from danger lat, lon: " + DANGER_AREA.getLatitude() + ", " + DANGER_AREA.getLongitude() + "\n");										
			 writeAlertLevel();
		}		
	}
	
	@Override
	protected void onStart() {
		super.onStart();
		configure();
	}

	void configure() {
		Log.i(LOG_TAG, "configure");
		new Prefs(this).setLastDeviceSettings(mSelection);
		
		// Make sure Bluetooth supported.
		if (null == mAdapter) {
			mAdapter = BluetoothAdapter.getDefaultAdapter();
			if (mAdapter == null) {
				Toast.makeText(this, "Bluetooth is not available - exiting...",
						Toast.LENGTH_LONG).show();
				finish();
				return;
			}
		}
		
		// Make sure Bluetooth enabled.
		if (!mAdapter.isEnabled()) {
			Intent enableIntent = new Intent(
					BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
			return;
		}
		
		// Make sure device setup.
		if ( null == mSelection.encryptionLevel ) {
			mUi.showEncryptionSettingsDialog(mSelection);
			return;
		}
		
		if ( null == mSelection.connectionRetryAttmpempts || null == mSelection.retryRateMs ) {
			mUi.showRetrySettingsDialog(mSelection);
			return;
		}
		
		if ( null == mSelection || null == mSelection.address ) {
			Intent newIntent = new Intent(ConnectActivity.this,
					SelectDeviceActivity.class);
			startActivityForResult(newIntent, REQUEST_SELECT_DEVICE);
			return;
		}
		
		// Create, initialize, register GATT profile client if needed.
		if (null == mClient) {
									
			// Listen for actions from the profile client.
			mReceiver = new BleActionsReceiver();
			final IntentFilter filter = new IntentFilter();
			filter.addAction(BleActions.ACTION_INITIALIZED);
			filter.addAction(BleActions.ACTION_INITIALIZE_FAILED);
			filter.addAction(BleActions.ACTION_REGISTERED);
			filter.addAction(BleActions.ACTION_CONNECTED);
			filter.addAction(BleActions.ACTION_REFRESHED);
			filter.addAction(BleActions.ACTION_NOTIFICATION);
			filter.addAction(BleActions.ACTION_DISCONNECTED);
			registerReceiver(mReceiver, filter);
			
			//Create client.
			mUi.mStatusView.append("Attempting to register...\n");
			mClient = new ProfileClient(ConnectActivity.this);
		}
	}

	@Override
	public void onStop() {
		Log.i(LOG_TAG, "onStop");
		
		clearConnections();
				
		super.onStop();
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		
		// XXX Does deregistering profile take a certain amount of time?
		// Might cause trouble if restart app within that time period?
		clearProfile();		
	}
	
	private BluetoothDevice getDevice() {
		Log.i(LOG_TAG, "getDevice");
		
		if (null == mAdapter || null == mSelection
				|| null == mSelection.address) {
			return null;
		}

		return mAdapter.getRemoteDevice(mSelection.address);
	}


	private void clearConnections() {
		Log.i(LOG_TAG, "clearConnections");

		mForegroundConnectionAttempter.removeMessages(ATTEMPT_CONNECTION_MESSAGE);
		
		final BluetoothDevice device = getDevice();
		if (null == mClient || null == device) {
			return;
		}
		mClient.cancelBackgroundConnection(device);
		mClient.disconnect(device);
	}

	private void clearProfile() {
		Log.i(LOG_TAG, "clearProfile");

		if ( null != mReceiver ) {
			unregisterReceiver(mReceiver);
			mReceiver = null;
		}
		
		if (null == mClient) {
			return;
		}

		try {
			mClient.deregister();
		} catch (InterruptedException ignored) {
		}
		
		mClient = null;
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		Log.i(LOG_TAG, "onActivityResult");
		
		// User returned from prompt to enable Bluetooth.
		if (requestCode == REQUEST_ENABLE_BT) {
			// User did not enable Bluetooth.
			if (resultCode != Activity.RESULT_OK) {
				Toast.makeText(this,
						"User decided not to enable Bluetooth - exiting...",
						Toast.LENGTH_LONG).show();
				finish();
				return;
			}
			// User did enable Bluetooth.
			configure();
			return;
		}
		// User returned from selecting a device.
		if (requestCode == REQUEST_SELECT_DEVICE) {
			if (resultCode == Activity.RESULT_OK && data != null) {
				String deviceAddress = data
						.getStringExtra(BluetoothDevice.EXTRA_DEVICE);
				BluetoothDevice mDevice = BluetoothAdapter.getDefaultAdapter()
						.getRemoteDevice(deviceAddress);

				mSelection.address = mDevice.getAddress();
				mSelection.name = mDevice.getName();
				mUi.mDeviceNameView.setText(mSelection.getDisplayName());
				configure();
			}
			return;
		}
	}

	void writeAlertLevel() {
		Log.i(LOG_TAG, "writeAlertLevel");
		
		Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
		 
		v.vibrate(VIBRATION_TIME_MS);

		final BluetoothDevice device = getDevice();
		if ( null == device || null == mClient ) {
			return;
		}

		mClient.alert(getDevice());
	}

	void cancelBackgroundConnection() {
		Log.i(LOG_TAG, "cancelBackgroundConnection");
		clearConnections();
	}

	void startForegroundConnectionAttempts() {
		Log.i(LOG_TAG, "connectForeground");
		mForegroundConnectionAttempter.removeMessages(ATTEMPT_CONNECTION_MESSAGE);
		mClient.setEncryptionLevel(mSelection.encryptionLevel);
		for ( int connectionAttempts = 0; 
				connectionAttempts < mSelection.connectionRetryAttmpempts; connectionAttempts++ ) {
			final int delayMs = connectionAttempts * mSelection.retryRateMs;

			Log.i(LOG_TAG, "scheduling attempt " + (connectionAttempts + 1) + " in: " + delayMs);
			
			final Message message = mForegroundConnectionAttempter.obtainMessage();
			message.what = ATTEMPT_CONNECTION_MESSAGE;
			message.arg1 = connectionAttempts;
			mForegroundConnectionAttempter.sendMessageDelayed(message, delayMs);
		}
	}

	private void performForegroundConnectionAttempt(final int connectionAttempts) {
		Log.i(LOG_TAG, "performForegroundConnectionAttempt: attempt " + (connectionAttempts + 1)
				+ " of  " + mSelection.connectionRetryAttmpempts);
		
		final BluetoothDevice device = getDevice();
		
		if ( null == device ) {
			mUi.mStatusView.append("Aborting connection attempts. No device selected.\n");
			mForegroundConnectionAttempter.removeMessages(ATTEMPT_CONNECTION_MESSAGE);
			return;
		}
		
		if ( null == mClient ) {
			mUi.mStatusView.append("Aborting connection attempts. Profile not initialized.\n");
			mForegroundConnectionAttempter.removeMessages(ATTEMPT_CONNECTION_MESSAGE);
			return;
		}
		
		if ( !mClient.isProfileRegistered() ) {
			mUi.mStatusView.append("Aborting connection attempts. Profile not registered.\n");
			mForegroundConnectionAttempter.removeMessages(ATTEMPT_CONNECTION_MESSAGE);
			return;
		}

		// TODO check these as well?
		//mClient.findConnectedDevice(arg0)
		//mClient.getConnectedDevices()
		//mClient.getPendingConnections()
		
		try {
			mClient.connect(device);
		} catch (Exception e) {
			Log.e(LOG_TAG, "error connecting", e);
		}
	}

	void connectBackground() {
		Log.i(LOG_TAG, "connectBackground");
		try {
			mClient.setEncryptionLevel(mSelection.encryptionLevel);
			mClient.connectBackground(getDevice());
		} catch (Exception e) {
			Log.e(LOG_TAG, "error background connecting", e);
		}
	}

	void onSetupDeviceClicked() {
		Log.i(LOG_TAG, "onSetupDeviceClicked");
		
		clearConnections();
		mSelection = new DeviceSettings();
		configure();
	}
}