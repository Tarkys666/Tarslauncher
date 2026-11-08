# Launcher cloud backups

## Configuration

Copy `Tarslauncher/local.properties.example` to `Tarslauncher/local.properties`.
Set `SUPABASE_URL`, `SUPABASE_ANON_KEY` (a publishable key is supported), and
`GOOGLE_WEB_CLIENT_ID`. Never put a service-role key or Google client secret in the APK.
The release workflow reads these three values from repository secrets of the same names.

The current project is `tigcjnlebzunmzplfnqq` in the TARS organization:
https://supabase.com/dashboard/project/tigcjnlebzunmzplfnqq

The Google Cloud project is `thematic-cursor-514100-i5` (`Tarslauncher`).
Its OAuth app name matches the Android app: **Facet Launcher**.
The configured Web client ID is:
`570492763236-i95mcgf5h7au1q1kdqlupra3tosa11cc.apps.googleusercontent.com`.
Android OAuth clients cover the local debug certificate and the existing release certificate.
The Google client secret was configured directly in Supabase and is not stored in this repository.

OAuth currently uses Google's testing mode. Test on the configured accounts before publishing
the consent screen or a new launcher release. The Android device sign-in flow still needs a device test.
Manage the permitted test accounts in Google Auth Platform's Audience settings.

## Google setup

1. Create a Google Cloud OAuth consent screen for the launcher, with basic email/profile access.
2. Create a **Web application** OAuth client. Use its client ID as `GOOGLE_WEB_CLIENT_ID`.
   Add `https://tigcjnlebzunmzplfnqq.supabase.co/auth/v1/callback` as an authorized redirect URI.
3. Create **Android** OAuth clients for package `com.tarslauncher.app`, with the SHA-1
   fingerprints of the debug and release signing certificates respectively. Use the same Cloud project.
4. Enable the Google provider in Supabase Authentication. Configure the Web client ID
   and its secret there. Keep the secret exclusively in Supabase, never in source or local Android config.
5. If the consent screen is in testing mode, add the intended test accounts before trying sign-in.

Google Sign-In now requests an ID token and exchanges it at
`/auth/v1/token?grant_type=id_token`. The launcher saves the returned Supabase session,
refreshes it before expiry, and sends the user's access token on backup/restore requests.
Restoring a cached Google email alone is deliberately insufficient; users of the old build must sign in again.

## Database

Apply migrations in order. The authenticated-user migration replaces the earlier header-based
policies with `auth.uid()` ownership, preserves existing rows, and revokes anonymous table access.
Old `google_<email>` backups are not automatically reassigned to new users.

Run `supabase/tests/user_backups_rls.sql` as postgres to check isolation. It rolls back its test rows.
Run `gradlew.bat :app:testDebugUnitTest :app:assembleDebug` inside `Tarslauncher` for Android validation.

Reference: https://supabase.com/docs/guides/auth/social-sign-in/auth-google
