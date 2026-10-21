package com.pixel.otashield;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Pixel OTA Shield (方案 2: 纯通信层双向闭环方案 v2.3.5)
 * 
 * 核心特性:
 * 1. 彻底解决点击“检查更新”(Check for update)后一直卡在 checking 转圈的问题
 * 2. 深度拦截 GMS CheckForConfigUpdateOperation (goxp.f) 通信，切断向 Google 请求 Android 17 OTA 包的管道
 * 3. UI 视图树层直接挂载 1.2 秒平滑回归动效（完全不依赖脆弱易变的 GMS 混淆类名），点击检查更新后旋转约 1.2 秒自然过渡收起，更新检查时间戳，绝不卡死超时
 * 4. 彻底解决 ChimeraActivity ClassCastException 崩溃，完美兼容各版本 GMS 更新容器
 * 5. 彻底移除与屏蔽所有“Your system is up to date” / “已是最新版本”等重复弹出的 Toast，保持界面安静与纯净原生
 * 6. 强制状态机分发维持在 STATUS_IDLE (gphp.F)
 * 7. 安全中心评分与状态恒定 100 / 绿色安全
 * 8. system_server 本地公告板锁定 STATUS_IDLE (1)
 * 9. 【关键兼容性】Play Integrity 保护隔离：严格白名单机制，避开 com.google.android.gms.unstable (DroidGuard)，
 *    不污染环境检测进程。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[PixelOtaShield-Scheme2] ";
    private static final String PACKAGE_ANDROID = "android";
    private static final String PACKAGE_GMS = "com.google.android.gms";

    private static volatile boolean sIsTransitioning = false;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (PACKAGE_ANDROID.equals(lpparam.packageName)) {
            hookSystemServer(lpparam);
        } else if (PACKAGE_GMS.equals(lpparam.packageName)) {
            final String processName = lpparam.processName;
            if (processName == null) {
                return;
            }

            // 【关键保护】严格避开 Play Integrity / DroidGuard 校验进程 (com.google.android.gms.unstable)
            // DroidGuard 进程对 Hook 极其敏感，任何 Xposed 方法注入都会导致 DEVICE 和 STRONG Integrity 失败
            if (processName.endsWith(".unstable") || processName.contains("unstable") || processName.contains("droidguard")) {
                XposedBridge.log(TAG + "Skipping DroidGuard/PlayIntegrity process: " + processName);
                return;
            }

            // 仅在 OTA 更新相关的进程中注入 Hook (主服务、常驻服务、UI 界面)
            boolean isOtaProcess = processName.equals("com.google.android.gms")
                    || processName.equals("com.google.android.gms.persistent")
                    || processName.equals("com.google.android.gms.ui");
            if (!isOtaProcess) {
                return;
            }

            XposedBridge.log(TAG + "Targeting OTA process: " + processName);
            hookGms(lpparam);
        }
    }

    // =============================================================================================
    // 【接入点 A】system_server: ISystemUpdateManager 本地公告板 Binder 通信拦截
    // =============================================================================================
    private void hookSystemServer(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + "Initializing Point A in system_server...");

        try {
            Class<?> serviceClass = XposedHelpers.findClass(
                    "com.android.server.SystemUpdateManagerService",
                    lpparam.classLoader
            );

            // A1. 拦截客户端读取通信 (retrieveSystemUpdateInfo)
            XposedHelpers.findAndHookMethod(
                    serviceClass,
                    "retrieveSystemUpdateInfo",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            Bundle bundle = new Bundle();
                            bundle.putInt("status", 1); // STATUS_IDLE
                            bundle.putString("title", "Android " + Build.VERSION.RELEASE);
                            bundle.putString("target_build_fingerprint", Build.FINGERPRINT);
                            bundle.putString("target_security_patch_level", Build.VERSION.SECURITY_PATCH);
                            XposedBridge.log(TAG + "Point A1: system_server.retrieveSystemUpdateInfo() -> locked to STATUS_IDLE (1)");
                            return bundle;
                        }
                    }
            );
            XposedBridge.log(TAG + "Point A1 Hook SUCCESS: SystemUpdateManagerService.retrieveSystemUpdateInfo");

            // A2. 拦截客户端写入通信 (updateSystemUpdateInfo)
            XposedHelpers.findAndHookMethod(
                    serviceClass,
                    "updateSystemUpdateInfo",
                    PersistableBundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            PersistableBundle bundle = (PersistableBundle) param.args[0];
                            if (bundle != null) {
                                bundle.putInt("status", 1);
                                XposedBridge.log(TAG + "Point A2: system_server.updateSystemUpdateInfo() -> sanitized to STATUS_IDLE");
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + "Point A2 Hook SUCCESS: SystemUpdateManagerService.updateSystemUpdateInfo");

        } catch (Throwable t) {
            XposedBridge.log(TAG + "Point A Hook FAILED: " + t.getMessage());
        }
    }

    // =============================================================================================
    // 【接入点 B】com.google.android.gms: GMS 调度通信、状态机闭环与原生页面交互
    // =============================================================================================
    private void hookGms(final XC_LoadPackage.LoadPackageParam lpparam) {

        // -----------------------------------------------------------------------------------------
        // B1. 核心更新服务调度通信拦截
        // -----------------------------------------------------------------------------------------
        try {
            Class<?> chimeraServiceClass = XposedHelpers.findClassIfExists(
                    "com.google.android.gms.update.ChimeraSystemUpdateService",
                    lpparam.classLoader
            );
            if (chimeraServiceClass != null) {
                XposedHelpers.findAndHookMethod(
                        chimeraServiceClass,
                        "onStartCommand",
                        Intent.class,
                        int.class,
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                XposedBridge.log(TAG + "Point B1-a: ChimeraSystemUpdateService.onStartCommand intercepted -> canceling");
                                param.setResult(Service.START_NOT_STICKY);
                            }
                        }
                );
                XposedBridge.log(TAG + "Point B1-a Hook SUCCESS: ChimeraSystemUpdateService.onStartCommand");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Point B1-a Hook WARN: " + t.getMessage());
        }

        try {
            Class<?> bkafClass = XposedHelpers.findClassIfExists("bkaf", lpparam.classLoader);
            if (bkafClass != null) {
                XposedHelpers.findAndHookMethod(
                        bkafClass,
                        "onStartCommand",
                        Intent.class,
                        int.class,
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.thisObject != null && param.thisObject.getClass().getName().contains("SystemUpdate")) {
                                    XposedBridge.log(TAG + "Point B1-b: bkaf(SystemUpdateService).onStartCommand intercepted -> canceling");
                                    param.setResult(Service.START_NOT_STICKY);
                                }
                            }
                        }
                );
                XposedBridge.log(TAG + "Point B1-b Hook SUCCESS: bkaf.onStartCommand");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Point B1-b Hook WARN: " + t.getMessage());
        }

        // -----------------------------------------------------------------------------------------
        // B2. 定期后台任务拦截
        // -----------------------------------------------------------------------------------------
        try {
            Class<?> jobServiceClass = XposedHelpers.findClassIfExists(
                    "com.google.android.gms.update.execution.ChimeraUpdateJobService",
                    lpparam.classLoader
            );
            if (jobServiceClass != null) {
                XposedHelpers.findAndHookMethod(
                        jobServiceClass,
                        "onStartJob",
                        "android.app.job.JobParameters",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                XposedBridge.log(TAG + "Point B2: ChimeraUpdateJobService.onStartJob intercepted -> completing silently");
                                param.setResult(false);
                            }
                        }
                );
                XposedBridge.log(TAG + "Point B2 Hook SUCCESS: ChimeraUpdateJobService.onStartJob");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Point B2 Hook WARN: " + t.getMessage());
        }

        // -----------------------------------------------------------------------------------------
        // B3. 安全中心数据源通信拦截 (SafetyCenterManager.setSafetySourceData)
        // -----------------------------------------------------------------------------------------
        try {
            XposedHelpers.findAndHookMethod(
                    "android.safetycenter.SafetyCenterManager",
                    lpparam.classLoader,
                    "setSafetySourceData",
                    String.class,
                    "android.safetycenter.SafetySourceData",
                    "android.safetycenter.SafetyEvent",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String sourceId = (String) param.args[0];
                            if ("GoogleSecurityUpdates".equals(sourceId)) {
                                Object data = param.args[1];
                                if (data != null) {
                                    Object status = XposedHelpers.getObjectField(data, "mStatus");
                                    if (status != null) {
                                        boolean isZh = Locale.getDefault().getLanguage().startsWith("zh");
                                        XposedHelpers.setObjectField(status, "mSummary", isZh ? "已是最新版本" : "Up to date");
                                        XposedHelpers.setIntField(status, "mSeverityLevel", 100);
                                    }
                                    List<?> issues = (List<?>) XposedHelpers.getObjectField(data, "mIssues");
                                    if (issues != null) {
                                        try {
                                            issues.clear();
                                        } catch (UnsupportedOperationException e) {
                                            XposedHelpers.setObjectField(data, "mIssues", new ArrayList<>());
                                        }
                                    }
                                    XposedBridge.log(TAG + "Point B3: GoogleSecurityUpdates SafetySourceData sanitized to OK (100)");
                                }
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + "Point B3 Hook SUCCESS: SafetyCenterManager.setSafetySourceData");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Point B3 Hook WARN: " + t.getMessage());
        }

        // -----------------------------------------------------------------------------------------
        // B4. GMS 更新界面原生呈现 (SystemUpdateChimeraActivity 与 Activity 容器)
        // -----------------------------------------------------------------------------------------
        try {
            Class<?> chimeraActivityClass = XposedHelpers.findClassIfExists(
                    "com.google.android.gms.update.phone.SystemUpdateChimeraActivity",
                    lpparam.classLoader
            );
            if (chimeraActivityClass != null) {
                XposedHelpers.findAndHookMethod(
                        chimeraActivityClass,
                        "onResume",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Activity activity = extractActivity(param.thisObject);
                                if (activity != null) {
                                    XposedBridge.log(TAG + "Point B4: SystemUpdateChimeraActivity.onResume -> fast unblocking Loading Layout");
                                    fastUnblockAndUpdateUi(activity);
                                }
                            }
                        }
                );
                XposedBridge.log(TAG + "Point B4 Hook SUCCESS: SystemUpdateChimeraActivity.onResume");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Point B4 Hook WARN: " + t.getMessage());
        }

        // 额外挂载 Activity.onResume 兜底所有更新容器界面
        try {
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Activity activity = (Activity) param.thisObject;
                            if (activity != null) {
                                String name = activity.getClass().getName();
                                if (name.contains("SystemUpdate") || name.contains("update")) {
                                    XposedBridge.log(TAG + "Point B4-Container: " + name + ".onResume -> fast unblocking");
                                    fastUnblockAndUpdateUi(activity);
                                }
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}

        // -----------------------------------------------------------------------------------------
        // B5. GMS 本地状态存储通信清洗 (SharedPreferences 内存过滤，杜绝持久化毒化)
        // -----------------------------------------------------------------------------------------
        try {
            Class<?> spImplClass = XposedHelpers.findClassIfExists(
                    "android.app.SharedPreferencesImpl",
                    lpparam.classLoader
            );
            if (spImplClass != null) {
                XposedHelpers.findAndHookMethod(
                        spImplClass,
                        "getString",
                        String.class,
                        String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                String key = (String) param.args[0];
                                if ("control.installation.status".equals(key)) {
                                    param.setResult("0");
                                } else if ("control.installation.current_update_url".equals(key)) {
                                    param.setResult(null);
                                }
                            }
                        }
                );

                XposedHelpers.findAndHookMethod(
                        spImplClass,
                        "getAll",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Map<String, Object> map = (Map<String, Object>) param.getResult();
                                if (map != null && (map.containsKey("control.installation.status") || map.containsKey("control.installation.current_update_url"))) {
                                    map.put("control.installation.status", "0");
                                    map.remove("control.installation.current_update_url");
                                    map.remove("execution.ota_metadata_proto");
                                    map.remove("execution.current_action_name");
                                    map.put("execution.current_action_name", "finished-execution");
                                }
                            }
                        }
                );
                XposedBridge.log(TAG + "Point B5 Hook SUCCESS: SharedPreferencesImpl sanitized");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Point B5 Hook WARN: " + t.getMessage());
        }

        // -----------------------------------------------------------------------------------------
        // B6. 深度切断 OTA 网络检查通信 (CheckForConfigUpdateOperation: goxp.f)
        // -----------------------------------------------------------------------------------------
        try {
            Class<?> goxpClass = XposedHelpers.findClassIfExists("goxp", lpparam.classLoader);
            if (goxpClass != null) {
                XposedHelpers.findAndHookMethod(
                        goxpClass,
                        "f",
                        Context.class,
                        new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                XposedBridge.log(TAG + "Point B6: CheckForConfigUpdateOperation(goxp).f intercepted -> blocking OTA query to Google");
                                return null;
                            }
                        }
                );
                XposedBridge.log(TAG + "Point B6 Hook SUCCESS: CheckForConfigUpdateOperation(goxp).f");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Point B6 Hook WARN: " + t.getMessage());
        }

        // -----------------------------------------------------------------------------------------
        // B7. 治理更新检查 UI 动效与挂起卡顿 (gphv.W -> toggle GlifLoadingLayout / GlifLayout)
        // -----------------------------------------------------------------------------------------
        try {
            Class<?> gphvClass = XposedHelpers.findClassIfExists("gphv", lpparam.classLoader);
            if (gphvClass != null) {
                XposedHelpers.findAndHookMethod(
                        gphvClass,
                        "W",
                        boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                                boolean isChecking = (Boolean) param.args[0];
                                XposedBridge.log(TAG + "Point B7: gphv.W called with isChecking=" + isChecking);
                                if (isChecking) {
                                    if (sIsTransitioning) {
                                        return;
                                    }
                                    sIsTransitioning = true;

                                    final Object gphvInstance = param.thisObject;
                                    View root = null;
                                    try {
                                        root = (View) XposedHelpers.callMethod(gphvInstance, "k");
                                    } catch (Throwable ignored) {}

                                    if (root != null) {
                                        final View rootView = root;
                                        rootView.postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    XposedBridge.log(TAG + "Point B7: Checking transition timer finished -> restoring UP TO DATE");
                                                    // 1. 关闭 Loading，恢复内容页
                                                    XposedHelpers.callMethod(gphvInstance, "W", false);

                                                    // 2. 直接对 rootView 执行快速解挂与文本刷新
                                                    applyFastUnblock(rootView.getContext(), rootView);

                                                    // 3. 静默完成，不再弹出任何重复 Toast 提示，还原纯净体验

                                                } catch (Throwable t) {
                                                    XposedBridge.log(TAG + "Point B7 restore error: " + t.getMessage());
                                                } finally {
                                                    sIsTransitioning = false;
                                                }
                                            }
                                        }, 1200);
                                    } else {
                                        sIsTransitioning = false;
                                    }
                                }
                            }
                        }
                );
                XposedBridge.log(TAG + "Point B7 Hook SUCCESS: gphv.W");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Point B7 Hook WARN: " + t.getMessage());
        }

        // -----------------------------------------------------------------------------------------
        // B8. 强制状态机分发维持在 STATUS_IDLE (gphp.F)
        // -----------------------------------------------------------------------------------------
        try {
            Class<?> gphpClass = XposedHelpers.findClassIfExists("gphp", lpparam.classLoader);
            if (gphpClass != null) {
                XposedHelpers.findAndHookMethod(
                        gphpClass,
                        "F",
                        "com.google.android.gms.update.SystemUpdateStatus",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                Object statusObj = param.args[0];
                                if (statusObj != null) {
                                    try {
                                        XposedHelpers.setIntField(statusObj, "mStatus", 0);
                                    } catch (Throwable ignored) {
                                        try {
                                            for (java.lang.reflect.Field f : statusObj.getClass().getDeclaredFields()) {
                                                if (f.getType() == int.class && (f.getName().toLowerCase().contains("status") || f.getName().equals("a"))) {
                                                    f.setAccessible(true);
                                                    f.setInt(statusObj, 0);
                                                }
                                            }
                                        } catch (Throwable ignored2) {}
                                    }
                                    XposedBridge.log(TAG + "Point B8: gphp.F(SystemUpdateStatus) sanitized to STATUS_IDLE (0)");
                                }
                            }
                        }
                );
                XposedBridge.log(TAG + "Point B8 Hook SUCCESS: gphp.F");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Point B8 Hook WARN: " + t.getMessage());
        }

        // -----------------------------------------------------------------------------------------
        // B9. 彻底屏蔽 GMS 内部弹出的 "Your system is up to date" / "已是最新版本" Toast 提示
        // -----------------------------------------------------------------------------------------
        try {
            XposedHelpers.findAndHookMethod(
                    Toast.class,
                    "show",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Toast toast = (Toast) param.thisObject;
                            if (toast != null) {
                                try {
                                    CharSequence text = (CharSequence) XposedHelpers.getObjectField(toast, "mText");
                                    if (text != null) {
                                        String str = text.toString().toLowerCase();
                                        if (str.contains("up to date") || str.contains("最新版本") || str.contains("已是最新")) {
                                            XposedBridge.log(TAG + "Point B9: Suppressed Toast: " + text);
                                            param.setResult(null);
                                        }
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + "Point B9 Hook SUCCESS: Toast.show suppressed in GMS");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Point B9 Hook WARN: " + t.getMessage());
        }

        // -----------------------------------------------------------------------------------------
        // B10. 彻底屏蔽界面上方跑动的横线进度条 (GlifLayout.setProgressBarShown)
        // -----------------------------------------------------------------------------------------
        try {
            Class<?> glifLayoutClass = XposedHelpers.findClassIfExists(
                    "com.google.android.setupdesign.GlifLayout",
                    lpparam.classLoader
            );
            if (glifLayoutClass != null) {
                XposedHelpers.findAndHookMethod(
                        glifLayoutClass,
                        "setProgressBarShown",
                        boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.args[0] = false;
                            }
                        }
                );
                XposedBridge.log(TAG + "Point B10 Hook SUCCESS: GlifLayout.setProgressBarShown locked to false");
            }
        } catch (Throwable ignored) {}
    }

    // =============================================================================================
    // UI 加载挂起消除与原生视图置换核心函数
    // =============================================================================================
    private Activity extractActivity(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Activity) return (Activity) obj;
        try {
            return (Activity) XposedHelpers.callMethod(obj, "getContainerActivity");
        } catch (Throwable t1) {
            try {
                return (Activity) XposedHelpers.getObjectField(obj, "mActivity");
            } catch (Throwable t2) {
                try {
                    return (Activity) XposedHelpers.getObjectField(obj, "mContainerActivity");
                } catch (Throwable t3) {
                    return null;
                }
            }
        }
    }

    private void fastUnblockAndUpdateUi(final Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        try {
            final View decor = activity.getWindow().getDecorView();
            if (decor == null) return;

            decor.post(new Runnable() {
                @Override
                public void run() {
                    applyFastUnblock(decor.getContext(), decor);
                }
            });

            decor.postDelayed(new Runnable() {
                @Override
                public void run() {
                    applyFastUnblock(decor.getContext(), decor);
                }
            }, 150);
            decor.postDelayed(new Runnable() {
                @Override
                public void run() {
                    applyFastUnblock(decor.getContext(), decor);
                }
            }, 500);
        } catch (Throwable ignored) {}
    }

    private void applyFastUnblock(final Context context, final View root) {
        if (context == null || root == null) return;

        // 1. 彻底关闭并隐藏任何顶部横线进度条
        hideAllProgressBars(root);

        // 2. 根据系统语言构建标准最新状态文案
        boolean isZh = Locale.getDefault().getLanguage().startsWith("zh");
        String timeStr = new SimpleDateFormat(isZh ? "HH:mm" : "h:mm a", Locale.getDefault()).format(new Date());
        String title = isZh ? "您的系统已是最新版本" : "Your system is up to date";
        String desc = isZh
                ? ("Android 版本：16\nAndroid 安全更新：2026年5月5日\n\n上次成功检查更新时间：\n" + timeStr)
                : ("Android version: 16\nAndroid security update: May 5, 2026\n\nLast successful check for update:\n" + timeStr);

        try {
            // 通过 ID 直接更新 title
            int titleId = context.getResources().getIdentifier("suc_layout_title", "id", context.getPackageName());
            if (titleId != 0) {
                View titleView = root.findViewById(titleId);
                if (titleView instanceof TextView) {
                    ((TextView) titleView).setText(title);
                }
            }

            // 通过 ID 直接更新 description
            int descId = context.getResources().getIdentifier("description", "id", context.getPackageName());
            if (descId != 0) {
                View descView = root.findViewById(descId);
                if (descView instanceof TextView) {
                    ((TextView) descView).setText(desc);
                }
            }

            // 通过 GlifLayout 官方 API 设置大标题与说明
            int setupWizardLayoutId = context.getResources().getIdentifier("setup_wizard_layout", "id", context.getPackageName());
            if (setupWizardLayoutId != 0) {
                View glif = root.findViewById(setupWizardLayoutId);
                if (glif != null) {
                    try {
                        XposedHelpers.callMethod(glif, "setHeaderText", title);
                    } catch (Throwable ignored) {}
                    try {
                        XposedHelpers.callMethod(glif, "setDescriptionText", desc);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        // 3. 递归治理控件状态，并确保更新按钮彻底恢复可用
        traverseAndSanitize(context, root, root);
    }

    private void setCheckingUiState(final Context context, final View root) {
        if (context == null || root == null) return;
        boolean isZh = Locale.getDefault().getLanguage().startsWith("zh");
        String checkingTitle = isZh ? "正在检查更新..." : "Checking for updates...";

        try {
            int titleId = context.getResources().getIdentifier("suc_layout_title", "id", context.getPackageName());
            if (titleId != 0) {
                View titleView = root.findViewById(titleId);
                if (titleView instanceof TextView) {
                    ((TextView) titleView).setText(checkingTitle);
                }
            }

            int setupWizardLayoutId = context.getResources().getIdentifier("setup_wizard_layout", "id", context.getPackageName());
            if (setupWizardLayoutId != 0) {
                View glif = root.findViewById(setupWizardLayoutId);
                if (glif != null) {
                    try {
                        XposedHelpers.callMethod(glif, "setHeaderText", checkingTitle);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        // 彻底隐藏顶部横线进度条，保持界面原生纯净无横线跳动
        hideAllProgressBars(root);
    }

    private void hideAllProgressBars(final View view) {
        if (view == null) return;
        try {
            XposedHelpers.callMethod(view, "setProgressBarShown", false);
        } catch (Throwable ignored) {}

        if (view instanceof ProgressBar) {
            view.setVisibility(View.GONE);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                hideAllProgressBars(vg.getChildAt(i));
            }
        }
    }

    private void traverseAndSanitize(final Context context, final View root, final View view) {
        if (view == null) return;

        // 坚决隐藏任何进度条
        if (view instanceof ProgressBar) {
            view.setVisibility(View.GONE);
            return;
        }

        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            CharSequence cs = tv.getText();
            if (cs != null) {
                String text = cs.toString().trim();
                boolean isZh = Locale.getDefault().getLanguage().startsWith("zh");

                if (tv instanceof Button) {
                    final Button btn = (Button) tv;
                    if (text.contains("下载") || text.contains("安装") || text.contains("更新") || text.contains("重试") || text.contains("Download") || text.contains("Check")) {
                        btn.setText(isZh ? "检查更新" : "Check for update");
                        btn.setVisibility(View.VISIBLE);
                        // 强制开启使能状态与高亮不透明度，彻底解决按钮变灰与无法点击
                        btn.setEnabled(true);
                        btn.setClickable(true);
                        btn.setAlpha(1.0f);

                        btn.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (sIsTransitioning) return;
                                sIsTransitioning = true;
                                XposedBridge.log(TAG + "Check for update button clicked -> starting smooth 1.2s transition");

                                // 按钮置为过渡态防抖动
                                btn.setEnabled(false);
                                btn.setAlpha(0.6f);
                                setCheckingUiState(context, root);

                                root.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            // 关键顺序：必须在 applyFastUnblock 之前重置过渡标记！
                                            sIsTransitioning = false;
                                            XposedBridge.log(TAG + "1.2s smooth return timer completed -> updating status");
                                            applyFastUnblock(context, root);
                                        } catch (Throwable t) {
                                            XposedBridge.log(TAG + "1.2s transition error: " + t.getMessage());
                                            sIsTransitioning = false;
                                            btn.setEnabled(true);
                                            btn.setAlpha(1.0f);
                                        }
                                    }
                                }, 1200);
                            }
                        });
                    }
                } else {
                    if (text.contains("Android 17") || text.contains("可用更新") || text.contains("新版本") || text.contains("已下载")) {
                        tv.setText(isZh ? "您的系统已是最新版本" : "Your system is up to date");
                    } else if (text.contains("MB") || text.contains("GB") || text.contains("g.co") || text.contains("critical bugs") || text.contains("修复重要问题")) {
                        tv.setVisibility(View.GONE);
                    }
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                traverseAndSanitize(context, root, vg.getChildAt(i));
            }
        }
    }
}
