package com.sensirion.smartgadget.view.device_management;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.sensirion.libble.BleManager;
import com.sensirion.libble.devices.BleDevice;
import com.sensirion.libble.devices.KnownDevices;
import com.sensirion.libble.listeners.devices.DeviceStateListener;
import com.sensirion.libble.listeners.devices.ScanListener;
import com.sensirion.smartgadget.R;
import com.sensirion.smartgadget.peripheral.rht_sensor.external.RHTHumigadgetSensorManager;
import com.sensirion.smartgadget.persistence.device_name_database.DeviceNameDatabaseManager;
import com.sensirion.smartgadget.utils.Interval;
import com.sensirion.smartgadget.utils.view.IndeterminateProgressDialog;
import com.sensirion.smartgadget.utils.view.ParentListFragment;
import com.sensirion.smartgadget.utils.view.SectionAdapter;
import com.sensirion.smartgadget.view.MainActivity;
import com.sensirion.smartgadget.view.device_management.utils.HumigadgetListItemAdapter;

import java.util.concurrent.Executors;

import butterknife.Bind;
import butterknife.BindBool;
import butterknife.BindColor;
import butterknife.BindString;
import butterknife.ButterKnife;

/**
 * Holds all the UI elements needed for showing the devices in range in a list
 */
public class ScanDeviceFragment extends ParentListFragment implements ScanListener, DeviceStateListener {

    // Class TAG
    @NonNull
    private static final String TAG = ScanDeviceFragment.class.getSimpleName();

    // Timeout attributes
    private static final byte DEVICE_SYNCHRONIZATION_TIMEOUT_SECONDS = 3;
    private static final int DEVICE_SYNCHRONIZATION_TIMEOUT_MILLISECONDS =
            DEVICE_SYNCHRONIZATION_TIMEOUT_SECONDS * Interval.ONE_SECOND.getNumberMilliseconds();
    private static final byte DEVICE_SYNCHRONIZATION_MAX_NUMBER_SYNCHRONIZATION_TRIES = 10;
    private static final byte DEVICE_TIMEOUT_SECONDS = 8; // Libble timeout -> 7.5 seconds.
    private static final int DEVICE_TIMEOUT_MILLISECONDS =
            DEVICE_TIMEOUT_SECONDS * Interval.ONE_SECOND.getNumberMilliseconds();

    // Update list attributes
    private static final int MINIMUM_TIME_UPDATE_LIST_DEVICES = Interval.ONE_SECOND.getNumberMilliseconds();

    // Resources extracted from the resources folder
    @BindString(R.string.label_connected)
    String CONNECTED_STRING;
    @BindString(R.string.label_discovered)
    String DISCOVERED_STRING;
    @BindString(R.string.typeface_condensed)
    String TYPEFACE_CONDENSED_LOCATION;
    @BindString(R.string.typeface_bold)
    String TYPEFACE_BOLD_LOCATION;
    @BindString(R.string.device_not_ready_dialog_title)
    String DEVICE_NOT_READY_DIALOG_TITLE;
    @BindString(R.string.device_not_ready_dialog_message_prefix)
    String DEVICE_NOT_READY_DIALOG_MESSAGE_PREFIX;
    @BindString(R.string.asking_for_unknown_device_characteristics_prefix)
    String ASKING_FOR_UNKNOWN_CHARACTERISTICS_PREFIX;
    @BindString(R.string.please_wait)
    String PLEASE_WAIT_STRING;
    @BindString(R.string.connecting_to_device_prefix)
    String CONNECTING_TO_DEVICE_PREFIX;
    @BindString(R.string.ok)
    String OK_STRING;
    @BindString(R.string.connection_timeout_dialog_title)
    String CONNECTION_TIMEOUT_DIALOG_TITLE;
    @BindString(R.string.connection_timeout_dialog_message)
    String CONNECTION_TIMEOUT_DIALOG_MESSAGE;
    @BindString(R.string.trying_connect_device_prefix)
    String TRYING_CONNECT_DEVICE_PREFIX;
    @BindBool(R.bool.is_tablet)
    boolean IS_TABLET;
    @BindColor(R.color.sensirion_grey_dark)
    int SENSIRION_GREY_DARK;
    @BindColor(R.color.sensirion_green)
    int SENSIRION_GREEN;

    // Injected views
    @Bind(R.id.scan_background)
    FrameLayout mBackground;
    @Bind(R.id.togglebutton_scan)
    ToggleButton mScanToggleButton;

    // Block Dialogs
    @Nullable
    private IndeterminateProgressDialog mIndeterminateProgressDialog;

    // Section Managers
    @Nullable
    private SectionAdapter mSectionAdapter;
    @Nullable
    private HumigadgetListItemAdapter mConnectedDevicesAdapter;
    @Nullable
    private HumigadgetListItemAdapter mDiscoveredDevicesAdapter;

    // Fragment state attributes
    @Nullable
    private Menu mOptionsMenu;
    private volatile boolean mIsRequestingCharacteristicsFromPeripheral = false;
    private long mTimestampLastListUpdate = 0;
    private boolean mNeedUpdateList = true;
    @Nullable
    private String mConnectionDialogDeviceAddress;

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        Log.i(TAG, "onCreateView()");

        final View rootView = inflater.inflate(R.layout.fragment_device_scan, container, false);

        ButterKnife.bind(this, rootView);

        final AssetManager assets = getContext().getAssets();
        final Typeface typefaceNormal = Typeface.createFromAsset(assets, TYPEFACE_CONDENSED_LOCATION);
        final Typeface typefaceBold = Typeface.createFromAsset(assets, TYPEFACE_BOLD_LOCATION);

        initListAdapter(typefaceNormal, typefaceBold);
        initToggleButton(typefaceBold);

        setHasOptionsMenu(true);

        initBackgroundListenerTablet();
        return rootView;
    }

    private void initBackgroundListenerTablet() {
        if (IS_TABLET) {
            mBackground.setOnTouchListener(
                    new View.OnTouchListener() {
                        @Override
                        public boolean onTouch(@NonNull final View view,
                                               @NonNull final MotionEvent motionEvent) {
                            final MainActivity parent = (MainActivity) getParent();
                            if (parent != null && isVisible()) {
                                parent.toggleTabletMenu();
                                return true;
                            }
                            return false;
                        }
                    }
            );
        }
    }

    private void initListAdapter(@NonNull final Typeface typefaceNormal,
                                 @NonNull final Typeface typefaceBold) {
        mSectionAdapter = new SectionAdapter() {
            @Nullable
            @Override
            protected View getHeaderView(final String caption,
                                         final int itemIndex,
                                         @Nullable final View convertView,
                                         final ViewGroup parent) {
                TextView headerTextView = (TextView) convertView;
                if (convertView == null) {
                    headerTextView = (TextView) View.inflate(getParent(), R.layout.listitem_scan_header, null);
                    headerTextView.setTypeface(typefaceBold);
                }
                headerTextView.setText(caption);
                return headerTextView;
            }
        };

        mConnectedDevicesAdapter = new HumigadgetListItemAdapter(typefaceNormal, typefaceBold);
        mDiscoveredDevicesAdapter = new HumigadgetListItemAdapter(typefaceNormal, typefaceBold);

        mSectionAdapter.addSectionToAdapter(CONNECTED_STRING, mConnectedDevicesAdapter);
        mSectionAdapter.addSectionToAdapter(DISCOVERED_STRING, mDiscoveredDevicesAdapter);

        setListAdapter(mSectionAdapter);
    }

    private void initToggleButton(@NonNull final Typeface typefaceBold) {

        mScanToggleButton.setTypeface(typefaceBold);

        mScanToggleButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (BleManager.getInstance().isBluetoothEnabled()) {
                    if (isChecked) {
                        BleManager.getInstance().registerNotificationListener(ScanDeviceFragment.this);
                        BleManager.getInstance().startScanning();
                        mScanToggleButton.setBackgroundColor(SENSIRION_GREY_DARK);
                        setRefreshActionButtonState(true);
                    } else {
                        BleManager.getInstance().stopScanning();
                        mScanToggleButton.setBackgroundColor(SENSIRION_GREEN);
                        setRefreshActionButtonState(false);
                    }
                } else {
                    BleManager.getInstance().requestEnableBluetooth(getContext());
                }
            }
        });
        // start scanning immediately when fragment is active
        mScanToggleButton.performClick();
    }

    @Override
    public void onListItemClick(final ListView l, final View v, final int position, final long id) {
        super.onListItemClick(l, v, position, id);
        final BleManager bleManager = BleManager.getInstance();
        if (bleManager.isBluetoothEnabled()) {
            Log.d(TAG, String.format("onListItemClick -> Clicked on position %s.", position));
        } else {
            bleManager.requestEnableBluetooth(getContext());
            return;
        }
        bleManager.stopScanning();

        if (mSectionAdapter == null) {
            Log.e(TAG, "onListItemClick -> Section adapter can't be null.");
            return;
        }

        final BleDevice device = (BleDevice) mSectionAdapter.getItem(position);
        if (device != null) {
            onDeviceClick(device);
        } else {
            Log.w(TAG, "onListItemClick -> The selected device is not a BleDevice.");
            updateList();
        }
    }

    private synchronized void onDeviceClick(@Nullable final BleDevice device) {
        if (device == null) {
            Log.e(TAG, "onDeviceClick -> Device clicked on an empty BleDevice.");
            updateList();
        } else if (device.isConnected()) {
            Log.d(TAG, "onDeviceClick -> Opening manage device fragment.");
            openManageDeviceFragment(device);
        } else {
            Log.d(TAG,
                    String.format(
                            "onDeviceClick -> Connecting to device with address %s.",
                            device.getAddress()
                    )
            );
            connectDevice(device);
        }
    }

    private void openManageDeviceFragment(@NonNull final BleDevice device) {
        if (RHTHumigadgetSensorManager.getInstance().isDeviceReady(device)) {
            final ManageDeviceFragment fragment = new ManageDeviceFragment();
            fragment.init(device.getAddress());
            final MainActivity mainActivity = (MainActivity) getParent();
            if (mainActivity == null) {
                Log.e(TAG, "openManageDeviceFragment -> Cannot obtain main activity");
            } else {
                mainActivity.changeFragment(fragment);
            }
        } else {
            showRetrievingCharacteristicsFromDeviceProgressDialog(device);
        }
    }

    private void connectDevice(@NonNull final BleDevice device) {
        mConnectionDialogDeviceAddress = device.getAddress();
        Log.i(TAG, String.format(TRYING_CONNECT_DEVICE_PREFIX, mConnectionDialogDeviceAddress));
        showConnectionInProgressDialog(device.getAddress());
        RHTHumigadgetSensorManager.getInstance().connectPeripheral(mConnectionDialogDeviceAddress);
    }

    @Override
    public void onResume() {
        super.onResume();
        final Activity parent = getParent();
        if (parent == null) {
            Log.e(TAG, "onResume -> Received null parent, can't enable Bluetooth");
        } else {
            RHTHumigadgetSensorManager.getInstance().requestEnableBluetooth(parent);
        }
        BleManager.getInstance().registerNotificationListener(this);
        updateList();
        BleManager.getInstance().startScanning();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (BleManager.getInstance().isScanning()) {
            BleManager.getInstance().stopScanning();
        }
        BleManager.getInstance().unregisterNotificationListener(this);
    }

    /**
     * Method called when trying to connect to a discovered device. Blocks the window until a device
     * was received or a timeout was produced.
     *
     * @param device that wants to retrieve its characteristics from
     */
    private void showRetrievingCharacteristicsFromDeviceProgressDialog(@NonNull final BleDevice device) {
        showRetrievingCharacteristicsProgressDialog(device);
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                byte numberTries = 0;
                while (++numberTries < DEVICE_SYNCHRONIZATION_MAX_NUMBER_SYNCHRONIZATION_TRIES) {
                    try {
                        RHTHumigadgetSensorManager.getInstance().synchronizeDeviceServices(device);
                        Thread.sleep(DEVICE_SYNCHRONIZATION_TIMEOUT_MILLISECONDS);
                        if (RHTHumigadgetSensorManager.getInstance().isDeviceReady(device)) {
                            break;
                        }
                    } catch (final InterruptedException ignored) {
                    }
                }
                if (mIndeterminateProgressDialog != null && mIndeterminateProgressDialog.isShowing()) {
                    mIndeterminateProgressDialog.dismiss();
                    mIndeterminateProgressDialog = null;
                }
                if (RHTHumigadgetSensorManager.getInstance().isDeviceReady(device)) {
                    openManageDeviceFragment(device);
                } else {
                    showDeviceNotReadyAlert(device);
                }
            }
        });
    }

    private void showDeviceNotReadyAlert(@NonNull final BleDevice device) {
        final Activity parent = getParent();
        if (parent == null) {
            Log.e(TAG, "showDeviceNotReadyAlert -> Cannot show alert because parent is null");
            return;
        }

        parent.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final AlertDialog.Builder builder = new AlertDialog.Builder(getParent());
                builder.setTitle(DEVICE_NOT_READY_DIALOG_TITLE);
                final String deviceName =
                        DeviceNameDatabaseManager.getInstance().readDeviceName(device.getAddress());
                builder.setMessage(
                        String.format(
                                DEVICE_NOT_READY_DIALOG_MESSAGE_PREFIX,
                                deviceName
                        )
                )
                        .setCancelable(false)
                        .setPositiveButton(OK_STRING, new DialogInterface.OnClickListener() {
                                    public void onClick(@NonNull DialogInterface dialog, int id) {
                                        dialog.dismiss();
                                        dialog.cancel();
                                    }
                                }
                        );
                final AlertDialog timeoutDialog = builder.create();
                timeoutDialog.show();
            }
        });
    }

    private void showRetrievingCharacteristicsProgressDialog(@NonNull final BleDevice device) {
        final Activity parent = getParent();
        if (parent == null) {
            Log.e(TAG, "showRetrievingCharacteristicsProgressDialog -> Not showing dialog with null parent.");
            return;
        }
        final String title = PLEASE_WAIT_STRING;
        final String deviceName = DeviceNameDatabaseManager.getInstance().readDeviceName(device.getAddress());
        final String message = String.format(ASKING_FOR_UNKNOWN_CHARACTERISTICS_PREFIX, deviceName);
        final boolean isCancelable = false;
        mIndeterminateProgressDialog = new IndeterminateProgressDialog(getParent(), title, message, isCancelable);
        mIndeterminateProgressDialog.show(getParent());
    }

    /**
     * Method called when trying to connect to a discovered device. Blocks the window until a device
     * was received or a timeout was produced.
     *
     * @param deviceAddress of the device.
     */
    private void showConnectionInProgressDialog(@NonNull final String deviceAddress) {
        final Context parent = getParent();
        if (parent == null) {
            Log.e(TAG, "showConnectionInProgressDialog -> Received a null parent.");
            return;
        }

        final String deviceName = DeviceNameDatabaseManager.getInstance().readDeviceName(deviceAddress);

        final String title = PLEASE_WAIT_STRING;
        final String message = String.format(CONNECTING_TO_DEVICE_PREFIX, deviceName);
        final boolean isCancelable = false;

        mIndeterminateProgressDialog = new IndeterminateProgressDialog(
                getParent(),
                title,
                message,
                isCancelable
        );
        mIndeterminateProgressDialog.show(getParent());

        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                int timeWaited = 0;
                final int timePerCheck = 40;
                while (isStillConnecting(timeWaited)) {
                    try {
                        Thread.sleep(timePerCheck);
                        timeWaited += timePerCheck;
                    } catch (final InterruptedException e) {
                        Log.e(TAG, "showConnectionInProgressDialog -> An interrupted exception was produced when connecting to peripheral -> ", e);
                    }
                    if (mIsRequestingCharacteristicsFromPeripheral) {
                        if (mIndeterminateProgressDialog == null) {
                            Log.e(TAG, "showConnectionInProgressDialog -> Progress dialog is already canceled.");
                        } else {
                            final String message = String.format(ASKING_FOR_UNKNOWN_CHARACTERISTICS_PREFIX, deviceName);
                            mIndeterminateProgressDialog.setMessage(message, getParent());
                        }
                    }
                }
                dismissConnectingProgressDialog(deviceAddress);
            }
        });
    }

    private boolean isStillConnecting(final int timeWaited) {
        if (mConnectionDialogDeviceAddress == null) {
            return false;
        }
        if (mIndeterminateProgressDialog != null && mIndeterminateProgressDialog.isShowing()) {
            if (timeWaited < DEVICE_TIMEOUT_MILLISECONDS) {
                return true;
            } else if (BleManager.getInstance().isDeviceConnected(mConnectionDialogDeviceAddress)) {
                return mIsRequestingCharacteristicsFromPeripheral;
            } else {
                mIsRequestingCharacteristicsFromPeripheral = false;
            }
        }
        return false;
    }

    private void dismissConnectingProgressDialog(@NonNull final String deviceAddress) {
        if (mIndeterminateProgressDialog != null) {
            if (mIndeterminateProgressDialog.isShowing()
                    && BleManager.getInstance().getConnectedDevice(deviceAddress) == null) {

                onConnectionTimeout(deviceAddress);
            } else {
                mIndeterminateProgressDialog.dismiss();
                mIndeterminateProgressDialog = null;
            }
        }
    }

    /**
     * In case of timeout when connecting to a device this dialog will appear.
     *
     * @param deviceAddress of the device.
     */
    private void onConnectionTimeout(@NonNull final String deviceAddress) {
        if (mIsRequestingCharacteristicsFromPeripheral) {
            return;
        }
        if (mIndeterminateProgressDialog == null) {
            Log.w(TAG, "onConnectionTimeout -> mIndeterminateProgressDialog is null. Not showing the connection timeout dialog.");
            return;
        }

        final Activity parent = getParent();
        if (parent == null) {
            Log.e(TAG, "onConnectionTimeout -> Cannot obtain the parent, not showing a timeout dialog.");
            return;
        }

        parent.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mIndeterminateProgressDialog.dismiss();
                mIndeterminateProgressDialog = null;

                final AlertDialog.Builder builder = new AlertDialog.Builder(getParent());
                builder.setTitle(CONNECTION_TIMEOUT_DIALOG_TITLE);
                final String deviceName = DeviceNameDatabaseManager.getInstance().readDeviceName(deviceAddress);
                builder
                        .setMessage(String.format("%s %s", CONNECTION_TIMEOUT_DIALOG_MESSAGE, deviceName))
                        .setCancelable(false)
                        .setPositiveButton(OK_STRING, new DialogInterface.OnClickListener() {
                                    public void onClick(@NonNull DialogInterface dialog, int id) {
                                        dialog.dismiss();
                                        dialog.cancel();
                                    }
                                }
                        );
                final AlertDialog timeoutDialog = builder.create();
                timeoutDialog.show();
                updateList();
            }
        });
    }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu, @NonNull final MenuInflater inflater) {
        menu.clear();
        mOptionsMenu = menu;
        inflater.inflate(R.menu.refresh_action_bar, menu);
        setRefreshActionButtonState(true);
        super.onCreateOptionsMenu(menu, inflater);
    }

    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.scan_device_refresh:
                final BleManager bleManager = BleManager.getInstance();
                if (bleManager.isBluetoothEnabled()) {
                    if (bleManager.isScanning()) {
                        bleManager.stopScanning();
                        setRefreshActionButtonState(false);
                    } else {
                        bleManager.startScanning();
                        setRefreshActionButtonState(true);
                    }
                    return true;
                } else {
                    bleManager.requestEnableBluetooth(getContext());
                }
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Shows or hides the refresh button in dependence if it's scanning or not.
     *
     * @param refreshing <code>true</code> for making it visible - <code>false</code> otherwise.
     */
    public void setRefreshActionButtonState(final boolean refreshing) {
        if (mOptionsMenu != null) {
            final MenuItem refreshItem = mOptionsMenu.findItem(R.id.scan_device_refresh);
            if (refreshItem != null) {
                if (refreshing) {
                    refreshItem.setActionView(R.layout.actionbar_indeterminate_progress);
                } else {
                    refreshItem.setActionView(null);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onDeviceConnected(@NonNull final BleDevice device) {
        Log.i(TAG,
                String.format(
                        "onDeviceConnected -> Received connected device with address: %s.",
                        device.getAddress()
                )
        );
        onDeviceConnectionStateChange(device.getAddress());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onDeviceDisconnected(@NonNull final BleDevice device) {
        final String deviceAddress = device.getAddress();
        Log.i(TAG,
                String.format(
                        "onDeviceConnected -> %s has lost the connected with the device: %s ",
                        TAG,
                        deviceAddress
                )
        );
        onDeviceConnectionStateChange(deviceAddress);

        if (mConnectionDialogDeviceAddress != null
                && mConnectionDialogDeviceAddress.equals(deviceAddress)) {

            dismissConnectingProgressDialog(deviceAddress);
        }
    }

    private void onDeviceConnectionStateChange(@NonNull final String deviceAddress) {
        Log.d(TAG,
                String.format(
                        "onDeviceConnectionStateChange -> Device with address %s changed its connection state",
                        deviceAddress
                )
        );
        if (mConnectionDialogDeviceAddress != null
                && mConnectionDialogDeviceAddress.equals(deviceAddress)) {

            if (BleManager.getInstance().isDeviceConnected(deviceAddress)) {
                mIsRequestingCharacteristicsFromPeripheral = true;
            } else if (mIsRequestingCharacteristicsFromPeripheral) {
                onConnectionTimeout(deviceAddress);
                mIsRequestingCharacteristicsFromPeripheral = false;
            }
        }
        mNeedUpdateList = true;
        updateList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onDeviceDiscovered(@NonNull final BleDevice device) {
        Log.i(TAG,
                String.format(
                        "onDeviceDiscovered -> Received discovered device with address %s and advertise name %s.",
                        device.getAddress(),
                        device.getAdvertisedName()
                )
        );
        updateList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onDeviceAllServicesDiscovered(@NonNull final BleDevice device) {
        Log.i(TAG,
                String.format(
                        "onDeviceAllServiceDiscovered -> Device %s has discovered all its services.",
                        device.getAddress()
                )
        );
        if (mIndeterminateProgressDialog != null && mIndeterminateProgressDialog.isShowing()) {
            if (device.isConnected()) {
                Executors.newSingleThreadExecutor().execute(
                        new Runnable() {
                            @Override
                            public void run() {
                                waitForServiceSynchronization(device);
                                mIsRequestingCharacteristicsFromPeripheral = false;
                            }
                        }
                );
            }
        }
    }

    private void waitForServiceSynchronization(@NonNull final BleDevice device) {
        byte tryNumber = 0;
        while (!RHTHumigadgetSensorManager.getInstance().isDeviceReady(device)
                && ++tryNumber < DEVICE_SYNCHRONIZATION_MAX_NUMBER_SYNCHRONIZATION_TRIES) {

            RHTHumigadgetSensorManager.getInstance().synchronizeDeviceServices(device);
            try {
                Thread.sleep(DEVICE_SYNCHRONIZATION_TIMEOUT_MILLISECONDS);
            } catch (@NonNull final InterruptedException ignored) {
            }

            if (RHTHumigadgetSensorManager.getInstance().isDeviceReady(device)) {
                Log.i(TAG,
                        String.format(
                                "onDeviceAllServiceDiscovered -> Device %s is synchronized.",
                                device.getAddress()
                        )
                );
            } else {
                Log.w(TAG,
                        String.format(
                                "onDeviceAllServiceDiscovered -> Device %s is not synchronized yet.",
                                device.getAddress()
                        )
                );
            }
        }
    }

    private void updateList() {
        if (mNeedUpdateList
                || mTimestampLastListUpdate + MINIMUM_TIME_UPDATE_LIST_DEVICES > System.currentTimeMillis()) {
            synchronized (this) {
                final Activity parent = getParent();
                if (parent == null) {
                    Log.e(TAG, "updateList -> Parent is null, cannot update the device list.");
                    return;
                }
                mNeedUpdateList = false;
                mTimestampLastListUpdate = System.currentTimeMillis();

                final Iterable<? extends BleDevice> connectedDevices =
                        BleManager.getInstance().getConnectedBleDevices();

                final Iterable<? extends BleDevice> discoveredDevices =
                        BleManager.getInstance().getDiscoveredBleDevices(
                                KnownDevices.RHT_GADGETS.getAdvertisedNames()
                        );

                if (mConnectedDevicesAdapter == null) {
                    Log.e(TAG, "updateList -> Connected device adapter is null.");
                    return;
                }
                if (mDiscoveredDevicesAdapter == null) {
                    Log.e(TAG, "updateList -> Discovered device adapter is null");
                    return;
                }

                getParent().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (ScanDeviceFragment.this) {
                            mConnectedDevicesAdapter.clear();
                            mConnectedDevicesAdapter.addAll(connectedDevices);
                            mDiscoveredDevicesAdapter.clear();
                            mDiscoveredDevicesAdapter.addAll(discoveredDevices);
                            setListAdapter(mSectionAdapter);
                        }
                    }
                });
            }
        }
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public void onScanStateChanged(final boolean isScanEnabled) {
        if (isScanEnabled) {
            Log.i(TAG, "mScanStateReceiver.onReceive() -> scanning STARTED.");
            mScanToggleButton.setChecked(true);
            setRefreshActionButtonState(true);
        } else {
            Log.i(TAG, "mScanStateReceiver.onReceive() -> scanning STOPPED.");
            mScanToggleButton.setChecked(false);
            setRefreshActionButtonState(false);
        }
        mNeedUpdateList = true;
    }
}