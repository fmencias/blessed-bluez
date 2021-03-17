package com.welie.blessed;

import com.welie.blessed.bluez.*;
import com.welie.blessed.internal.InternalCallback;
import org.bluez.Device1;
import org.bluez.exceptions.*;
import org.freedesktop.dbus.DBusMap;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.UInt16;
import org.freedesktop.dbus.types.Variant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.welie.blessed.BluetoothPeripheral.*;
import static com.welie.blessed.ConnectionState.CONNECTED;
import static java.lang.Thread.sleep;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;

/**
 * Represents a Bluetooth Central object
 */
public class BluetoothCentralManager {
    private static final String TAG = BluetoothCentralManager.class.getSimpleName();
    private final Logger logger = LoggerFactory.getLogger(TAG);

    @NotNull
    private final BluezAdapter adapter;

    @NotNull
    private final BluetoothCentralManagerCallback bluetoothCentralManagerCallback;

    @NotNull
    private final Handler callBackHandler = new Handler("Central-callback");

    @NotNull
    private final Handler queueHandler = new Handler("Central-queue");

    @NotNull
    private final Handler signalHandler = new Handler("Central-signal");

    @Nullable
    private ScheduledFuture<?> timeoutFuture;

    protected volatile boolean isScanning = false;
    private volatile boolean isPowered = false;
    private volatile boolean isStoppingScan = false;
    private volatile boolean autoScanActive = false;
    private volatile boolean normalScanActive = false;
    private volatile boolean commandQueueBusy;

    @NotNull
    protected final Map<DiscoveryFilter, Object> scanFilters = new EnumMap<>(DiscoveryFilter.class);

    @NotNull
    protected final Queue<Runnable> commandQueue = new ConcurrentLinkedQueue<>();

    @NotNull
    private String currentCommand = "";

    @NotNull
    private String currentDeviceAddress = "";

    @NotNull
    private final Map<String, BluetoothPeripheral> connectedPeripherals = new ConcurrentHashMap<>();

    @NotNull
    private final Map<String, BluetoothPeripheral> unconnectedPeripherals = new ConcurrentHashMap<>();

    @NotNull
    private final Map<String, BluezDevice> scannedBluezDevices = new ConcurrentHashMap<>();

    @NotNull
    private final Map<String, BluetoothPeripheral> scannedPeripherals = new ConcurrentHashMap<>();

    @NotNull
    private final Map<String, ScanResult> scanResultCache = new ConcurrentHashMap<>();

    @NotNull
    protected Set<String> scanPeripheralNames = new HashSet<>();

    @NotNull
    protected Set<String> scanPeripheralAddresses = new HashSet<>();

    @NotNull
    protected Set<UUID> scanServiceUUIDs = new HashSet<>();

    @NotNull
    protected final List<String> reconnectPeripheralAddresses = new ArrayList<>();

    @NotNull
    protected final Map<String, BluetoothPeripheralCallback> reconnectCallbacks = new ConcurrentHashMap<>();

    @NotNull
    protected final Map<String, String> pinCodes = new ConcurrentHashMap<>();

    @NotNull
    private final Set<String> scanOptions;

    private static final int ADDRESS_LENGTH = 17;
    static final short DISCOVERY_RSSI_THRESHOLD = -80;

    // Scan in intervals. Make sure it is less than 10seconds to avoid issues with
    // Bluez internal scanning
    private static final long SCAN_WINDOW = TimeUnit.SECONDS.toMillis(6);
    private static final long SCAN_INTERVAL = TimeUnit.SECONDS.toMillis(8);
    protected static final long CONNECT_DELAY = TimeUnit.MILLISECONDS.toMillis(500);

    // Null check errors
    private static final String NULL_PERIPHERAL_ERROR = "no valid peripheral specified";

    // Bluez Adapter property strings
    static final String PROPERTY_DISCOVERING = "Discovering";
    static final String PROPERTY_POWERED = "Powered";

    // Bluez interface names
    protected static final String BLUEZ_ADAPTER_INTERFACE = "org.bluez.Adapter1";

    private static final String ENQUEUE_ERROR = "ERROR: Could not enqueue stop scanning command";

    // Scan options
    public static final String SCANOPTION_NO_NULL_NAMES = "ScanOption.NoNullNames";

    /**
     * Avoid auto restart scanning
     */
    public static final String SCANOPTION_NO_AUTORESTART = "ScanOption.NoAutoRestart";

    /**
     * Disable Filter duplicate data
     */
    public static final String SCANOPTION_DISABLE_FILTER_DUPLICATE_DATA = "ScanOption.DisableFilterDuplicateData";

    private final InternalCallback internalCallback = new InternalCallback() {
        @Override
        public void connected(@NotNull final BluetoothPeripheral peripheral) {
            final String peripheralAddress = peripheral.getAddress();
            connectedPeripherals.put(peripheralAddress, peripheral);
            unconnectedPeripherals.remove(peripheralAddress);
            scannedPeripherals.remove(peripheralAddress);

            completeConnectOrDisconnectCommand(peripheralAddress);

            callBackHandler.post(() -> {
                bluetoothCentralManagerCallback.onConnectedPeripheral(peripheral);
            });
        }

        @Override
        public void servicesDiscovered(@NotNull final BluetoothPeripheral peripheral) {
            restartScannerIfNeeded();
        }

        @Override
        public void serviceDiscoveryFailed(@NotNull final BluetoothPeripheral peripheral) {
            logger.info("Service discovery failed");
            if (peripheral.isPaired()) {
                callBackHandler.postDelayed(() -> removeDevice(peripheral), 200L);
            }
        }

        @Override
        public void connectFailed(@NotNull final BluetoothPeripheral peripheral, @NotNull final BluetoothCommandStatus status) {
            final String peripheralAddress = peripheral.getAddress();
            connectedPeripherals.remove(peripheralAddress);
            unconnectedPeripherals.remove(peripheralAddress);
            scannedPeripherals.remove(peripheralAddress);

            // Complete the 'connect' command if this was the device we were connecting
            completeConnectOrDisconnectCommand(peripheralAddress);

            callBackHandler.post(() -> bluetoothCentralManagerCallback.onConnectionFailed(peripheral, status));

            restartScannerIfNeeded();
        }

        @Override
        public void disconnected(@NotNull final BluetoothPeripheral peripheral, @NotNull final BluetoothCommandStatus status) {
            final String peripheralAddress = peripheral.getAddress();
            connectedPeripherals.remove(peripheralAddress);
            unconnectedPeripherals.remove(peripheralAddress);
            scannedPeripherals.remove(peripheralAddress);

            completeConnectOrDisconnectCommand(peripheralAddress);

            // Remove unbonded devices from DBus to make setting notifications work on
            // reconnection (Bluez issue)
            if (!peripheral.isPaired()) {
                removeDevice(peripheral);
            }

            callBackHandler.post(() -> bluetoothCentralManagerCallback.onDisconnectedPeripheral(peripheral, status));

            restartScannerIfNeeded();
        }

        private void restartScannerIfNeeded() {
            if (autoScanActive || normalScanActive) {
                startScanning();
            }
        }

        private void completeConnectOrDisconnectCommand(@NotNull final String deviceAddress) {
            // Complete the 'connect' command if this was the device we were connecting
            if (currentCommand.equalsIgnoreCase(PROPERTY_CONNECTED) && deviceAddress.equalsIgnoreCase(currentDeviceAddress)) {
                completedCommand();
            }
        }
    };

    /**
     * Construct a new BluetoothCentral object
     *
     * @param bluetoothCentralManagerCallback the callback to call for updates
     */
    public BluetoothCentralManager(@NotNull final BluetoothCentralManagerCallback bluetoothCentralManagerCallback) {
        this(bluetoothCentralManagerCallback, Collections.emptySet());
    }

    public BluetoothCentralManager(@NotNull final BluetoothCentralManagerCallback bluetoothCentralManagerCallback, @NotNull final Set<String> scanOptions) {
        this(bluetoothCentralManagerCallback, scanOptions, new BluezAdapterProvider().adapter);
    }

    BluetoothCentralManager(@NotNull final BluetoothCentralManagerCallback bluetoothCentralManagerCallback, @NotNull final Set<String> scanOptions, @NotNull final BluezAdapter bluezAdapter) {
        this.bluetoothCentralManagerCallback = Objects.requireNonNull(bluetoothCentralManagerCallback, "no valid bluetoothCallback provided");
        this.scanOptions = Objects.requireNonNull(scanOptions, "no scanOptions provided");
        this.adapter = Objects.requireNonNull(bluezAdapter, "no bluez adapter provided");

        logger.info(String.format("using adapter %s", adapter.getDeviceName()));

        // Make sure the adapter is powered on
        isPowered = adapter.isPowered();
        if (!isPowered) {
            logger.info("adapter not on, so turning it on now");
            adapterOn();
        }

        try {
            setupPairingAgent();
            BluezSignalHandler.getInstance().addCentral(this);
        } catch (Exception ignore) {
        }
    }

    private void setupPairingAgent() throws BluezInvalidArgumentsException, BluezAlreadyExistsException, BluezDoesNotExistException {
        // Setup pairing agent
        PairingAgent agent = new PairingAgent("/test/agent", adapter.getDBusConnection(), new PairingDelegate() {
            @Override
            public String requestPassCode(@NotNull final String deviceAddress) {
                logger.info(String.format("received passcode request for %s", deviceAddress));

                // See if we have a pass code for this device
                String passCode = pinCodes.get(deviceAddress);

                // If we don't have one, ask the application for a pass code
                if (passCode == null) {
                    passCode = bluetoothCentralManagerCallback.onPinRequest(getPeripheral(deviceAddress));
                }
                logger.info(String.format("sending passcode %s", passCode));
                return passCode;
            }

            @Override
            public void onPairingStarted(@NotNull final String deviceAddress) {
                BluetoothPeripheral peripheral = getPeripheral(deviceAddress);
                peripheral.gattCallback.onPairingStarted();
            }
        });

        BluezAgentManager agentManager = DbusHelper.getBluezAgentManager(adapter.getDBusConnection());
        if (agentManager != null) {
//                The capability parameter can have the values
//                "DisplayOnly", "DisplayYesNo", "KeyboardOnly",
//                "NoInputNoOutput" and "KeyboardDisplay" which
//                reflects the input and output capabilities of the
//                agent.
            agentManager.registerAgent(agent, "KeyboardOnly");
            agentManager.requestDefaultAgent(agent);
        }
    }

    /**
     * Scan for any peripheral that is advertising.
     */
    public void scanForPeripherals() {
        // Stop the current scan if it is active
        isScanning = adapter.isDiscovering();
        if (isScanning)
            stopScan();

        // Start unfiltered BLE scan
        normalScanActive = true;
        resetScanFilters();
        startScanning();
    }

    /**
     * Scan for peripherals that advertise at least one of the specified service
     * UUIDs.
     *
     * @param serviceUUIDs an array of service UUIDs
     */
    public void scanForPeripheralsWithServices(@NotNull final UUID[] serviceUUIDs) {
        Objects.requireNonNull(serviceUUIDs, "no service UUIDs supplied");

        // Make sure there is at least 1 service UUID in the list
        if (serviceUUIDs.length == 0) {
            throw new IllegalArgumentException("at least one service UUID  must be supplied");
        }

        // Stop the current scan if it is active
        isScanning = adapter.isDiscovering();
        if (isScanning)
            stopScan();

        // Store serviceUUIDs to scan for and start scan
        resetScanFilters();
        scanServiceUUIDs = new HashSet<>(Arrays.asList(serviceUUIDs));
        normalScanActive = true;
        startScanning();
    }

    /**
     * Scan for peripherals with advertisement names containing any of the specified
     * peripheral names.
     * <p>
     * Substring matching is used so only a partial peripheral names has to be
     * supplied.
     *
     * @param peripheralNames array of partial peripheral names
     */
    public void scanForPeripheralsWithNames(@NotNull final String[] peripheralNames) {
        Objects.requireNonNull(peripheralNames, "no peripheral names supplied");

        // Make sure there is at least 1 peripheral name in the list
        if (peripheralNames.length == 0) {
            throw new IllegalArgumentException("at least one peripheral name must be supplied");
        }

        // Stop the current scan if it is active
        isScanning = adapter.isDiscovering();
        if (isScanning)
            stopScan();

        // Store peripheral names to scan for and start scan
        resetScanFilters();
        scanPeripheralNames = new HashSet<>(Arrays.asList(peripheralNames));
        normalScanActive = true;
        startScanning();
    }

    /**
     * Scan for peripherals that have any of the specified peripheral mac addresses.
     *
     * @param peripheralAddresses array of peripheral mac addresses to scan for
     */
    public void scanForPeripheralsWithAddresses(@NotNull final String[] peripheralAddresses) {
        Objects.requireNonNull(peripheralAddresses, "no peripheral addresses supplied");

        // Make sure there is at least 1 filter in the list
        if (peripheralAddresses.length == 0) {
            throw new IllegalArgumentException("at least one peripheral address must be supplied");
        }

        // Stop the current scan if it is active
        isScanning = adapter.isDiscovering();
        if (isScanning)
            stopScan();

        // Store peripheral address to scan for and start scan
        resetScanFilters();
        scanPeripheralAddresses = new HashSet<>(Arrays.asList(peripheralAddresses));
        normalScanActive = true;
        startScanning();
    }

    /**
     * Stop scanning for peripherals.
     */
    public void stopScan() {
        normalScanActive = false;
        stopScanning();
    }

    private void resetScanFilters() {
        scanPeripheralNames = new HashSet<>();
        scanPeripheralAddresses = new HashSet<>();
        scanServiceUUIDs = new HashSet<>();
        scanFilters.clear();
        setBasicFilters();
    }

    private void setBasicFilters() {
        scanFilters.put(DiscoveryFilter.Transport, DiscoveryTransport.LE);
        scanFilters.put(DiscoveryFilter.RSSI, DISCOVERY_RSSI_THRESHOLD);
        if (this.scanOptions.contains(SCANOPTION_DISABLE_FILTER_DUPLICATE_DATA)) {
            scanFilters.put(DiscoveryFilter.DuplicateData, false);
        } else {
            scanFilters.put(DiscoveryFilter.DuplicateData, true);
        }

    }

    private boolean notAllowedByFilter(@NotNull final ScanResult scanResult) {
        if (!scanPeripheralNames.isEmpty() && scanResult.getName() != null) {
            return !scanPeripheralNames.contains(scanResult.getName());
        }

        if (!scanPeripheralAddresses.isEmpty()) {
            return !scanPeripheralAddresses.contains(scanResult.getAddress());
        }

        if (!scanServiceUUIDs.isEmpty()) {
            List<UUID> scanResultUUIDs = scanResult.getUuids();
            for (UUID uuid : scanServiceUUIDs) {
                if (scanResultUUIDs.contains(uuid)) {
                    return false;
                }
            }
            return true;
        }

        // No filter set
        return false;
    }

    private void onFoundReconnectionPeripheral(@NotNull final BluetoothPeripheral peripheral) {
        final String peripheralAddress = peripheral.getAddress();
        final BluetoothPeripheralCallback peripheralCallback = reconnectCallbacks.get(peripheralAddress);

        logger.info(String.format("found peripheral to autoconnect '%s'", peripheralAddress));
        autoScanActive = false;
        stopScanning();

        reconnectPeripheralAddresses.remove(peripheralAddress);
        reconnectCallbacks.remove(peripheralAddress);
        unconnectedPeripherals.remove(peripheralAddress);

        // Make sure we have a valid BluezDevice object and refresh the name
        if (peripheral.getDevice() == null) {
            peripheral.setDevice(Objects.requireNonNull(getDeviceByAddress(peripheralAddress)));
            if (peripheral.getDevice() != null) {
                peripheral.setName(peripheral.getDevice().getName());
            }
        }

        connectPeripheral(peripheral, peripheralCallback);

        if (!reconnectPeripheralAddresses.isEmpty()) {
            autoScanActive = true;
            startScanning();
        } else if (normalScanActive) {
            startScanning();
        }
    }

    private void onScanResult(@NotNull final BluetoothPeripheral peripheral, @NotNull final ScanResult scanResult) {
        // Check first if we are autoconnecting to this peripheral
        if (reconnectPeripheralAddresses.contains(scanResult.getAddress())) {
            onFoundReconnectionPeripheral(peripheral);
            return;
        }

        if (normalScanActive && isScanning && !isStoppingScan) {
            // Implement SCANOPTION_NO_NULL_NAMES
            if (scanOptions.contains(SCANOPTION_NO_NULL_NAMES) && (scanResult.getName() == null))
                return;

            if (notAllowedByFilter(scanResult))
                return;

            callBackHandler.post(() -> {
                scanResult.stamp();
                bluetoothCentralManagerCallback.onDiscoveredPeripheral(peripheral, scanResult);
            });
        }
    }

    void handleInterfaceAddedForDevice(@NotNull final String path, @NotNull final Map<String, Variant<?>> value) {
        final String deviceAddress;
        final String deviceName;
        final int rssi;
        ArrayList<@NotNull String> serviceUUIDs = null;

        // Grab address
        if ((value.get(PROPERTY_ADDRESS) != null) && (value.get(PROPERTY_ADDRESS).getValue() instanceof String)) {
            deviceAddress = (String) value.get(PROPERTY_ADDRESS).getValue();
        } else {
            // There MUST be an address, so if not bail out...
            return;
        }

        // Get the device
        final BluezDevice device = getDeviceByAddress(deviceAddress);
        if (device == null) {
            // Something is very wrong, don't handle this signal
            return;
        }

        // Grab name
        if ((value.get(PROPERTY_NAME) != null) && (value.get(PROPERTY_NAME).getValue() instanceof String)) {
            deviceName = (String) value.get(PROPERTY_NAME).getValue();
        } else {
            deviceName = null;
        }

        // Grab service UUIDs
        if ((value.get(PROPERTY_SERVICE_UUIDS) != null) && (value.get(PROPERTY_SERVICE_UUIDS).getValue() instanceof ArrayList)) {
            serviceUUIDs = (ArrayList) value.get(PROPERTY_SERVICE_UUIDS).getValue();
        }

        // Grab RSSI
        if ((value.get(PROPERTY_RSSI) != null) && (value.get(PROPERTY_RSSI).getValue() instanceof Short)) {
            rssi = (Short) value.get(PROPERTY_RSSI).getValue();
        } else {
            rssi = DISCOVERY_RSSI_THRESHOLD;
        }

        // Convert the service UUIDs
        final List<@NotNull UUID> finalServiceUUIDs = new ArrayList<>();
        if (serviceUUIDs != null) {
            serviceUUIDs.stream().map(UUID::fromString).forEach(finalServiceUUIDs::add);
        }

        // Get manufacturer data
        final Map<Integer, byte[]> manufacturerData = new HashMap<>();
        if ((value.get(PROPERTY_MANUFACTURER_DATA) != null) && (value.get(PROPERTY_MANUFACTURER_DATA).getValue() instanceof Map)) {
            final DBusMap<UInt16, Variant<byte[]>> mdata = (DBusMap) value.get(PROPERTY_MANUFACTURER_DATA).getValue();
            mdata.forEach((k, v) -> manufacturerData.put(k.intValue(), v.getValue()));
        }

        // Get service data
        final Map<String, byte[]> serviceData = new HashMap<>();
        if ((value.get(PROPERTY_SERVICE_DATA) != null) && (value.get(PROPERTY_SERVICE_DATA).getValue() instanceof Map)) {
            final DBusMap<String, Variant<byte[]>> sdata = (DBusMap) value.get(PROPERTY_SERVICE_DATA).getValue();
            sdata.forEach((k, v) -> serviceData.put(k, v.getValue()));
        }

        // Create ScanResult
        final ScanResult scanResult = new ScanResult(deviceName, deviceAddress, finalServiceUUIDs, rssi, manufacturerData, serviceData);
        final BluetoothPeripheral peripheral = getPeripheral(deviceAddress);
        scanResultCache.put(deviceAddress, scanResult);
        onScanResult(peripheral, scanResult);
    }

    private final AbstractPropertiesChangedHandler propertiesChangedHandler = new AbstractPropertiesChangedHandler() {
        @Override
        public void handle(final Properties.PropertiesChanged propertiesChanged) {
            switch (propertiesChanged.getInterfaceName()) {
                case BLUEZ_DEVICE_INTERFACE:
                    // If we are not scanning, we ignore device propertiesChanged
                    if ((!isScanning) || isStoppingScan)
                        return;

                    // Get the BluezDevice object
                    final BluezDevice bluezDevice = getDeviceByPath(propertiesChanged.getPath());
                    if (bluezDevice == null)
                        return;

                    // Handle the propertiesChanged object
                    handlePropertiesChangedForDeviceWhenScanning(bluezDevice, propertiesChanged.getPropertiesChanged());
                    break;
                case BLUEZ_ADAPTER_INTERFACE:
                    propertiesChanged.getPropertiesChanged().forEach((propertyName, value) -> handlePropertiesChangedForAdapter(propertyName, value));
                    break;
                default:
            }
        }
    };

    void handleSignal(@NotNull final Properties.PropertiesChanged propertiesChanged) {
        signalHandler.post(() -> propertiesChangedHandler.handle(propertiesChanged));
    }

    @SuppressWarnings("unchecked")
    private void handlePropertiesChangedForDeviceWhenScanning(@NotNull final BluezDevice bluezDevice, @NotNull final Map<String, Variant<?>> propertiesChanged) {
        Objects.requireNonNull(bluezDevice, "no valid bluezDevice supplied");
        Objects.requireNonNull(propertiesChanged, "no valid propertieschanged supplied");
        final String deviceAddress = bluezDevice.getAddress();
        if (deviceAddress == null)
            return;

        // Check if the properties are belonging to a scan
        Set<String> keys = propertiesChanged.keySet();
        if (!(keys.contains(PROPERTY_RSSI) || keys.contains(PROPERTY_MANUFACTURER_DATA) || keys.contains(PROPERTY_SERVICE_DATA)))
            return;

        // See if we have a cached scanResult, if not create a new one
        ScanResult scanResult = getScanResult(deviceAddress);
        if (scanResult == null) {
            scanResult = getScanResultFromDevice(bluezDevice);
            scanResultCache.put(deviceAddress, scanResult);
        }

        updateScanResult(propertiesChanged, scanResult);

        final BluetoothPeripheral peripheral = getPeripheral(deviceAddress);
        onScanResult(peripheral, scanResult);
    }

    private void updateScanResult(@NotNull final Map<String, Variant<?>> propertiesChanged, @NotNull final ScanResult scanResult) {
        // Update the scanResult
        Set<String> keys = propertiesChanged.keySet();
        if (keys.contains(PROPERTY_RSSI)) {
            scanResult.setRssi((Short) propertiesChanged.get(PROPERTY_RSSI).getValue());
        }

        if (keys.contains(PROPERTY_MANUFACTURER_DATA)) {
            final Map<Integer, byte[]> manufacturerData = new HashMap<>();
            final DBusMap<UInt16, Variant<byte[]>> mdata = (DBusMap<UInt16, Variant<byte[]>>) propertiesChanged.get(PROPERTY_MANUFACTURER_DATA).getValue();
            mdata.forEach((k, v) -> manufacturerData.put(k.intValue(), v.getValue()));
            scanResult.setManufacturerData(manufacturerData);
        }

        if (keys.contains(PROPERTY_SERVICE_DATA)) {
            final Map<String, byte[]> serviceData = new HashMap<>();
            final DBusMap<String, Variant<byte[]>> sdata = (DBusMap<String, Variant<byte[]>>) propertiesChanged.get(PROPERTY_SERVICE_DATA).getValue();
            sdata.forEach((k, v) -> serviceData.put(k, v.getValue()));
            scanResult.setServiceData(serviceData);
        }
    }

    @NotNull
    private ScanResult getScanResultFromDevice(@NotNull final BluezDevice bluezDevice) {
        Objects.requireNonNull(bluezDevice, "no valid bluezDevice supplied");

        final String deviceName = bluezDevice.getName();
        final String deviceAddress = bluezDevice.getAddress();
        final List<@NotNull UUID> uuids = bluezDevice.getUuids();
        final Short rssi = bluezDevice.getRssi();
        final int rssiInt = rssi == null ? DISCOVERY_RSSI_THRESHOLD : rssi;
        final Map<@NotNull Integer, byte[]> manufacturerData = bluezDevice.getManufacturerData();
        final Map<@NotNull String, byte[]> serviceData = bluezDevice.getServiceData();
        return new ScanResult(deviceName, deviceAddress, uuids, rssiInt, manufacturerData, serviceData);
    }

    private void handlePropertiesChangedForAdapter(@NotNull final String propertyName, @NotNull final Variant<?> value) {
        switch (propertyName) {
            case PROPERTY_DISCOVERING:
                isScanning = (Boolean) value.getValue();
                logger.info(String.format("scan %s", isScanning ? "started" : "stopped"));

                if (isScanning) {
                    isStoppingScan = false;
                    if (this.scanOptions.contains(SCANOPTION_DISABLE_FILTER_DUPLICATE_DATA)) {
                        invokeCmdToScanDuplicateData();
                    }

                    callBackHandler.post(bluetoothCentralManagerCallback::onScanStarted);
                } else {
                    scannedPeripherals.clear();
                    scannedBluezDevices.clear();
                    scanResultCache.clear();
                    callBackHandler.post(bluetoothCentralManagerCallback::onScanStopped);
                }

                if (currentCommand.equalsIgnoreCase(PROPERTY_DISCOVERING)) {
                    callBackHandler.postDelayed(this::completedCommand, 100L);
                }
                break;
            case PROPERTY_POWERED:
                isPowered = (Boolean) value.getValue();
                logger.info(String.format("powered %s", isPowered ? "on" : "off"));

                if (currentCommand.equalsIgnoreCase(PROPERTY_POWERED)) {
                    callBackHandler.postDelayed(this::completedCommand, 100L);
                }
                break;
            default:
        }
    }

    private void setScanFilter(@NotNull final Map<DiscoveryFilter, Object> filter) throws BluezInvalidArgumentsException, BluezNotReadyException, BluezNotSupportedException, BluezFailedException {
        Map<String, Variant<?>> filters = new LinkedHashMap<>();
        for (Map.Entry<DiscoveryFilter, Object> entry : filter.entrySet()) {
            if (!entry.getKey().getValueClass().isInstance(entry.getValue())) {
                throw new BluezInvalidArgumentsException("Filter value not of required type " + entry.getKey().getValueClass());
            }
            if (entry.getValue() instanceof Enum<?>) {
                filters.put(entry.getKey().name(), new Variant<>(entry.getValue().toString()));
            } else {
                filters.put(entry.getKey().name(), new Variant<>(entry.getValue()));
            }
        }
        adapter.setDiscoveryFilter(filters);
    }

    /*
     * Start a continuous scan with scan filters set to find all devices. This will
     * try to start a scan even if one is running already
     */
    private void startScanning() {
        // Make sure the adapter is on
        if (!isPowered)
            return;

        final boolean result = commandQueue.add(() -> {

            // Just in case, set isStoppingScan to false
            isStoppingScan = false;

            // If we are already scanning then complete the command immediately
            isScanning = adapter.isDiscovering();
            if (isScanning) {
                completedCommand();
                return;
            }

            // Set scan filter. We have to do this before every scan since Bluez doesn't
            // remember this
            try {
                setScanFilter(scanFilters);
            } catch (BluezInvalidArgumentsException | BluezNotReadyException | BluezFailedException | BluezNotSupportedException e) {
                logger.error("Error setting scan filer");
                logger.error(e.toString());
            }

            // Start the discovery
            try {
                currentCommand = PROPERTY_DISCOVERING;
                adapter.startDiscovery();
                if (!this.scanOptions.contains(SCANOPTION_NO_AUTORESTART)) {
                    startScanTimer();
                }
            } catch (BluezFailedException e) {
                logger.error("Could not start discovery (failed)");
                completedCommand();
            } catch (BluezNotReadyException e) {
                logger.error("Could not start discovery (not ready)");
                completedCommand();
            } catch (DBusExecutionException e) {
                // Still need to see what this could be
                logger.error("Error starting scanner");
                logger.error(e.getMessage());
                completedCommand();
            }
        });

        if (result) {
            nextCommand();
        } else {
            logger.error(ENQUEUE_ERROR);
        }

    }

    private void invokeCmdToScanDuplicateData() {
        // this is a very ugly workaround to receive all advertising packets
        String cmd1 = "/usr/bin/hcitool cmd 0x08 0x000C 0x00 0x00";
        String cmd2 = "/usr/bin/hcitool cmd 0x08 0x000C 0x01 0x00";
        // cmd = "ls -l";
        logger.info("Invoking the magic");
        try {
            Process proc;
            proc = Runtime.getRuntime().exec(cmd1);
            proc.waitFor();

            if (proc.exitValue() > 0) {
                BufferedReader br = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
                String error = "";
                String line;
                while ((line = br.readLine()) != null) {
                    error += line;
                }
                logger.warn("Failed to execute the magic: {}", error);
            } else {
                proc.destroy();
                proc = Runtime.getRuntime().exec(cmd2);
                proc.waitFor();

                if (proc.exitValue() > 0) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
                    String error = "";
                    String line;
                    while ((line = br.readLine()) != null) {
                        error += line;
                    }
                    logger.warn("Failed to execute the magic: {}", error);
                } else {

                    /*
                     * BufferedReader br = new BufferedReader(new
                     * InputStreamReader(proc.getInputStream())); String out = ""; String line;
                     * while ((line = br.readLine()) != null) { out += line; }
                     * logger.warn("Magic stout: {}", out);
                     */
                }
                proc.destroy();
            }

        } catch (IOException e) {
            logger.error(e.getMessage());
        } catch (RuntimeException rtex) {
            logger.error(rtex.getMessage());
            rtex.printStackTrace();
        } catch (InterruptedException e) {
            logger.error(e.getMessage());
        }
    }

    /*
     * Stop the scanner
     */
    private void stopScanning() {
        // Make sure the adapter is on
        if (!isPowered)
            return;

        // Set flag to true in order to stop sending scan results
        isStoppingScan = true;

        final boolean result = commandQueue.add(() -> {
            // Check if we are scanning
            isScanning = adapter.isDiscovering();
            if (!isScanning) {
                isStoppingScan = false;
                completedCommand();
                return;
            }

            // Stop the discovery
            try {
                currentCommand = PROPERTY_DISCOVERING;
                cancelTimeoutTimer();
                adapter.stopDiscovery();
            } catch (BluezNotReadyException e) {
                logger.error("Could not stop discovery (not ready)");
                completedCommand();
            } catch (BluezFailedException e) {
                logger.error("Could not stop discovery (failed)");
                completedCommand();
            } catch (BluezNotAuthorizedException e) {
                logger.error("Could not stop discovery (not authorized)");
                completedCommand();
            } catch (DBusExecutionException e) {
                // Usually this is the exception "No discovery started"
                logger.error(e.getMessage());
                if (e.getMessage().equalsIgnoreCase("No discovery started")) {
                    logger.error("Could not stop scan, because we are not scanning!");
                    isStoppingScan = false;
                    isScanning = false; // This shouldn't be needed but seems it is...
                } else if (e.getMessage().equalsIgnoreCase("Operation already in progress")) {
                    logger.error("a stopDiscovery is in progress");
                }
                completedCommand();
            }
        });

        if (result) {
            nextCommand();
        } else {
            logger.error(ENQUEUE_ERROR);
        }
    }

    private void startScanTimer() {
        cancelTimeoutTimer();

        final Runnable timeoutRunnable = () -> {
            stopScanning();
            queueHandler.postDelayed(this::startScanning, SCAN_INTERVAL - SCAN_WINDOW);
        };
        timeoutFuture = queueHandler.postDelayed(timeoutRunnable, SCAN_WINDOW);
    }

    /**
     * Cancel the scan timeout timer
     */
    private void cancelTimeoutTimer() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    /**
     * Turn on the Bluetooth adaptor
     */
    @SuppressWarnings("unused")
    public void adapterOn() {
        final boolean result = commandQueue.add(() -> {

            if (!adapter.isPowered()) {
                logger.info("Turning on adapter");
                currentCommand = PROPERTY_POWERED;
                adapter.setPowered(true);
            } else {
                // If it is already on we won't receive a callback so just complete the command
                logger.info("Adapter already on");
                completedCommand();
            }
        });

        if (result) {
            nextCommand();
        } else {
            logger.error(ENQUEUE_ERROR);
        }
    }

    /**
     * Turn off the Bluetooth adaptor
     */
    @SuppressWarnings("unused")
    public void adapterOff() {
        final boolean result = commandQueue.add(() -> {
            if (adapter.isPowered()) {
                logger.info("Turning off adapter");
                currentCommand = PROPERTY_POWERED;
                adapter.setPowered(false);
            } else {
                // If it is already off we won't receive a callback so just complete the command
                logger.info("Adapter already off");
                completedCommand();
            }
        });

        if (result) {
            nextCommand();
        } else {
            logger.error(ENQUEUE_ERROR);
        }
    }

    /**
     * Connect to a known peripheral immediately. The peripheral must have been
     * found by scanning for this call to succeed. This method will time out in max
     * 30 seconds on most phones and in 5 seconds on Samsung phones. If the
     * peripheral is already connected, no connection attempt will be made. This
     * method is asynchronous and there can be only one outstanding connect.
     *
     * @param peripheral         BLE peripheral to connect with
     * @param peripheralCallback the peripheral callback to use
     */
    public void connectPeripheral(@NotNull final BluetoothPeripheral peripheral, @NotNull final BluetoothPeripheralCallback peripheralCallback) {
        this.connectPeripheral(peripheral, peripheralCallback, true);
    }

    /**
     * Connect to a known peripheral immediately. The peripheral must have been
     * found by scanning for this call to succeed. This method will time out in max
     * 30 seconds on most phones and in 5 seconds on Samsung phones. If the
     * peripheral is already connected, no connection attempt will be made. This
     * method is asynchronous and there can be only one outstanding connect.
     *
     * @param peripheral         BLE peripheral to connect with
     * @param peripheralCallback the peripheral callback to use
     * @param stopScanning       stop scanning if true. Some adapters have issues
     *                           with (dis)connecting while scanning
     */
    public void connectPeripheral(@NotNull final BluetoothPeripheral peripheral, @NotNull final BluetoothPeripheralCallback peripheralCallback, @NotNull boolean stopScanning) {
        Objects.requireNonNull(peripheral, NULL_PERIPHERAL_ERROR);
        Objects.requireNonNull(peripheralCallback, "no valid peripheral callback specified");
        peripheral.setPeripheralCallback(peripheralCallback);

        // Check if we are already connected
        if (connectedPeripherals.containsKey(peripheral.getAddress())) {
            logger.warn(String.format("WARNING: Already connected to %s'", peripheral.getAddress()));
            return;
        }

        // Check if we already have an outstanding connection request for this
        // peripheral
        if (unconnectedPeripherals.containsKey(peripheral.getAddress())) {
            logger.warn(String.format("WARNING: Already connecting to %s'", peripheral.getAddress()));
            return;
        }

        // Make sure we have BluezDevice
        if (peripheral.getDevice() == null) {
            logger.warn(String.format("WARNING: Peripheral '%s' doesn't have Bluez device", peripheral.getAddress()));
            return;
        }

        if (stopScanning) {
            // Some adapters have issues with (dis)connecting while scanning, so stop scan
            // first
            stopScanning();
        }

        unconnectedPeripherals.put(peripheral.getAddress(), peripheral);
        final boolean result = commandQueue.add(() -> {
            try {
                sleep(CONNECT_DELAY);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // Refresh BluezDevice because it may be old
            scannedBluezDevices.remove(adapter.getPath(peripheral.getAddress()));
            BluezDevice bluezDevice = getDeviceByAddress(peripheral.getAddress());
            if (bluezDevice != null) {
                peripheral.setDevice(bluezDevice);
            }

            currentDeviceAddress = peripheral.getAddress();
            currentCommand = PROPERTY_CONNECTED;

            try {
                peripheral.connect();
            } catch (NullPointerException ignored) {
                completedCommand();
            }
        });

        if (result) {
            nextCommand();
        } else {
            logger.error(ENQUEUE_ERROR);
        }
    }

    /**
     * Automatically connect to a peripheral when it is advertising. It is not
     * necessary to scan for the peripheral first. This call is asynchronous and
     * will not time out.
     *
     * @param peripheral         the peripheral
     * @param peripheralCallback the peripheral callback to use
     * @return true if all arguments were valid, otherwise false
     */
    @SuppressWarnings("UnusedReturnValue,unused")
    public boolean autoConnectPeripheral(@NotNull final BluetoothPeripheral peripheral, @NotNull final BluetoothPeripheralCallback peripheralCallback) {
        Objects.requireNonNull(peripheral, NULL_PERIPHERAL_ERROR);
        Objects.requireNonNull(peripheralCallback, "no valid peripheral callback specified");

        final String peripheralAddress = peripheral.getAddress();
        if (reconnectPeripheralAddresses.contains(peripheralAddress))
            return false;

        reconnectPeripheralAddresses.add(peripheralAddress);
        reconnectCallbacks.put(peripheralAddress, peripheralCallback);
        unconnectedPeripherals.put(peripheralAddress, peripheral);

        logger.info(String.format("autoconnect to %s", peripheralAddress));
        startAutoConnectScan();
        return true;
    }

    /**
     * Autoconnect to a batch of peripherals.
     * <p>
     * Use this function to autoConnect to a batch of peripherals, instead of
     * calling autoConnect on each of them.
     *
     * @param batch the map of peripherals and their callbacks to autoconnect to
     */
    @SuppressWarnings("unused")
    public void autoConnectPeripheralsBatch(@NotNull final Map<BluetoothPeripheral, BluetoothPeripheralCallback> batch) {
        Objects.requireNonNull(batch, "no valid batch provided");

        for (Map.Entry<BluetoothPeripheral, BluetoothPeripheralCallback> entry : batch.entrySet()) {
            final String peripheralAddress = entry.getKey().getAddress();
            reconnectPeripheralAddresses.add(peripheralAddress);
            reconnectCallbacks.put(peripheralAddress, entry.getValue());
            unconnectedPeripherals.put(peripheralAddress, entry.getKey());
        }

        if (!reconnectPeripheralAddresses.isEmpty()) {
            startAutoConnectScan();
        }
    }

    private void startAutoConnectScan() {
        autoScanActive = true;
        if (!isScanning) {
            setBasicFilters();
            startScanning();
        }
    }

    /**
     * Cancel an active or pending connection for a peripheral.
     *
     * @param peripheral the peripheral
     */
    @SuppressWarnings("unused")
    public void cancelConnection(@NotNull final BluetoothPeripheral peripheral) {
        Objects.requireNonNull(peripheral, NULL_PERIPHERAL_ERROR);

        if (peripheral.getState() == CONNECTED) {
            // Some adapters have issues with (dis)connecting while scanning, so stop scan
            // first
            stopScanning();

            // Queue the low level disconnect
            final boolean result = commandQueue.add(() -> {
                currentDeviceAddress = peripheral.getAddress();
                currentCommand = PROPERTY_CONNECTED;
                peripheral.disconnectBluezDevice();
            });

            if (result) {
                nextCommand();
            } else {
                logger.error(ENQUEUE_ERROR);
            }
            return;
        }

        // We might be autoconnecting to this peripheral
        final String peripheralAddress = peripheral.getAddress();
        if (reconnectPeripheralAddresses.contains(peripheralAddress)) {
            reconnectPeripheralAddresses.remove(peripheralAddress);
            reconnectCallbacks.remove(peripheralAddress);

            callBackHandler.post(() -> {
                bluetoothCentralManagerCallback.onDisconnectedPeripheral(peripheral, BluetoothCommandStatus.COMMAND_SUCCESS);
            });
        }
    }

    /**
     * Remove bond for a peripheral
     *
     * @param peripheralAddress the address of the peripheral
     * @return true if bond was removed, otherwise false
     */
    @SuppressWarnings("unused")
    public boolean removeBond(@NotNull final String peripheralAddress) {
        Objects.requireNonNull(peripheralAddress, "no peripheral address provided");

        final BluezDevice bluezDevice = getDeviceByAddress(peripheralAddress);
        if (bluezDevice == null)
            return false;
        return removeDevice(bluezDevice);
    }

    /**
     * Get the list of connected peripherals.
     *
     * @return list of connected peripherals
     */
    @SuppressWarnings("unused")
    @NotNull
    public List<BluetoothPeripheral> getConnectedPeripherals() {
        return new ArrayList<>(connectedPeripherals.values());
    }

    /**
     * Get a peripheral object matching the specified mac address.
     *
     * @param peripheralAddress mac address
     * @return a BluetoothPeripheral object matching the specified mac address or
     *         null if it was not found
     */
    @NotNull
    public BluetoothPeripheral getPeripheral(@NotNull final String peripheralAddress) {
        Objects.requireNonNull(peripheralAddress, "no valid peripheral address provided");

        if (!isValidBluetoothAddress(peripheralAddress)) {
            String message = String.format("%s is not a valid address. Make sure all alphabetic characters are uppercase.", peripheralAddress);
            throw new IllegalArgumentException(message);
        }

        if (scannedPeripherals.containsKey(peripheralAddress)) {
            return scannedPeripherals.get(peripheralAddress);
        } else if (connectedPeripherals.containsKey(peripheralAddress)) {
            return connectedPeripherals.get(peripheralAddress);
        } else if (unconnectedPeripherals.containsKey(peripheralAddress)) {
            return unconnectedPeripherals.get(peripheralAddress);
        } else {
            BluezDevice bluezDevice = getDeviceByAddress(peripheralAddress);
            BluetoothPeripheral bluetoothPeripheral = new BluetoothPeripheral(this, bluezDevice, bluezDevice != null ? bluezDevice.getName() : null, peripheralAddress, internalCallback, null,
                    callBackHandler);
            scannedPeripherals.put(peripheralAddress, bluetoothPeripheral);
            return bluetoothPeripheral;
        }
    }

    @Nullable
    private ScanResult getScanResult(@NotNull final String peripheralAddress) {
        return scanResultCache.get(peripheralAddress);
    }

    /**
     * Set a fixed PIN code for a peripheral that asks fir a PIN code during
     * bonding.
     * <p>
     * This PIN code will be used to programmatically bond with the peripheral when
     * it asks for a PIN code. Note that this only works for devices with a fixed
     * PIN code.
     *
     * @param peripheralAddress the address of the peripheral
     * @param pin               the 6 digit PIN code as a string, e.g. "123456"
     * @return true if the pin code and peripheral address are valid and stored
     *         internally
     */
    @SuppressWarnings("UnusedReturnValue,unused")
    public boolean setPinCodeForPeripheral(@NotNull final String peripheralAddress, @NotNull final String pin) {
        Objects.requireNonNull(peripheralAddress, "no peripheral address provided");
        Objects.requireNonNull(pin, "no pin provided");

        if (!isValidBluetoothAddress(peripheralAddress)) {
            logger.error(String.format("%s is not a valid address. Make sure all alphabetic characters are uppercase.", peripheralAddress));
            return false;
        }

        if (pin.length() != 6) {
            logger.error(String.format("%s is not 6 digits long", pin));
            return false;
        }

        pinCodes.put(peripheralAddress, pin);
        return true;
    }

    /**
     * Validate a String Bluetooth address, such as "00:43:A8:23:10:F0"
     * <p>
     * Alphabetic characters must be uppercase to be valid.
     *
     * @param address Bluetooth address as string
     * @return true if the address is valid, false otherwise
     */
    private boolean isValidBluetoothAddress(@NotNull final String address) {
        Objects.requireNonNull(address, "address is null");
        if (address.length() != ADDRESS_LENGTH) {
            return false;
        }

        for (int i = 0; i < ADDRESS_LENGTH; i++) {
            char c = address.charAt(i);
            switch (i % 3) {
                case 0:
                case 1:
                    if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')) {
                        // hex character, OK
                        break;
                    }
                    return false;
                case 2:
                    if (c == ':') {
                        break; // OK
                    }
                    return false;
                default:
                    return false;
            }
        }
        return true;
    }

    /**
     * The current command has been completed, move to the next command in the queue
     * (if any)
     */
    private void completedCommand() {
        commandQueue.poll();
        commandQueueBusy = false;
        currentCommand = "";
        nextCommand();
    }

    /**
     * Execute the next command in the subscribe queue. A queue is used because the
     * calls have to be executed sequentially. If the read or write fails, the next
     * command in the queue is executed.
     */
    private void nextCommand() {
        synchronized (this) {
            // If there is still a command being executed then bail out
            if (commandQueueBusy) {
                return;
            }

            // Execute the next command in the queue
            final Runnable bluetoothCommand = commandQueue.peek();
            if (bluetoothCommand != null) {
                commandQueueBusy = true;

                queueHandler.post(() -> {
                    try {
                        bluetoothCommand.run();
                    } catch (Exception ex) {
                        logger.warn("ERROR: Command exception for central");
                        logger.warn(ex.getMessage());
                        completedCommand();
                    }
                });
            }
        }
    }

    @Nullable
    private BluezDevice getDeviceByPath(@NotNull final String devicePath) {
        Objects.requireNonNull(devicePath, "device path is null");

        BluezDevice bluezDevice = scannedBluezDevices.get(devicePath);
        if (bluezDevice == null) {
            bluezDevice = adapter.getBluezDeviceByPath(devicePath);
            if (bluezDevice != null) {
                scannedBluezDevices.put(devicePath, bluezDevice);
            }
        }
        return bluezDevice;
    }

    @Nullable
    private BluezDevice getDeviceByAddress(@NotNull final String deviceAddress) {
        Objects.requireNonNull(deviceAddress, "device address is null");

        return getDeviceByPath(adapter.getPath(deviceAddress));
    }

    /*
     * Function to clean up device from Bluetooth cache
     */
    protected void removeDevice(@NotNull final BluetoothPeripheral peripheral) {
        BluezDevice bluetoothDevice = getDeviceByAddress(peripheral.getAddress());
        if (bluetoothDevice == null)
            return;

        boolean isBonded = peripheral.isPaired();
        logger.info(String.format("removing peripheral '%s' %s (%s)", peripheral.getName(), peripheral.getAddress(), isBonded ? "BONDED" : "BOND_NONE"));
        removeDevice(bluetoothDevice);
    }

    private boolean removeDevice(@NotNull final BluezDevice bluetoothDevice) {
        try {
            Device1 rawDevice = bluetoothDevice.getRawDevice();
            if (rawDevice != null) {
                adapter.removeDevice(rawDevice);
                return true;
            }
            return false;
        } catch (Exception e) {
            logger.error("Error removing device");
            return false;
        }
    }
}
