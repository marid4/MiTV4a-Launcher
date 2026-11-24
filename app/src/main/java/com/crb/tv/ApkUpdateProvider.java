package com.crb.tv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * ContentProvider internal untuk menyajikan file APK hasil download update
 * ke installer sistem Android (tanpa ketergantungan FileProvider eksternal).
 */
public final class ApkUpdateProvider extends ContentProvider {

    public static final String AUTHORITY = "com.crb.tv.apkprovider";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (getContext() == null) {
            throw new FileNotFoundException("Context unavailable");
        }
        File updateDir = new File(getContext().getCacheDir(), "updates");
        File apkFile = new File(updateDir, "CrbLauncher-update.apk");
        if (!apkFile.exists() || !apkFile.isFile()) {
            throw new FileNotFoundException("Update APK not found: " + apkFile.getAbsolutePath());
        }
        return ParcelFileDescriptor.open(apkFile, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
