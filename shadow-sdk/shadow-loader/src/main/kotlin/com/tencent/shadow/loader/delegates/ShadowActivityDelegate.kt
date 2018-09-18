package com.tencent.shadow.loader.delegates

import android.app.Activity
import android.app.Dialog
import android.app.Fragment
import android.content.Context
import android.content.Context.LAYOUT_INFLATER_SERVICE
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.AttributeSet
import android.view.*
import com.tencent.hydevteam.pluginframework.plugincontainer.HostActivityDelegate
import com.tencent.hydevteam.pluginframework.plugincontainer.HostActivityDelegator
import com.tencent.shadow.loader.infos.PluginActivityInfo
import com.tencent.shadow.loader.managers.ComponentManager.Companion.CM_ACTIVITY_INFO_KEY
import com.tencent.shadow.loader.managers.ComponentManager.Companion.CM_CLASS_NAME_KEY
import com.tencent.shadow.loader.managers.ComponentManager.Companion.CM_EXTRAS_BUNDLE_KEY
import com.tencent.shadow.loader.managers.ComponentManager.Companion.CM_LOADER_BUNDLE_KEY
import com.tencent.shadow.loader.managers.ComponentManager.Companion.CM_PART_KEY
import com.tencent.shadow.runtime.FixedContextLayoutInflater
import com.tencent.shadow.runtime.PluginActivity

/**
 * 壳子Activity与插件Activity转调关系的实现类
 * 它是抽象的是因为它缺少必要的业务信息.业务必须继承这个类提供业务信息.
 *
 * @author cubershi
 */
class ShadowActivityDelegate(private val mDI: DI) : HostActivityDelegate, ShadowDelegate() {
    companion object {
        const val PLUGIN_OUT_STATE_KEY = "PLUGIN_OUT_STATE_KEY"
    }

    private lateinit var mHostActivityDelegator: HostActivityDelegator
    private lateinit var mPluginActivity: PluginActivity
    private lateinit var mPartKey: String
    private lateinit var mBundleForPluginLoader: Bundle
    private var mPluginActivityCreated = false
    private var mDependenciesInjected = false
    private var mBeforeOnCreateOnWindowAttributesChangedCalledParams: WindowManager.LayoutParams? = null

    override fun setDelegator(hostActivityDelegator: HostActivityDelegator) {
        mHostActivityDelegator = hostActivityDelegator
    }

    override fun getPluginActivity(): Any = mPluginActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        val pluginInitBundle = if (savedInstanceState == null) mHostActivityDelegator.intent.extras else savedInstanceState

        val partKey = pluginInitBundle.getString(CM_PART_KEY)!!
        mPartKey = partKey
        mDI.inject(this, partKey)
        mDependenciesInjected = true

        val bundleForPluginLoader = pluginInitBundle.getBundle(CM_LOADER_BUNDLE_KEY)!!
        mBundleForPluginLoader = bundleForPluginLoader
        bundleForPluginLoader.classLoader = this.javaClass.classLoader
        val pluginActivityClassName = bundleForPluginLoader.getString(CM_CLASS_NAME_KEY)
        val pluginActivityInfo: PluginActivityInfo = bundleForPluginLoader.getParcelable(CM_ACTIVITY_INFO_KEY)

        if (savedInstanceState == null) {
            val pluginExtras: Bundle? = pluginInitBundle.getBundle(CM_EXTRAS_BUNDLE_KEY)
            mHostActivityDelegator.intent.replaceExtras(pluginExtras)
        }
        mHostActivityDelegator.intent.setExtrasClassLoader(mPluginClassLoader)

        mHostActivityDelegator.setTheme(pluginActivityInfo.themeResource)
        try {
            val aClass = mPluginClassLoader.loadClass(pluginActivityClassName)
            val pluginActivity = PluginActivity::class.java.cast(aClass.newInstance())
            initPluginActivity(pluginActivity)
            mPluginActivity = pluginActivity

            //使PluginActivity替代ContainerActivity接收Window的Callback
            mHostActivityDelegator.window.callback = pluginActivity

            //Activity.onCreate调用之前应该先收到onWindowAttributesChanged。
            pluginActivity.onWindowAttributesChanged(mBeforeOnCreateOnWindowAttributesChangedCalledParams)
            mBeforeOnCreateOnWindowAttributesChangedCalledParams = null

            val pluginSavedInstanceState: Bundle? = savedInstanceState?.getBundle(PLUGIN_OUT_STATE_KEY)
            pluginSavedInstanceState?.classLoader = mPluginClassLoader
            pluginActivity.onCreate(pluginSavedInstanceState)
            mPluginActivityCreated = true
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    private fun initPluginActivity(pluginActivity: PluginActivity) {
        pluginActivity.setHostActivityDelegator(mHostActivityDelegator)
        pluginActivity.setPluginResources(mPluginResources)
        pluginActivity.setHostContextAsBase(mHostActivityDelegator.hostActivity as Context)
        pluginActivity.setPluginClassLoader(mPluginClassLoader)
        pluginActivity.setPluginComponentLauncher(mComponentManager)
        pluginActivity.setPluginApplication(mPluginApplication)
        pluginActivity.setPluginPackageManager(mPluginPackageManager)
        pluginActivity.setShadowApplication(mPluginApplication)
        pluginActivity.setLibrarySearchPath(mPluginClassLoader.getLibrarySearchPath())
    }

    override fun onResume() {
        mPluginActivity.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        mPluginActivity.onNewIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val pluginOutState = Bundle(mPluginClassLoader)
        mPluginActivity.onSaveInstanceState(pluginOutState)
        outState.putBundle(PLUGIN_OUT_STATE_KEY, pluginOutState)
        outState.putString(CM_PART_KEY, mPartKey)
        outState.putBundle(CM_LOADER_BUNDLE_KEY, mBundleForPluginLoader)
    }

    override fun onPause() {
        mPluginActivity.onPause()
    }

    override fun onStart() {
        mPluginActivity.onStart()
    }

    override fun onStop() {
        mPluginActivity.onStop()
    }

    override fun onDestroy() {
        mPluginActivity.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        mPluginActivity.onConfigurationChanged(newConfig)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return mPluginActivity.dispatchKeyEvent(event)
    }

    override fun finish() {
        mPluginActivity.finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        mPluginActivity.onActivityResult(requestCode, resultCode, data)
    }

    override fun onChildTitleChanged(childActivity: Activity, title: CharSequence) {
        mPluginActivity.onChildTitleChanged(childActivity, title)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        val pluginSavedInstanceState: Bundle? = savedInstanceState?.getBundle(PLUGIN_OUT_STATE_KEY)
        mPluginActivity.onRestoreInstanceState(pluginSavedInstanceState)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        val pluginSavedInstanceState: Bundle? = savedInstanceState?.getBundle(PLUGIN_OUT_STATE_KEY)
        mPluginActivity.onPostCreate(pluginSavedInstanceState)
    }

    override fun onRestart() {
        mPluginActivity.onRestart()
    }

    override fun onUserLeaveHint() {
        mPluginActivity.onUserLeaveHint()
    }

    override fun onCreateThumbnail(outBitmap: Bitmap, canvas: Canvas): Boolean {
        return mPluginActivity.onCreateThumbnail(outBitmap, canvas)
    }

    override fun onCreateDescription(): CharSequence? {
        return mPluginActivity.onCreateDescription()
    }

    override fun onRetainNonConfigurationInstance(): Any? {
        return mPluginActivity.onRetainNonConfigurationInstance()
    }

    override fun onLowMemory() {
        mPluginActivity.onLowMemory()
    }

    override fun onTrackballEvent(event: MotionEvent): Boolean {
        return mPluginActivity.onTrackballEvent(event)
    }

    override fun onUserInteraction() {
        mPluginActivity.onUserInteraction()
    }

    override fun onWindowAttributesChanged(params: WindowManager.LayoutParams) {
        if (mPluginActivityCreated) {
            mPluginActivity.onWindowAttributesChanged(params)
        } else {
            mBeforeOnCreateOnWindowAttributesChangedCalledParams = params
        }
    }

    override fun onContentChanged() {
        mPluginActivity.onContentChanged()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        mPluginActivity.onWindowFocusChanged(hasFocus)
    }

    override fun onCreatePanelView(featureId: Int): View? {
        return mPluginActivity.onCreatePanelView(featureId)
    }

    override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean {
        return mPluginActivity.onCreatePanelMenu(featureId, menu)
    }

    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean {
        return mPluginActivity.onPreparePanel(featureId, view, menu)
    }

    override fun onPanelClosed(featureId: Int, menu: Menu) {
        mPluginActivity.onPanelClosed(featureId, menu)
    }

    override fun onCreateDialog(id: Int): Dialog {
        return mPluginActivity.onCreateDialog(id)
    }

    override fun onPrepareDialog(id: Int, dialog: Dialog) {
        mPluginActivity.onPrepareDialog(id, dialog)
    }

    override fun onApplyThemeResource(theme: Resources.Theme, resid: Int, first: Boolean) {
        mHostActivityDelegator.superOnApplyThemeResource(theme, resid, first)
        if (mPluginActivityCreated) {
            mPluginActivity.onApplyThemeResource(theme, resid, first)
        }
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
        return mPluginActivity.onCreateView(name, context, attrs)
    }

    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
        return mPluginActivity.onCreateView(parent, name, context, attrs)
    }

    override fun startActivityFromChild(child: Activity, intent: Intent, requestCode: Int) {
        mPluginActivity.startActivityFromChild(child, intent, requestCode)
    }

    override fun getClassLoader(): ClassLoader {
        return mPluginClassLoader
    }

    override fun getLayoutInflater(): LayoutInflater {
        val inflater = mHostActivityDelegator.applicationContext.getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        inflater.cloneInContext(mPluginActivity)
        return FixedContextLayoutInflater(inflater, mPluginActivity)
    }

    override fun getResources(): Resources {
        if (mDependenciesInjected) {
            return mPluginResources
        } else {
            //预期只有android.view.Window.getDefaultFeatures会调用到这个分支，此时我们还无法确定插件资源
            //而getDefaultFeatures只需要访问系统资源
            return Resources.getSystem()
        }
    }

    override fun onBackPressed() {
        mPluginActivity.onBackPressed()
    }

    override fun onAttachedToWindow() {
        mPluginActivity.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        mPluginActivity.onDetachedFromWindow()
    }

    override fun onAttachFragment(fragment: Fragment?) {
        mPluginActivity.onAttachFragment(fragment)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>?, grantResults: IntArray?) {
        mPluginActivity.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}