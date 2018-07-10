package com.sdl.hellosdlandroid

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import com.smartdevicelink.exception.SdlException
import com.smartdevicelink.proxy.LockScreenManager
import com.smartdevicelink.proxy.RPCRequest
import com.smartdevicelink.proxy.RPCResponse
import com.smartdevicelink.proxy.SdlProxyALM
import com.smartdevicelink.proxy.callbacks.OnServiceEnded
import com.smartdevicelink.proxy.callbacks.OnServiceNACKed
import com.smartdevicelink.proxy.interfaces.IProxyListenerALM
import com.smartdevicelink.proxy.rpc.*
import com.smartdevicelink.proxy.rpc.enums.*
import com.smartdevicelink.proxy.rpc.listeners.OnRPCResponseListener
import com.smartdevicelink.transport.*
import com.smartdevicelink.util.CorrelationIdGenerator
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.*

class SdlService : Service(), IProxyListenerALM {
    private var iconCorrelationId: Int = 0

    private var remoteFiles: List<String>? = null

    // variable to create and call functions of the SyncProxy
    private var proxy: SdlProxyALM? = null

    private var firstNonHmiNone = true
    private var isVehicleDataSubscribed = false

    private var lockScreenUrlFromCore: String? = null
    private val lockScreenManager = LockScreenManager()

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        Log.d(TAG, "onCreate")
        super.onCreate()
        remoteFiles = ArrayList()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterForeground()
        }
    }

    @SuppressLint("NewApi")
    fun enterForeground() {
        val notification = Notification.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Connected through SDL")
                .setSmallIcon(R.drawable.ic_sdl)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .build()
        startForeground(FOREGROUND_SERVICE_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //Check if this was started with a flag to force a transport connect
        val forced = intent != null && intent.getBooleanExtra(TransportConstants.FORCE_TRANSPORT_CONNECTED, false)
        startProxy(forced, intent)

        return Service.START_STICKY
    }

    override fun onDestroy() {
        disposeSyncProxy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun startProxy(forceConnect: Boolean, intent: Intent?) {
        Log.i(TAG, "Trying to start proxy")
        if (proxy == null) {
            try {
                Log.i(TAG, "Starting SDL Proxy")
                var transport: BaseTransportConfig? = null
                if (BuildConfig.TRANSPORT == "MBT") {
                    val securityLevel: Int
                    if (BuildConfig.SECURITY == "HIGH") {
                        securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_HIGH
                    } else if (BuildConfig.SECURITY == "MED") {
                        securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_MED
                    } else if (BuildConfig.SECURITY == "LOW") {
                        securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_LOW
                    } else {
                        securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF
                    }
                    transport = MultiplexTransportConfig(this, APP_ID, securityLevel)
                } else if (BuildConfig.TRANSPORT == "LBT") {
                    transport = BTTransportConfig()
                } else if (BuildConfig.TRANSPORT == "TCP") {
                    transport = TCPTransportConfig(TCP_PORT, DEV_MACHINE_IP_ADDRESS, true)
                } else if (BuildConfig.TRANSPORT == "USB") {
                    if (intent != null && intent.hasExtra(UsbManager.EXTRA_ACCESSORY)) { //If we want to support USB transport
                        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.HONEYCOMB) {
                            Log.e(TAG, "Unable to start proxy. Android OS version is too low")
                            return
                        } else {
                            //We have a usb transport
                            transport = USBTransportConfig(baseContext, intent.getParcelableExtra<Parcelable>(UsbManager.EXTRA_ACCESSORY) as UsbAccessory)
                            Log.d(TAG, "USB created.")
                        }
                    }
                }
                if (transport != null) {
                    proxy = SdlProxyALM(this, APP_NAME, true, APP_ID, transport)
                }
            } catch (e: SdlException) {
                e.printStackTrace()
                // error creating proxy, returned proxy = null
                if (proxy == null) {
                    stopSelf()
                }
            }

        } else if (forceConnect) {
            proxy!!.forceOnConnected()
        }
    }

    private fun disposeSyncProxy() {
        LockScreenActivity.updateLockScreenStatus(LockScreenStatus.OFF)

        if (proxy != null) {
            try {
                proxy!!.dispose()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                proxy = null
            }
        }
        this.firstNonHmiNone = true
        this.isVehicleDataSubscribed = false

    }

    /**
     * Will show a sample test message on screen as well as speak a sample test message
     */
    private fun showTest() {
        try {
            proxy!!.show(TEST_COMMAND_NAME, "Command has been selected", TextAlignment.CENTERED, CorrelationIdGenerator.generateId())
            proxy!!.speak(TEST_COMMAND_NAME, CorrelationIdGenerator.generateId())
        } catch (e: SdlException) {
            e.printStackTrace()
        }

    }

    /**
     * Add commands for the app on SDL.
     */
    private fun sendCommands() {
        val command = AddCommand()
        val params = MenuParams()
        params.menuName = TEST_COMMAND_NAME
        command.cmdID = TEST_COMMAND_ID
        command.menuParams = params
        command.vrCommands = listOf(TEST_COMMAND_NAME)
        sendRpcRequest(command)
    }

    /**
     * Sends an RPC Request to the connected head unit. Automatically adds a correlation id.
     * @param request the rpc request that is to be sent to the module
     */
    private fun sendRpcRequest(request: RPCRequest) {
        try {
            proxy!!.sendRPCRequest(request)
        } catch (e: SdlException) {
            e.printStackTrace()
        }

    }

    /**
     * Sends the app icon through the uploadImage method with correct params
     */
    private fun sendIcon() {
        iconCorrelationId = CorrelationIdGenerator.generateId()
        uploadImage(R.mipmap.ic_launcher, ICON_FILENAME, iconCorrelationId, true)
    }

    /**
     * This method will help upload an image to the head unit
     * @param resource the R.drawable.__ value of the image you wish to send
     * @param imageName the filename that will be used to reference this image
     * @param correlationId the correlation id to be used with this request. Helpful for monitoring putfileresponses
     * @param isPersistent tell the system if the file should stay or be cleared out after connection.
     */
    private fun uploadImage(resource: Int, imageName: String, correlationId: Int, isPersistent: Boolean) {
        val putFile = PutFile()
        putFile.fileType = FileType.GRAPHIC_PNG
        putFile.sdlFileName = imageName
        putFile.correlationID = correlationId
        putFile.persistentFile = isPersistent
        putFile.systemFile = false
        putFile.bulkData = contentsOfResource(resource)

        try {
            proxy!!.sendRPCRequest(putFile)
        } catch (e: SdlException) {
            e.printStackTrace()
        }

    }

    /**
     * Helper method to take resource files and turn them into byte arrays
     * @param resource Resource file id.
     * @return Resulting byte array.
     */
    private fun contentsOfResource(resource: Int): ByteArray? {
        var input: InputStream? = null
        try {
            input = resources.openRawResource(resource)
            val os = ByteArrayOutputStream(input!!.available())
            val bufferSize = 4096
            val buffer = ByteArray(bufferSize)
            var available: Int
            do {
                available = input.read(buffer)
                if (available > 0) {
                    os.write(buffer, 0, available)
                }
            } while(available >= 0)
            return os.toByteArray()
        } catch (e: IOException) {
            Log.w(TAG, "Can't read icon file", e)
            return null
        } finally {
            if (input != null) {
                try {
                    input.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }

            }
        }
    }

    override fun onProxyClosed(info: String, e: Exception, reason: SdlDisconnectedReason) {
        stopSelf()
        if (reason == SdlDisconnectedReason.LANGUAGE_CHANGE && BuildConfig.TRANSPORT == "MBT") {
            val intent = Intent(TransportConstants.START_ROUTER_SERVICE_ACTION)
            intent.putExtra(SdlReceiver.RECONNECT_LANG_CHANGE, true)
            sendBroadcast(intent)
        }
    }

    override fun onOnHMIStatus(notification: OnHMIStatus) {
        if (notification.hmiLevel == HMILevel.HMI_FULL) {
            if (notification.firstRun!!) {
                // send welcome message if applicable
                performWelcomeMessage()
            }
            // Other HMI (Show, PerformInteraction, etc.) would go here
        }

        if (notification.hmiLevel != HMILevel.HMI_NONE && firstNonHmiNone) {
            sendCommands()
            //uploadImages();
            firstNonHmiNone = false

            // Other app setup (SubMenu, CreateChoiceSet, etc.) would go here
        } else {
            //We have HMI_NONE
            if (notification.firstRun!!) {
                uploadImages()
            }
        }
    }

    /**
     * Listener for handling when a lockscreen image is downloaded.
     */
    private inner class LockScreenDownloadedListener : LockScreenManager.OnLockScreenIconDownloadedListener {

        override fun onLockScreenIconDownloaded(icon: Bitmap) {
            Log.i(TAG, "Lock screen icon downloaded successfully")
            LockScreenActivity.updateLockScreenImage(icon)
        }

        override fun onLockScreenIconDownloadError(e: Exception) {
            Log.e(TAG, "Couldn't download lock screen icon, resorting to default.")
            LockScreenActivity.updateLockScreenImage(BitmapFactory.decodeResource(resources,
                    R.drawable.sdl))
        }
    }

    /**
     * Will show a sample welcome message on screen as well as speak a sample welcome message
     */
    private fun performWelcomeMessage() {
        try {
            val image = Image()
            image.value = SDL_IMAGE_FILENAME
            image.imageType = ImageType.DYNAMIC

            //Set the welcome message on screen
            proxy!!.show(APP_NAME, WELCOME_SHOW, null, null, null, null, null, image, null, null, TextAlignment.CENTERED, CorrelationIdGenerator.generateId())

            //Say the welcome message
            proxy!!.speak(WELCOME_SPEAK, CorrelationIdGenerator.generateId())

        } catch (e: SdlException) {
            e.printStackTrace()
        }

    }

    /**
     * Requests list of images to SDL, and uploads images that are missing.
     */
    private fun uploadImages() {
        val listFiles = ListFiles()
        listFiles.onRPCResponseListener = object : OnRPCResponseListener() {
            override fun onResponse(correlationId: Int, response: RPCResponse) {
                if (response.success!!) {
                    remoteFiles = (response as ListFilesResponse).filenames
                }

                // Check the mutable set for the AppIcon
                // If not present, upload the image
                if (remoteFiles == null || !remoteFiles!!.contains(SdlService.ICON_FILENAME)) {
                    sendIcon()
                } else {
                    // If the file is already present, send the SetAppIcon request
                    try {
                        proxy!!.setappicon(ICON_FILENAME, CorrelationIdGenerator.generateId())
                    } catch (e: SdlException) {
                        e.printStackTrace()
                    }

                }

                // Check the mutable set for the SDL image
                // If not present, upload the image
                if (remoteFiles == null || !remoteFiles!!.contains(SdlService.SDL_IMAGE_FILENAME)) {
                    uploadImage(R.drawable.sdl, SDL_IMAGE_FILENAME, CorrelationIdGenerator.generateId(), true)
                }
            }
        }
        this.sendRpcRequest(listFiles)
    }

    override fun onListFilesResponse(response: ListFilesResponse) {
        Log.i(TAG, "onListFilesResponse from SDL ")
    }

    override fun onPutFileResponse(response: PutFileResponse) {
        Log.i(TAG, "onPutFileResponse from SDL")
        if (response.correlationID == iconCorrelationId) { //If we have successfully uploaded our icon, we want to set it
            try {
                proxy!!.setappicon(ICON_FILENAME, CorrelationIdGenerator.generateId())
            } catch (e: SdlException) {
                e.printStackTrace()
            }

        }
    }

    override fun onOnLockScreenNotification(notification: OnLockScreenStatus) {
        LockScreenActivity.updateLockScreenStatus(notification.showLockScreen)
    }

    override fun onOnCommand(notification: OnCommand) {
        val id = notification.cmdID
        if (id != null) {
            when (id) {
                TEST_COMMAND_ID -> showTest()
            }
        }
    }

    /**
     * Callback method that runs when the add command response is received from SDL.
     */
    override fun onAddCommandResponse(response: AddCommandResponse) {
        Log.i(TAG, "AddCommand response from SDL: " + response.resultCode.name)

    }

    /*  Vehicle Data   */
    override fun onOnPermissionsChange(notification: OnPermissionsChange) {
        Log.i(TAG, "Permision changed: " + notification)

        /* Uncomment to subscribe to vehicle data
		List<PermissionItem> permissions = notification.getPermissionItem();
		for(PermissionItem permission:permissions){
			if(permission.getRpcName().equalsIgnoreCase(FunctionID.SUBSCRIBE_VEHICLE_DATA.name())){
				if(permission.getHMIPermissions().getAllowed()!=null && permission.getHMIPermissions().getAllowed().size()>0){
					if(!isVehicleDataSubscribed){ //If we haven't already subscribed we will subscribe now
						//TODO: Add the vehicle data items you want to subscribe to
						//proxy.subscribevehicledata(gps, speed, rpm, fuelLevel, fuelLevel_State, instantFuelConsumption, externalTemperature, prndl, tirePressure, odometer, beltStatus, bodyInformation, deviceStatus, driverBraking, correlationID);
						proxy.subscribevehicledata(false, true, rpm, false, false, false, false, false, false, false, false, false, false, false, autoIncCorrId++);
					}
				}
			}
		}
		*/
    }

    /**
     * Rest of the SDL callbacks from the head unit
     */

    override fun onSubscribeVehicleDataResponse(response: SubscribeVehicleDataResponse) {
        if (response.success!!) {
            Log.i(TAG, "Subscribed to vehicle data")
            this.isVehicleDataSubscribed = true
        }
    }

    override fun onOnVehicleData(notification: OnVehicleData) {
        Log.i(TAG, "Vehicle data notification from SDL")
        //TODO Put your vehicle data code here
        //ie, notification.getSpeed().
    }

    override fun onAddSubMenuResponse(response: AddSubMenuResponse) {
        Log.i(TAG, "AddSubMenu response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onCreateInteractionChoiceSetResponse(response: CreateInteractionChoiceSetResponse) {
        Log.i(TAG, "CreateInteractionChoiceSet response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onAlertResponse(response: AlertResponse) {
        Log.i(TAG, "Alert response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onDeleteCommandResponse(response: DeleteCommandResponse) {
        Log.i(TAG, "DeleteCommand response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onDeleteInteractionChoiceSetResponse(response: DeleteInteractionChoiceSetResponse) {
        Log.i(TAG, "DeleteInteractionChoiceSet response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onDeleteSubMenuResponse(response: DeleteSubMenuResponse) {
        Log.i(TAG, "DeleteSubMenu response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onPerformInteractionResponse(response: PerformInteractionResponse) {
        Log.i(TAG, "PerformInteraction response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onResetGlobalPropertiesResponse(
            response: ResetGlobalPropertiesResponse) {
        Log.i(TAG, "ResetGlobalProperties response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onSetGlobalPropertiesResponse(response: SetGlobalPropertiesResponse) {
        Log.i(TAG, "SetGlobalProperties response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onSetMediaClockTimerResponse(response: SetMediaClockTimerResponse) {
        Log.i(TAG, "SetMediaClockTimer response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onShowResponse(response: ShowResponse) {
        Log.i(TAG, "Show response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onSpeakResponse(response: SpeakResponse) {
        Log.i(TAG, "SpeakCommand response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onOnButtonEvent(notification: OnButtonEvent) {
        Log.i(TAG, "OnButtonEvent notification from SDL: " + notification)
    }

    override fun onOnButtonPress(notification: OnButtonPress) {
        Log.i(TAG, "OnButtonPress notification from SDL: " + notification)
    }

    override fun onSubscribeButtonResponse(response: SubscribeButtonResponse) {
        Log.i(TAG, "SubscribeButton response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onUnsubscribeButtonResponse(response: UnsubscribeButtonResponse) {
        Log.i(TAG, "UnsubscribeButton response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onOnTBTClientState(notification: OnTBTClientState) {
        Log.i(TAG, "OnTBTClientState notification from SDL: " + notification)
    }

    override fun onUnsubscribeVehicleDataResponse(
            response: UnsubscribeVehicleDataResponse) {
        Log.i(TAG, "UnsubscribeVehicleData response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onGetVehicleDataResponse(response: GetVehicleDataResponse) {
        Log.i(TAG, "GetVehicleData response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onReadDIDResponse(response: ReadDIDResponse) {
        Log.i(TAG, "ReadDID response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onGetDTCsResponse(response: GetDTCsResponse) {
        Log.i(TAG, "GetDTCs response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onPerformAudioPassThruResponse(response: PerformAudioPassThruResponse) {
        Log.i(TAG, "PerformAudioPassThru response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onEndAudioPassThruResponse(response: EndAudioPassThruResponse) {
        Log.i(TAG, "EndAudioPassThru response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onOnAudioPassThru(notification: OnAudioPassThru) {
        Log.i(TAG, "OnAudioPassThru notification from SDL: " + notification)
    }

    override fun onDeleteFileResponse(response: DeleteFileResponse) {
        Log.i(TAG, "DeleteFile response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onSetAppIconResponse(response: SetAppIconResponse) {
        Log.i(TAG, "SetAppIcon response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onScrollableMessageResponse(response: ScrollableMessageResponse) {
        Log.i(TAG, "ScrollableMessage response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onChangeRegistrationResponse(response: ChangeRegistrationResponse) {
        Log.i(TAG, "ChangeRegistration response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onSetDisplayLayoutResponse(response: SetDisplayLayoutResponse) {
        Log.i(TAG, "SetDisplayLayout response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onOnLanguageChange(notification: OnLanguageChange) {
        Log.i(TAG, "OnLanguageChange notification from SDL: " + notification)
    }

    override fun onSliderResponse(response: SliderResponse) {
        Log.i(TAG, "Slider response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onOnHashChange(notification: OnHashChange) {
        Log.i(TAG, "OnHashChange notification from SDL: " + notification)
    }

    override fun onOnSystemRequest(notification: OnSystemRequest) {
        Log.i(TAG, "OnSystemRequest notification from SDL: " + notification)

        // Download the lockscreen icon Core desires
        if (notification.requestType == RequestType.LOCK_SCREEN_ICON_URL && lockScreenUrlFromCore == null) {
            lockScreenUrlFromCore = notification.url
            if (lockScreenUrlFromCore != null && lockScreenManager.lockScreenIcon == null) {
                lockScreenManager.downloadLockScreenIcon(lockScreenUrlFromCore, LockScreenDownloadedListener())
            }
        }
    }

    override fun onSystemRequestResponse(response: SystemRequestResponse) {
        Log.i(TAG, "SystemRequest response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onOnKeyboardInput(notification: OnKeyboardInput) {
        Log.i(TAG, "OnKeyboardInput notification from SDL: " + notification)
    }

    override fun onOnTouchEvent(notification: OnTouchEvent) {
        Log.i(TAG, "OnTouchEvent notification from SDL: " + notification)
    }

    override fun onDiagnosticMessageResponse(response: DiagnosticMessageResponse) {
        Log.i(TAG, "DiagnosticMessage response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onOnStreamRPC(notification: OnStreamRPC) {
        Log.i(TAG, "OnStreamRPC notification from SDL: " + notification)
    }

    override fun onStreamRPCResponse(response: StreamRPCResponse) {
        Log.i(TAG, "StreamRPC response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onDialNumberResponse(response: DialNumberResponse) {
        Log.i(TAG, "DialNumber response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onSendLocationResponse(response: SendLocationResponse) {
        Log.i(TAG, "SendLocation response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onServiceEnded(serviceEnded: OnServiceEnded) {

    }

    override fun onServiceNACKed(serviceNACKed: OnServiceNACKed) {

    }

    override fun onShowConstantTbtResponse(response: ShowConstantTbtResponse) {
        Log.i(TAG, "ShowConstantTbt response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onAlertManeuverResponse(response: AlertManeuverResponse) {
        Log.i(TAG, "AlertManeuver response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onUpdateTurnListResponse(response: UpdateTurnListResponse) {
        Log.i(TAG, "UpdateTurnList response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onServiceDataACK(dataSize: Int) {

    }

    override fun onGetWayPointsResponse(response: GetWayPointsResponse) {
        Log.i(TAG, "GetWayPoints response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onSubscribeWayPointsResponse(response: SubscribeWayPointsResponse) {
        Log.i(TAG, "SubscribeWayPoints response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onUnsubscribeWayPointsResponse(response: UnsubscribeWayPointsResponse) {
        Log.i(TAG, "UnsubscribeWayPoints response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onOnWayPointChange(notification: OnWayPointChange) {
        Log.i(TAG, "OnWayPointChange notification from SDL: " + notification)
    }

    override fun onOnDriverDistraction(notification: OnDriverDistraction) {
        // Some RPCs (depending on region) cannot be sent when driver distraction is active.
    }

    override fun onError(info: String, e: Exception) {}

    override fun onGenericResponse(response: GenericResponse) {
        Log.i(TAG, "Generic response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onGetSystemCapabilityResponse(response: GetSystemCapabilityResponse) {
        Log.i(TAG, "GetSystemCapabilityResponse from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onSendHapticDataResponse(response: SendHapticDataResponse) {
        Log.i(TAG, "SendHapticData response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onButtonPressResponse(response: ButtonPressResponse) {
        Log.i(TAG, "ButtonPress response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onSetInteriorVehicleDataResponse(response: SetInteriorVehicleDataResponse) {
        Log.i(TAG, "SetInteriorVehicleData response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onGetInteriorVehicleDataResponse(response: GetInteriorVehicleDataResponse) {
        Log.i(TAG, "GetInteriorVehicleData response from SDL: " + response.resultCode.name + " Info: " + response.info)
    }

    override fun onOnInteriorVehicleData(notification: OnInteriorVehicleData) {
        Log.i(TAG, "OnInteriorVehicleData from SDL: " + notification)

    }

    companion object {

        private val TAG = "SDL Service"

        private val APP_NAME = "Hello Sdl"
        private val APP_ID = "93732031"

        private val ICON_FILENAME = "sdl_zebra_icon.png"
        private val SDL_IMAGE_FILENAME = "sdl_full_image.png"

        private val WELCOME_SHOW = "zebra sdl"
        private val WELCOME_SPEAK = "zebra sdl"

        private val TEST_COMMAND_NAME = "Zebra Command"
        private val TEST_COMMAND_ID = 1

        private val FOREGROUND_SERVICE_ID = 111

        // TCP/IP transport config
        // The default port is 12345
        // The IP is of the machine that is running SDL Core
        private val TCP_PORT = 12345
        private val DEV_MACHINE_IP_ADDRESS = "192.168.1.78"
    }

}
