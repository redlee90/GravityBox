/*
 * Copyright (C) 2013 Peter Gregus for GravityBox Project (C3C076@xda)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ceco.lollipop.gravitybox;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import com.ceco.lollipop.gravitybox.ModStatusBar.ContainerType;
import com.ceco.lollipop.gravitybox.R;
import com.ceco.lollipop.gravitybox.managers.StatusBarIconManager;
import com.ceco.lollipop.gravitybox.managers.SysUiManagers;
import com.ceco.lollipop.gravitybox.managers.StatusBarIconManager.ColorInfo;
import com.ceco.lollipop.gravitybox.managers.StatusBarIconManager.IconManagerListener;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_InitPackageResources.InitPackageResourcesParam;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.XModuleResources;
import android.content.res.XResources;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

public class StatusbarSignalCluster implements BroadcastSubReceiver, IconManagerListener {
    public static final String TAG = "GB:StatusbarSignalCluster";
    protected static final boolean DEBUG = false;

    protected static XSharedPreferences sPrefs;

    // HSPA+
    protected static int sQsHpResId;
    protected static int sSbHpResId;
    protected static int[][] DATA_HP;
    protected static int[] QS_DATA_HP;

    protected ContainerType mContainerType;
    protected LinearLayout mView;
    protected StatusBarIconManager mIconManager;
    protected Resources mResources;
    protected Resources mGbResources;
    protected Field mFldWifiGroup;
    private Field mFldMobileGroup;
    private List<String> mErrorsLogged = new ArrayList<String>();

    // Data activity
    protected boolean mDataActivityEnabled;
    protected Object mNetworkControllerCallback;
    protected SignalActivity mWifiActivity;
    protected SignalActivity mMobileActivity;

    protected static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    protected void logAndMute(String key, Throwable t) {
        if (!mErrorsLogged.contains(key)) {
            XposedBridge.log(t);
            mErrorsLogged.add(key);
        }
    }

    // Signal activity
    enum SignalType { WIFI, MOBILE };
    class SignalActivity {
        boolean enabled;
        boolean activityIn;
        boolean activityOut;
        Drawable imageDataIn;
        Drawable imageDataOut;
        Drawable imageDataInOut;
        ImageView activityView;
        SignalType signalType;

        public SignalActivity(ViewGroup container, SignalType type) {
            this(container, type, Gravity.BOTTOM | Gravity.CENTER);
        }

        public SignalActivity(ViewGroup container, SignalType type, int gravity) {
            signalType = type;
            if (mDataActivityEnabled) {
                activityView = new ImageView(container.getContext());
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
                lp.gravity = gravity;
                activityView.setLayoutParams(lp);
                activityView.setTag("gbDataActivity");
                container.addView(activityView);
                if (type == SignalType.WIFI) {
                    imageDataIn = mGbResources.getDrawable(R.drawable.stat_sys_wifi_in);
                    imageDataOut = mGbResources.getDrawable(R.drawable.stat_sys_wifi_out);
                    imageDataInOut = mGbResources.getDrawable(R.drawable.stat_sys_wifi_inout);
                } else if (type == SignalType.MOBILE) {
                    imageDataIn = mGbResources.getDrawable(R.drawable.stat_sys_signal_in);
                    imageDataOut = mGbResources.getDrawable(R.drawable.stat_sys_signal_out);
                    imageDataInOut = mGbResources.getDrawable(R.drawable.stat_sys_signal_inout);
                }
                updateDataActivityColor();
            }
        }

        public void update() {
            try {
                update(enabled, activityIn, activityOut);
            } catch (Throwable t) {
                logAndMute("SignalActivity.update", t);
            }
        }

        public void update(boolean enabled, boolean in, boolean out) throws Throwable {
            this.enabled = enabled;
            activityIn = in;
            activityOut = out;

            // in/out activity
            if (mDataActivityEnabled) {
                if (activityIn && activityOut) {
                    activityView.setImageDrawable(imageDataInOut);
                } else if (activityIn) {
                    activityView.setImageDrawable(imageDataIn);
                } else if (activityOut) {
                    activityView.setImageDrawable(imageDataOut);
                } else {
                    activityView.setImageDrawable(null);
                }
                activityView.setVisibility(activityIn || activityOut ?
                        View.VISIBLE : View.GONE);
                if (DEBUG) log("SignalActivity: " + signalType + ": data activity indicators updated");
            }
        }

        public void updateDataActivityColor() {
            if (mIconManager == null) return;

            if (imageDataIn != null) {
                imageDataIn = mIconManager.applyDataActivityColorFilter(imageDataIn);
            }
            if (imageDataOut != null) {
                imageDataOut = mIconManager.applyDataActivityColorFilter(imageDataInOut);
            }
            if (imageDataInOut != null) {
                imageDataInOut = mIconManager.applyDataActivityColorFilter(imageDataInOut);
            }
        }
    } 

    public static void initResources(XSharedPreferences prefs, InitPackageResourcesParam resparam) {
        XModuleResources modRes = XModuleResources.createInstance(GravityBox.MODULE_PATH, resparam.res);

        if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_SIGNAL_CLUSTER_HPLUS, false) &&
                !Utils.isMotoXtDevice() && !Utils.isMtkDevice()) {

            sQsHpResId = XResources.getFakeResId(modRes, R.drawable.ic_qs_signal_hp);
            sSbHpResId = XResources.getFakeResId(modRes, R.drawable.stat_sys_data_fully_connected_hp);
    
            resparam.res.setReplacement(sQsHpResId, modRes.fwd(R.drawable.ic_qs_signal_hp));
            resparam.res.setReplacement(sSbHpResId, modRes.fwd(R.drawable.stat_sys_data_fully_connected_hp));
    
            DATA_HP = new int[][] {
                    { sSbHpResId, sSbHpResId, sSbHpResId, sSbHpResId },
                    { sSbHpResId, sSbHpResId, sSbHpResId, sSbHpResId }
            };
            QS_DATA_HP = new int[] { sQsHpResId, sQsHpResId };
            if (DEBUG) log("H+ icon resources initialized");
        }

        if (!Utils.isMtkDevice()) {
            String lteStyle = prefs.getString(GravityBoxSettings.PREF_KEY_SIGNAL_CLUSTER_LTE_STYLE, "DEFAULT");
            if (!lteStyle.equals("DEFAULT")) {
                resparam.res.setReplacement(ModStatusBar.PACKAGE_NAME, "bool", "config_show4GForLTE",
                        lteStyle.equals("4G"));
            }
        }
    }

    public static StatusbarSignalCluster create(ContainerType containerType,
            LinearLayout view, XSharedPreferences prefs) throws Throwable {
        sPrefs = prefs;
        if (Utils.isMotoXtDevice()) {
            return new StatusbarSignalClusterMoto(containerType, view);
        } else if (Utils.isMtkDevice()) {
            return new StatusbarSignalClusterMtk(containerType, view);
        } else {
            return new StatusbarSignalCluster(containerType, view);
        }
    }

    public StatusbarSignalCluster(ContainerType containerType, LinearLayout view) throws Throwable {
        mContainerType = containerType;
        mView = view;
        mIconManager = SysUiManagers.IconManager;
        mResources = mView.getResources();
        mGbResources = Utils.getGbContext(mView.getContext()).getResources();

        mFldWifiGroup = resolveField("mWifiGroup", "mWifiViewGroup");
        mFldMobileGroup = resolveField("mMobileGroup", "mMobileViewGroup");

        initPreferences();
        createHooks();

        if (mIconManager != null) {
            mIconManager.registerListener(this);
        }
    }

    private Field resolveField(String... fieldNames) {
        Field field = null;
        for (String fieldName : fieldNames) {
            try {
                field = XposedHelpers.findField(mView.getClass(), fieldName);
                if (DEBUG) log(fieldName + " field found");
                break;
            } catch (NoSuchFieldError nfe) {
                if (DEBUG) log(fieldName + " field NOT found");
            }
        }
        return field;
    }

    protected void createHooks() {
        if (Build.VERSION.SDK_INT >= 22) {
            try {
                XposedHelpers.findAndHookMethod(mView.getClass(), "getOrInflateState", int.class, new XC_MethodHook() {
                    @SuppressWarnings("unchecked")
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (mView != param.thisObject) return;

                        if (mDataActivityEnabled && mMobileActivity == null) {
                            List<Object> phoneStates = (List<Object>) XposedHelpers.getObjectField(
                                    param.thisObject, "mPhoneStates");
                            ViewGroup mobileGroup = (ViewGroup) XposedHelpers.getObjectField(
                                phoneStates.get(0), "mMobileGroup");
                            mMobileActivity = new SignalActivity(mobileGroup, SignalType.MOBILE,
                                Gravity.BOTTOM | Gravity.END);
                        }
                        update();
                    }
                });
            } catch (Throwable t) {
                log("Error hooking getOrInflateState: " + t.getMessage());
            }
        }

        if (mDataActivityEnabled) {
            try {
                XposedHelpers.findAndHookMethod(mView.getClass(), "onAttachedToWindow", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (mView != param.thisObject) return;

                        ViewGroup wifiGroup = (ViewGroup) mFldWifiGroup.get(param.thisObject);
                        if (wifiGroup != null) {
                            mWifiActivity = new SignalActivity(wifiGroup, SignalType.WIFI);
                            if (DEBUG) log("onAttachedToWindow: mWifiActivity created");
                        }

                        if (Build.VERSION.SDK_INT < 22) {
                            ViewGroup mobileGroup = (ViewGroup) mFldMobileGroup.get(param.thisObject);
                            if (mobileGroup != null) {
                                mMobileActivity = new SignalActivity(mobileGroup, SignalType.MOBILE,
                                        Gravity.BOTTOM | Gravity.END);
                                if (DEBUG) log("onAttachedToWindow: mMobileActivity created");
                            }
                        }
                    }
                });

                XposedHelpers.findAndHookMethod(mView.getClass(), "onDetachedFromWindow", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (mView != param.thisObject) return;

                        mWifiActivity = null;
                        mMobileActivity = null;
                        if (DEBUG) log("onDetachedFromWindow: signal activities destoyed");
                    }
                });
            } catch (Throwable t) {
                log("Error hooking SignalActivity related methods: " + t.getMessage());
            }
        }

        if (sPrefs.getBoolean(GravityBoxSettings.PREF_KEY_SIGNAL_CLUSTER_HPLUS, false) &&
                !Utils.isMotoXtDevice()) {
            try {
                if (Build.VERSION.SDK_INT >= 22) {
                    final Class<?> mobileNetworkCtrlClass = XposedHelpers.findClass(
                            "com.android.systemui.statusbar.policy.NetworkControllerImpl.MobileSignalController", 
                            mView.getContext().getClassLoader());
                    XposedHelpers.findAndHookMethod(mobileNetworkCtrlClass, "mapIconSets", new XC_MethodHook() {
                        @SuppressWarnings("unchecked")
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            SparseArray<Object> iconSet = (SparseArray<Object>) XposedHelpers.getObjectField(
                                    param.thisObject, "mNetworkToIconLookup");
                            Object hGroup = iconSet.get(TelephonyManager.NETWORK_TYPE_HSPAP);
                            Constructor<?> c = hGroup.getClass().getConstructor(
                                    String.class, int[][].class, int[][].class, int[].class,
                                    int.class, int.class, int.class, int.class, 
                                    int.class, int.class, int.class, boolean.class, int[].class);
                            Object hPlusGroup = c.newInstance("HP",
                                   XposedHelpers.getObjectField(hGroup, "mSbIcons"),
                                   XposedHelpers.getObjectField(hGroup, "mQsIcons"),
                                   XposedHelpers.getObjectField(hGroup, "mContentDesc"),
                                   XposedHelpers.getIntField(hGroup, "mSbNullState"),
                                   XposedHelpers.getIntField(hGroup, "mQsNullState"),
                                   XposedHelpers.getIntField(hGroup, "mSbDiscState"),
                                   XposedHelpers.getIntField(hGroup, "mQsDiscState"),
                                   XposedHelpers.getIntField(hGroup, "mDiscContentDesc"),
                                   XposedHelpers.getIntField(hGroup, "mDataContentDescription"),
                                   sSbHpResId,
                                   XposedHelpers.getBooleanField(hGroup, "mIsWide"),
                                   new int[] { sQsHpResId, sQsHpResId });
                            iconSet.put(TelephonyManager.NETWORK_TYPE_HSPAP, hPlusGroup);
                        }
                    });
                } else {
                    final Class<?> networkCtrlClass = XposedHelpers.findClass(
                            "com.android.systemui.statusbar.policy.NetworkControllerImpl", 
                            mView.getContext().getClassLoader());
                    XposedHelpers.findAndHookMethod(networkCtrlClass, "updateDataNetType", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (DEBUG) log("NetworkController: updateDataNetType");
                            if (!(XposedHelpers.getBooleanField(param.thisObject, "mIsWimaxEnabled") &&
                                    XposedHelpers.getBooleanField(param.thisObject, "mWimaxConnected")) &&
                                    XposedHelpers.getIntField(param.thisObject, "mDataNetType") ==
                                        TelephonyManager.NETWORK_TYPE_HSPAP) {
                                int inetCondition = XposedHelpers.getIntField(param.thisObject, "mInetCondition");
                                XposedHelpers.setObjectField(param.thisObject, "mDataIconList", DATA_HP[inetCondition]);
                                boolean isCdmaEri = (Boolean) XposedHelpers.callMethod(param.thisObject, "isCdma") &&
                                        (Boolean) XposedHelpers.callMethod(param.thisObject, "isCdmaEri");
                                boolean isRoaming = ((TelephonyManager) XposedHelpers.getObjectField(
                                        param.thisObject, "mPhone")).isNetworkRoaming();
                                if (!isCdmaEri && !isRoaming) {
                                    XposedHelpers.setIntField(param.thisObject, "mDataTypeIconId", sSbHpResId);
                                    XposedHelpers.setIntField(param.thisObject, "mQSDataTypeIconId",
                                            QS_DATA_HP[inetCondition]);
                                    if (DEBUG) {
                                        log("H+ inet condition: " + inetCondition);
                                        log("H+ data type: " + sSbHpResId);
                                        log("H+ QS data type: " + QS_DATA_HP[inetCondition]);
                                    }
                                }
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                logAndMute("updateDataNetType", t);
            }
        }
    }

    public static void disableSignalExclamationMarks(ClassLoader cl) {
        final String CLASS_WIFI_ICONS = "com.android.systemui.statusbar.policy.WifiIcons";
        final String CLASS_TELEPHONY_ICONS = "com.android.systemui.statusbar.policy.TelephonyIcons";
        Class<?> clsWifiIcons = null;
        Class<?> clsTelephonyIcons = null;
        final String[] wifiFields = new String[] {
                "WIFI_SIGNAL_STRENGTH", "WIFI_SIGNAL_STRENGTH_NARROW", "WIFI_SIGNAL_STRENGTH_WIDE"
        };
        final String[] mobileFields = new String[] {
                "TELEPHONY_SIGNAL_STRENGTH", "TELEPHONY_SIGNAL_STRENGTH_ROAMING", 
                "DATA_SIGNAL_STRENGTH", "SB_TELEPHONY_SIGNAL_STRENGTH_4_BAR_NARROW",
                "SB_TELEPHONY_SIGNAL_STRENGTH_4_BAR_SEPARATED_NARROW", "SB_TELEPHONY_SIGNAL_STRENGTH_4_BAR_SEPARATED_WIDE",
                "SB_TELEPHONY_SIGNAL_STRENGTH_4_BAR_WIDE", "SB_TELEPHONY_SIGNAL_STRENGTH_5_BAR_NARROW",
                "SB_TELEPHONY_SIGNAL_STRENGTH_5_BAR_SEPARATED_NARROW", "SB_TELEPHONY_SIGNAL_STRENGTH_5_BAR_SEPARATED_WIDE",
                "SB_TELEPHONY_SIGNAL_STRENGTH_5_BAR_WIDE", "SB_TELEPHONY_SIGNAL_STRENGTH_6_BAR_NARROW",
                "SB_TELEPHONY_SIGNAL_STRENGTH_6_BAR_SEPARATED_NARROW", "SB_TELEPHONY_SIGNAL_STRENGTH_6_BAR_SEPARATED_WIDE",
                "SB_TELEPHONY_SIGNAL_STRENGTH_6_BAR_WIDE"
        };

        // Get classes
        try {
            clsWifiIcons = XposedHelpers.findClass(CLASS_WIFI_ICONS, cl);
        } catch (Throwable t) { }

        try {
            clsTelephonyIcons = XposedHelpers.findClass(CLASS_TELEPHONY_ICONS, cl);
        } catch (Throwable t) { }

        // WiFi
        for (String field : wifiFields) {
            try {
                int[][] wifiIcons = (int[][]) XposedHelpers.getStaticObjectField(clsWifiIcons, field);
                for (int i = 0; i < wifiIcons[1].length; i++) {
                    wifiIcons[0][i] = wifiIcons[1][i];
                }
            } catch (Throwable t) {
                //log("disableSignalExclamationMarks: field=" + field + ": " + t.getMessage()); 
            }
        }

        // Mobile
        for (String field : mobileFields) {
            try {
                int[][] telephonyIcons = (int[][]) XposedHelpers.getStaticObjectField(clsTelephonyIcons, field);
                for (int i = 0; i < telephonyIcons[1].length; i++) {
                    telephonyIcons[0][i] = telephonyIcons[1][i];
                }
            } catch (Throwable t) {
                //log("disableSignalExclamationMarks: field=" + field + ": " + t.getMessage());
            }
        }
    }

    @Override
    public void onBroadcastReceived(Context context, Intent intent) { }

    protected void initPreferences() { 
        mDataActivityEnabled = mContainerType != ContainerType.HEADER && 
                sPrefs.getBoolean(GravityBoxSettings.PREF_KEY_SIGNAL_CLUSTER_DATA_ACTIVITY, false);
    }

    protected boolean supportsDataActivityIndicators() {
        return mDataActivityEnabled;
    }

    protected void setNetworkController(Object networkController) {
        final ClassLoader classLoader = mView.getClass().getClassLoader();
        final Class<?> networkCtrlCbClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback", 
                classLoader);
        mNetworkControllerCallback = Proxy.newProxyInstance(classLoader, 
                new Class<?>[] { networkCtrlCbClass }, new NetworkControllerCallback());
            XposedHelpers.callMethod(networkController, "addNetworkSignalChangedCallback",
                    mNetworkControllerCallback);
        if (DEBUG) log("setNetworkController: callback registered");
    }

    protected void update() {
        if (mView != null) {
            try {
                isSecondaryMobileGroup = false;
                updateIconColorRecursive(mView);
            } catch (Throwable t) {
                logAndMute("update", t);
            }
        }
    }

    protected boolean isSecondaryMobileGroup;
    protected void updateIconColorRecursive(ViewGroup vg) {
        if (mIconManager == null) return;

        int count = vg.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = vg.getChildAt(i);
            if (child instanceof ViewGroup) {
                if (child.getId() != View.NO_ID) { 
                    String resName = mResources.getResourceEntryName(child.getId());
                    if (resName.startsWith("mobile_combo")) {
                        isSecondaryMobileGroup = !resName.equals("mobile_combo");
                    }
                }
                updateIconColorRecursive((ViewGroup) child);
            } else if (child instanceof ImageView) {
                ImageView iv = (ImageView) child;
                if ("gbDataActivity".equals(iv.getTag())) {
                    continue;
                }
                if (mIconManager.isColoringEnabled() && mIconManager.getSignalIconMode() !=
                        StatusBarIconManager.SI_MODE_DISABLED) {
                    int color = isSecondaryMobileGroup ?
                            mIconManager.getIconColor(1) : mIconManager.getIconColor(0);
                    iv.setColorFilter(color, PorterDuff.Mode.SRC_IN);
                } else {
                    iv.clearColorFilter();
                }
            }
        }
    }

    @Override
    public void onIconManagerStatusChanged(int flags, ColorInfo colorInfo) {
        if ((flags & (StatusBarIconManager.FLAG_ICON_COLOR_CHANGED |
                StatusBarIconManager.FLAG_DATA_ACTIVITY_COLOR_CHANGED |
                StatusBarIconManager.FLAG_ICON_COLOR_SECONDARY_CHANGED |
                StatusBarIconManager.FLAG_SIGNAL_ICON_MODE_CHANGED)) != 0) {
            if ((flags & StatusBarIconManager.FLAG_DATA_ACTIVITY_COLOR_CHANGED) != 0 &&
                    mDataActivityEnabled) {
                if (mWifiActivity != null) {
                    mWifiActivity.updateDataActivityColor();
                }
                if (mMobileActivity != null) {
                    mMobileActivity.updateDataActivityColor();
                }
            }
            update();
        }
    }

    protected class NetworkControllerCallback implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String methodName = method.getName();

            try {
                if (methodName.equals("onWifiSignalChanged")) {
                    if (DEBUG) {
                        log("WiFi enabled: " + args[0]);
                        log("WiFi activity in: " + (Boolean)args[3]);
                        log("WiFi activity out: " + (Boolean)args[4]);
                    }
                    if (mWifiActivity != null) {
                        mWifiActivity.update((Boolean)args[0],
                                (Boolean)args[3], (Boolean)args[4]);
                    }
                } else if (methodName.equals("onMobileDataSignalChanged")) {
                    if (DEBUG) {
                        log("Mobile data enabled: " + args[0]);
                        log("Mobile data activity in: " + (Boolean)args[4]);
                        log("Mobile data activity out: " + (Boolean)args[5]);
                    }
                    if (mMobileActivity != null) {
                        mMobileActivity.update((Boolean)args[0], 
                                (Boolean)args[4], (Boolean)args[5]);
                    }
                }
            } catch (Throwable t) {
                logAndMute("NetworkControllerCallback", t);
            }

            return null;
        }
    }
}
