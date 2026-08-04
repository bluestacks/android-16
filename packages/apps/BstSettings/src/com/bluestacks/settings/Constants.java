package com.bluestacks.settings;

/**
 * Created by sdream on 28/07/16.
 */

public class Constants {

    static String ANDROID_SETTINGS_APP_PACKAGE_NAME = "com.android.settings";
    static String ANDROID_PERMISSION_CONTROLLER_APP_PACKAGE_NAME = "com.android.permissioncontroller";
    static String GMS_PACKAGE_NAME = "com.google.android.gms";
    static String MEDIA_MANAGER_PACKAGE_NAME = "com.bluestacks.filemanager";
    static String STORAGE_ACTIVITY = ANDROID_SETTINGS_APP_PACKAGE_NAME+".BstStorageSettings";
    static String APPLICATIONS_ACTIVITY = ANDROID_SETTINGS_APP_PACKAGE_NAME+".applications.ManageApplications";
    static String SOUND_NOTIFICATIONS_ACTIVITY = ANDROID_SETTINGS_APP_PACKAGE_NAME+".SoundSettings";
    static String HOME_ACTIVITY = ANDROID_PERMISSION_CONTROLLER_APP_PACKAGE_NAME +".role.ui.DefaultAppListActivity";
    static String ANDROID_SETTINGS_ACTIVITY = ANDROID_SETTINGS_APP_PACKAGE_NAME+".SettingsActivity";
    static String LOCATION_ACTIVITY = ANDROID_SETTINGS_APP_PACKAGE_NAME+".BstLocationSettings";
    static String LANGUAGE_INPUT_ACTIVITY = ANDROID_SETTINGS_APP_PACKAGE_NAME+".LanguageSettings";
    static String ADD_ACCOUNT_ACTIVITY = ANDROID_SETTINGS_APP_PACKAGE_NAME+".BstAccountsSettings";
    static String GOOGLE_ACCOUNT_ACTIVITY = GMS_PACKAGE_NAME+".app.settings.GoogleSettingsLink";
    static String SECURITY_ACTIVITY = ANDROID_SETTINGS_APP_PACKAGE_NAME+".SecuritySettings";
    static String DATE_TIME_ACTIVITY = ANDROID_SETTINGS_APP_PACKAGE_NAME+".Settings$DateTimeSettingsActivity";
    static String ACCESSIBILITY_ACTIVITY = Constants.ANDROID_SETTINGS_APP_PACKAGE_NAME+".BstAccessibilitySettings";
    static String ABOUT_PHONE = Constants.ANDROID_SETTINGS_APP_PACKAGE_NAME+".BstAboutPhone";

    static String IMPORT_FROM_WINDOWS_ACTION = "com.bluestacks.filemanager.SHOW_IMPORT_FROM_WINDOWS_DIALOG";
    static String CATEGORY_DEFAULT = "android.intent.category.DEFAULT";

    static String FRAGMENT_NAME_KEY = "fragName";
    static String SHOW_KEY = "show";
    static String PACKAGE_DATA_SCHEME = "package";

    static String ACCOUNT_TYPE_GOOGLE = "com.google";
    static int SOURCE_PLAY_STORE = 0;
    static int SOURCE_CDN = 1;
}
