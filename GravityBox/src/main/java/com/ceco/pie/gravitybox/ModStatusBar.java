/*
 * Copyright (C) 2019 Peter Gregus for GravityBox Project (C3C076@xda)
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
package com.ceco.pie.gravitybox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.ceco.pie.gravitybox.TrafficMeterAbstract.TrafficMeterMode;
import com.ceco.pie.gravitybox.managers.SysUiManagers;
import com.ceco.pie.gravitybox.managers.TunerManager;
import com.ceco.pie.gravitybox.quicksettings.QsQuickPulldownHandler;
import com.ceco.pie.gravitybox.shortcuts.AShortcut;
import com.ceco.pie.gravitybox.tuner.TunerMainActivity;
import com.ceco.pie.gravitybox.visualizer.VisualizerController;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import android.app.AlarmManager;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.service.notification.NotificationListenerService.RankingMap;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ModStatusBar {
    public static final String PACKAGE_NAME = "com.android.systemui";
    private static final String TAG = "GB:ModStatusBar";
    public static final String CLASS_STATUSBAR = "com.android.systemui.statusbar.phone.StatusBar";
    private static final String CLASS_PHONE_STATUSBAR_VIEW = "com.android.systemui.statusbar.phone.PhoneStatusBarView";
    private static final String CLASS_PHONE_STATUSBAR_POLICY = "com.android.systemui.statusbar.phone.PhoneStatusBarPolicy";
    private static final String CLASS_POWER_MANAGER = "android.os.PowerManager";
    private static final String CLASS_EXPANDABLE_NOTIF_ROW = "com.android.systemui.statusbar.ExpandableNotificationRow";
    private static final String CLASS_STATUSBAR_WM = "com.android.systemui.statusbar.phone.StatusBarWindowManager";
    private static final String CLASS_PANEL_VIEW = "com.android.systemui.statusbar.phone.PanelView";
    public static final String CLASS_NOTIF_PANEL_VIEW = "com.android.systemui.statusbar.phone.NotificationPanelView";
    private static final String CLASS_ASSIST_MANAGER = "com.android.systemui.assist.AssistManager";
    private static final String CLASS_COLLAPSED_SB_FRAGMENT = "com.android.systemui.statusbar.phone.CollapsedStatusBarFragment";
    private static final String CLASS_NOTIF_ICON_CONTAINER = "com.android.systemui.statusbar.phone.NotificationIconContainer";
    private static final String CLASS_NOTIF_ENTRY_MANAGER = "com.android.systemui.statusbar.NotificationEntryManager";
    private static final String CLASS_QS_FRAGMENT = "com.android.systemui.qs.QSFragment";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_LAYOUT = false;

    private static final float BRIGHTNESS_CONTROL_PADDING = 0.15f;
    private static final int BRIGHTNESS_CONTROL_LONG_PRESS_TIMEOUT = 750; // ms
    private static final int BRIGHTNESS_CONTROL_LINGER_THRESHOLD = 20;
    private static final float BRIGHTNESS_ADJ_RESOLUTION = 100;
    private static final int STATUS_BAR_DISABLE_EXPAND = 0x00010000;
    public static final String SETTING_ONGOING_NOTIFICATIONS = "gb_ongoing_notifications";

    public static final String ACTION_START_SEARCH_ASSIST = "gravitybox.intent.action.START_SEARCH_ASSIST";
    public static final String ACTION_EXPAND_NOTIFICATIONS = "gravitybox.intent.action.EXPAND_NOTIFICATIONS";
    public static final String ACTION_EXPAND_QUICKSETTINGS = "gravitybox.intent.action.EXPAND_QUICKSETTINGS";
    public static final String ACTION_PHONE_STATUSBAR_VIEW_MADE = "gravitybox.intent.action.PHONE_STATUSBAR_VIEW_MADE";

    public enum ContainerType { STATUSBAR, HEADER, KEYGUARD }

    public static class StatusBarState {
        public static final int SHADE = 0;
        public static final int KEYGUARD = 1;
        public static final int SHADE_LOCKED = 2;
    }

    public interface StatusBarStateChangedListener {
        void onStatusBarStateChanged(int oldState, int newState);
    }

    private static ViewGroup mLeftArea;
    private static ViewGroup mRightArea;
    private static LinearLayout mLayoutCenter;
    private static LinearLayout mLayoutCenterKg;
    private static StatusbarClock mClock;
    private static Object mStatusBar;
    private static ViewGroup mStatusBarView;
    private static Context mContext;
    private static boolean mAlarmHide = false;
    private static Object mPhoneStatusBarPolicy;
    private static SettingsObserver mSettingsObserver;
    private static String mOngoingNotif;
    private static TrafficMeterAbstract mTrafficMeter;
    private static TrafficMeterMode mTrafficMeterMode = TrafficMeterMode.OFF;
    private static boolean mNotifExpandAll;
    private static XSharedPreferences mPrefs;
    private static ProgressBarController mProgressBarCtrl;
    private static int mStatusBarState;
    private static boolean mDisablePeek;
    private static GestureDetector mGestureDetector;
    private static boolean mDt2sEnabled;
    private static long[] mCameraVp;
    private static BatteryStyleController mBatteryStyleCtrlSb;
    private static BatteryStyleController mBatteryStyleCtrlSbHeader;
    private static BatteryBarView mBatteryBarViewSb;
    private static ProgressBarView mProgressBarViewSb;
    private static VisualizerController mVisualizerCtrl;
    private static SystemIconController mSystemIconController;
    private static StatusbarQuietHoursIcon mQhIcon;

    // Brightness control
    private static boolean mBrightnessControlEnabled;
    private static boolean mAutomaticBrightness;
    private static boolean mBrightnessChanged;
    private static float mScreenWidth;
    private static int mMinBrightness;
    private static int mPeekHeight;
    private static boolean mJustPeeked;
    private static int mLinger;
    private static int mInitialTouchX;
    private static int mInitialTouchY;
    private static int BRIGHTNESS_ON = 255;

    private static List<BroadcastSubReceiver> mBroadcastSubReceivers = new ArrayList<>();
    private static List<StatusBarStateChangedListener> mStateChangeListeners =
            new ArrayList<>();

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) log("Broadcast received: " + intent.toString());

            for (BroadcastSubReceiver bsr : mBroadcastSubReceivers) {
                bsr.onBroadcastReceived(context, intent);
            }

            if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_CLOCK_CHANGED)) {
                if (intent.hasExtra(GravityBoxSettings.EXTRA_CLOCK_POSITION)) {
                    setClockPosition(intent.getStringExtra(GravityBoxSettings.EXTRA_CLOCK_POSITION));
                    updateTrafficMeterPosition();
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_CLOCK_POSITION_HEADER)) {
                    setClockPositionHeader(intent.getStringExtra(
                            GravityBoxSettings.EXTRA_CLOCK_POSITION_HEADER));
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_CLOCK_LONGPRESS_LINK)) {
                    QuickStatusBarHeader.setClockLongpressLink(
                        intent.getStringExtra(GravityBoxSettings.EXTRA_CLOCK_LONGPRESS_LINK));
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_ALARM_HIDE)) {
                    mAlarmHide = intent.getBooleanExtra(GravityBoxSettings.EXTRA_ALARM_HIDE, false);
                    if (mPhoneStatusBarPolicy != null) {
                        XposedHelpers.callMethod(mPhoneStatusBarPolicy, "updateAlarm");
                    }
                }
            } else if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_STATUSBAR_CHANGED)) {
                if (intent.hasExtra(GravityBoxSettings.EXTRA_SB_BRIGHTNESS)) {
                    mBrightnessControlEnabled = intent.getBooleanExtra(
                            GravityBoxSettings.EXTRA_SB_BRIGHTNESS, false);
                    if (mSettingsObserver != null) {
                        mSettingsObserver.update();
                    }
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_SB_DISABLE_PEEK)) {
                    mDisablePeek = intent.getBooleanExtra(GravityBoxSettings.EXTRA_SB_DISABLE_PEEK, false);
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_SB_DT2S)) {
                    mDt2sEnabled = intent.getBooleanExtra(GravityBoxSettings.EXTRA_SB_DT2S, false);
                }
            } else if (intent.getAction().equals(
                    GravityBoxSettings.ACTION_PREF_ONGOING_NOTIFICATIONS_CHANGED)) {
                if (intent.hasExtra(GravityBoxSettings.EXTRA_ONGOING_NOTIF)) {
                    mOngoingNotif = intent.getStringExtra(GravityBoxSettings.EXTRA_ONGOING_NOTIF);
                    if (DEBUG) log("mOngoingNotif = " + mOngoingNotif);
                } else if (intent.hasExtra(GravityBoxSettings.EXTRA_ONGOING_NOTIF_RESET)) {
                    mOngoingNotif = "";
                    Settings.Secure.putString(mContext.getContentResolver(),
                            SETTING_ONGOING_NOTIFICATIONS, "");
                    if (DEBUG) log("Ongoing notifications list reset");
                }
            } else if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_DATA_TRAFFIC_CHANGED)) {
                if (intent.hasExtra(GravityBoxSettings.EXTRA_DT_MODE)) {
                    try {
                        TrafficMeterMode mode = TrafficMeterMode.valueOf(
                            intent.getStringExtra(GravityBoxSettings.EXTRA_DT_MODE));
                        setTrafficMeterMode(mode);
                    } catch (Throwable t) {
                        GravityBox.log(TAG, t);
                    }
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_DT_POSITION)) {
                    updateTrafficMeterPosition();
                }
            } else if (intent.getAction().equals(ACTION_START_SEARCH_ASSIST)) {
                startSearchAssist();
            } else if (intent.getAction().equals(ACTION_EXPAND_NOTIFICATIONS)) {
                setNotificationPanelState(intent);
            } else if (intent.getAction().equals(ACTION_EXPAND_QUICKSETTINGS)) {
                setNotificationPanelState(intent, true);
            } else if (intent.getAction().equals(GravityBoxSettings.ACTION_NOTIF_EXPAND_ALL_CHANGED) &&
                    intent.hasExtra(GravityBoxSettings.EXTRA_NOTIF_EXPAND_ALL)) {
                mNotifExpandAll = intent.getBooleanExtra(GravityBoxSettings.EXTRA_NOTIF_EXPAND_ALL, false);
            } else if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_POWER_CHANGED) &&
                    intent.hasExtra(GravityBoxSettings.EXTRA_POWER_CAMERA_VP)) {
                setCameraVibratePattern(intent.getStringExtra(GravityBoxSettings.EXTRA_POWER_CAMERA_VP));
            }
        }
    };

    static class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS_MODE), false, this);
            update();
        }

        @Override
        public void onChange(boolean selfChange) {
            update();
        }

        public void update() {
            try {
                ContentResolver resolver = mContext.getContentResolver();
                int brightnessMode = (Integer) XposedHelpers.callStaticMethod(Settings.System.class,
                        "getIntForUser", resolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE, 0, -2);
                mAutomaticBrightness = brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }
        }
    }

    private static List<String> sSupportedResourceNames;
    public static void initResources(final XSharedPreferences prefs, final XSharedPreferences tunerPrefs) {
        new ResourceProxy(PACKAGE_NAME, new ResourceProxy.Interceptor() {
            @Override
            public List<String> getSupportedResourceNames() {
                if (sSupportedResourceNames == null) {
                    sSupportedResourceNames = new ArrayList<>();
                    sSupportedResourceNames.addAll(Arrays.asList(
                            "rounded_corner_content_padding"
                    ));
                    // add overriden items from Advanced tuning if applicable
                    if (tunerPrefs.getBoolean(TunerMainActivity.PREF_KEY_ENABLED, false) &&
                            !tunerPrefs.getBoolean(TunerMainActivity.PREF_KEY_LOCKED, false)) {
                        TunerManager.addUserItemKeysToList(TunerManager.Category.FRAMEWORK,
                                tunerPrefs, sSupportedResourceNames);
                        TunerManager.addUserItemKeysToList(TunerManager.Category.SYSTEMUI,
                                tunerPrefs, sSupportedResourceNames);
                    }
                }
                return sSupportedResourceNames;
            }

            @Override
            public boolean onIntercept(ResourceProxy.ResourceSpec resourceSpec) {
                // Advanced tuning has priority
                if (tunerPrefs.getBoolean(TunerMainActivity.PREF_KEY_ENABLED, false) &&
                        !tunerPrefs.getBoolean(TunerMainActivity.PREF_KEY_LOCKED, false)) {
                    if (TunerManager.onIntercept(tunerPrefs, resourceSpec)) {
                        return true;
                    }
                }

                if (!resourceSpec.pkgName.equals(PACKAGE_NAME))
                    return false;

                switch (resourceSpec.name) {
                    case "rounded_corner_content_padding":
                        if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_CORNER_PADDING_REMOVE, false)) {
                            resourceSpec.value = 0;
                            return true;
                        }
                        break;
                }
                return false;
            }
        });
    }

    public static int getStatusBarState() {
        return mStatusBarState;
    }

    private static void prepareLayoutStatusBar() {
        try {
            Resources res = mContext.getResources();

            // inject new center layout container into base status bar
            mLayoutCenter = new LinearLayout(mContext);
            mLayoutCenter.setLayoutParams(new LayoutParams(
                            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            mLayoutCenter.setGravity(Gravity.CENTER);
            if (DEBUG_LAYOUT) mLayoutCenter.setBackgroundColor(0x4dff0000);
            mStatusBarView.addView(mLayoutCenter);
            if (DEBUG) log("mLayoutCenter injected");

            mRightArea = mStatusBarView
                    .findViewById(res.getIdentifier("system_icons", "id", PACKAGE_NAME));
            mLeftArea = mStatusBarView
                    .findViewById(res.getIdentifier("status_bar_left_side", "id", PACKAGE_NAME));

            if (mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_STATUSBAR_CLOCK_MASTER_SWITCH, true)) {
                // find statusbar clock
                TextView clock = mStatusBarView.findViewById(
                        res.getIdentifier("clock", "id", PACKAGE_NAME));
                if (clock != null) {
                    mClock = new StatusbarClock(mPrefs);
                    mClock.setClock((ViewGroup)clock.getParent(), mLeftArea, mRightArea, mLayoutCenter, clock);
                    mBroadcastSubReceivers.add(mClock);
                }
                setClockPosition(mPrefs.getString(
                        GravityBoxSettings.PREF_KEY_STATUSBAR_CLOCK_POSITION, "DEFAULT"));
                setClockPositionHeader(mPrefs.getString(
                        GravityBoxSettings.PREF_KEY_STATUSBAR_CLOCK_POSITION_HEADER, "DEFAULT"));
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void destroyLayoutStatusBar() {
        try {
            // disable traffic meter
            setTrafficMeterMode(TrafficMeterMode.OFF);

            // destroy clock
            if (mClock != null) {
                setClockPosition(StatusbarClock.ClockPosition.DEFAULT);
                mBroadcastSubReceivers.remove(mClock);
                mClock.destroy();
                mClock = null;
                if (DEBUG) log("destroyLayoutStatusBar: Clock destroyed");
            }

            // destroy center layout
            if (mLayoutCenter != null) {
                mStatusBarView.removeView(mLayoutCenter);
                mLayoutCenter.removeAllViews();
                mLayoutCenter = null;
                if (DEBUG) log("destroyLayoutStatusBar: mLayoutCenter destroyed");
            }

            // destroy QH icon
            if (mQhIcon != null) {
                mQhIcon.destroy();
                mQhIcon = null;
            }

            mLeftArea = null;
            mRightArea = null;
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void prepareLayoutKeyguard() {
        try {
            // inject new center layout container into keyguard status bar
            mLayoutCenterKg = new LinearLayout(mContext);
            mLayoutCenterKg.setLayoutParams(new LayoutParams(
                            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            mLayoutCenterKg.setGravity(Gravity.CENTER);
            mLayoutCenterKg.setVisibility(View.GONE);
            if (DEBUG_LAYOUT) mLayoutCenterKg.setBackgroundColor(0x4d0000ff);
            ((ViewGroup) XposedHelpers.getObjectField(
                    mStatusBar, "mKeyguardStatusBar")).addView(mLayoutCenterKg);
            if (DEBUG) log("mLayoutCenterKg injected");
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void prepareBrightnessControl() {
        try {
            Class<?> powerManagerClass = XposedHelpers.findClass(CLASS_POWER_MANAGER,
                    mContext.getClassLoader());
            Resources res = mContext.getResources();
            mMinBrightness = res.getInteger(res.getIdentifier(
                    "config_screenBrightnessSettingMinimum", "integer", "android"));
            mPeekHeight = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 84,
                    res.getDisplayMetrics());
            BRIGHTNESS_ON = XposedHelpers.getStaticIntField(powerManagerClass, "BRIGHTNESS_ON");
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void prepareTrafficMeter() {
        try {
            TrafficMeterMode mode = TrafficMeterMode.valueOf(
                    mPrefs.getString(GravityBoxSettings.PREF_KEY_DATA_TRAFFIC_MODE, "OFF"));
            setTrafficMeterMode(mode);
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void prepareBatteryStyle(ContainerType containerType) {
        try {
            ViewGroup container = null;
            switch (containerType) {
                case STATUSBAR:
                    container = mStatusBarView;
                    break;
                case KEYGUARD:
                    container = (ViewGroup) XposedHelpers.getObjectField(
                            mStatusBar, "mKeyguardStatusBar");
                    break;
                default: break;
            }
            if (container != null) {
                BatteryStyleController bsc = new BatteryStyleController(
                        containerType, container, mPrefs);
                if (containerType == ContainerType.STATUSBAR) {
                    if (mBatteryStyleCtrlSb != null) {
                        mBroadcastSubReceivers.remove(mBatteryStyleCtrlSb);
                        mBatteryStyleCtrlSb.destroy();
                        if (DEBUG) log("prepareBatteryStyle: old BatteryStyleController destroyed");
                    }
                    mBatteryStyleCtrlSb = bsc;
                }
                mBroadcastSubReceivers.add(bsc);
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void prepareBatteryStyleHeader(ViewGroup container) {
        try {
            BatteryStyleController bsc = new BatteryStyleController(
                    ContainerType.HEADER, container, "quick_status_bar_system_icons", mPrefs);
            if (mBatteryStyleCtrlSbHeader != null) {
                mBroadcastSubReceivers.remove(mBatteryStyleCtrlSbHeader);
                mBatteryStyleCtrlSbHeader.destroy();
                if (DEBUG) log("prepareBatteryStyleHeader: old BatteryStyleController destroyed");
            }
            mBatteryStyleCtrlSbHeader = bsc;
            mBroadcastSubReceivers.add(bsc);
            if (DEBUG) log("prepareBatteryStyleHeader: BatteryStyleController crated");
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void prepareBatteryBar(ContainerType containerType) {
        try {
            ViewGroup container = null;
            switch (containerType) {
                case STATUSBAR:
                    container = mStatusBarView;
                    break;
                case KEYGUARD:
                    container = (ViewGroup) XposedHelpers.getObjectField(
                            mStatusBar, "mKeyguardStatusBar");
                    break;
                default: break;
            }
            if (container != null) {
                BatteryBarView bbView = new BatteryBarView(containerType, container, mPrefs);
                if (containerType == ContainerType.STATUSBAR) {
                    if (mBatteryBarViewSb != null) {
                        mBroadcastSubReceivers.remove(mBatteryBarViewSb);
                        mProgressBarCtrl.unregisterListener(mBatteryBarViewSb);
                        mStateChangeListeners.remove(mBatteryBarViewSb);
                        mBatteryBarViewSb.destroy();
                        if (DEBUG) log("prepareBatteryBar: old BatteryBarView destroyed");
                    }
                    mBatteryBarViewSb = bbView;
                }
                mBroadcastSubReceivers.add(bbView);
                mProgressBarCtrl.registerListener(bbView);
                mStateChangeListeners.add(bbView);
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void prepareProgressBar(ContainerType containerType) {
        try {
            ViewGroup container = null;
            switch (containerType) {
                case STATUSBAR:
                    container = mStatusBarView;
                    break;
                case KEYGUARD:
                    container = (ViewGroup) XposedHelpers.getObjectField(
                            mStatusBar, "mKeyguardStatusBar");
                    break;
                default: break;
            }
            if (container != null) {
                ProgressBarView pbView = new ProgressBarView(
                        containerType, container, mPrefs, mProgressBarCtrl);
                if (containerType == ContainerType.STATUSBAR) {
                    if (mProgressBarViewSb != null) {
                        mProgressBarCtrl.unregisterListener(mProgressBarViewSb);
                        mStateChangeListeners.remove(mProgressBarViewSb);
                        mProgressBarViewSb.destroy();
                        if (DEBUG) log("prepareProgressBar: old ProgressBarView destroyed");
                    }
                    mProgressBarViewSb = pbView;
                }
                mProgressBarCtrl.registerListener(pbView);
                mStateChangeListeners.add(pbView);
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void prepareGestureDetector() {
        try {
            mGestureDetector = new GestureDetector(mContext, 
                    new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    Intent intent = new Intent(ModHwKeys.ACTION_SLEEP);
                    mContext.sendBroadcast(intent);
                    return true;
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void prepareQuietHoursIcon() {
        try {
            if (SysUiManagers.QuietHoursManager != null && mSystemIconController != null) {
                mQhIcon = new StatusbarQuietHoursIcon(mSystemIconController);
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    public static void init(final XSharedPreferences prefs, final ClassLoader classLoader) {
        try {
            mPrefs = prefs;

            final Class<?> statusBarClass =
                    XposedHelpers.findClass(CLASS_STATUSBAR, classLoader);
            final Class<?> phoneStatusBarPolicyClass = 
                    XposedHelpers.findClass(CLASS_PHONE_STATUSBAR_POLICY, classLoader);
            final Class<?> expandableNotifRowClass = XposedHelpers.findClass(CLASS_EXPANDABLE_NOTIF_ROW, classLoader);
            final Class<?> statusBarWmClass = XposedHelpers.findClass(CLASS_STATUSBAR_WM, classLoader);
            final Class<?> notifPanelViewClass = XposedHelpers.findClass(CLASS_NOTIF_PANEL_VIEW, classLoader);

            QuickStatusBarHeader.init(classLoader);

            if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_VISUALIZER_ENABLE, false)) {
                mVisualizerCtrl = new VisualizerController(classLoader, prefs);
                mStateChangeListeners.add(mVisualizerCtrl);
                mBroadcastSubReceivers.add(mVisualizerCtrl);
            }

            mAlarmHide = prefs.getBoolean(GravityBoxSettings.PREF_KEY_ALARM_ICON_HIDE, false);
            QuickStatusBarHeader.setClockLongpressLink(prefs.getString(
                    GravityBoxSettings.PREF_KEY_STATUSBAR_CLOCK_LONGPRESS_LINK, null));
            mBrightnessControlEnabled = prefs.getBoolean(
                    GravityBoxSettings.PREF_KEY_STATUSBAR_BRIGHTNESS, false);
            mOngoingNotif = prefs.getString(GravityBoxSettings.PREF_KEY_ONGOING_NOTIFICATIONS, "");
            mNotifExpandAll = prefs.getBoolean(GravityBoxSettings.PREF_KEY_NOTIF_EXPAND_ALL, false);
            mDisablePeek = prefs.getBoolean(GravityBoxSettings.PREF_KEY_STATUSBAR_DISABLE_PEEK, false);
            mDt2sEnabled = prefs.getBoolean(GravityBoxSettings.PREF_KEY_STATUSBAR_DT2S, false);
            setCameraVibratePattern(prefs.getString(GravityBoxSettings.PREF_KEY_POWER_CAMERA_VP, null));

            XposedBridge.hookAllConstructors(phoneStatusBarPolicyClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    mPhoneStatusBarPolicy = param.thisObject;
                }
            });

            XposedHelpers.findAndHookMethod(statusBarClass, "makeStatusBarView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    mStatusBar = param.thisObject;
                    mContext = (Context) XposedHelpers.getObjectField(mStatusBar, "mContext");
                    mProgressBarCtrl = new ProgressBarController(mContext, mPrefs);
                    mBroadcastSubReceivers.add(mProgressBarCtrl);
                    QuickStatusBarHeader.setStatusBar(mStatusBar);

                    if (SysUiManagers.AppLauncher != null) {
                        SysUiManagers.AppLauncher.setStatusBar(mStatusBar);
                    }

                    prepareLayoutKeyguard();
                    if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_BATTERY_TWEAKS_ENABLED, true)) {
                        prepareBatteryStyle(ContainerType.KEYGUARD);
                    }
                    prepareBatteryBar(ContainerType.KEYGUARD);
                    prepareProgressBar(ContainerType.KEYGUARD);
                    prepareBrightnessControl();
                    prepareGestureDetector();

                    IntentFilter intentFilter = new IntentFilter();
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_CLOCK_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_STATUSBAR_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_ONGOING_NOTIFICATIONS_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_DATA_TRAFFIC_CHANGED);
                    intentFilter.addAction(ACTION_START_SEARCH_ASSIST);
                    intentFilter.addAction(ACTION_EXPAND_NOTIFICATIONS);
                    intentFilter.addAction(ACTION_EXPAND_QUICKSETTINGS);
                    intentFilter.addAction(GravityBoxSettings.ACTION_NOTIF_EXPAND_ALL_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_SYSTEM_ICON_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_STATUSBAR_DOWNLOAD_PROGRESS_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_BATTERY_BAR_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_BATTERY_STYLE_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_BATTERY_PERCENT_TEXT_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_BATTERY_PERCENT_TEXT_SIZE_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_BATTERY_PERCENT_TEXT_STYLE_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_SIGNAL_CLUSTER_CHANGED);
                    intentFilter.addAction(Intent.ACTION_SCREEN_ON);
                    intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_POWER_CHANGED);
                    intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
                    if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_VISUALIZER_ENABLE, false)) {
                        intentFilter.addAction(GravityBoxSettings.ACTION_VISUALIZER_SETTINGS_CHANGED);
                    }

                    mContext.registerReceiver(mBroadcastReceiver, intentFilter);

                    mSettingsObserver = new SettingsObserver(
                            (Handler) XposedHelpers.getObjectField(mStatusBar, "mHandler"));
                    mSettingsObserver.observe();

                    mContext.sendBroadcast(new Intent(ACTION_PHONE_STATUSBAR_VIEW_MADE));
                }
            });

            XposedHelpers.findAndHookMethod(CLASS_PHONE_STATUSBAR_VIEW, classLoader,
                    "setBar", statusBarClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (mStatusBarView != null) {
                        destroyLayoutStatusBar();
                    }
                    mStatusBarView = (ViewGroup) param.thisObject;
                    prepareLayoutStatusBar();
                    if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_BATTERY_TWEAKS_ENABLED, true)) {
                        prepareBatteryStyle(ContainerType.STATUSBAR);
                    }
                    prepareBatteryBar(ContainerType.STATUSBAR);
                    prepareProgressBar(ContainerType.STATUSBAR);
                    prepareTrafficMeter();
                    prepareQuietHoursIcon();
                }
            });

            XposedHelpers.findAndHookMethod(CLASS_PHONE_STATUSBAR_VIEW, classLoader, "onPanelCollapsed", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (mClock != null) {
                        mClock.setClockVisibility();
                    }
                }
            });

            // Header
            try {
                XposedHelpers.findAndHookMethod(CLASS_QS_FRAGMENT, classLoader, "onViewCreated",
                        View.class, Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_BATTERY_TWEAKS_ENABLED, true)) {
                            prepareBatteryStyleHeader((ViewGroup) XposedHelpers.getObjectField(
                                    param.thisObject, "mHeader"));
                        }
                    }
                });
            } catch (Throwable t) {
                GravityBox.log(TAG, "Error setting up header:" + t);
            }

            // brightness control
            try {
                XposedHelpers.findAndHookMethod(statusBarClass, 
                        "interceptTouchEvent", MotionEvent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!mBrightnessControlEnabled) return;
    
                        brightnessControl((MotionEvent) param.args[0]);
                        if ((XposedHelpers.getIntField(param.thisObject, "mDisabled1")
                                & STATUS_BAR_DISABLE_EXPAND) != 0) {
                            param.setResult(true);
                        }
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!mBrightnessControlEnabled || !mBrightnessChanged) return;
    
                        int action = ((MotionEvent) param.args[0]).getAction();
                        final boolean upOrCancel = (action == MotionEvent.ACTION_UP ||
                                action == MotionEvent.ACTION_CANCEL);
                        if (upOrCancel) {
                            mBrightnessChanged = false;
                            if (mJustPeeked && XposedHelpers.getBooleanField(
                                    param.thisObject, "mExpandedVisible")) {
                                Object notifPanel = XposedHelpers.getObjectField(
                                        param.thisObject, "mNotificationPanel");
                                XposedHelpers.callMethod(notifPanel, "fling", 10, false);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                GravityBox.log(TAG, "Error setting up brightness control", t);
            }

            // Ongoing notification blocker and progress bar
            try {
                XposedHelpers.findAndHookMethod(CLASS_NOTIF_ENTRY_MANAGER, classLoader, "addNotification",
                        StatusBarNotification.class, RankingMap.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        final StatusBarNotification notif = (StatusBarNotification) param.args[0];
                        final String pkg = notif.getPackageName();
                        final boolean clearable = notif.isClearable();
                        final int id = notif.getId();
                        final Notification n = notif.getNotification();
                        if (DEBUG) log ("addNotificationViews: pkg=" + pkg + "; id=" + id + 
                                        "; iconId=" + n.icon + "; clearable=" + clearable);
    
                        if (clearable) return;
    
                        // store if new
                        final String notifData = pkg + "," + n.icon;
                        final ContentResolver cr = mContext.getContentResolver();
                        String storedNotifs = Settings.Secure.getString(cr,
                                SETTING_ONGOING_NOTIFICATIONS);
                        if (storedNotifs == null || !storedNotifs.contains(notifData)) {
                            if (storedNotifs == null || storedNotifs.isEmpty()) {
                                storedNotifs = notifData;
                            } else {
                                storedNotifs += "#C3C0#" + notifData;
                            }
                            if (DEBUG) log("New storedNotifs = " + storedNotifs);
                            Settings.Secure.putString(cr, SETTING_ONGOING_NOTIFICATIONS, storedNotifs);
                        }
    
                        // block if requested
                        if (mOngoingNotif.contains(notifData)) {
                            param.setResult(null);
                            if (DEBUG) log("Ongoing notification " + notifData + " blocked.");
                        }
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (mProgressBarCtrl != null) {
                            mProgressBarCtrl.onNotificationAdded((StatusBarNotification)param.args[0]);
                        }
                    }
                });
    
                XposedHelpers.findAndHookMethod(CLASS_NOTIF_ENTRY_MANAGER, classLoader, "updateNotification",
                        StatusBarNotification.class, RankingMap.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (mProgressBarCtrl != null) {
                            mProgressBarCtrl.onNotificationUpdated((StatusBarNotification)param.args[0]);
                        }
                    }
                });
    
                XposedHelpers.findAndHookMethod(CLASS_NOTIF_ENTRY_MANAGER, classLoader, "removeNotification",
                        String.class, RankingMap.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (mProgressBarCtrl != null) {
                            Object notifData = XposedHelpers.getObjectField(param.thisObject, "mNotificationData");
                            Object entry = XposedHelpers.callMethod(notifData, "get", param.args[0]);
                            if (entry != null) {
                                mProgressBarCtrl.onNotificationRemoved((StatusBarNotification)
                                        XposedHelpers.getObjectField(entry, "notification"));
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                GravityBox.log(TAG, "Error setting up ongoing notification control and progress bar", t);
            }

            // Expanded notifications
            try {
                if (Utils.isSamsungRom()) {
                    XposedHelpers.findAndHookMethod(expandableNotifRowClass, "isUserExpanded", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (mNotifExpandAll) {
                                param.setResult(true);
                            }
                        }
                    });
                } else {
                    XposedHelpers.findAndHookMethod(expandableNotifRowClass, "setSystemExpanded", boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (mNotifExpandAll) {
                                param.args[0] = true;
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                GravityBox.log(TAG, "Error setting up always expanded notifications", t);
            }

            // Hide alarm icon
            try {
                XposedHelpers.findAndHookMethod(phoneStatusBarPolicyClass, "updateAlarm", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object iconCtrl = XposedHelpers.getObjectField(param.thisObject, "mIconController");
                        if (iconCtrl != null) {
                            AlarmManager alarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
                            boolean alarmSet = (alarmManager.getNextAlarmClock() != null);
                            XposedHelpers.callMethod(iconCtrl, "setIconVisibility", "alarm_clock",
                                    (alarmSet && !mAlarmHide));
                        }
                    }
                });
            } catch (Throwable t) {
                GravityBox.log(TAG, "Error setting up Hide alarm icon", t);
            }

            // Status bar Bluetooth icon policy
            mSystemIconController = new SystemIconController(classLoader, prefs);
            mBroadcastSubReceivers.add(mSystemIconController);

            // status bar state change handling
            try {
                XposedHelpers.findAndHookMethod(statusBarWmClass, "setStatusBarState",
                        int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object currentState = XposedHelpers.getObjectField(param.thisObject, "mCurrentState");
                        int oldState = XposedHelpers.getIntField(currentState, "statusBarState");
                        mStatusBarState = (Integer) param.args[0];
                        if (DEBUG) log("setStatusBarState: oldState="+oldState+"; newState="+mStatusBarState);
                        for (StatusBarStateChangedListener listener : mStateChangeListeners) {
                            listener.onStatusBarStateChanged(oldState, mStatusBarState);
                        }
                        // switch centered layout based on status bar state
                        if (mLayoutCenter != null) {
                            mLayoutCenter.setVisibility(mStatusBarState == StatusBarState.SHADE ?
                                    View.VISIBLE : View.GONE);
                        }
                        if (mLayoutCenterKg != null) {
                            mLayoutCenterKg.setVisibility(mStatusBarState != StatusBarState.SHADE ?
                                    View.VISIBLE : View.GONE);
                        }
                        // update traffic meter position
                        updateTrafficMeterPosition();
                    }
                });
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }

            // Disable peek
            try {
                XposedHelpers.findAndHookMethod(CLASS_PANEL_VIEW, classLoader,
                        "runPeekAnimation", long.class, float.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (mDisablePeek) {
                            param.setResult(null);
                        }
                    }
                });
                XposedBridge.hookAllMethods(XposedHelpers.findClass(CLASS_PANEL_VIEW, classLoader),
                        "expand", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (mDisablePeek) {
                            XposedHelpers.setBooleanField(param.thisObject,
                                    QsQuickPulldownHandler.getQsExpandFieldName(), false);
                        }
                    }
                });
            } catch (Throwable t) {
                GravityBox.log(TAG, "Error setting up Disable peek hooks: ", t);
            }

            // DT2S
            try {
                XposedHelpers.findAndHookMethod(CLASS_PHONE_STATUSBAR_VIEW, classLoader,
                        "onTouchEvent", MotionEvent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (mDt2sEnabled && mDisablePeek && mGestureDetector != null) {
                            mGestureDetector.onTouchEvent((MotionEvent)param.args[0]);
                        }
                    }
                });
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }

            // Camera vibrate pattern
            try {
                XposedHelpers.findAndHookMethod(CLASS_STATUSBAR, classLoader,
                        "vibrateForCameraGesture", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (mCameraVp != null) {
                            if (mCameraVp.length == 1 && mCameraVp[0] == 0) {
                                param.setResult(null);
                            } else {
                                Vibrator v = (Vibrator) XposedHelpers.getObjectField(param.thisObject, "mVibrator");
                                v.vibrate(VibrationEffect.createWaveform(mCameraVp, -1));
                                param.setResult(null);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                // ignore as some earlier 6.0 releases lack that functionality
            }

            // Search assist SystemUI crash workaround for disabled navbar
            try {
                XposedHelpers.findAndHookMethod(CLASS_ASSIST_MANAGER, classLoader,
                        "startAssist", Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object orbView = XposedHelpers.getObjectField(param.thisObject, "mView");
                        if (orbView == null) {
                            XposedHelpers.callMethod(param.thisObject, "updateAssistInfo");
                            if (XposedHelpers.getObjectField(param.thisObject, "mAssistComponent") != null) {
                                XposedHelpers.callMethod(param.thisObject, "startAssistInternal", param.args[0]);
                                param.setResult(null);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }

            // brightness control in lock screen
            try {
                XposedHelpers.findAndHookMethod(notifPanelViewClass, "onTouchEvent",
                        MotionEvent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (mBrightnessControlEnabled) {
                            View kgHeader = (View) XposedHelpers.getObjectField(
                                    param.thisObject, "mKeyguardStatusBar");
                            if (kgHeader.getVisibility() == View.VISIBLE) {
                                brightnessControl((MotionEvent) param.args[0]);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }

            // Hide center layout whenever needed
            try {
                XposedHelpers.findAndHookMethod(CLASS_COLLAPSED_SB_FRAGMENT, classLoader,
                        "hideSystemIconArea", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (DEBUG) log("hideSystemIconArea");
                        updateHiddenByPolicy(true);
                    }
                });
                XposedHelpers.findAndHookMethod(CLASS_COLLAPSED_SB_FRAGMENT, classLoader,
                        "showSystemIconArea", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (DEBUG) log("showSystemIconArea");
                        updateHiddenByPolicy(false);
                    }
                });
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }

            // Adjust notification icon area for center clock
            try {
                XposedHelpers.findAndHookMethod(CLASS_NOTIF_ICON_CONTAINER, classLoader,
                        "getActualWidth", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (mLayoutCenter != null && mLayoutCenter.getChildCount() > 0 &&
                                mLayoutCenter.getChildAt(0).getVisibility() == View.VISIBLE) {
                            int width = Math.round(mLayoutCenter.getWidth()/2f -
                                            mLayoutCenter.getChildAt(0).getWidth()/2f) - 4;
                            if (width > 0) {
                                if (mTrafficMeter != null &&
                                        mTrafficMeter.getVisibility() == View.VISIBLE &&
                                        mTrafficMeter.getTrafficMeterPosition() == GravityBoxSettings.DT_POSITION_LEFT) {
                                    width -= (mTrafficMeter.getWidth()+4);
                                }
                                param.setResult(width);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }
        }
        catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void updateHiddenByPolicy(boolean hidden) {
        if (mLayoutCenter != null) {
            mLayoutCenter.setVisibility(hidden ? View.GONE : View.VISIBLE);
        }
        if (mTrafficMeter != null) {
            mTrafficMeter.setHiddenByPolicy(hidden);
        }
        if (mBatteryBarViewSb != null) {
            mBatteryBarViewSb.setHiddenByPolicy(hidden);
        }
    }

    private static void setClockPosition(String position) {
        try {
            setClockPosition(StatusbarClock.ClockPosition.valueOf(position));
        } catch (IllegalArgumentException e) {
            log("Invalid value for clock position: " + position);
        }
    }

    private static void setClockPosition(StatusbarClock.ClockPosition position) {
        if (mClock != null) {
            mClock.moveToPosition(position);
        }
    }

    private static void setClockPositionHeader(String position) {
        try {
            setClockPositionHeader(StatusbarClock.ClockPosition.valueOf(position));
        } catch (IllegalArgumentException e) {
            log("Invalid value for clock position: " + position);
        }
    }

    private static void setClockPositionHeader(StatusbarClock.ClockPosition position) {
        QuickStatusBarHeader.setClockPosition(position);
    }

    private static void setTrafficMeterMode(TrafficMeterMode mode) throws Throwable {
        if (mTrafficMeterMode == mode) return;

        mTrafficMeterMode = mode;

        removeTrafficMeterView();
        if (mTrafficMeter != null) {
            mBroadcastSubReceivers.remove(mTrafficMeter);
            if (SysUiManagers.IconManager != null) {
                SysUiManagers.IconManager.unregisterListener(mTrafficMeter);
            }
            if (mProgressBarCtrl != null) {
                mProgressBarCtrl.unregisterListener(mTrafficMeter);
            }
            mTrafficMeter = null;
        }

        if (mTrafficMeterMode != TrafficMeterMode.OFF) {
            mTrafficMeter = TrafficMeterAbstract.create(mContext, mTrafficMeterMode);
            mTrafficMeter.initialize(mPrefs);
            updateTrafficMeterPosition();
            if (SysUiManagers.IconManager != null) {
                SysUiManagers.IconManager.registerListener(mTrafficMeter);
            }
            if (mProgressBarCtrl != null) {
                mProgressBarCtrl.registerListener(mTrafficMeter);
            }
            mBroadcastSubReceivers.add(mTrafficMeter);
        }
    }

    private static void removeTrafficMeterView() {
        if (mTrafficMeter != null) {
            if (mLeftArea != null) {
                mLeftArea.removeView(mTrafficMeter);
            }
            if (mLayoutCenter != null) {
                mLayoutCenter.removeView(mTrafficMeter);
            }
            if (mLayoutCenterKg != null) {
                mLayoutCenterKg.removeView(mTrafficMeter);
            }
            if (mRightArea != null) {
                mRightArea.removeView(mTrafficMeter);
            }
        }
    }

    private static void updateTrafficMeterPosition() {
        removeTrafficMeterView();

        if (mTrafficMeterMode != TrafficMeterMode.OFF && mTrafficMeter != null &&
                (mStatusBarState == StatusBarState.SHADE || mTrafficMeter.isAllowedInLockscreen())) {
            final int position = mStatusBarState == StatusBarState.SHADE ?
                    mTrafficMeter.getTrafficMeterPosition() :
                        GravityBoxSettings.DT_POSITION_AUTO;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)
                    mTrafficMeter.getLayoutParams();
            switch(position) {
                case GravityBoxSettings.DT_POSITION_AUTO:
                    lp.width = LinearLayout.LayoutParams.WRAP_CONTENT;
                    lp.weight = 0;
                    if (mStatusBarState == StatusBarState.SHADE) {
                        if (mClock != null && mClock.getCurrentPosition() == StatusbarClock.ClockPosition.CENTER) {
                            if (mLeftArea != null) {
                                mLeftArea.addView(mTrafficMeter, 0);
                            }
                        } else if (mLayoutCenter != null) {
                            mLayoutCenter.addView(mTrafficMeter);
                        }
                    } else if (mLayoutCenterKg != null) {
                        mLayoutCenterKg.addView(mTrafficMeter);
                    }
                    break;
                case GravityBoxSettings.DT_POSITION_LEFT:
                    lp.width = LinearLayout.LayoutParams.WRAP_CONTENT;
                    lp.weight = 0;
                    if (mLeftArea != null) {
                        mLeftArea.addView(mTrafficMeter, 0);
                    }
                    break;
                case GravityBoxSettings.DT_POSITION_RIGHT:
                    lp.weight = 1;
                    lp.width = 0;
                    if (mRightArea != null) {
                        mRightArea.addView(mTrafficMeter, 0);
                    }
                    break;
            }
            mTrafficMeter.setLayoutParams(lp);
        }
    }

    private static Runnable mLongPressBrightnessChange = new Runnable() {
        @Override
        public void run() {
            try {
                XposedHelpers.callMethod(mStatusBarView, "performHapticFeedback", 
                        HapticFeedbackConstants.LONG_PRESS);
                adjustBrightness(mInitialTouchX);
                mLinger = BRIGHTNESS_CONTROL_LINGER_THRESHOLD + 1;
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
            }
        }
    };

    private static DisplayManager mDisplayManager;
    private static DisplayManager getDisplayManager() {
        if (mDisplayManager == null) {
            mDisplayManager = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
        }
        return mDisplayManager;
    }

    private static void adjustBrightness(int x) {
        try {
            mBrightnessChanged = true;
            float raw = ((float) x) / mScreenWidth;

            // Add a padding to the brightness control on both sides to
            // make it easier to reach min/max brightness
            float padded = Math.min(1.0f - BRIGHTNESS_CONTROL_PADDING,
                    Math.max(BRIGHTNESS_CONTROL_PADDING, raw));
            float value = (padded - BRIGHTNESS_CONTROL_PADDING) /
                    (1 - (2.0f * BRIGHTNESS_CONTROL_PADDING));

            if (mAutomaticBrightness) {
                float adj = (value * 100) / (BRIGHTNESS_ADJ_RESOLUTION / 2f) - 1;
                adj = Math.max(adj, -1);
                adj = Math.min(adj, 1);
                final float val = adj;
                XposedHelpers.callMethod(getDisplayManager(), "setTemporaryAutoBrightnessAdjustment", val);
                AsyncTask.execute(() ->
                        XposedHelpers.callStaticMethod(Settings.System.class, "putFloatForUser",
                        mContext.getContentResolver(),"screen_auto_brightness_adj", val, -2));
            } else {
                int newBrightness = mMinBrightness + Math.round(value *
                        (BRIGHTNESS_ON - mMinBrightness));
                newBrightness = Math.min(newBrightness, BRIGHTNESS_ON);
                newBrightness = Math.max(newBrightness, mMinBrightness);
                final int val = newBrightness;
                XposedHelpers.callMethod(getDisplayManager(), "setTemporaryBrightness", val);
                AsyncTask.execute(() ->
                        XposedHelpers.callStaticMethod(Settings.System.class, "putIntForUser",
                        mContext.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS, val, -2));
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void brightnessControl(MotionEvent event) {
        try {
            final int action = event.getAction();
            final int x = (int) event.getRawX();
            final int y = (int) event.getRawY();
            Handler handler = (Handler) XposedHelpers.getObjectField(mStatusBar, "mHandler");
            int statusBarHeight = (int)XposedHelpers.callMethod(mStatusBar, "getStatusBarHeight");

            if (action == MotionEvent.ACTION_DOWN) {
                if (y < statusBarHeight) {
                    mLinger = 0;
                    mInitialTouchX = x;
                    mInitialTouchY = y;
                    mJustPeeked = true;
                    mScreenWidth = (float) mContext.getResources().getDisplayMetrics().widthPixels;
                    handler.removeCallbacks(mLongPressBrightnessChange);
                    handler.postDelayed(mLongPressBrightnessChange,
                            BRIGHTNESS_CONTROL_LONG_PRESS_TIMEOUT);
                }
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (y < statusBarHeight && mJustPeeked) {
                    if (mLinger > BRIGHTNESS_CONTROL_LINGER_THRESHOLD) {
                        adjustBrightness(x);
                    } else {
                        final int xDiff = Math.abs(x - mInitialTouchX);
                        final int yDiff = Math.abs(y - mInitialTouchY);
                        final int touchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
                        if (xDiff > yDiff) {
                            mLinger++;
                        }
                        if (xDiff > touchSlop || yDiff > touchSlop) {
                            handler.removeCallbacks(mLongPressBrightnessChange);
                        }
                    }
                } else {
                    if (y > mPeekHeight) {
                        mJustPeeked = false;
                    }
                    handler.removeCallbacks(mLongPressBrightnessChange);
                }
            } else if (action == MotionEvent.ACTION_UP ||
                        action == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(mLongPressBrightnessChange);
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    public static void startSearchAssist() {
        try {
            Object assistManager = XposedHelpers.getObjectField(mStatusBar, "mAssistManager");
            XposedHelpers.callMethod(assistManager, "startAssist", new Bundle());
            XposedHelpers.callMethod(mStatusBar, "awakenDreams");
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void setCameraVibratePattern(String value) {
        if (value == null || value.isEmpty()) {
            mCameraVp = null;
        } else if ("0".equals(value)) {
            mCameraVp = new long[] {0};
        } else {
            try {
                mCameraVp = Utils.csvToLongArray(value);
            } catch (Throwable t) {
                GravityBox.log(TAG, t);
                mCameraVp = null;
            }
        }
    }

    private static void setNotificationPanelState(Intent intent) {
        setNotificationPanelState(intent, false);
    }

    private static void setNotificationPanelState(Intent intent, boolean withQs) {
        try {
            if (!intent.hasExtra(AShortcut.EXTRA_ENABLE)) {
                Object notifPanel = XposedHelpers.getObjectField(mStatusBar, "mNotificationPanel");
                if ((boolean) XposedHelpers.callMethod(notifPanel, "isFullyCollapsed")) {
                    expandNotificationPanel(withQs);
                } else {
                    collapseNotificationPanel();
                }
            } else {
                if (intent.getBooleanExtra(AShortcut.EXTRA_ENABLE, false)) {
                    expandNotificationPanel(withQs);
                } else {
                    collapseNotificationPanel();
                }
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void expandNotificationPanel(boolean withQs) {
        Object notifPanel = XposedHelpers.getObjectField(mStatusBar, "mNotificationPanel");
        if (withQs && XposedHelpers.getBooleanField(notifPanel, "mQsExpansionEnabled")) {
            XposedHelpers.callMethod(notifPanel, "expand", false);
            XposedHelpers.callMethod(notifPanel, "setQsExpansion",
                    XposedHelpers.getFloatField(notifPanel, "mQsMaxExpansionHeight"));
        } else {
            XposedHelpers.callMethod(notifPanel, "expand", true);
        }
    }

    private static void collapseNotificationPanel() {
        XposedHelpers.callMethod(mStatusBar, "postAnimateCollapsePanels");
    }
}
