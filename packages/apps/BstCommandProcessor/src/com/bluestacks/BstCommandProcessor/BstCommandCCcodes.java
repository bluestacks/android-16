package com.bluestacks.BstCommandProcessor;

/**
 * this codes must be the same as host side codes. see hd\Source\gcall\include\GcallCcCodes.h in host side
 */
public class BstCommandCCcodes {
    public static final int GCALL_CC_OnActivityDisplayed = 0x01;
    public static final int GCALL_CC_EnableAds = 0x02;
    public static final int GCALL_CC_DisableAds = 0x03;
    public static final int GCALL_CC_ShowNowggSignInPopUp = 0x04;
    public static final int GCALL_CC_GPAppInstall = 0x05;
    public static final int GCALL_CC_AutoExecutor = 0x06;
    public static final int GCALL_CC_AGENT_IMPORT_FILES = 0x07;
    public static final int GCALL_CC_AGENT_EXPORT_FILES = 0x08;
    public static final int GCALL_CC_SetSmartDownloadEnabled = 0x09;
    
    public static String getCCCodeString(int ccCode) {
        switch (ccCode) {
            case GCALL_CC_OnActivityDisplayed:
                return "GCALL_CC_OnActivityDisplayed";
            case GCALL_CC_EnableAds:
                return "GCALL_CC_EnableAds";
            case GCALL_CC_DisableAds:
                return "GCALL_CC_DisableAds";
            case GCALL_CC_ShowNowggSignInPopUp:
                return "GCALL_CC_ShowNowggSignInPopUp";
            case GCALL_CC_GPAppInstall:
                return "GCALL_CC_GPAppInstall";
            case GCALL_CC_AutoExecutor:
                return "GCALL_CC_AutoExecutor";
            case GCALL_CC_AGENT_IMPORT_FILES:
                return "GCALL_CC_AGENT_IMPORT_FILES";
            case GCALL_CC_AGENT_EXPORT_FILES:
                return "GCALL_CC_AGENT_EXPORT_FILES";
            case GCALL_CC_SetSmartDownloadEnabled:
                return "GCALL_CC_SetSmartDownloadEnabled";
            default:
                return "UnknownCCcode";
        }
    }
}

