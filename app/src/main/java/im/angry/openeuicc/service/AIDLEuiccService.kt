package im.angry.openeuicc.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteCallbackList
import android.provider.Settings
import android.provider.Telephony
import android.telephony.UiccSlotMapping
import android.text.TextUtils
import android.util.Log
import com.google.gson.Gson
import im.angry.openeuicc.AidlResult
import im.angry.openeuicc.EuiccAidlCallback
import im.angry.openeuicc.EuiccAidlInterface
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.core.EuiccChannelManager
import im.angry.openeuicc.sms.SmsInterceptor
import im.angry.openeuicc.ui.MainActivity
import im.angry.openeuicc.ui.ProfileDownloadFragment
import im.angry.openeuicc.ui.ProfileRenameFragment
import im.angry.openeuicc.util.OpenEuiccContextMarker
import im.angry.openeuicc.util.dsdsEnabled
import im.angry.openeuicc.util.operational
import im.angry.openeuicc.util.preferenceRepository
import im.angry.openeuicc.util.setDsdsEnabled
import im.angry.openeuicc.util.simSlotMapping
import im.angry.openeuicc.util.uiccCardsInfoCompat
import im.angry.openeuicc.util.updateSimSlotMapping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.lpac_jni.LocalProfileInfo
import net.typeblog.lpac_jni.ProfileDownloadCallback
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.IllegalFormatException
import java.util.Timer
import java.util.TimerTask

class AIDLEuiccService : Service(), OpenEuiccContextMarker {
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private lateinit var mCallbacks: RemoteCallbackList<EuiccAidlCallback>
    private var slotId: Int? = 1
    private var portId: Int? = 0

    private var smsInterceptor: BroadcastReceiver? = null
    var isSmsInterceptorRegistered = false

    private var currentChannel: EuiccChannel? = null
        get() = if (euiccChannelManager == null) null
        else euiccChannelManager?.findEuiccChannelByPortBlocking(
            slotId!!,
            portId!!
        )

    private var euiccChannelManager: EuiccChannelManager? = null


    private val euiccChannelManagerServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            euiccChannelManager =
                (service!! as EuiccChannelManagerService.LocalBinder).service.euiccChannelManager
            onInit()

        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // These activities should never lose the EuiccChannelManagerService connection
            //mCallbacks.kill()
            currentChannel = null
            euiccChannelManager = null
        }
    }

    companion object {
        private const val TAG = "AIDLEuiccService"
    }


    override fun onBind(intent: Intent): IBinder {
        mCallbacks = RemoteCallbackList()

        bindService(
            Intent(application, EuiccChannelManagerService::class.java),
            euiccChannelManagerServiceConnection,
            Context.BIND_AUTO_CREATE
        )

        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mCallbacks.kill()
        currentChannel = null
        closeSmsReceiver()

        unbindService(euiccChannelManagerServiceConnection)
    }


    private val binder = object : EuiccAidlInterface.Stub() {
        override fun init(callback: EuiccAidlCallback?) {
            Log.i(TAG, "init..")
            mCallbacks = RemoteCallbackList()
            callback?.let {
                mCallbacks.register(it)

                bindService(
                    Intent(application, EuiccChannelManagerService::class.java),
                    euiccChannelManagerServiceConnection,
                    Context.BIND_AUTO_CREATE
                )
                it.onResult(AidlResult())
            }
        }

        override fun refreshChannel(callback: EuiccAidlCallback?) {
            Log.i(TAG, "refreshChannel..")

            callback?.let {
                mCallbacks.register(it)


                euiccChannelManager?.notifyEuiccProfilesChanged(currentChannel!!.logicalSlotId)
            }


        }

        override fun getAllChannel(callback: EuiccAidlCallback?) {
            Log.i(TAG, "getAllChannel..")

            callback?.let { cb ->
                mCallbacks.register(cb)
                serviceScope.launch {
                    val channels = euiccChannelManager?.enumerateEuiccChannels()?.onEach {
                        Log.d(MainActivity.TAG, "slot ${it.slotId} port ${it.portId}")
                        Log.d(MainActivity.TAG, it.lpa.eID)
                        // Request the system to refresh the list of profiles every time we start
                        // Note that this is currently supposed to be no-op when unprivileged,
                        // but it could change in the future
                        euiccChannelManager?.notifyEuiccProfilesChanged(it.logicalSlotId)
                    }?.map {
                        mapOf(
                            "slotId" to it.slotId,
                            "portId" to it.portId,
                            "logicalSlotId" to it.logicalSlotId
                        )
                    }

                    cb.onResult(AidlResult(1, Gson().toJson(channels)))
                }
            }
        }

        override fun refreshSIMCards(callback: EuiccAidlCallback?) {
            Log.i(TAG, "refreshSIMCards..")

            callback?.let { mCallbacks.register(it) }

        }

        override fun getAllCards(callback: EuiccAidlCallback?) {
            Log.i(TAG, "getAllCards..")
            callback?.let { cb ->
                mCallbacks.register(cb)


                serviceScope.launch {
                    val profiles = withContext(Dispatchers.IO) {
                        euiccChannelManager?.notifyEuiccProfilesChanged(currentChannel!!.logicalSlotId)
                        currentChannel!!.lpa.profiles
                    }

                    withContext(Dispatchers.Main) {
                        cb.onResult(AidlResult(1, Gson().toJson(profiles.operational)))
                    }
                }

            }
        }

        override fun searchCards(searchText: String?, callback: EuiccAidlCallback?) {
            Log.i(TAG, "searchCards..")
            callback?.let { cb ->
                mCallbacks.register(cb)

                serviceScope.launch {
                    val profiles = withContext(Dispatchers.IO) {
                        euiccChannelManager?.notifyEuiccProfilesChanged(currentChannel!!.logicalSlotId)
                        currentChannel!!.lpa.profiles.filter {
                            it.iccid.contains("$searchText") or
                                    it.name.contains("$searchText") or
                                    it.providerName.contains("$searchText")
                        }
                    }

                    withContext(Dispatchers.Main) {
                        cb.onResult(AidlResult(1, Gson().toJson(profiles.operational)))
                    }
                }

            }
        }

        override fun addCardByActvationCode(actvationCode: String?, callback: EuiccAidlCallback?) {
            Log.i(TAG, "addCardByActvationCode..")
            callback?.let {
                mCallbacks.register(it)

                if (!TextUtils.isEmpty(actvationCode)) {
                    Log.i(TAG, "actvationCode: $actvationCode")
                    val components = actvationCode!!.split("$")
                    if (components.size < 3 || components[0] != "LPA:1") return
                    val server = components[1]
                    val activationCode = components[2]
                    val confirmationCode = components[3]
                    val imei = components[4]
                    serviceScope.launch {
                        doDownloadProfile(it, server, activationCode, confirmationCode, imei)
                    }
                }
            }
        }

        override fun addCardByQrCode(qrCode: Bitmap?, callback: EuiccAidlCallback?) {
            Log.i(TAG, "addCardByQrCode..")
            callback?.let { mCallbacks.register(it) }
        }

        override fun enableCard(iccid: String?, callback: EuiccAidlCallback?) {
            Log.i(TAG, "enableCard..")
            callback?.let {
                mCallbacks.register(it)
                if (iccid != null) {
                    enableOrDisableProfile(callback, AidlResult(), iccid, true)
                }
            }
        }

        override fun disableCard(iccid: String?, callback: EuiccAidlCallback?) {
            Log.i(TAG, "disableCard..")
            callback?.let {
                mCallbacks.register(it)
                if (iccid != null) {
                    enableOrDisableProfile(callback, AidlResult(), iccid, false)
                }
            }
        }

        override fun deleteCard(iccid: String?, callback: EuiccAidlCallback?) {
            Log.i(TAG, "deleteCard..")
            callback?.let {
                mCallbacks.register(it)
                if (TextUtils.isEmpty(iccid))
                    callback.onResult(AidlResult(2, "", "iccid 为空，删除失败"))
                delete(callback, AidlResult(), iccid ?: "");
            }
        }

        override fun renameCard(iccid: String?, newName: String?, callback: EuiccAidlCallback?) {
            Log.i(TAG, "renameCard..")
            callback?.let {
                mCallbacks.register(it)
                rename(callback, iccid, newName)
            }
        }


        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                return super.onTransact(code, data, reply, flags)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unexpected remote exception", e)
                throw e
            }
        }

        /**
         * 唤起方法，如需客户端处理，需要返回
         */
        override fun callMethod(
            methodName: String?,
            params: MutableMap<Any?, Any?>?,
            callback: EuiccAidlCallback?
        ) {
            Log.d(
                TAG,
                "call methodName: $methodName .. params: ${params.toString()}，callback:$callback"
            )
            callback?.let {
                mCallbacks.register(it)
                if (methodName.isNullOrEmpty()) {
                    callback.onResult(AidlResult(2, "", "方法名为空"))
                    return
                }

                if (euiccChannelManager == null || currentChannel == null || slotId == null || portId == null) {
                    Log.d(TAG, "euiccChannelManager 或者 currentChannel 为空，进行初始化")
                    Log.d(TAG, "bindService()")
                    bindService(
                        Intent(application, EuiccChannelManagerService::class.java),
                        euiccChannelManagerServiceConnection,
                        Context.BIND_AUTO_CREATE
                    )
                    Log.d(TAG, "init()")
                    serviceScope.launch {
                        init()
                    }
                }

                val result = AidlResult()
                result.methodName = methodName

                try {
                    when (methodName) {
                        "setDsdsEnabled" -> handleDsdsEnabled(params, callback, result)
                        "init" -> handleInit(params, callback, result)
                        "refreshChannel" -> handleRefreshChannel(callback, result)
                        "getAllChannel" -> handleGetAllChannel(callback, result)
                        "setChannel" -> handleSetChannel(params, callback, result)
                        "refreshSIMCards" -> {}
                        "getAllCards" -> handleGetAllCards(callback, result)
                        "getCardDetail" -> handleGetCardDetail(callback, result)
                        "searchCards" -> handleGetAllCards(params, callback, result)
                        "addCardByActvationCode" -> handleAddCardByActvationCode(
                            params,
                            callback,
                            result
                        )

                        "addCardByQrCode" -> {}
                        "enableCard" -> handleEnableCard(params, callback, result)
                        "disableCard" -> handleDisableCard(params, callback, result)
                        "deleteCard" -> handleDeleteCard(params, callback, result)
                        "renameCard" -> handleRenameCard(params, callback, result)
                        "getAllSMS" -> handleGetAllSMS(callback, result)
                        "initSmsReceiver" -> initSmsReceiver(params, callback, result)
                        "saveSmsMd5s" -> handleSaveSmsMd5s(params, callback, result)
                        "closeSmsReceiver" -> handleCloseSmsReceiver(callback, result)
                        "getMobileNetState" -> handleGetMobileNetState(callback, result)
                        "turnOnOffMobileNet" -> handleTurnOnOffMobileNet(params, callback, result)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    callback.onResult(result.apply {
                        state = 2
                        msg = "执行出错 e: ${e.stackTrace}"
                    })
                }

            }
        }

    }

    /**
     * 保存服务器已接收的 md5
     */
    private fun handleSaveSmsMd5s(
        params: MutableMap<Any?, Any?>?,
        callback: EuiccAidlCallback,
        result: AidlResult
    ) {
        if (params != null) {
            val md5s: Array<String> = params.get("smsMd5s") as Array<String>
            saveStringArray2File(md5s)

        }
    }


    private fun handleGetMobileNetState(callback: EuiccAidlCallback, result: AidlResult) {
        with(result) {
            state = 1
            data = Gson().toJson(hashMapOf("mobileState" to getMobileNetState()))
            msg = "获取移动数据状态"
        }
        callback.onResult(result)
    }

    private fun getMobileNetState(): Int {
        // 获取移动数据状态
        val mobileDataState = Settings.Global.getInt(contentResolver, "mobile_data")
        // 获取漫游状态
        val roamingSate = Settings.Global.getInt(contentResolver, Settings.Global.DATA_ROAMING)

        return if (mobileDataState == 1 || roamingSate == 1) 1 else 0
    }


    private fun handleTurnOnOffMobileNet(
        params: MutableMap<Any?, Any?>?,
        callback: EuiccAidlCallback,
        result: AidlResult
    ) {

        val isEnable = if (params != null) {
            if (params["isEnable"] as Boolean) 1 else 0
        } else 0

        turnOffOnMobileNet(isEnable)

        //查询当前网络是否和想要的网络状态一样
        if (getMobileNetState() == isEnable) {
            result.state = 1
        } else {
            result.state = 2
        }

        result.msg = "${if (isEnable == 1) "开启" else "关闭"} 网络"

        callback.onResult(result)
    }

    fun turnOffOnMobileNet(isEnable: Int) {
        // 关闭移动数据：
        Settings.Global.putInt(contentResolver, "mobile_data", isEnable)

        // 关闭漫游服务：
        Settings.Global.putInt(contentResolver, Settings.Global.DATA_ROAMING, isEnable)
    }

    private fun handleSetChannel(
        params: MutableMap<Any?, Any?>?,
        callback: EuiccAidlCallback,
        result: AidlResult
    ) {
        if (params != null) {
            if (euiccChannelManager!!.findEuiccChannelByPortBlocking(
                    params.get("slotId") as Int,
                    params.get("portId") as Int
                ) != null
            ) {
                slotId = params.get("slotId") as Int
                portId = params.get("portId") as Int
                with(result) {
                    state = 1
                    msg = "通道切换成功"
                }
            } else {
                with(result) {
                    state = 2
                    msg = "通道切换失败"
                }
            }
        } else {
            with(result) {
                state = 2
                msg = "参数不能为空"
            }

        }
        callback.onResult(result)
    }

    /**
     *  获取全部短信
     *  thread_id：对话的序号，如同一个手机号发的几条短信，他们的thread_id是相同的
     *  address：发件人地址（手机号）
     *  person：发件人，如果发件人在通讯录中则为具体名字，陌生人为null
     *  date：日期，long类型
     *  protocol：协议，0为SMS_RPOTO, 1为MMS_PROTO
     *  read：是否阅读，0表示未读，1表示已读
     *  status：短信状态，-1表示接收，0表示已读，64表示待发送，128表示已发送
     *  type：短信类型，1是接收到的，2是已发出
     *  body：短信内容
     *  service_center：短信服务中心号码
     */

    @SuppressLint("MissingPermission", "Range")
    private fun handleGetAllSMS(callback: EuiccAidlCallback, result: AidlResult) {
        val smsList: MutableList<SMS> = ArrayList()
        val cursor = contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, null)
        cursor?.use { it ->
            try {
                while (it.moveToNext()) {
                    // TODO: 这里需要判断当前这一条短信是否已经上报过，如果是，跳过！
                    val subId = it.getInt(it.getColumnIndex("sub_id"))
                    Log.d(TAG, "handleGetAllSMS: subId: ${subId}")
                    // 这条短信可能自己往外面发，且发送失败的，这里subId 是空的
                    if (subId <= 0) {
                        continue
                    }
                    val iccid =
                        appContainer.subscriptionManager.allSubscriptionInfoList.first { info -> info.subscriptionId == subId }.iccId
                    val date = it.getLong(it.getColumnIndex(Telephony.Sms.DATE))
                    val body = it.getString(it.getColumnIndex(Telephony.Sms.BODY))
                    val md5 = "${iccid}${date}${body}".md5()

                    // 判断是否为新的短信，如果不是则跳过
                    if (!isNewSms(md5)) {
                        //这里真实保存 md5 的时机应该是上报给服务器之后，成功了再进行写入，这里只是测试追加和获取功能
                        //saveStringArray2File(this, arrayOf(md5))

                        Log.d(TAG, "没有新的短信要发送")
                        continue
                    }

                    smsList.add(
                        SMS(
                            id = it.getString(it.getColumnIndex(Telephony.Sms._ID)),
                            thread_id = it.getString(it.getColumnIndex(Telephony.Sms.THREAD_ID)),
                            address = it.getString(it.getColumnIndex(Telephony.Sms.ADDRESS)),
                            person = it.getString(it.getColumnIndex(Telephony.Sms.PERSON)),
                            protocol = it.getInt(it.getColumnIndex(Telephony.Sms.PROTOCOL)),
                            read = it.getInt(it.getColumnIndex(Telephony.Sms.READ)),
                            status = it.getInt(it.getColumnIndex(Telephony.Sms.STATUS)),
                            type = it.getInt(it.getColumnIndex(Telephony.Sms.TYPE)),
                            service_center = it.getString(it.getColumnIndex(Telephony.Sms.SERVICE_CENTER)),
                            date = date,
                            body = body,
                            received_iccid = iccid,
                            md5 = md5
                        ).also {
                            Log.d(TAG, it.toString())
                        }
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        with(result) {
            state = 1
            data = Gson().toJson(smsList)
            msg += "成功，一共有${smsList.size}条数据"
        }

        callback.onResult(result)
    }

    /**
     * 判断这条短信是否没有上报过
     */
    private fun isNewSms(md5: String): Boolean {
        return !getUploadedSmsMd5Array().also { Log.d(TAG, "all sms :${it.contentToString()}") }
            .contains(md5)
    }

    private fun getUploadedSmsMd5Array(): Array<String> {
        return loadStringArrayFromFile() ?: emptyArray()
    }

    data class SMS(
        val id: String,
        val thread_id: String,
        val address: String,
        val person: String?,
        val date: Long,
        val protocol: Int,
        val read: Int,
        val status: Int,
        val type: Int,
        val body: String,
        val service_center: String?,
        var received_iccid: String? = "",
        val md5: String? = ""
    )


    private fun initSmsReceiver(
        params: MutableMap<Any?, Any?>?,
        callback: EuiccAidlCallback,
        result: AidlResult
    ) {
        var interval = 10 * 1000L
        var timeout = 10 * 60 * 1000L
        if (params != null) {
            if (params["interval"] != null) {
                interval = params["interval"] as Long
            }

            if (params["timeout"] != null) {
                timeout = params["timeout"] as Long
            }
        }

        // 倒计时且按间隔执行上报任务
        startCountdownTimer(callback, result, timeout,interval)

        // 监听
        doSmsInterceptor(result, callback)

    }

    private var countDownTimer: CountDownTimer? = null

    private fun startCountdownTimer(
        callback: EuiccAidlCallback,
        result: AidlResult,
        timeout: Long,
        interval: Long,
    ) {
        serviceScope.launch {

            // 取消之前的CountDownTimer对象
            countDownTimer?.cancel()

            // 创建一个新的CountDownTimer对象
            countDownTimer = object : CountDownTimer(timeout, interval) {
                override fun onTick(millisUntilFinished: Long) {
                    // 每次间隔执行的操作
                    // 上报短信
                    Log.d(TAG, "定时上报短信！！！！！剩余时间：${millisUntilFinished}ms")
                    result.msg = "定时上报短信！"
                    handleGetAllSMS(callback, result)
                }

                override fun onFinish() {
                    handleCloseSmsReceiver(
                        callback,
                        result.also { it.msg = "到达超时限制，关闭监听和上报" }
                    )
                }
            }

            // 启动倒计时
            countDownTimer?.start()
        }

    }


    private fun doSmsInterceptor(
        result: AidlResult,
        callback: EuiccAidlCallback
    ) {
        if (smsInterceptor != null && (smsInterceptor as SmsInterceptor).onSmsReceivedListener != null && isSmsInterceptorRegistered) {
            Log.d(TAG, "initSmsReceiver: 已经有短信拦截器了，不需要再创建")
            return
        }

        smsInterceptor = SmsInterceptor()
        (smsInterceptor as SmsInterceptor).onSmsReceivedListener =
            object : SmsInterceptor.OnSmsReceivedListener {
                override fun onReceived(intent: Intent) {
                    // 上报短信
                    Log.d(TAG, "监听到短信！！！！！")
                    result.msg = "监听到短信！"
                    handleGetAllSMS(callback, result)
                }
            }
        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        if (!isSmsInterceptorRegistered) {
            val registerReceiver = registerReceiver(smsInterceptor, filter)
            isSmsInterceptorRegistered = true
        }
    }

    /**
     * 超时/成功获取到对应的短信后需要关闭
     */
    private fun handleCloseSmsReceiver(callback: EuiccAidlCallback, result: AidlResult) {
        closeSmsReceiver()

        callback.onResult(result)
    }


    private fun closeSmsReceiver() {
        try {
            if (isSmsInterceptorRegistered && smsInterceptor != null) {
                unregisterReceiver(smsInterceptor)
                isSmsInterceptorRegistered = false
            }
        } catch (e: IllegalFormatException) {
            e.printStackTrace()
        }

        countDownTimer?.cancel()
        countDownTimer = null
    }


    /**
     * 获取这张卡的更多信息
     */
    @SuppressLint("MissingPermission")
    private fun handleGetCardDetail(callback: EuiccAidlCallback, result: AidlResult) {

        val number = telephonyManager.line1Number
        val IMSI = telephonyManager.getSubscriberId()
        val IMEI = telephonyManager.getImei(slotId!!)

        with(result) {
            state = 1
            data = Gson().toJson(
                hashMapOf(
                    "phoneNumber" to "$number",
                    "IMSI" to IMSI,
                    "IMEI" to IMEI
                )
            )
            msg = "获取详细信息"
        }
        callback.onResult(result)
    }

    // 设置是否支持双卡
    private fun handleDsdsEnabled(
        params: MutableMap<Any?, Any?>?,
        callback: EuiccAidlCallback,
        result: AidlResult
    ) {
        val isEnable = if (params != null) params["isEnable"] as Boolean else false

        Log.d(TAG, "handleDsdsEnabled: isEnable : $isEnable")
        telephonyManager.setDsdsEnabled(euiccChannelManager!!, isEnable)

        with(result) {
            state = 1
            data = "{isEnable:${telephonyManager.dsdsEnabled}}"
            msg = "双卡支持"
        }
        callback.onResult(result)
    }

    private fun handleRenameCard(
        params: MutableMap<Any?, Any?>?,
        callback: EuiccAidlCallback,
        result: AidlResult
    ) {

        rename(callback, params?.get("iccid") as String, params["nickname"] as String)
    }

    private fun handleDeleteCard(
        params: MutableMap<Any?, Any?>?,
        callback: EuiccAidlCallback,
        result: AidlResult
    ) {
        delete(callback, result, params?.get("iccid") as String)
    }

    private fun handleDisableCard(
        params: MutableMap<Any?, Any?>?,
        callback: EuiccAidlCallback,
        result: AidlResult
    ) {
        val iccid = params?.get("iccid") as String
        enableOrDisableProfile(callback, result, iccid, false)
    }

    private fun handleEnableCard(
        params: MutableMap<Any?, Any?>?,
        callback: EuiccAidlCallback,
        result: AidlResult
    ) {
        val iccid = params?.get("iccid") as String
        enableOrDisableProfile(callback, result, iccid, true)
    }

    /**
     * 激活码添加卡
     */
    private fun handleAddCardByActvationCode(
        params: MutableMap<Any?, Any?>?,
        callback: EuiccAidlCallback,
        result: AidlResult
    ) {
        val actvationCode = params?.get("actvationCode") as String

        if (!TextUtils.isEmpty(actvationCode)) {
            Log.i(TAG, "actvationCode: $actvationCode")
            val components = actvationCode.split("$")
            Log.d(TAG, "components: $components")
            if (components.size < 3 || components[0] != "LPA:1") {
                Log.e(TAG, "Invalid activation code !!!!!")
                with(result) {
                    state = 2
                    msg = "Invalid activation code !!!!!"
                }
                callback.onResult(result)
                return
            }
            val server = components[1]
            val activationCode = components[2]
            val confirmationCode = components[3]
            val imei = components[4]

            Log.i(TAG, "serviceScope.launch: $actvationCode")
            serviceScope.launch {

                var currentProgress = 0

                Log.d(TAG, "向运营商发起下载请求")
                beginTrackedOperation {
                    if (currentChannel == null) {
                        return@beginTrackedOperation false
                    } else {
                        currentChannel!!.lpa.downloadProfile(
                            server,
                            activationCode,
                            imei,
                            confirmationCode,
                            object : ProfileDownloadCallback {
                                override fun onStateUpdate(state: ProfileDownloadCallback.DownloadState) {
                                    currentProgress = state.progress
                                    Log.d(
                                        TAG,
                                        "doDownloadProfile，当前进度：${state.progress}，状态名：${state.name}"
                                    )
                                }

                            })

                        // If we get here, we are successful
                        // Only send notifications if the user allowed us to
                        preferenceRepository.notificationDownloadFlow.first()
                    }
                }

                with(result) {
                    if (currentProgress >= 80) {

                        state = 1
                        msg = "下载成功"
                        data = Gson().toJson(
                            hashMapOf(
                                "iccid" to currentChannel!!.lpa.notifications.firstOrNull()?.iccid,
                                "actvationCode" to actvationCode,
                                "imei" to telephonyManager.getImei()
                            )
                        )
                        Log.d(TAG, "下载 SIM 卡结果：$data 。。。 imei：${telephonyManager.getImei()}")

                    } else {
                        state = 2
                        msg = "下载失败"
                    }
                }
                callback.onResult(result)
            }
        }
    }

    private fun handleGetAllCards(callback: EuiccAidlCallback, result: AidlResult) {
        handleGetAllCards(hashMapOf(), callback, result)
    }


    private fun handleGetAllCards(
        params: MutableMap<Any?, Any?>?,
        callback: EuiccAidlCallback,
        result: AidlResult
    ) {
        val searchText = "${params?.get("searchText") ?: ""}"
        serviceScope.launch {
            var profiles = localProfileInfos(currentChannel!!)
            if (searchText.isNotEmpty()) {
                profiles = profiles.filter {
                    it.iccid.contains(searchText) or
                            it.name.contains(searchText) or
                            it.providerName.contains(searchText)
                }
            }

            with(result) {
                state = 1
                data = Gson().toJson(profiles.operational)
                msg = "获取成功"
            }
            callback.onResult(result)

        }
    }

    private suspend fun localProfileInfos(it: EuiccChannel): List<LocalProfileInfo> {
        val profiles = withContext(Dispatchers.IO) {
            euiccChannelManager?.notifyEuiccProfilesChanged(it.logicalSlotId)
            it.lpa.profiles
        }
        return profiles
    }

    private fun handleGetAllChannel(callback: EuiccAidlCallback, result: AidlResult) {
        serviceScope.launch {
            val channels = euiccChannelManager?.enumerateEuiccChannels()?.onEach {
                Log.d(MainActivity.TAG, "slot ${it.slotId} port ${it.portId}")
                Log.d(MainActivity.TAG, it.lpa.eID)
                // Request the system to refresh the list of profiles every time we start
                // Note that this is currently supposed to be no-op when unprivileged,
                // but it could change in the future
                euiccChannelManager?.notifyEuiccProfilesChanged(it.logicalSlotId)
            }?.map {
                mapOf(
                    "slotId" to it.slotId,
                    "portId" to it.portId,
                    "logicalSlotId" to it.logicalSlotId
                )
            }

            with(result) {
                state = 1
                msg = "通道列表"
                data = Gson().toJson(channels)
            }
            callback.onResult(result)
        }
    }

    private fun handleRefreshChannel(callback: EuiccAidlCallback, result: AidlResult) {

        euiccChannelManager?.notifyEuiccProfilesChanged(currentChannel!!.logicalSlotId)


        callback.onResult(result)
    }

    private fun handleInit(
        params: MutableMap<Any?, Any?>?,
        callback: EuiccAidlCallback,
        result: AidlResult
    ) {
        serviceScope.launch {
            if (euiccChannelManager == null || currentChannel == null) {
                bindService(
                    Intent(application, EuiccChannelManagerService::class.java),
                    euiccChannelManagerServiceConnection,
                    Context.BIND_AUTO_CREATE
                )
            }
            if (params != null) {
                val mSlotId = if (params.contains("slotId")) params["slotId"] as Int else 1
                val mPortId = if (params.contains("portId")) params["portId"] as Int else 0
                // 进行插槽映射
                slotMapping(mSlotId, mPortId)
            }

            result.msg = "初始化完成"
            callback.onResult(result)
        }
    }


    fun onInit() {
        serviceScope.launch {
            init()
        }
    }

    private suspend fun init() {
        val knownChannels = withContext(Dispatchers.IO) {
            euiccChannelManager?.enumerateEuiccChannels()?.onEach {
                Log.d(MainActivity.TAG, "slot ${it.slotId} port ${it.portId}")
                Log.d(MainActivity.TAG, it.lpa.eID)
                // Request the system to refresh the list of profiles every time we start
                // Note that this is currently supposed to be no-op when unprivileged,
                // but it could change in the future
                euiccChannelManager?.notifyEuiccProfilesChanged(it.logicalSlotId)
            }
        }

        Log.d(TAG, "knownChannels: $knownChannels")

        withContext(Dispatchers.Main) {
            knownChannels?.sortedBy { it.logicalSlotId }

            if (!knownChannels.isNullOrEmpty()) {
                slotId = knownChannels.first().slotId
                portId = knownChannels.first().portId
            } else {
                // 没有映射成功的卡直接进行卡映射
                Log.d(TAG, "没有映射成功的卡直接进行卡映射")
                slotMapping(1, 0)
            }
        }
    }

    private fun enableOrDisableProfile(
        callback: EuiccAidlCallback,
        result: AidlResult,
        iccid: String,
        enable: Boolean
    ) {
        serviceScope.launch {

            beginTrackedOperation {

                val res = if (enable) {
                    currentChannel!!.lpa.enableProfile(iccid)
                } else {
                    currentChannel!!.lpa.disableProfile(iccid)
                }

                if (!res) {
                    Log.d(
                        TAG,
                        "Failed to enable / disable profile $iccid"
                    )
                    result.state = 2
                    result.msg =
                        "${if (enable) "enable" else "disable"} 失败，卡不存在或者当前卡已处于对应状态"
                    callback.onResult(result)
                    return@beginTrackedOperation false
                }

                try {
                    euiccChannelManager?.waitForReconnect(
                        slotId!!,
                        portId!!,
                        timeoutMillis = 30 * 1000
                    )
                } catch (e: TimeoutCancellationException) {
                    // 切换状态超时，干掉
                    result.state = 2
                    result.msg = "${if (enable) "enable" else "disable"} 失败，超时（30s）！！"
                    callback.onResult(result)
                    return@beginTrackedOperation false
                }
                // 到这里就算是成功了
                result.state = 1
                result.msg = "${if (enable) "enable" else "disable"} 成功"
                callback.onResult(result)
                currentChannel = euiccChannelManager!!.findEuiccChannelByPort(slotId!!, portId!!)

                if (enable) {
                    preferenceRepository.notificationEnableFlow.first()
                } else {
                    preferenceRepository.notificationDisableFlow.first()
                }
            }

            // 判断网络
            if (getMobileNetState() == 1) {
                turnOffOnMobileNet(0)
            }
        }

    }

    suspend fun beginTrackedOperation(op: suspend () -> Boolean) =

        withContext(Dispatchers.IO) {
            val latestSeq = currentChannel!!.lpa.notifications.firstOrNull()?.seqNumber ?: 0
            Log.d(TAG, "Latest notification is $latestSeq before operation")
            if (op()) {
                Log.d(TAG, "Operation has requested notification handling")
                // Note that the exact instance of "channel" might have changed here if reconnected;
                // so we MUST use the automatic getter for "channel"
                currentChannel!!.lpa.notifications.filter { it.seqNumber > latestSeq }.forEach {
                    Log.d(TAG, "Handling notification $it")
                    currentChannel!!.lpa.handleNotification(it.seqNumber)
                }
            }
            Log.d(TAG, "Operation complete")

        }

    suspend fun doDownloadProfile(
        callback: EuiccAidlCallback,
        server: String,
        code: String?,
        confirmationCode: String?,
        imei: String?
    ) = beginTrackedOperation {
        if (currentChannel == null) {
            return@beginTrackedOperation false
        } else {
            var currentProgress = 0;
            currentChannel!!.lpa.downloadProfile(
                server,
                code,
                imei,
                confirmationCode,
                object : ProfileDownloadCallback {
                    override fun onStateUpdate(state: ProfileDownloadCallback.DownloadState) {
                        currentProgress = state.progress
                        Log.d(
                            TAG,
                            "doDownloadProfile，当前进度：${state.progress}，状态名：${state.name}"
                        )
                    }
                })
            if (currentProgress >= 80) {
                callback.onResult(AidlResult(1, "", "下载成功"))
            } else {
                callback.onResult(AidlResult(2, "", "下载失败"))
            }
            // If we get here, we are successful
            // Only send notifications if the user allowed us to
            preferenceRepository.notificationDownloadFlow.first()
        }
    }


    private fun rename(callback: EuiccAidlCallback, iccid: String?, nickname: String?) {
        if (TextUtils.isEmpty(iccid)) {
            callback.onResult(AidlResult(2, "", "iccid 为空，修改失败"))
        }


        if ((nickname ?: "").length >= 64) {
            Log.e(TAG, "名字太长，修改失败")
            callback.onResult(AidlResult(2, "", "名字太长，修改失败"))
            return
        }

        serviceScope.launch {
            try {
                doRename(iccid!!, nickname ?: "")
                callback.onResult(AidlResult(1, "", "修改成功"))
            } catch (e: Exception) {
                Log.d(ProfileRenameFragment.TAG, "Failed to rename profile")
                Log.d(ProfileRenameFragment.TAG, Log.getStackTraceString(e))
                callback.onResult(AidlResult(2, "", "修改失败，未知错误"))
            }

        }
    }

    private suspend fun doRename(iccid: String, name: String) = withContext(Dispatchers.IO) {
        if (currentChannel != null && !currentChannel!!.lpa.setNickname(iccid, name)) {
            throw java.lang.RuntimeException("Profile nickname not changed")
        }
    }


    private fun delete(callback: EuiccAidlCallback, result: AidlResult, iccid: String) {


        serviceScope.launch {
            try {
                doDelete(iccid)
                callback.onResult(
                    result.also {
                        it.state = 1
                        it.msg = "删除成功"
                    }
                )
            } catch (e: Exception) {
                Log.d(ProfileDownloadFragment.TAG, "Error deleting profile")
                Log.d(ProfileDownloadFragment.TAG, Log.getStackTraceString(e))

                callback.onResult(
                    result.also {
                        it.state = 2
                        it.msg = "删除失败"
                    })
            }
        }
    }

    private suspend fun doDelete(iccid: String) = beginTrackedOperation {
        if (currentChannel == null) {
            false
        } else {

            currentChannel!!.lpa.deleteProfile(iccid)
            preferenceRepository.notificationDeleteFlow.first()

        }

    }

    fun String.md5(): String {
        return MessageDigest
            .getInstance("MD5")
            .digest(toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * 存已经上报的 SMS 的 md5 到文件里面
     */
    fun saveStringArray2File(data: Array<String>) {
        // 获取公共目录
        val dataDir = "/data/local"
        // 创建文件对象
        val file = File("$dataDir/euicc")
        if (!file.exists()) {
            file.mkdirs()
        }
        val file2 = File(file.absoluteFile, "sms_uploaded_md5_array.txt")
        // 读取文件现有内容并转换为集合
        val existingData = if (file2.exists()) {
            file2.readText().split(",").toSet()
        } else {
            emptySet<String>()
        }
        // 过滤掉已存在的元素，仅保留新元素
        val newData = data.filterNot { it in existingData }

        // 创建FileOutputStream对象
        FileOutputStream(file2, true).use {
            // 判断剩下来的元素是否不为空
            if (newData.isNotEmpty()) {

                //如果这个文件里已经有了数据，则先添加一个逗号
                if (existingData.isNotEmpty()) {
                    it.write(",".toByteArray())
                }
                // 将数组数据字符串形式追加存入文件
                it.write(newData.joinToString(",").toByteArray())
            }
        }
    }

    /**
     * 去除已经上报的 SMS 的 md5
     */
    fun loadStringArrayFromFile(): Array<String>? {
        // 获取公共目录
        val dataDir = "/data/local"
        val file = File("$dataDir/euicc", "sms_uploaded_md5_array.txt")
        // 检查文件是否存在
        if (!file.exists()) {
            println("File does not exist.")
            return emptyArray()
        }
        // 读取文件内容
        val content = file.readText(Charsets.UTF_8)
        // 将内容转为数组
        return content.split(",").toTypedArray()
    }


    // 插槽映射
    private suspend fun slotMapping(slotId: Int, portId: Int) {
        // 获取到已经映射好的结果
        val mapped = withContext(Dispatchers.IO) {
            telephonyManager.simSlotMapping
        }
        // 获取物理插槽
        val ports = telephonyManager.uiccCardsInfoCompat.flatMap { it.ports }.toMutableList()

        // 用来存放映射的容器
        val mappings: MutableList<UiccSlotMapping> = mutableListOf()


        Log.d("slotMapping", "mapped: $mapped")
        Log.d(
            "slotMapping",
            "ports: ${ports.map { "(mPortIndex=${it.portIndex} ,physicalSlotIndex=${it.card.physicalSlotIndex}, mLogicalSlotIndex=${it.logicalSlotIndex})" }}"
        )


        val find = ports.find { it.card.physicalSlotIndex == slotId && it.portIndex == portId }

        if (find != null) {
            // 找到目标插槽
            Log.d("slotMapping", "找到目标插槽：LogicalSlotIndex=${find.logicalSlotIndex}")

            if (find.logicalSlotIndex != 0) {
                Log.d("slotMapping", "这个插槽对应的默认虚拟插槽index不为 0，需要重新映射")
                // 只有一个！
                mappings.add(
                    UiccSlotMapping(
                        find.portIndex, find.card.physicalSlotIndex, 0
                    )
                )
                ports.remove(find)
                // 判断是否开启了 多卡多待
                if (telephonyManager.dsdsEnabled) {
                    ports.forEach {
                        mappings.add(
                            UiccSlotMapping(
                                it.portIndex,
                                it.card.physicalSlotIndex,
                                mappings.size
                            )
                        )
                    }
                }

                Log.d("slotMapping", "mappings: $mappings")

                try {
                    withContext(Dispatchers.IO) {
                        // Use the utility method from PrivilegedTelephonyUtils to ensure
                        // unmapped ports have all profiles disabled
                        telephonyManager.updateSimSlotMapping(
                            euiccChannelManager!!,
                            mappings
                        )
                    }
                } catch (e: Exception) {
                    //Toast.makeText(this@AIDLEuiccService, R.string.slot_mapping_failure, Toast.LENGTH_LONG).show()
                    e.printStackTrace()
                }
                delay(2000)
                withContext(Dispatchers.Main) {
                    try {
                        euiccChannelManager!!.invalidate()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } else {
                // 插槽映射关系正确
                Log.d("slotMapping", "插槽映射关系正确，退出")
            }
        } else {
            // 没有这个插槽
            Log.d("slotMapping", "没有这个插槽，退出")
        }

    }

}