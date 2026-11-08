package com.tarslauncher.app;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RemoteViews;

// These components run in the separate test APK process, without the target's Kotlin runtime.
public final class WidgetFixtures {
    public static class ReadyProvider extends AppWidgetProvider {
        @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
            int layout = context.getResources().getIdentifier("test_widget", "layout", context.getPackageName());
            for (int id : ids) manager.updateAppWidget(id, new RemoteViews(context.getPackageName(), layout));
        }
    }

    public static class CancelledProvider extends AppWidgetProvider {}

    public static class ReadyConfiguration extends Activity {
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            int id = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            new ReadyProvider().onUpdate(this, AppWidgetManager.getInstance(this), new int[]{id});
            setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
            finish();
        }
    }

    public static class CancelledConfiguration extends Activity {
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            setResult(RESULT_CANCELED);
            finish();
        }
    }
}
