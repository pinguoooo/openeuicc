package im.angry.openeuicc

import android.util.Log
import im.angry.openeuicc.core.PrivilegedEuiccChannelManager
import im.angry.openeuicc.di.AppContainer
import im.angry.openeuicc.di.PrivilegedAppContainer
import im.angry.openeuicc.util.NeverCrash


class PrivilegedOpenEuiccApplication: OpenEuiccApplication() {
    override val appContainer: AppContainer by lazy {
        PrivilegedAppContainer(this)
    }
    override fun onCreate() {
        super.onCreate()
        (appContainer.euiccChannelManager as PrivilegedEuiccChannelManager).closeAllStaleChannels()

        NeverCrash.getInstance()
            .setDebugMode(BuildConfig.DEBUG)
            .setMainCrashHandler { t: Thread?, e: Throwable? ->
                //todo 跨线程操作时注意线程调度回主线程操作
                Log.e("PrivilegedOpenEuiccApplication", "主线程异常") //此处log只是展示，当debug为true时，主类内部log会打印异常信息
            }
            .setUncaughtCrashHandler { t: Thread?, e: Throwable? ->
                //todo 跨线程操作时注意线程调度回主线程操作
                Log.e("PrivilegedOpenEuiccApplication", "子线程异常") //此处log只是展示，当debug为true时，主类内部log会打印异常信息
            }
            .register(this)

    }
}