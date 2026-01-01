# Google Services Configuration

## ⚠️ IMPORTANT: Replace google-services.json

The current `app/google-services.json` is a TEMPLATE file and will NOT work for Google Sign-In.

## Steps to Fix:

### 1. Download Official File
1. Go to [Google Cloud Console](https://console.cloud.google.com)
2. Select your project (ID: 306072714025)
3. Navigate to **APIs & Services** → **Credentials**
4. Download the official `google-services.json`

### 2. Replace Current File
- Delete current `app/google-services.json`
- Copy the downloaded file to `app/google-services.json`

### 3. Verify Configuration
The official file should contain:
- Real API keys (not "REPLACE_WITH_REAL_API_KEY")
- Real project ID (not "REPLACE_WITH_REAL_PROJECT_ID")
- Real app ID (not "REPLACE_WITH_REAL_APP_ID")
- Your OAuth client ID: `306072714025-ml4uhebu3mtulsc5hvgl19o6o96tier1.apps.googleusercontent.com`
- Your SHA-1 fingerprint: `99:4A:7E:76:63:C4:73:6C:F7:31:3B:33:15:B2:D1:A6:3F:5B:E8:A2`

## Current Status
- ✅ Google Services plugin added to build.gradle.kts
- ✅ OAuth Client ID configured
- ✅ SHA-1 fingerprint added to Google Cloud Console
- ❌ **MISSING: Official google-services.json file**

## Error Code 10 Fix
Once you replace with the official file, the Google Sign-In error code 10 (DEVELOPER_ERROR) should be resolved.
