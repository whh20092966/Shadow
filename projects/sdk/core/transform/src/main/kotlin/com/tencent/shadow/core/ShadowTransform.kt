package com.tencent.shadow.core

import com.android.build.api.transform.TransformInvocation
import com.tencent.shadow.core.transformkit.ClassPoolBuilder
import com.tencent.shadow.core.transformkit.DirInputClass
import com.tencent.shadow.core.transformkit.JarInputClass
import com.tencent.shadow.core.transformkit.JavassistTransform
import javassist.*
import org.gradle.api.Project
import java.io.File

class ShadowTransform(project: Project, classPoolBuilder: ClassPoolBuilder, val useHostContext: () -> Array<String>) : JavassistTransform(project, classPoolBuilder) {

    companion object {
        const val ShadowFragmentClassname = "com.tencent.shadow.runtime.ShadowFragment"
        const val ShadowDialogFragmentClassname = "com.tencent.shadow.runtime.ShadowDialogFragment"
        const val AndroidDialogClassname = "android.app.Dialog"
        const val ShadowDialogClassname = "com.tencent.shadow.runtime.ShadowDialog"
        const val AndroidWebViewClassname = "android.webkit.WebView"
        const val ShadowWebViewClassname = "com.tencent.shadow.runtime.ShadowWebView"
        const val AndroidPendingIntentClassname = "android.app.PendingIntent"
        const val ShadowPendingIntentClassname = "com.tencent.shadow.runtime.ShadowPendingIntent"
        const val ShadowUriClassname = "com.tencent.shadow.runtime.UriConverter"
        const val AndroidUriClassname = "android.net.Uri"
        val RenameMap = mapOf(
                "android.app.Application"
                        to "com.tencent.shadow.runtime.ShadowApplication"
                ,
                "android.app.Activity"
                        to "com.tencent.shadow.runtime.ShadowActivity"
                ,
                "android.app.Service"
                        to "com.tencent.shadow.runtime.ShadowService"
                ,
                "android.app.Fragment"
                        to ShadowFragmentClassname
                ,
                "android.app.DialogFragment"
                        to ShadowDialogFragmentClassname
                ,
                "android.app.FragmentManager"
                        to "com.tencent.shadow.runtime.PluginFragmentManager"
                ,
                "android.app.FragmentTransaction"
                        to "com.tencent.shadow.runtime.PluginFragmentTransaction"
                ,
                "android.app.Application\$ActivityLifecycleCallbacks"
                        to "com.tencent.shadow.runtime.ShadowActivityLifecycleCallbacks"
                ,
                AndroidDialogClassname
                        to ShadowDialogClassname
                ,
                "android.app.Instrumentation"
                        to "com.tencent.shadow.runtime.ShadowInstrumentation"

        )

        const val RemoteLocalSdkPackageName = "com.tencent.shadow.remoteview.localsdk"
        val RemoteViewRenameMap = mapOf(
                "com.tencent.shadow.remoteview.localsdk.RemoteViewCreator" to "com.tencent.shadow.runtime.remoteview.ShadowRemoteViewCreator",
                "com.tencent.shadow.remoteview.localsdk.RemoteViewCreatorFactory" to "com.tencent.shadow.runtime.remoteview.ShadowRemoteViewCreatorFactory",
                "com.tencent.shadow.remoteview.localsdk.RemoteViewCreateCallback" to "com.tencent.shadow.runtime.remoteview.ShadowRemoteViewCreateCallback",
                "com.tencent.shadow.remoteview.localsdk.RemoteViewCreateException" to "com.tencent.shadow.runtime.remoteview.ShadowRemoteViewCreateException"
        )
    }

    private val containerFragmentCtClass: CtClass get() = classPool["com.tencent.shadow.runtime.ContainerFragment"]
    private val containerDialogFragmentCtClass: CtClass get() = classPool["com.tencent.shadow.runtime.ContainerDialogFragment"]

    val mAppFragments: MutableSet<CtClass> = mutableSetOf()
    val mAppDialogFragments: MutableSet<CtClass> = mutableSetOf()

    override fun beforeTransform(invocation: TransformInvocation) {
        super.beforeTransform(invocation)
        mAppFragments.clear()
        mAppDialogFragments.clear()
    }

    override fun onTransform() {
        step1_renameShadowClass()
        step2_findFragments()
        step3_renameFragments()
        step4_redirectDialogMethod()
        step5_renameWebViewChildClass()
        step6_redirectPendingIntentMethod()
        step7_redirectUriMethod()
        step8_keepHostContext()
    }



    private fun renameRemoteViewCreatorClass() {
        forEachAppClass { ctClass ->
            // 除RemoteLocalSdk包外的所有类，都需要替换
            if (RemoteLocalSdkPackageName != ctClass.packageName) {
                RemoteViewRenameMap.forEach {
                    ctClass.replaceClassName(it.key, it.value)
                }
            }

        }
    }

    private inline fun forEachAppClass(action: (CtClass) -> Unit) {
        val appClasses = mCtClassInputMap.keys
        appClasses.forEach(action)
    }

    private inline fun forEachCanRecompileAppClass(targetClassList: List<String>, action: (CtClass) -> Unit) {
        val appClasses = mCtClassInputMap.keys
        appClasses.filter { ctClass ->
            targetClassList.any { targetClass ->
                ctClass.refClasses.contains(targetClass)
            }
        }.filter {
            it.refClasses.all {
                var found: Boolean;
                try {
                    classPool[it as String]
                    found = true
                } catch (e: NotFoundException) {
                    found = false
                }
                found
            }
        }.forEach(action)
    }

    private inline fun forEachCanRecompileAppClass( action: (CtClass) -> Unit) {
        val appClasses = mCtClassInputMap.keys
        appClasses.filter {
            it.refClasses.all {
                var found: Boolean;
                try {
                    classPool[it as String]
                    found = true
                } catch (e: NotFoundException) {
                    found = false
                }
                found
            }
        }.forEach(action)
    }

    private fun step1_renameShadowClass() {
        forEachAppClass { ctClass ->
            RenameMap.forEach {
                ctClass.replaceClassName(it.key, it.value)
            }
        }

        // 替换跨插件apk创建view相关的类
        renameRemoteViewCreatorClass()
    }

    private fun step2_findFragments() {
        forEachAppClass { ctClass ->
            if (ctClass.isDialogFragment()) {
                mAppDialogFragments.add(ctClass)
            } else if (ctClass.isFragment()) {
                mAppFragments.add(ctClass)
            }
        }
    }

    private fun step3_renameFragments() {
        val fragmentsName = listOf(mAppFragments, mAppDialogFragments).flatten().flatMap { listOf(it.name) }
        forEachAppClass { ctClass ->
            fragmentsName.forEach { fragmentName ->
                //把插件中更改插件中的 fragment 类型，如  XXXFragment 添加后缀 XXXFragment_
                ctClass.replaceClassName(fragmentName, fragmentName.appendFragmentAppendix())
            }
        }
        listOf(
                mAppFragments to containerFragmentCtClass,
                mAppDialogFragments to containerDialogFragmentCtClass
        ).forEach { (fragmentSet, container) ->
            fragmentSet.forEach {
                val inputClass = mCtClassInputMap[it]!!
                val originalFragmentName = it.name.removeFragmentAppendix()
                var ctClassOriginOutputFile: File? = null
                var ctClassOriginOutputEntryName: String? = null
                when (inputClass) {
                    is DirInputClass -> {
                        ctClassOriginOutputFile = inputClass.getOutput(originalFragmentName)
                    }
                    is JarInputClass -> {
                        ctClassOriginOutputEntryName = inputClass.getOutput(originalFragmentName)
                    }
                }

                inputClass.renameOutput(originalFragmentName, it.name)

                //重新生成一个容器类，并且这个容器是继承于 ContainerFragment的，而且这个类的的名就是就是插件中使用的名字。
                //到这里，容器类使用的名字就是 插件代码中用的类名， 而插件中使用的类名则为 插件类名加后缀_
                val newContainerFragmentCtClass = classPool.makeClass(originalFragmentName, container)
                when (inputClass) {
                    is DirInputClass -> {
                        inputClass.addOutput(newContainerFragmentCtClass.name, ctClassOriginOutputFile!!)
                    }
                    is JarInputClass -> {
                        inputClass.addOutput(newContainerFragmentCtClass.name, ctClassOriginOutputEntryName!!)
                    }
                }
            }
        }
    }

    private fun step4_redirectDialogMethod() {
        val dialogMethods = classPool[AndroidDialogClassname].methods!!
        val shadowDialogMethods = classPool[ShadowDialogClassname].methods!!
        val method_getOwnerActivity = dialogMethods.find { it.name == "getOwnerActivity" }!!
        val method_setOwnerActivity = dialogMethods.find { it.name == "setOwnerActivity" }!!
        val method_getOwnerPluginActivity = shadowDialogMethods.find { it.name == "getOwnerPluginActivity" }!!
        val method_setOwnerPluginActivity = shadowDialogMethods.find { it.name == "setOwnerPluginActivity" }!!
        //appClass中的Activity都已经被改名为ShadowActivity了．所以要把方法签名也先改一下．
        method_getOwnerActivity.copyDescriptorFrom(method_getOwnerPluginActivity)
        method_setOwnerActivity.copyDescriptorFrom(method_setOwnerPluginActivity)

        val codeConverter = CodeConverter()
        codeConverter.redirectMethodCall(method_getOwnerActivity, method_getOwnerPluginActivity)
        codeConverter.redirectMethodCall(method_setOwnerActivity, method_setOwnerPluginActivity)

        forEachCanRecompileAppClass(listOf(ShadowDialogClassname)) { appCtClass ->
            try {
                appCtClass.instrument(codeConverter)
            } catch (e: Exception) {
                System.err.println("处理" + appCtClass.name + "时出错")
                throw e
            }
        }
    }

    private fun step5_renameWebViewChildClass(){
        forEachCanRecompileAppClass { ctClass ->
            if (ctClass.superclass.name == AndroidWebViewClassname) {
                ctClass.classFile.superclass = ShadowWebViewClassname
            }
        }

        val codeConverter = CodeConverter()
        codeConverter.replaceNew(classPool[AndroidWebViewClassname], classPool[ShadowWebViewClassname])
        forEachCanRecompileAppClass(listOf(AndroidWebViewClassname)) { appCtClass ->
            try {
                appCtClass.instrument(codeConverter)
            } catch (e: Exception) {
                System.err.println("处理" + appCtClass.name + "时出错")
                throw e
            }
        }
    }

    private fun step6_redirectPendingIntentMethod(){
        val pendingIntentMethod = classPool[AndroidPendingIntentClassname].methods!!
        val shadowPendingIntentMethod = classPool[ShadowPendingIntentClassname].methods!!

        val method_getPengdingIntent = pendingIntentMethod.filter { it.name == "getService" || it.name == "getActivity" }
        val shadow_method_getPengdingIntent = shadowPendingIntentMethod.filter { it.name == "getService" || it.name == "getActivity"}!!
        val codeConverter = CodeConverter()

        for( ctAndroidMethod in method_getPengdingIntent) {
            for (ctShadowMedthod in shadow_method_getPengdingIntent) {
                if(ctShadowMedthod.methodInfo.name == ctAndroidMethod.methodInfo.name && ctAndroidMethod.methodInfo.descriptor == ctShadowMedthod.methodInfo.descriptor){
                    codeConverter.redirectMethodCall(ctAndroidMethod, ctShadowMedthod)
                }
            }
        }

        forEachCanRecompileAppClass(listOf(AndroidPendingIntentClassname)) { appCtClass ->
            try {
                appCtClass.instrument(codeConverter)
            } catch (e: Exception) {
                System.err.println("处理" + appCtClass.name + "时出错")
                throw e
            }
        }

    }

    private fun step7_redirectUriMethod(){
        val uriMethod = classPool[AndroidUriClassname].methods!!
        val shadowUriMethod = classPool[ShadowUriClassname].methods!!

        val method_parse = uriMethod.filter { it.name == "parse"  }
        val shadow_method_parse = shadowUriMethod.filter { it.name == "parse"}!!
        val codeConverter = CodeConverter()

        for( ctAndroidMethod in method_parse) {
            for (ctShadowMedthod in shadow_method_parse) {
                if( ctAndroidMethod.methodInfo.descriptor == ctShadowMedthod.methodInfo.descriptor){

                    //从字节码上，修改android 原生的 Uri 中的 parse 方法
                    codeConverter.redirectMethodCall(ctAndroidMethod, ctShadowMedthod)
                }
            }
        }

        forEachCanRecompileAppClass(listOf(AndroidUriClassname)) { appCtClass ->
            try {
                appCtClass.instrument(codeConverter)
            } catch (e: Exception) {
                System.err.println("处理" + appCtClass.name + "时出错")
                throw e
            }
        }

    }

    private fun step8_keepHostContext() {
        val ShadowContextClassName = "com.tencent.shadow.runtime.ShadowContext"

        data class Rule(
                val ctClass: CtClass
                , val ctMethod: CtMethod
                , val convertArgs: Array<Int>
        )

        fun parseKeepHostContextRules(): List<Rule> {
            fun String.assertRuleHasOnlyOneChar(char: Char) {
                if (this.count { it == char } != 1) {
                    throw IllegalArgumentException("rule:${this}中\'$char\'的数量不为1")
                }
            }

            val appClasses = mCtClassInputMap.keys

            return useHostContext().map { rule ->
                rule.assertRuleHasOnlyOneChar('(')
                rule.assertRuleHasOnlyOneChar(')')

                val indexOfLeftParenthesis = rule.indexOf('(')
                val indexOfRightParenthesis = rule.indexOf(')')

                val classNameAndMethodNamePart = rule.substring(0, indexOfLeftParenthesis)
                val indexOfLastDot = classNameAndMethodNamePart.indexOfLast { it == '.' }

                val className = classNameAndMethodNamePart.substring(0, indexOfLastDot)
                val methodName = classNameAndMethodNamePart.substring(indexOfLastDot + 1, indexOfLeftParenthesis)
                val methodParametersClassName = rule.substring(indexOfLeftParenthesis + 1, indexOfRightParenthesis).split(',')
                val keepSpecifying = rule.substring(indexOfRightParenthesis + 1)

                val ctClass = appClasses.find {
                    it.name == className
                } ?: throw ClassNotFoundException("没有找到${rule}中指定的类$className")

                val parametersCtClass = methodParametersClassName.map {
                    classPool.getOrNull(it)
                            ?: throw ClassNotFoundException("没有找到${rule}中指定的类$it")
                }.toTypedArray()
                val ctMethod = ctClass.getDeclaredMethod(methodName, parametersCtClass)

                val tmp = keepSpecifying.split('$')
                val convertArgs = tmp.subList(1, tmp.size).map { Integer.parseInt(it) }.toTypedArray()

                Rule(ctClass, ctMethod, convertArgs)
            }
        }

        val rules = parseKeepHostContextRules()

        fun wrapArg(num: Int): String = "(($ShadowContextClassName)\$${num}).getBaseContext()"

        for (rule in rules) {
            val ctClass = rule.ctClass
            val ctMethod = rule.ctMethod
            val cloneMethod = CtNewMethod.copy(ctMethod, ctMethod.name + "_KeepHostContext", ctClass, null)

            val newBodyBuilder = StringBuilder()
            newBodyBuilder.append("${ctMethod.name}(")
            for (i in 1..cloneMethod.parameterTypes.size) {//从1开始是因为在Javassist中$0表示this,$1表示第一个参数
                if (i > 1) {
                    newBodyBuilder.append(',')
                }
                if (i in rule.convertArgs) {
                    newBodyBuilder.append(wrapArg(i))
                } else {
                    newBodyBuilder.append("\$${i}")
                }
            }
            newBodyBuilder.append(");")

            cloneMethod.setBody(newBodyBuilder.toString())
            ctClass.addMethod(cloneMethod)

            val codeConverter = CodeConverter()
            codeConverter.redirectMethodCall(ctMethod, cloneMethod)
            forEachCanRecompileAppClass(listOf(ctClass.name)) {
                if (it != ctClass)
                    it.instrument(codeConverter)
            }
        }
    }

    private fun CtMethod.copyDescriptorFrom(other: CtMethod) {
        methodInfo.descriptor = other.methodInfo.descriptor
    }

    private fun String.appendFragmentAppendix() = this + "_"

    private fun String.removeFragmentAppendix() = this.substring(0, this.length - 1)

    private fun CtClass.isClassOf(className: String): Boolean {
        var tmp: CtClass? = this
        do {
            if (tmp?.name == className) {
                return true
            }
            try {
                tmp = tmp?.superclass
            } catch (e: NotFoundException) {
                return false
            }
        } while (tmp != null)
        return false
    }

    private fun CtClass.isFragment(): Boolean = isClassOf(ShadowFragmentClassname)
    private fun CtClass.isDialogFragment(): Boolean = isClassOf(ShadowDialogFragmentClassname)

    override fun getName(): String = "ShadowTransform"
}